package dev.omalauncher.pp.linux

import org.json.JSONObject

/*
 * The parts of snapshots ([Snapshots]) that don't touch the disk: which paths a snapshot holds, its manifest's
 * lines, its metadata, which snapshots to keep, and the steps of a restore. Kept apart so they can be tested.
 */

/** Why a snapshot was taken. [key] is what meta.json stores. */
enum class SnapshotReason(
    val key: String,
) {
    /** Automatically, before "update" in settings ran pacman -Syu. */
    Update("update"),

    /** "snapshot now" in settings › linux › snapshots. */
    Manual("manual"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Manual
    }
}

/** A complete snapshot, as its meta.json says. */
data class SnapshotMeta(
    /** Its directory's name; sorts by time. */
    val id: String,
    val createdAt: Long,
    val reason: SnapshotReason,
    /** Whether it holds /root and /home, so a restore can roll them back too. */
    val home: Boolean,
    val files: Int,
    /** The files' size as they are in the rootfs, uncompressed: what a restore writes. */
    val bytes: Long,
    /** Of [bytes], what's under /root and /home. */
    val homeBytes: Long,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("createdAt", createdAt)
            .put("reason", reason.key)
            .put("home", home)
            .put("files", files)
            .put("bytes", bytes)
            .put("homeBytes", homeBytes)

    companion object {
        fun fromJson(o: JSONObject) =
            SnapshotMeta(
                id = o.getString("id"),
                createdAt = o.getLong("createdAt"),
                reason = SnapshotReason.of(o.optString("reason")),
                home = o.optBoolean("home"),
                files = o.optInt("files"),
                bytes = o.optLong("bytes"),
                homeBytes = o.optLong("homeBytes"),
            )

        /** A directory name for a snapshot taken at [millis]: sortable, and unique even for two in the same second. */
        fun idAt(
            millis: Long,
            taken: Collection<String>,
        ): String {
            val base = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(millis))
            return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
        }
    }
}

/**
 * Which paths of the rootfs a snapshot holds, relative to it ("usr/bin/ls"). Directories whose contents are
 * [skipsContents] are kept empty: their contents only live as long as Linux runs, are caches that fill again, or
 * are bound in from outside the rootfs by proot, so they aren't Linux's to roll back. /root and /home ([isHome]) are
 * the user's files: a snapshot may hold them, but a restore keeps them as they are unless asked.
 */
object SnapshotPaths {
    /** Kept as empty directories. */
    private val EMPTY =
        setOf(
            "tmp", "run", "proc", "sys", "dev", "mnt", "var/tmp", "var/run", "var/lock",
            // Packages pacman downloaded: the update empties it anyway (CLEAN_PACMAN_CACHE).
            "var/cache/pacman/pkg",
            // Launcher data proot binds over the guest's home (Proot.dataBinds), and the caches in it.
            "root/.local/share/omarchy",
            "root/.config/omalauncher/widgets",
            "root/.config/omalauncher/style",
            "root/.config/omalauncher/home",
            "root/.config/omarchy/themes",
            "root/.config/omarchy/backgrounds",
            "root/.cache",
        )

    /** Files that only mean something while a process runs. */
    private val SKIPPED = setOf("var/lib/pacman/db.lck")

    fun skipsContents(path: String) = path in EMPTY || (path.startsWith("home/") && path.count { it == '/' } == 2 && path.endsWith("/.cache"))

    fun skips(path: String) = path in SKIPPED

    /** The user's files: /root, /home and everything in them. */
    fun isHome(path: String) = path == "root" || path == "home" || path.startsWith("root/") || path.startsWith("home/")

    /** The directories of [isHome] a restore moves over from the current rootfs when it keeps them. */
    val HOME_DIRS = listOf("root", "home")
}

