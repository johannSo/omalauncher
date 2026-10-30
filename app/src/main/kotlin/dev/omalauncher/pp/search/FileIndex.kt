package dev.omalauncher.pp.search

import android.os.SystemClock
import android.util.Log
import dev.omalauncher.pp.widgets.lang.matchScore
import java.io.File
import java.nio.file.Files

/** A file or folder in Linux's home: its guest path, and its name, which is what matches. */
class FileEntry(
    val path: String,
    val name: String,
    val dir: Boolean,
)

/**
 * The names of the files in Linux's home folder, to find from the bar without walking the disk on every keystroke: walked
 * once as the bar opens and again when it's [STALE_MS] old, at most [MAX_ENTRIES] deep into [MAX_DEPTH] levels. Hidden
 * files and folders, and the trees that hold thousands of files nobody looks for by name (node_modules, build output),
 * stay out. Links aren't followed, so a link out of home or round in a loop is listed but not walked.
 */
class FileIndex(
    /** The guest's home, as a guest path. */
    private val home: String,
    /** The Android folder [home] is, or null without Linux. */
    private val hostHome: () -> File?,
) {
    @Volatile private var entries: List<FileEntry> = emptyList()

    @Volatile private var walkedAt = 0L

    /** Walks home again if the last walk is old. Blocks: call off the main thread. */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (walkedAt != 0L && now - walkedAt < STALE_MS) return
        walkedAt = now
        val root = runCatching { hostHome() }.getOrNull()
        if (root == null || !root.isDirectory) {
            entries = emptyList()
            return
        }
        val found = ArrayList<FileEntry>()
        val started = SystemClock.elapsedRealtime()
        // Breadth first, so a deep tree that hits the limit leaves out its depths rather than home's other folders.
        var level = listOf(root to home)
        var depth = 0
        while (level.isNotEmpty() && depth < MAX_DEPTH && found.size < MAX_ENTRIES) {
            val next = ArrayList<Pair<File, String>>()
            for ((dir, guest) in level) {
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (f in children) {
                    if (found.size >= MAX_ENTRIES) break
                    val name = f.name
                    if (name.startsWith('.') || name in SKIPPED) continue
                    val path = "$guest/$name"
                    val link = runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)
                    val isDir = !link && f.isDirectory
                    found += FileEntry(path, name, isDir)
                    if (isDir) next += f to path
                }
            }
            level = next
            depth++
        }
        entries = found
        Log.i(TAG, "indexed ${found.size} files in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** Files and folders [query] names, by their names as apps' are: from a word's start, or the name whole. */
    fun search(query: String): List<Pair<FileEntry, Int>> {
        if (query.trim().length < 2) return emptyList()
        return entries.mapNotNull { e ->
            val score = matchScore(e.name, query)
            if (score >= MIN_SCORE) e to score else null
        }
    }

    private companion object {
        const val TAG = "search"
        const val STALE_MS = 5 * 60_000L
        const val MAX_ENTRIES = 20_000
        const val MAX_DEPTH = 6

        /** A substring anywhere in the name: "report" finds "q3-report.pdf". */
        const val MIN_SCORE = 200

        /** Trees of generated files and module caches (~/go is Go's), too many and never looked for by name. */
        val SKIPPED = setOf("node_modules", "__pycache__", "target", "venv", "go")
    }
}
