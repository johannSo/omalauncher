package dev.omalauncher.pp.linux

import dev.omalauncher.pp.widgets.lang.Json
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Num
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import dev.omalauncher.pp.widgets.lang.Value

/**
 * The launcher's hooks in the agents' own config files, which are the user's: Claude Code's ~/.claude/settings.json
 * and Codex's ~/.codex/hooks.json share a format (`hooks.<Event>` lists of `{matcher, hooks: [{type, command,
 * timeout}]}`). Ours are the commands that start with [HELPER]; everything else in the file stays as it was, and a
 * file that doesn't parse is left alone.
 */
object AgentHooks {
    /** The guest helper the hooks run (assets/agent-status.sh). */
    const val HELPER = "/usr/local/bin/omalauncher-agent-status"

    /** What Claude Code reports: the session's start and end, prompts, tools, its questions, the end of each turn. */
    val CLAUDE_EVENTS = listOf("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PermissionRequest", "Notification", "Stop", "SessionEnd")

    /** Codex's hooks have no Notification; its notify (in /etc/codex/config.toml) reports the end of turns too. */
    val CODEX_EVENTS = listOf("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PermissionRequest", "Stop", "SessionEnd")

    /** Seconds an ordinary hook may take; ours write a file and exit. */
    private const val QUICK_TIMEOUT = 10

    /** How long past the launcher's own timeout a permission hook may wait, so the launcher's "ask in the terminal" comes first. */
    private const val PERMISSION_MARGIN = 30

    fun command(agent: String) = "$HELPER $agent"

    /**
     * [text] (null: no file yet) with [agent]'s hooks for [events], or without any of ours when [events] is empty.
     * [waitSeconds] is how long the launcher waits for an answer to a permission request. Null when the file is
     * right as it is, or isn't JSON we can safely rewrite.
     */
    fun merge(
        text: String?,
        agent: String,
        events: List<String>,
        waitSeconds: Int,
    ): String? {
        if (text.isNullOrBlank() && events.isEmpty()) return null
        val root = if (text.isNullOrBlank()) Obj(emptyMap()) else runCatching { Json.parse(text) }.getOrNull() as? Obj ?: return null
        val hooks = root.fields["hooks"]
        if (hooks != null && hooks !is Obj) return null
        val merged = LinkedHashMap<String, Value>()
        for ((event, groups) in (hooks as? Obj)?.fields.orEmpty()) {
            // A list we don't understand is the user's to fix; ours go elsewhere then.
            if (groups !is Lst) {
                merged[event] = groups
                continue
            }
            val kept = groups.items.mapNotNull(::withoutOurs)
            if (kept.isNotEmpty() || groups.items.isEmpty()) merged[event] = Lst(kept)
        }
        for (event in events) {
            val existing = merged[event]
            if (existing != null && existing !is Lst) continue
            val timeout = if (event == "PermissionRequest") waitSeconds + PERMISSION_MARGIN else QUICK_TIMEOUT
            val ours = Obj(mapOf("hooks" to Lst(listOf(Obj(mapOf("type" to Str("command"), "command" to Str(command(agent)), "timeout" to Num(timeout.toDouble())))))))
            merged[event] = Lst(((existing as? Lst)?.items ?: emptyList()) + ours)
        }
        val fields = LinkedHashMap(root.fields)
        if (merged.isEmpty()) fields.remove("hooks") else fields["hooks"] = Obj(merged)
        val next = Obj(fields)
        if (next == root && !text.isNullOrBlank()) return null
        return pretty(next) + "\n"
    }

    /** A matcher group without our handlers; null if nothing of its own is left. Groups of a shape we don't know stay. */
    private fun withoutOurs(group: Value): Value? {
        val fields = (group as? Obj)?.fields ?: return group
        val handlers = fields["hooks"] as? Lst ?: return group
        val kept = handlers.items.filter { !isOurs(it) }
        if (kept.size == handlers.items.size) return group
        if (kept.isEmpty()) return null
        return Obj(LinkedHashMap(fields).apply { put("hooks", Lst(kept)) })
    }

    private fun isOurs(handler: Value): Boolean {
        val command = ((handler as? Obj)?.fields?.get("command") as? Str)?.v ?: return false
        return command == HELPER || command.startsWith("$HELPER ")
    }

    /** JSON as Claude Code writes its settings: two spaces per level, one field or item per line. */
    fun pretty(
        v: Value,
        indent: String = "",
    ): String =
        when (v) {
            is Obj ->
                if (v.fields.isEmpty()) {
                    "{}"
                } else {
                    val inner = "$indent  "
                    v.fields.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) -> inner + Json.write(Str(k)) + ": " + pretty(x, inner) }
                }
            is Lst ->
                if (v.items.isEmpty()) {
                    "[]"
                } else {
                    val inner = "$indent  "
                    v.items.joinToString(",\n", "[\n", "\n$indent]") { inner + pretty(it, inner) }
                }
            else -> Json.write(v) ?: "null"
        }
}