/** One line of a snapshot's manifest: a directory, a symlink or a regular file of the rootfs. */
data class ManifestEntry(
    val type: Type,
    /** Relative to the rootfs, '/'-separated. */
    val path: String,
    /** Permission bits, with setuid, setgid and sticky. */
    val mode: Int = 0,
    val mtime: Long = 0L,
    val size: Long = 0L,
    /** The content's sha256, its object in the store; empty for an empty file, which needs none. */
    val hash: String = "",
    /**
     * The file's inode, ctime and mtime when it was read: a file that still has all three is the same file, so the
     * next snapshot takes its hash from here instead of reading it again. Any write or rename changes the ctime.
     */
    val stamp: String = "",
    /** A symlink's target, as it is: guest-absolute, or a host path for proot's link2symlink files. */
    val target: String = "",
) {
    enum class Type(
        val code: Char,
    ) {
        Dir('d'),
        File('f'),
        Link('l'),
    }

    fun encode(): String =
        listOf(type.code.toString(), mode.toString(8), mtime.toString(), size.toString(), hash, stamp, escape(path), escape(target)).joinToString("\t")

    companion object {
        /** The entry a line says, or null for one that isn't (a newer launcher's type, a damaged line). */
        fun decode(line: String): ManifestEntry? {
            val f = line.split('\t')
            if (f.size < 8) return null
            val type = Type.entries.firstOrNull { it.code.toString() == f[0] } ?: return null
            return runCatching {
                ManifestEntry(type, unescape(f[6]), f[1].toInt(8), f[2].toLong(), f[3].toLong(), f[4], f[5], unescape(f[7]))
            }.getOrNull()
        }

        /** Linux names may hold any byte but / and NUL: tabs and newlines would split a line. */
        fun escape(s: String): String =
            buildString {
                for (c in s) {
                    when (c) {
                        '%' -> append("%25")
                        '\t' -> append("%09")
                        '\n' -> append("%0A")
                        '\r' -> append("%0D")
                        else -> append(c)
                    }
                }
            }

        fun unescape(s: String): String {
            if ('%' !in s) return s
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '%' && i + 2 < s.length) {
                    out.append(s.substring(i + 1, i + 3).toInt(16).toChar())
                    i += 3
                } else {
                    out.append(s[i++])
                }
            }
            return out.toString()
        }
    }
}

/** Which snapshots to delete so the newest [keep] remain; the rest, oldest first. */
fun snapshotsToPrune(
    snapshots: List<SnapshotMeta>,
    keep: Int,
): List<SnapshotMeta> = snapshots.sortedWith(compareByDescending<SnapshotMeta> { it.createdAt }.thenByDescending { it.id }).drop(keep.coerceAtLeast(1)).reversed()

/**
 * What each snapshot holds that no other one does, in stored bytes: what deleting it frees. Snapshots share every
 * file that didn't change between them. [objects] lists each one's objects, [sizes] the objects' stored sizes.
 */
fun ownBytes(
    objects: Map<String, Set<String>>,
    sizes: Map<String, Long>,
): Map<String, Long> {
    val users = HashMap<String, Int>()
    for (set in objects.values) for (o in set) users[o] = (users[o] ?: 0) + 1
    return objects.mapValues { (_, set) -> set.sumOf { if (users[it] == 1) sizes[it] ?: 0L else 0L } }
}

/**
 * Whether there's room for a snapshot that stores [newBytes] more: they're counted uncompressed, and [reserve]
 * stays free for the update the snapshot comes before.
 */
fun snapshotFits(
    newBytes: Long,
    free: Long,
    reserve: Long,
) = free - newBytes >= reserve

/**
 * The guest manifest (managed.json: launcher-written files and their hashes) to have after a restore: the
 * snapshot's record of the system it brings back, and the current one's for /root and /home when those stay.
 * Its paths are relative to filesDir ("arch/root/.config/…").
 */
fun mergedGuestManifest(
    snapshot: Map<String, String>,
    current: Map<String, String>,
    keepHome: Boolean,
): Map<String, String> {
    if (!keepHome) return snapshot
    fun home(path: String) = path.startsWith("arch/") && SnapshotPaths.isHome(path.removePrefix("arch/"))
    return snapshot.filterKeys { !home(it) } + current.filterKeys(::home)
}

