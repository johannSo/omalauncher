package dev.omalauncher.pp.search

import android.util.Log
import dev.omalauncher.pp.linux.Package
import dev.omalauncher.pp.widgets.lang.matchScore
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The packages pacman's sync databases list, read straight from them (`/var/lib/pacman/sync/<repo>.db`, gzipped tars of each
 * package's `desc`), so the bar finds packages without starting proot, which `pacman -Ss` takes. Read again when a
 * database changes, after `pacman -Sy`.
 */
class PackageIndex(
    private val rootfs: File,
) {
    @Volatile private var packages: List<Package> = emptyList()

    @Volatile private var readFor: Map<String, Long> = emptyMap()

    /** Reads the databases again if one changed. Blocks: call off the main thread. */
    fun refresh() {
        val dbs = File(rootfs, "var/lib/pacman/sync").listFiles { f -> f.name.endsWith(".db") }.orEmpty().sortedBy { it.name }
        val stamps = dbs.associate { it.name to it.lastModified() }
        if (stamps == readFor) return
        readFor = stamps
        val local = installedNames()
        packages =
            dbs.flatMap { db ->
                runCatching { db.inputStream().use { parseSyncDb(it, db.name.removeSuffix(".db")) } }
                    .onFailure { Log.w(TAG, "cannot read ${db.name}", it) }
                    .getOrDefault(emptyList())
            }.map { if (it.name in local) it.copy(installed = true) else it }
    }

    /** What's installed, by the local database's directory names, `name-pkgver-pkgrel`, as [dev.omalauncher.pp.linux.Pacman] reads them. */
    private fun installedNames(): Set<String> =
        File(rootfs, "var/lib/pacman/local").list().orEmpty().mapTo(HashSet()) { it.substringBeforeLast('-').substringBeforeLast('-') }

    /** Whether the databases have been read: without them, "install x" asks pacman itself. */
    val ready get() = packages.isNotEmpty()

    /**
     * Packages for [query], best first, scored as apps' names are. [exactOnly]: just a package called [query] and those
     * whose names start with it, and only if there's one called that, for a plain query, which is rarely a package's name;
     * otherwise any whose name or description has it, as `pacman -Ss` finds them.
     */
    fun search(
        query: String,
        exactOnly: Boolean,
    ): List<Pair<Package, Int>> {
        val q = query.trim().lowercase()
        if (q.length < 2 || ' ' in q) return emptyList()
        val all = packages
        if (exactOnly) {
            if (all.none { it.name == q }) return emptyList()
            return all.filter { it.name.startsWith(q) }.map { it to matchScore(it.name, q) }
        }
        return all.mapNotNull { p ->
            val score = matchScore(p.name, q).takeIf { it > 0 && q in p.name } ?: if (q in p.description.lowercase()) BY_DESCRIPTION else 0
            if (score > 0) p to score else null
        }.sortedWith(compareByDescending<Pair<Package, Int>> { it.second }.thenBy { it.first.name.length })
    }

    private companion object {
        const val TAG = "search"

        /** Found in the description only: under any name that has it. */
        const val BY_DESCRIPTION = 50
    }
}

/**
 * The packages in a sync database [input], a gzipped tar of `name-version/desc` files, as pacman's `%NAME%`, `%VERSION%`
 * and `%DESC%` give them; [repo] is the database's name. Pure, for the unit tests.
 */
fun parseSyncDb(
    input: InputStream,
    repo: String,
): List<Package> {
    val tar = BufferedInputStream(GZIPInputStream(input, 1 shl 16), 1 shl 16)
    val out = ArrayList<Package>()
    val header = ByteArray(512)
    var longName: String? = null
    while (true) {
        if (!readFully(tar, header)) break
        // Two zero blocks end the archive; one is enough to stop at.
        if (header.all { it == 0.toByte() }) break
        val size = octal(header, 124, 12)
        val type = header[156].toInt().toChar()
        val prefix = cString(header, 345, 155)
        val name = longName ?: (if (prefix.isEmpty()) cString(header, 0, 100) else "$prefix/${cString(header, 0, 100)}")
        longName = null
        val padded = (size + 511) / 512 * 512
        when {
            // GNU's long name: the next entry's name is this one's data.
            type == 'L' -> {
                longName = String(readBytes(tar, size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
                skip(tar, padded - size)
            }
            name.endsWith("/desc") && size in 1..(1 shl 20) -> {
                desc(String(readBytes(tar, size.toInt()), Charsets.UTF_8), repo)?.let(out::add)
                skip(tar, padded - size)
            }
            else -> skip(tar, padded)
        }
    }
    return out
}

/** A `desc` file's package: its `%NAME%`, `%VERSION%` and `%DESC%`, each on the lines after it. */
private fun desc(
    text: String,
    repo: String,
): Package? {
    val fields = HashMap<String, String>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val key = lines[i]
        if (key.startsWith('%') && key.endsWith('%') && i + 1 < lines.size) fields[key.trim('%')] = lines[i + 1]
        i++
    }
    val name = fields["NAME"] ?: return null
    return Package(name, fields["VERSION"].orEmpty(), fields["DESC"].orEmpty(), repo, installed = false)
}

private fun readFully(
    s: InputStream,
    into: ByteArray,
): Boolean {
    var n = 0
    while (n < into.size) {
        val r = s.read(into, n, into.size - n)
        if (r < 0) return false
        n += r
    }
    return true
}

private fun readBytes(
    s: InputStream,
    size: Int,
): ByteArray = ByteArray(size).also { if (!readFully(s, it)) throw java.io.EOFException() }

private fun skip(
    s: InputStream,
    count: Long,
) {
    var left = count
    while (left > 0) {
        val n = s.skip(left)
        if (n <= 0) {
            if (s.read() < 0) return
            left--
        } else {
            left -= n
        }
    }
}

private fun cString(
    b: ByteArray,
    from: Int,
    len: Int,
): String {
    var end = from
    while (end < from + len && b[end] != 0.toByte()) end++
    return String(b, from, end - from, Charsets.UTF_8)
}

private fun octal(
    b: ByteArray,
    from: Int,
    len: Int,
): Long {
    var v = 0L
    for (i in from until from + len) {
        val c = b[i].toInt()
        // Leading spaces pad it; a NUL or a space after the digits ends it.
        if (c in '0'.code..'7'.code) v = v * 8 + (c - '0'.code) else if (c == 0 || v > 0) break
    }
    return v
}
