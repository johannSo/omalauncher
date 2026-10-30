package dev.omalauncher.pp.linux

import android.content.Context
import android.system.Os
import android.system.StructStat
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.pp.LauncherData
import dev.omalauncher.pp.R
import dev.omalauncher.pp.theme.SnapshotStyle
import dev.omalauncher.pp.theme.StyleStore
import dev.omalauncher.pp.upgrade.Versioned
import dev.omalauncher.pp.upgrade.writeAtomically
import dev.omalauncher.pp.widgets.lang.hex
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

private const val TAG = "snapshots"

/** Free storage a snapshot leaves for the update it comes before: pacman downloads and unpacks next to the old files. */
private const val RESERVE = 1_500_000_000L

/** Free storage a restore leaves: arch.old is deleted only once the restored rootfs is in place. */
private const val RESTORE_RESERVE = 300_000_000L

/** Below this much free storage, settings warns that snapshots may be skipped. */
const val SNAPSHOT_LOW_SPACE = 3_000_000_000L

/** Threads hashing and compressing files: the phone stays usable meanwhile. */
private const val WORKERS = 3

/**
 * Snapshots of the Linux system, to roll back to when an update (or anything else) breaks it.
 *
 * Android doesn't let apps make hard links in their storage (why proot runs with --link2symlink, and why
 * [Bootstrap] copies the tarball's), and its ext4 and f2fs have no reflinks, so a cheap `cp -al` copy isn't
 * possible. Hard links would also be unsafe here: pacman replaces package files (libarchive unlinks before it
 * writes), but pacman's own database under /var/lib/pacman, its log, `locale-gen`'s archive, and every file the
 * user or an editor changes in /etc are written in place and would change in the snapshot as well.
 *
 * So a snapshot is a manifest of the rootfs (every directory, symlink and file, with modes and times) plus the
 * contents of its files in a store shared by all snapshots, each kept once by its sha256, deflated unless it's
 * compressed already. A file still has the inode, ctime and mtime the last snapshot saw is the same file, so the
 * next snapshot takes its hash from there without reading it: the first snapshot costs about half the rootfs, one
 * after an update only what the update changed. Deleting a snapshot deletes the contents no other one uses.
 *
 * It all lives in filesDir/snapshots, outside the rootfs and the directories proot binds into it, so tools in the
 * guest that walk the filesystem (du, find, pacman) never see it, and [dev.omalauncher.pp.Backup] doesn't take it.
 * [restore] unpacks a snapshot next to the rootfs and swaps the two by renaming, so the rootfs is the old one or the
 * restored one, never a mix; see [restoreSteps].
 */