/**
 * A restore swaps whole directories by renaming them, so the rootfs is always either the old one or the restored
 * one, never a mix: the snapshot is unpacked to arch.restore, then arch becomes arch.old, the kept /root and /home
 * move over, and arch.restore becomes arch. A marker records that a restore is under way; after the app was killed
 * during one, [restoreSteps] says how to finish from what's on disk.
 */
enum class RestorePhase(
    val key: String,
) {
    /** arch.restore is being written: until it's complete, arch is untouched. */
    Unpacking("unpacking"),

    /** arch.restore is complete: from here the restore goes on to the end, also after a restart. */
    Swapping("swapping"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

enum class RestoreStep {
    /** An unfinished unpack: the restore is abandoned and arch stays. */
    DeleteStaging,
    ArchToOld,

    /** /root and /home from arch.old into arch.restore, each unless it's there already. */
    MoveHome,
    StagingToArch,

    /** No restored rootfs to put in place (it can't happen, but arch mustn't stay missing): the old one goes back. */
    OldToArch,

    /** The launcher's records of the rootfs (its setup steps, the files it wrote) as the snapshot has them. */
    RestoreRecords,
    DeleteMarker,
    DeleteOld,
}

/**
 * The steps that take a restore in [phase] to its end, from which of arch, arch.restore ([staging]) and arch.old
 * ([old]) exist. A restore runs these from (true, true, false, Swapping); a restart after a kill runs them from
 * wherever it stopped. Every step can run twice.
 */
fun restoreSteps(
    phase: RestorePhase?,
    arch: Boolean,
    staging: Boolean,
    old: Boolean,
    keepHome: Boolean,
): List<RestoreStep> {
    val steps = ArrayList<RestoreStep>()
    when {
        phase == null || phase == RestorePhase.Unpacking -> {
            if (staging) steps += RestoreStep.DeleteStaging
            if (!arch && old) steps += RestoreStep.OldToArch
            steps += RestoreStep.DeleteMarker
        }
        // Not swapped yet.
        arch && staging && !old -> {
            steps += RestoreStep.ArchToOld
            if (keepHome) steps += RestoreStep.MoveHome
            steps += listOf(RestoreStep.StagingToArch, RestoreStep.RestoreRecords, RestoreStep.DeleteMarker, RestoreStep.DeleteOld)
        }
        // Between the two renames.
        !arch && staging -> {
            if (keepHome && old) steps += RestoreStep.MoveHome
            steps += listOf(RestoreStep.StagingToArch, RestoreStep.RestoreRecords, RestoreStep.DeleteMarker)
            if (old) steps += RestoreStep.DeleteOld
        }
        // Swapped: what's left is the records and the old rootfs.
        arch && !staging -> {
            steps += listOf(RestoreStep.RestoreRecords, RestoreStep.DeleteMarker)
            if (old) steps += RestoreStep.DeleteOld
        }
        !arch && old -> steps += listOf(RestoreStep.OldToArch, RestoreStep.DeleteMarker)
        // All three, which a restore never leaves (it clears arch.old first): arch is what runs, and stays.
        arch -> steps += listOf(RestoreStep.DeleteStaging, RestoreStep.DeleteMarker, RestoreStep.DeleteOld)
        else -> steps += RestoreStep.DeleteMarker
    }
    return steps
}

/** Files that are compressed already: storing them as they are is as small, and faster. */
fun storedAsIs(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in INCOMPRESSIBLE
}

private val INCOMPRESSIBLE =
    setOf("gz", "tgz", "xz", "zst", "bz2", "lz4", "lzma", "br", "zip", "jar", "apk", "7z", "png", "jpg", "jpeg", "webp", "gif", "woff", "woff2", "mp3", "ogg", "opus", "mp4", "webm", "pkg")
