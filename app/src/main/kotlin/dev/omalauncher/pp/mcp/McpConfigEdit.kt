package dev.omalauncher.pp.mcp

/**
 * Adds the launcher's MCP server to a coding agent's own config file, as text: the user's file keeps its order,
 * formatting and comments, and nothing that's already there changes. A file that can't be read safely is left alone.
 */
object McpConfigEdit {
    sealed interface Result {
        /** The file's new text. */
        data class Write(
            val text: String,
        ) : Result

        /** The server is there already, as we wrote it or as the user changed it. */
        data object Present : Result

        data class Unreadable(
            val why: String,
        ) : Result
    }

    /**
     * A JSON (or JSONC, comments and trailing commas allowed) config with servers in an object [section] at the top,
     * as `"section": { "name": entry }`; [entry] is JSON. A missing or empty file becomes one with just that.
     */
    fun json(
        text: String?,
        section: String,
        name: String,
        entry: String,
    ): Result {
        if (text.isNullOrBlank()) return Result.Write("{\n  ${quote(section)}: {\n    ${quote(name)}: $entry\n  }\n}\n")
        val root =
            try {
                JsonScan(text).document()
            } catch (e: IllegalArgumentException) {
                return Result.Unreadable(e.message.orEmpty())
            }
        val servers = root.members.firstOrNull { it.key == section } ?: return Result.Write(insert(text, root, section, "{\n    ${quote(name)}: $entry\n  }", 1))
        val obj = servers.obj ?: return Result.Unreadable("$section isn't an object")
        if (obj.members.any { it.key == name }) return Result.Present
        return Result.Write(insert(text, obj, name, entry, 2))
    }

    /**
     * A TOML config with servers as `[mcp_servers.name]` tables (Codex's). Found by looking at table headers and
     * keys line by line, so it works on whatever TOML the agent wrote (quoted keys, dates); a new table goes at the end.
     */
    fun toml(
        text: String?,
        name: String,
        entry: String,
    ): Result {
        val table = "$TOML_SECTION.$name"
        var header = ""
        var multiline: String? = null
        for (raw in text.orEmpty().lines()) {
            val line = raw.trim()
            // Inside a """ or ''' string, nothing is a header or a key.
            multiline?.let { q ->
                if (line.split(q).size % 2 == 0) multiline = null
                continue
            }
            for (q in listOf("\"\"\"", "'''")) if (line.split(q).size % 2 == 0) multiline = q
            if (line.startsWith("[")) {
                header = bareKey(line.removePrefix("[[").removePrefix("[").substringBefore("]"))
                if (header == table || header.startsWith("$table.")) return Result.Present
                continue
            }
            val key = line.substringBefore('=').takeIf { '=' in line && !line.startsWith("#") }?.let(::bareKey) ?: continue
            val full = if (header.isEmpty()) key else "$header.$key"
            if (full == table || full.startsWith("$table.")) return Result.Present
            // `mcp_servers = { … }` at the top: a [mcp_servers.x] table after it would be a duplicate.
            if (full == TOML_SECTION) return Result.Unreadable("$TOML_SECTION is an inline table")
        }
        val base = text.orEmpty().let { if (it.isEmpty() || it.endsWith("\n")) it else "$it\n" }
        return Result.Write(base + (if (base.isBlank()) "" else "\n") + "[$table]\n$entry\n")
    }

    private const val TOML_SECTION = "mcp_servers"

    /** A TOML key or header as bare dotted words, quotes and spaces dropped, so `mcp_servers . "x"` reads mcp_servers.x. */
    private fun bareKey(key: String) = key.split('.').joinToString(".") { it.trim().trim('"', '\'') }

    /** [text] with `"key": value` as [obj]'s first member, indented as at [depth]. */
    private fun insert(
        text: String,
        obj: JsonScan.Obj,
        key: String,
        value: String,
        depth: Int,
    ): String {
        val indent = "  ".repeat(depth)
        val member = "\n$indent${quote(key)}: $value"
        val after = if (obj.members.isEmpty()) "\n" + "  ".repeat(depth - 1) else ","
        return text.substring(0, obj.open + 1) + member + after + text.substring(obj.open + 1)
    }

    fun quote(s: String) = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /**
     * Just enough of a JSON reader to find objects' members and where objects start: values are skipped, not
     * decoded. Takes `//` and block comments and trailing commas, as JSONC configs have them. Throws
     * [IllegalArgumentException] for anything else that isn't JSON.
     */
    private class JsonScan(
        private val s: String,
    ) {
        class Member(
            val key: String,
            /** The value, when it's an object. */
            val obj: Obj?,
        )

        class Obj(
            /** Where its `{` is. */
            val open: Int,
            val members: List<Member>,
        )

        private var i = 0

        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at character $i")

        fun document(): Obj {
            gaps()
            if (s.getOrNull(i) != '{') fail("not a JSON object")
            val root = obj(0)
            gaps()
            if (i < s.length) fail("trailing text")
            return root
        }

        private fun gaps() {
            while (i < s.length) {
                when {
                    s[i].isWhitespace() -> i++
                    s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                    s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: fail("unterminated comment")
                    else -> return
                }
            }
        }

        private fun obj(depth: Int): Obj {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            val open = i++
            val members = ArrayList<Member>()
            while (true) {
                gaps()
                when (s.getOrNull(i)) {
                    '}' -> {
                        i++
                        return Obj(open, members)
                    }
                    '"' -> {
                        val key = string()
                        gaps()
                        if (s.getOrNull(i++) != ':') fail("expected ':'")
                        gaps()
                        members += Member(key, if (s.getOrNull(i) == '{') obj(depth + 1) else value(depth + 1).let { null })
                        gaps()
                        when (s.getOrNull(i)) {
                            ',' -> i++
                            '}' -> Unit
                            else -> fail("expected ',' or '}'")
                        }
                    }
                    else -> fail("expected a key")
                }
            }
        }

        private fun value(depth: Int) {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            when (val c = s.getOrNull(i) ?: fail("unexpected end")) {
                '{' -> obj(depth)
                '"' -> string()
                '[' -> {
                    i++
                    while (true) {
                        gaps()
                        if (s.getOrNull(i) == ']') {
                            i++
                            return
                        }
                        value(depth + 1)
                        gaps()
                        when (s.getOrNull(i)) {
                            ',' -> i++
                            ']' -> Unit
                            else -> fail("expected ',' or ']'")
                        }
                    }
                }
                else -> {
                    if (c != '-' && !c.isLetterOrDigit()) fail("unexpected '$c'")
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.")) i++
                    val word = s.substring(start, i)
                    if (word !in listOf("true", "false", "null") && word.toDoubleOrNull() == null) fail("unexpected '$word'")
                }
            }
        }

        private fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                when (val c = s.getOrNull(i++) ?: fail("unterminated string")) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s.getOrNull(i++) ?: fail("unterminated string")) {
                            'u' -> {
                                out.append(s.substring(i, (i + 4).coerceAtMost(s.length)).toIntOrNull(16)?.toChar() ?: fail("bad escape"))
                                i += 4
                            }
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private companion object {
            const val MAX_DEPTH = 256
        }
    }
}