class Snapshots(
    private val context: Context,
    private val proot: Proot,
    private val style: StyleStore,
    private val pacman: Pacman,
    /** The rootfs was swapped and the launcher's records of it put back: whoever caches them reads them again. */
    private val onRestored: () -> Unit,
) {
    val dir = File(proot.filesDir, "snapshots")
    private val objects = File(dir, "objects")
    private val marker = File(dir, "restoring.json")
    private val staging = File(proot.filesDir, "arch.restore")
    private val old = File(proot.filesDir, "arch.old")

    /** One store change at a time: taking, deleting and restoring all read what the others write. */
    private val mutex = Mutex()

    /** Snapshots taken and deleted from settings go on after it closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "snapshot job failed", e) })

    /** Names the files objects are written to before they're renamed to their hash. */
    private val tmpCount = AtomicInteger()

    /** Complete snapshots, newest first. */
    var list by mutableStateOf<List<SnapshotMeta>>(emptyList())
        private set

    /** What deleting each one would free, by id; see [ownBytes]. */
    var own by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** The store's size on disk, once [load] measured it. */
    var stored by mutableStateOf<Long?>(null)
        private set

    var job by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** Why the last snapshot before an update wasn't taken (no room), until one is. */
    var skipped by mutableStateOf<String?>(null)
        private set

    val busy get() = job is JobState.Working

    /** A restore was asked for and waits for Linux to shut down; [busy] meanwhile, so it isn't asked twice. */
    fun waitForRestore(waiting: Boolean = true) {
        job = if (waiting) JobState.Working(R.string.linux_job_waiting, emptyList()) else JobState.Idle
    }

    /** Reads the snapshots once; everything that changes them reads them again. */
    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { if (stored == null) refresh() } }

    /**
     * Before anything runs in the guest: finishes a restore the app was killed during, or drops what an interrupted
     * one left. Leftovers of an interrupted snapshot go too.
     */
    fun recover() {
        try {
            if (marker.exists()) {
                finishRestore()
            } else {
                LauncherData.deleteTree(staging)
                if (proot.rootfs.exists()) LauncherData.deleteTree(old) else if (old.exists()) Os.rename(old.path, proot.rootfs.path)
            }
            val partial = dir.listFiles { f -> f.name.startsWith(PARTIAL) }.orEmpty()
            partial.forEach(LauncherData::deleteTree)
            if (partial.isNotEmpty()) collectGarbage()
        } catch (e: Exception) {
            Log.e(TAG, "recovery failed", e)
        }
    }

    /** Takes the restore [marker] records to its end from what's on disk; see [restoreSteps]. */
    private fun finishRestore() {
        val m = Versioned.readFile(marker, VERSION)
        val phase = m?.optString("phase")?.let(RestorePhase::of)
        val keepHome = m?.optBoolean("keepHome", true) ?: true
        val id = m?.optString("id").orEmpty()
        Log.i(TAG, "finishing restore of $id ($phase)")
        run(restoreSteps(phase, proot.rootfs.exists(), staging.exists(), old.exists(), keepHome), id, keepHome)
    }

    /** "snapshot now": waits for any pacman job first, so packages don't change while it reads them. */
    fun takeNow() {
        if (busy) return
        job = JobState.Working(R.string.linux_job_waiting, emptyList())
        scope.launch { pacman.lock.withLock { take(SnapshotReason.Manual) } }
    }

    /**
     * Before a system update, if style.toml's snapshots.before-update asks for one; the caller holds pacman's lock.
     * [publish] shows its progress in the update's place. With no room, it's skipped and [skipped] says why.
     */
    suspend fun beforeUpdate(publish: (JobState) -> Unit) {
        if (!style.style[SnapshotStyle.BEFORE_UPDATE] || !proot.rootfs.isDirectory) return
        take(SnapshotReason.Update, auto = true, publish = publish)
    }

    /** Takes a snapshot and deletes the oldest past snapshots.keep. Returns whether it was taken. */
    private suspend fun take(
        reason: SnapshotReason,
        auto: Boolean = false,
        publish: (JobState) -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                fun progress(line: String) =
                    JobState.Working(R.string.snapshots_job_taking, listOf(line)).let {
                        job = it
                        publish(it)
                    }
                progress(text(R.string.snapshots_step_scanning))
                var partial: File? = null
                try {
                    // Before an update, settings may not have been opened yet: the last snapshot spares rereading files.
                    if (stored == null) refresh()
                    val id = SnapshotMeta.idAt(System.currentTimeMillis(), list.map { it.id })
                    partial = File(dir, PARTIAL + id)
                    val home = style.style[SnapshotStyle.HOME]
                    val previous =
                        list.firstOrNull()?.let { latest ->
                            HashMap<String, ManifestEntry>().also { map -> readManifest(latest.id) { if (it.type == ManifestEntry.Type.File) map[it.path] = it } }
                        }.orEmpty()
                    val scan = scan(home, previous)
                    val newBytes = scan.todo.sumOf { scan.entries[it].size }
                    objects.mkdirs()
                    val free = dir.usableSpace
                    if (!snapshotFits(newBytes, free, RESERVE)) {
                        val why = text(R.string.snapshots_no_room, formatBytes(newBytes + RESERVE), formatBytes(free))
                        if (auto) {
                            skipped = why
                            job = JobState.Idle
                            return@withContext false
                        }
                        throw IOException(why)
                    }
                    storeAll(scan, ::progress)
                    progress(text(R.string.snapshots_step_saving))
                    partial.mkdirs()
                    writeManifest(File(partial, MANIFEST), scan.entries)
                    copyRecords(proot.filesDir, File(partial, RECORDS))
                    val files = scan.entries.filter { it.type == ManifestEntry.Type.File }
                    val meta =
                        SnapshotMeta(
                            id = id,
                            createdAt = System.currentTimeMillis(),
                            reason = reason,
                            home = home,
                            files = files.size,
                            bytes = files.sumOf { it.size },
                            homeBytes = files.filter { SnapshotPaths.isHome(it.path) }.sumOf { it.size },
                        )
                    Versioned.writeFile(File(partial, META), VERSION, meta.toJson())
                    // Only a complete snapshot has its own name: one cut short is never listed or restored.
                    Os.rename(partial.path, File(dir, id).path)
                    val pruned = snapshotsToPrune(readAll(), style.style[SnapshotStyle.KEEP])
                    for (s in pruned) LauncherData.deleteTree(File(dir, s.id))
                    if (pruned.isNotEmpty()) collectGarbage()
                    if (auto) skipped = null
                    refresh()
                    job = JobState.Idle
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "snapshot failed", e)
                    runCatching {
                        partial?.let(LauncherData::deleteTree)
                        collectGarbage()
                        refresh()
                    }
                    job = JobState.Failed(listOf(e.message ?: e.toString()))
                    false
                }
            }
        }

    private class Scan(
        val entries: ArrayList<ManifestEntry>,
        /** Indices of the files whose contents aren't stored yet. */
        val todo: ArrayList<Int>,
    )

    /** Every path of the rootfs a snapshot holds (see [SnapshotPaths]), parents first. */
    private fun scan(
        home: Boolean,
        previous: Map<String, ManifestEntry>,
    ): Scan {
        val root = proot.rootfs.toPath()
        val entries = ArrayList<ManifestEntry>()
        val todo = ArrayList<Int>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    d: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val path = root.relativize(d).toString()
                    if (path.isEmpty()) return FileVisitResult.CONTINUE
                    if (!home && SnapshotPaths.isHome(path)) return FileVisitResult.SKIP_SUBTREE
                    val st = Os.lstat(d.toString())
                    entries += ManifestEntry(ManifestEntry.Type.Dir, path, st.st_mode and MODE_BITS, st.millis())
                    return if (SnapshotPaths.skipsContents(path)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val path = root.relativize(file).toString()
                    if ((!home && SnapshotPaths.isHome(path)) || SnapshotPaths.skips(path)) return FileVisitResult.CONTINUE
                    when {
                        attrs.isSymbolicLink -> entries += ManifestEntry(ManifestEntry.Type.Link, path, target = Os.readlink(file.toString()))
                        attrs.isRegularFile -> {
                            val st = Os.lstat(file.toString())
                            val stamp = "${st.st_ino}:${st.st_ctim.tv_sec}.${st.st_ctim.tv_nsec}:${st.st_mtim.tv_sec}.${st.st_mtim.tv_nsec}"
                            val before = previous[path]
                            val same = before != null && before.stamp == stamp && before.size == st.st_size && (before.hash.isEmpty() || objectFile(before.hash).exists())
                            val hash = if (same) before!!.hash else ""
                            if (!same && st.st_size > 0) todo += entries.size
                            entries += ManifestEntry(ManifestEntry.Type.File, path, st.st_mode and MODE_BITS, st.millis(), st.st_size, hash, stamp)
                        }
                        // Sockets, fifos and device nodes: only a running system has them.
                        else -> {}
                    }
                    return FileVisitResult.CONTINUE
                }

                /** A directory the app can't read (mode 000): the guest can't either, so it's left out. */
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    Log.w(TAG, "skipped $file: $exc")
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return Scan(entries, todo)
    }

    /** Stores the contents of [scan]'s new files, a few at a time; files gone meanwhile leave the manifest. */
    private suspend fun storeAll(
        scan: Scan,
        progress: (String) -> Unit,
    ) {
        val next = AtomicInteger()
        val done = AtomicInteger()
        val bytes = AtomicLong()
        val total = scan.todo.sumOf { scan.entries[it].size }
        val stored = arrayOfNulls<Pair<String, Long>>(scan.todo.size)
        val gone = BooleanArray(scan.todo.size)
        coroutineScope {
            repeat(WORKERS) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= scan.todo.size) break
                        ensureActive()
                        val entry = scan.entries[scan.todo[i]]
                        val result = store(File(proot.rootfs, entry.path), entry.mode)
                        if (result == null) gone[i] = true else stored[i] = result
                        val n = done.incrementAndGet()
                        val b = bytes.addAndGet(entry.size)
                        if (n % 200 == 0 || n == scan.todo.size) {
                            if (dir.usableSpace < RESERVE / 2) throw IOException(text(R.string.snapshots_ran_out))
                            progress(text(R.string.snapshots_step_storing, formatBytes(b), formatBytes(total)))
                        }
                    }
                }
            }
        }
        for (i in scan.todo.indices.reversed()) {
            val at = scan.todo[i]
            if (gone[i]) {
                scan.entries.removeAt(at)
            } else {
                val (hash, size) = stored[i]!!
                scan.entries[at] = scan.entries[at].copy(hash = hash, size = size)
            }
        }
    }

    /** Puts [file]'s contents in the store; its hash and size, or null if it's gone. */
    private fun store(
        file: File,
        mode: Int,
    ): Pair<String, Long>? {
        val input =
            try {
                FileInputStream(file)
            } catch (e: FileNotFoundException) {
                if (!file.exists()) return null
                // Unreadable to its owner (mode 000), which the guest's fake root reads anyway.
                Os.chmod(file.path, mode or 0x100)
                try {
                    FileInputStream(file)
                } finally {
                    Os.chmod(file.path, mode)
                }
            }
        val tmp = File(objects, "tmp-" + tmpCount.incrementAndGet())
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val compress = !storedAsIs(file.name)
        input.use { src ->
            tmp.outputStream().buffered(1 shl 16).use { raw ->
                raw.write(if (compress) DEFLATED else AS_IS)
                val deflater = if (compress) Deflater(Deflater.BEST_SPEED) else null
                try {
                    val out: OutputStream = if (deflater != null) DeflaterOutputStream(raw, deflater, 1 shl 16) else raw
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        size += n
                    }
                    (out as? DeflaterOutputStream)?.finish()
                } finally {
                    deflater?.end()
                }
            }
        }
        val hash = digest.digest().hex()
        val target = objectFile(hash)
        if (target.exists()) {
            tmp.delete()
        } else {
            target.parentFile?.mkdirs()
            Os.rename(tmp.path, target.path)
        }
        return hash to size
    }

    /** Deletes snapshot [id] and the contents only it used. */
    fun delete(id: String) {
        scope.launch { deleteNow(id) }
    }

    private suspend fun deleteNow(id: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    LauncherData.deleteTree(File(dir, id))
                    collectGarbage()
                }.onFailure { Log.e(TAG, "delete failed", it) }
                refresh()
            }
        }

    /** Linux was removed: its snapshots go with it. */
    suspend fun deleteAll() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { LauncherData.deleteTree(dir) }.onFailure { Log.e(TAG, "delete failed", it) }
                skipped = null
                refresh()
            }
        }

    /**
     * Rolls the rootfs back to snapshot [id]. Linux must be shut down: the caller stops it first, so nothing writes
     * into the rootfs meanwhile. /root and /home stay as they are unless [withHome] and the snapshot holds them.
     * A failure before the swap leaves the rootfs untouched. Returns whether it was restored.
     */
    suspend fun restore(
        id: String,
        withHome: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                job = JobState.Working(R.string.snapshots_job_restoring, emptyList())
                var swapping = false
                try {
                    val meta = readMeta(id) ?: throw IOException(text(R.string.snapshots_gone))
                    val keepHome = !(withHome && meta.home)
                    LauncherData.deleteTree(staging)
                    LauncherData.deleteTree(old)
                    val need = meta.bytes - (if (keepHome) meta.homeBytes else 0L)
                    val free = proot.filesDir.usableSpace
                    if (free - need < RESTORE_RESERVE) throw IOException(text(R.string.snapshots_no_room_restore, formatBytes(need + RESTORE_RESERVE), formatBytes(free)))
                    writeMarker(id, RestorePhase.Unpacking, keepHome)
                    unpack(id, keepHome, meta.files)
                    writeMarker(id, RestorePhase.Swapping, keepHome)
                    swapping = true
                    job = JobState.Working(R.string.snapshots_job_restoring, listOf(text(R.string.snapshots_step_swapping)))
                    run(restoreSteps(RestorePhase.Swapping, proot.rootfs.exists(), staging.exists(), old.exists(), keepHome), id, keepHome)
                    refresh()
                    job = JobState.Idle
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "restore failed", e)
                    // Before the swap nothing changed. After it began, it's taken to its end once more from what's on
                    // disk, so Linux doesn't start on a missing rootfs; failing that, the next start does (recover).
                    runCatching {
                        if (swapping) {
                            finishRestore()
                        } else {
                            LauncherData.deleteTree(staging)
                            marker.delete()
                        }
                    }.onFailure { Log.e(TAG, "restore not finished", it) }
                    job = JobState.Failed(listOf(e.message ?: e.toString()))
                    false
                }
            }
        }

    /** Writes snapshot [id]'s files to arch.restore, checking each against its hash; dirs get their modes last. */
    private fun unpack(
        id: String,
        keepHome: Boolean,
        files: Int,
    ) {
        staging.mkdirs()
        val dirs = ArrayList<ManifestEntry>()
        var n = 0
        readManifest(id) { e ->
            if (keepHome && SnapshotPaths.isHome(e.path)) return@readManifest
            val target = File(staging, e.path)
            when (e.type) {
                ManifestEntry.Type.Dir -> {
                    target.mkdirs()
                    dirs += e
                }
                ManifestEntry.Type.Link -> {
                    target.parentFile?.mkdirs()
                    Os.symlink(e.target, target.path)
                }
                ManifestEntry.Type.File -> {
                    target.parentFile?.mkdirs()
                    if (e.hash.isEmpty()) target.createNewFile() else extract(e.hash, target)
                    Os.chmod(target.path, e.mode)
                    target.setLastModified(e.mtime)
                    if (++n % 500 == 0) job = JobState.Working(R.string.snapshots_job_restoring, listOf(text(R.string.snapshots_step_unpacking, n, files)))
                }
            }
        }
        for (d in dirs.asReversed()) {
            val target = File(staging, d.path)
            runCatching { Os.chmod(target.path, d.mode) }
            target.setLastModified(d.mtime)
        }
    }

    private fun extract(
        hash: String,
        target: File,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        openObject(hash).use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val got = digest.digest().hex()
        if (got != hash) throw IOException(text(R.string.snapshots_damaged, target.relativeTo(staging).path))
    }

    /** Runs a restore's [steps]; see [restoreSteps]. */
    private fun run(
        steps: List<RestoreStep>,
        id: String,
        keepHome: Boolean,
    ) {
        val arch = proot.rootfs
        for (step in steps) {
            when (step) {
                RestoreStep.DeleteStaging -> LauncherData.deleteTree(staging)
                RestoreStep.ArchToOld -> Os.rename(arch.path, old.path)
                RestoreStep.MoveHome ->
                    for (name in SnapshotPaths.HOME_DIRS) {
                        val from = File(old, name)
                        val to = File(staging, name)
                        if (from.exists() && !to.exists()) Os.rename(from.path, to.path)
                    }
                RestoreStep.StagingToArch -> Os.rename(staging.path, arch.path)
                RestoreStep.OldToArch -> Os.rename(old.path, arch.path)
                RestoreStep.RestoreRecords -> restoreRecords(File(File(dir, id), RECORDS), keepHome)
                RestoreStep.DeleteMarker -> marker.delete()
                RestoreStep.DeleteOld -> LauncherData.deleteTree(old)
            }
        }
    }

    /**
     * The launcher's records of the rootfs as they were with the snapshot: which setup steps ran on it (newer ones
     * then run again) and the files it wrote there, so the next sync updates them rather than taking them as edited.
     */
    private fun restoreRecords(
        records: File,
        keepHome: Boolean,
    ) {
        val steps = File(records, ROOTFS_JOURNAL)
        if (steps.isFile) writeAtomically(File(proot.filesDir, ROOTFS_JOURNAL), steps.readBytes())
        val manifest = File(records, LauncherData.GUEST_MANIFEST)
        if (manifest.isFile) {
            val current = File(proot.filesDir, LauncherData.GUEST_MANIFEST)
            val merged = mergedGuestManifest(readMap(manifest), readMap(current), keepHome)
            Versioned.writeFile(current, MANAGED_VERSION, JSONObject(merged as Map<*, *>))
        }
        onRestored()
    }

    private fun readMap(file: File): Map<String, String> {
        val json = Versioned.readFile(file, MANAGED_VERSION) ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private fun copyRecords(
        from: File,
        to: File,
    ) {
        to.mkdirs()
        for (name in listOf(ROOTFS_JOURNAL, LauncherData.GUEST_MANIFEST)) {
            File(from, name).takeIf { it.isFile }?.copyTo(File(to, name), overwrite = true)
        }
    }

    private fun writeMarker(
        id: String,
        phase: RestorePhase,
        keepHome: Boolean,
    ) = Versioned.writeFile(marker, VERSION, JSONObject().put("id", id).put("phase", phase.key).put("keepHome", keepHome))

    /** Deletes stored contents no snapshot uses any more, and temp files of an interrupted one. */
    private fun collectGarbage() {
        val used = HashSet<String>()
        for (s in readAll()) readManifest(s.id) { if (it.hash.isNotEmpty()) used += it.hash }
        for (sub in objects.listFiles().orEmpty()) {
            if (!sub.isDirectory) {
                sub.delete()
                continue
            }
            for (f in sub.listFiles().orEmpty()) if (sub.name + f.name !in used) f.delete()
            sub.delete() // only if empty
        }
    }

    /** Reads every snapshot's metadata and what each one alone holds. */
    private fun refresh() {
        val all = readAll()
        val sets = all.associate { s -> s.id to HashSet<String>().also { set -> readManifest(s.id) { if (it.hash.isNotEmpty()) set += it.hash } } }
        val sizes = HashMap<String, Long>()
        for (set in sets.values) for (h in set) if (h !in sizes) sizes[h] = objectFile(h).length()
        own = ownBytes(sets, sizes)
        stored = sizes.values.sum()
        list = all.sortedByDescending { it.createdAt }
    }

    private fun readAll(): List<SnapshotMeta> =
        dir.listFiles { f -> f.isDirectory && !f.name.startsWith(PARTIAL) && f.name != objects.name }.orEmpty().mapNotNull { readMeta(it.name) }

    private fun readMeta(id: String): SnapshotMeta? = runCatching { Versioned.readFile(File(File(dir, id), META), VERSION)?.let(SnapshotMeta::fromJson) }.getOrNull()

    private fun readManifest(
        id: String,
        each: (ManifestEntry) -> Unit,
    ) {
        val file = File(File(dir, id), MANIFEST)
        if (!file.isFile) return
        GZIPInputStream(file.inputStream().buffered(1 shl 16)).bufferedReader().useLines { lines -> lines.forEach { line -> ManifestEntry.decode(line)?.let(each) } }
    }

    private fun writeManifest(
        file: File,
        entries: List<ManifestEntry>,
    ) {
        java.io.FileOutputStream(file).use { fos ->
            val gz = GZIPOutputStream(fos.buffered(1 shl 16))
            gz.bufferedWriter().apply {
                for (e in entries) {
                    write(e.encode())
                    write('\n'.code)
                }
                flush()
            }
            gz.finish()
            gz.flush()
            // Synced before the rename that makes the snapshot complete.
            fos.fd.sync()
        }
    }

    private fun objectFile(hash: String) = File(File(objects, hash.substring(0, 2)), hash.substring(2))

    private fun openObject(hash: String): InputStream {
        val input = objectFile(hash).inputStream().buffered(1 shl 16)
        return when (input.read()) {
            AS_IS -> input
            DEFLATED -> {
                val inflater = Inflater()
                object : FilterInputStream(InflaterInputStream(input, inflater, 1 shl 16)) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inflater.end()
                        }
                    }
                }
            }
            else -> {
                input.close()
                throw IOException(text(R.string.snapshots_damaged, hash))
            }
        }
    }

    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ) = context.getString(id, *args)

    private fun StructStat.millis() = st_mtim.tv_sec * 1000 + st_mtim.tv_nsec / 1_000_000

    private companion object {
        const val VERSION = 1

        /** managed.json's own format version (ManagedFiles). */
        const val MANAGED_VERSION = 1
        const val META = "meta.json"
        const val MANIFEST = "manifest.gz"

        /** The launcher's records of the rootfs at the time: see [restoreRecords]. */
        const val RECORDS = "records"

        /** A snapshot being written: `.partial-<id>` until it's complete. */
        const val PARTIAL = ".partial-"
        const val MODE_BITS = 0xfff
        const val DEFLATED = 'z'.code
        const val AS_IS = 'r'.code
    }
}

/** Sizes as the Linux screen shows them: "850 MB", "2.3 GB". */
fun formatBytes(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"
