package dev.omalauncher.pp.linux

import dev.omalauncher.pp.widgets.lang.Json
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Null
import dev.omalauncher.pp.widgets.lang.Num
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import dev.omalauncher.pp.widgets.lang.TRUE
import dev.omalauncher.pp.widgets.lang.Value

/** What a coding agent in a terminal is doing, as its hooks say; [word] is how widgets and the guest name it. */
enum class AgentPhase(
    val word: String,
) {
    Running("running"),

    /** Waiting for the user's next prompt, or an answer it asked for in the terminal. */
    Input("input"),

    /** Waiting for a yes or no to run a tool: on the phone while it has [AgentSession.requests], else in the terminal. */
    Permission("permission"),

    /** Finished its turn; it waits for input too, but this is the news the notification brings. */
    Done("done"),
}

/**
 * A request file the guest helper (assets/agent-status.sh) drops into the inbox: a word saying what it is, then its
 * lines. A report is `report`, the agent's bin, the helper's pid and its ancestors' (which find the terminal it runs
 * in), `wait` when the helper waits for an answer on its FIFO (else `-`), and the agent's JSON to the end. An answer
 * is `answer`, the request's id and `allow` or `deny`.
 */
sealed interface AgentMessage {
    data class Report(
        val agent: String,
        val pids: List<Int>,
        val waits: Boolean,
        val payload: String,
    ) : AgentMessage

    data class Answer(
        val request: String,
        val allow: Boolean,
    ) : AgentMessage

    companion object {
        /** Request ids are file names the helper makes (`<µs>-<pid>`); anything else could name a path. */
        val ID = Regex("[0-9A-Za-z_-]{1,64}")

        fun parse(text: String): AgentMessage? {
            val lines = text.split('\n', limit = 5)
            return when (lines[0].trim()) {
                "report" -> {
                    if (lines.size < 5) return null
                    val agent = lines[1].trim().takeIf { ID.matches(it) } ?: return null
                    val pids = lines[2].trim().split(' ').mapNotNull { it.toIntOrNull() }.filter { it > 0 }
                    Report(agent, pids, lines[3].trim() == "wait", lines[4])
                }
                "answer" -> {
                    val request = lines.getOrNull(1)?.trim()?.takeIf { ID.matches(it) } ?: return null
                    when (lines.getOrNull(2)?.trim()) {
                        "allow" -> Answer(request, true)
                        "deny" -> Answer(request, false)
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}

/**
 * What an agent's hook or notify call says happened. Claude Code's hooks and Codex's share their JSON
 * (`hook_event_name`, `session_id`, `cwd`, `tool_name`, `tool_input`, …); Codex's notify sends
 * `{"type": "agent-turn-complete", "thread-id", "cwd", "last-assistant-message"}`.
 */
data class AgentEvent(
    val kind: Kind,
    /** The agent's own id for the session; null where it gives none. */
    val session: String?,
    /** What the session is about now: the prompt, the tool, the last message; null keeps what was said before. */
    val summary: String?,
    val cwd: String?,
    /** Where Claude Code keeps the conversation, for the last message when Stop doesn't say it. */
    val transcript: String? = null,
    /** Codex's notify, which knows no session of the hooks': it's matched to one in the same terminal. */
    val fromNotify: Boolean = false,
) {
    enum class Kind {
        Start,
        Prompt,
        Tool,
        ToolDone,

        /** A permission request a hook may answer: PermissionRequest. */
        Permission,

        /** The agent asking for permission in the terminal (Claude Code's permission_prompt notification). */
        PermissionPrompt,
        Input,
        Done,
        End,
    }

    companion object {
        /** The longest summary kept: a notification shows a few lines, a widget fewer. */
        const val SUMMARY_MAX = 300

        /** [payload] from [agent]'s hooks or notify; null for what doesn't change its status (a subagent stopping, a login). */
        fun parse(payload: String): AgentEvent? {
            val json = runCatching { Json.parse(payload.trim()) }.getOrNull() as? Obj ?: return null
            val f = json.fields
            val cwd = f.text("cwd")
            // Codex's notify.
            f.text("type")?.let { type ->
                if (type != "agent-turn-complete") return null
                return AgentEvent(Kind.Done, f.text("thread-id"), f.text("last-assistant-message")?.let(::summarize), cwd, fromNotify = true)
            }
            val session = f.text("session_id") ?: return null
            fun event(
                kind: Kind,
                summary: String? = null,
            ) = AgentEvent(kind, session, summary?.let(::summarize), cwd, f.text("transcript_path"))
            return when (f.text("hook_event_name")) {
                "SessionStart" -> event(Kind.Start)
                "UserPromptSubmit" -> event(Kind.Prompt, f.text("prompt"))
                "PreToolUse" -> event(Kind.Tool, describeTool(f.text("tool_name"), f["tool_input"]))
                "PostToolUse", "PostToolUseFailure" -> event(Kind.ToolDone)
                "PermissionRequest" -> event(Kind.Permission, describeTool(f.text("tool_name"), f["tool_input"]))
                "Notification" ->
                    when (f.text("notification_type")) {
                        "permission_prompt" -> event(Kind.PermissionPrompt, f.text("message"))
                        "idle_prompt", "agent_needs_input", "elicitation_dialog", "elicitation_url_dialog" -> event(Kind.Input, f.text("message"))
                        "agent_completed" -> event(Kind.Done)
                        else -> null
                    }
                // A stop hook that made it go on isn't the end of its turn.
                "Stop" -> if (f["stop_hook_active"] == TRUE) null else event(Kind.Done, f.text("last_assistant_message"))
                "SessionEnd" -> event(Kind.End)
                else -> null
            }
        }

        /** "Bash: npm test", "Edit: src/Main.kt": the tool and what it's for, as the terminal's permission prompt names them. */
        fun describeTool(
            name: String?,
            input: Value?,
        ): String? {
            name ?: return null
            val f = (input as? Obj)?.fields.orEmpty()
            // Codex's tools describe themselves; a command may come as a list of words.
            val what =
                f.text("description").takeIf { name !in DESCRIBED_BY_INPUT }
                    ?: (f["command"] as? Lst)?.items?.joinToString(" ")
                    ?: listOf("command", "file_path", "notebook_path", "path", "url", "query", "pattern", "prompt").firstNotNullOfOrNull { f.text(it) }
                    ?: f.values.firstNotNullOfOrNull { (it as? Str)?.v?.takeIf(String::isNotBlank) }
            return if (what == null) name else "$name: ${what.lineSequence().first()}"
        }

        /** Claude Code's tools whose `description` is Claude's note on why, not what runs: the prompt shows the command. */
        private val DESCRIBED_BY_INPUT = setOf("Bash", "Task", "Agent")

        /** One line, without markdown's emphasis, cut at [SUMMARY_MAX]. */
        fun summarize(text: String): String? {
            val line = text.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty()) return null
            return if (line.length <= SUMMARY_MAX) line else line.take(SUMMARY_MAX - 1).trimEnd() + "…"
        }

        /**
         * The last thing the assistant said, from the end of a Claude Code transcript (JSON lines): Stop gives it as
         * `last_assistant_message` since 2026, and older versions only point at the transcript.
         */
        fun lastAssistantText(tail: String): String? {
            for (line in tail.lineSequence().toList().asReversed()) {
                if (!line.contains("\"assistant\"")) continue
                val entry = runCatching { Json.parse(line) }.getOrNull() as? Obj ?: continue
                if (entry.fields.text("type") != "assistant") continue
                val content = (entry.fields["message"] as? Obj)?.fields?.get("content")
                val text =
                    when (content) {
                        is Str -> content.v
                        is Lst -> content.items.mapNotNull { (it as? Obj)?.fields?.takeIf { p -> p.text("type") == "text" }?.text("text") }.joinToString("\n")
                        else -> null
                    }
                summarize(text.orEmpty())?.let { return it }
            }
            return null
        }

        private fun Map<String, Value>.text(key: String) = (this[key] as? Str)?.v?.takeIf { it.isNotBlank() }
    }
}

/** A permission request the agent's hook waits on: answered from the phone, or given up on after the timeout. */
data class AgentRequest(
    /** The helper's request id; its answer goes to `<id>.fifo` in the inbox. */
    val id: String,
    val summary: String?,
    /** When it came, in ms. */
    val asked: Long,
)

/** One agent session in a terminal. */
data class AgentSession(
    /** The launcher's: the notification's id and the key widgets open it by. */
    val id: Int,
    /** The agent's bin (claude, codex). */
    val agent: String,
    /** The agent's own id for it. */
    val session: String,
    /** The pid of the terminal (the Wayland client) it runs in. */
    val window: Int,
    val phase: AgentPhase,
    val summary: String?,
    val cwd: String?,
    /** When [phase] began, in ms. */
    val since: Long,
    /** When it last reported, in ms. */
    val updated: Long,
    /** Permission requests waiting for the phone, oldest first: parallel subagents can each ask. */
    val requests: List<AgentRequest> = emptyList(),
    /** The user swiped its notification away: it comes back with the next news, not with every tool call. */
    val dismissed: Boolean = false,
) {
    /** The request the notification's Allow and Deny answer. */
    val request get() = requests.firstOrNull()

    /** What widgets read in `android.agents`' `sessions`. */
    fun value(name: String): Value =
        Obj(
            mapOf(
                "key" to Str("$KEY_PREFIX$id"),
                "agent" to Str(agent),
                "name" to Str(name),
                "status" to Str(phase.word),
                "summary" to (summary?.let(::Str) ?: Null),
                "cwd" to (cwd?.let(::Str) ?: Null),
                "since" to Num(since / 1000.0),
                "request" to (request?.id?.let(::Str) ?: Null),
                "icon" to Str("icon:l:agent-$agent"),
            ),
        )

    companion object {
        /** `open("agent:3")` opens session 3's terminal. */
        const val KEY_PREFIX = "agent:"
    }
}

/**
 * The agent sessions running in terminals, from their reports: which phase each is in, what it's doing, and which
 * permission requests wait for the phone. Pure bookkeeping, so it can be tested; [AgentStatus][dev.omalauncher.pp.AgentStatus]
 * feeds it and shows the result. Not thread-safe.
 */
class AgentSessions {
    private var nextId = 1

    var all: List<AgentSession> = emptyList()
        private set

    /** What a change did to a session, for its notification. */
    data class Change(
        val session: AgentSession,
        /** It has news worth a sound: done, waiting for input, or asking permission. */
        val alert: Boolean = false,
        /** It ended: its notification goes. */
        val removed: Boolean = false,
        /** Requests no one will answer from the phone any more: the helpers waiting on them are told to ask in the terminal. */
        val dropped: List<AgentRequest> = emptyList(),
    )

    fun byId(id: Int) = all.firstOrNull { it.id == id }

    /**
     * Applies [event] from [agent] in the terminal whose pid is [window]. [request] is the helper's id when it waits
     * for an answer to a permission request. Null when it changes nothing: a session that ended before it began,
     * or news from one that isn't known.
     */
    fun report(
        agent: String,
        event: AgentEvent,
        window: Int,
        request: String?,
        now: Long,
    ): Change? {
        val old =
            all.firstOrNull { it.agent == agent && it.session == event.session && event.session != null }
                // Codex's notify knows its thread, not the hooks' session: the latest of its sessions in that terminal.
                ?: if (event.fromNotify) all.filter { it.agent == agent && it.window == window }.maxByOrNull { it.updated } else null
        if (event.kind == AgentEvent.Kind.End) {
            old ?: return null
            all = all - old
            return Change(old, removed = true, dropped = old.requests)
        }
        val session =
            old ?: AgentSession(
                id = nextId++,
                agent = agent,
                session = event.session ?: "notify-$window",
                window = window,
                phase = AgentPhase.Input,
                summary = null,
                cwd = event.cwd,
                since = now,
                updated = now,
            )
        val phase =
            when (event.kind) {
                AgentEvent.Kind.Start -> AgentPhase.Input
                AgentEvent.Kind.Prompt, AgentEvent.Kind.Tool, AgentEvent.Kind.ToolDone -> AgentPhase.Running
                AgentEvent.Kind.Permission, AgentEvent.Kind.PermissionPrompt -> AgentPhase.Permission
                AgentEvent.Kind.Input -> AgentPhase.Input
                AgentEvent.Kind.Done -> AgentPhase.Done
                AgentEvent.Kind.End -> error("handled above")
            }
        val asked = event.kind == AgentEvent.Kind.Permission && request != null
        // A turn that ended leaves nothing to answer; tools of a subagent may run while another one asks.
        val ended = phase == AgentPhase.Done || phase == AgentPhase.Input
        val dropped = if (ended) session.requests else emptyList()
        val requests =
            when {
                asked -> session.requests + AgentRequest(request!!, event.summary, now)
                ended -> emptyList()
                else -> session.requests
            }
        // While the phone has a question to answer, the session shows it, whatever else its subagents report.
        val shown = if (requests.isNotEmpty() && !asked && phase == AgentPhase.Running) AgentPhase.Permission else phase
        val alert =
            when {
                asked -> true
                old == null -> false
                // A wait the user hasn't heard about yet; done and then idle is one wait.
                shown == AgentPhase.Permission -> session.phase != AgentPhase.Permission
                shown == AgentPhase.Done || shown == AgentPhase.Input -> session.phase == AgentPhase.Running || session.phase == AgentPhase.Permission
                else -> false
            }
        val summary =
            when {
                // The question on the phone stays what the session says until it's answered.
                shown == AgentPhase.Permission && requests.isNotEmpty() -> requests.first().summary ?: session.summary
                else -> event.summary ?: session.summary
            }
        val next =
            session.copy(
                phase = shown,
                summary = summary,
                cwd = event.cwd ?: session.cwd,
                since = if (shown != session.phase || old == null) now else session.since,
                updated = now,
                requests = requests,
                dismissed = session.dismissed && !alert,
            )
        all = if (old == null) all + next else all.map { if (it.id == next.id) next else it }
        return Change(next, alert = alert, dropped = dropped)
    }

    /** Takes request [id] off its session, answered or given up on; null if no session waits for it any more. */
    fun resolve(
        id: String,
        now: Long,
        answered: Boolean,
    ): Change? {
        val session = all.firstOrNull { s -> s.requests.any { it.id == id } } ?: return null
        val requests = session.requests.filter { it.id != id }
        val next =
            when {
                requests.isNotEmpty() -> session.copy(requests = requests, summary = requests.first().summary ?: session.summary)
                // Answered: the tool runs, or the agent goes on without it. Given up on: it asks in the terminal now.
                answered -> session.copy(requests = requests, phase = AgentPhase.Running, since = now)
                else -> session.copy(requests = requests)
            }
        all = all.map { if (it.id == next.id) next else it }
        return Change(next)
    }

    /** Requests waiting longer than [timeoutMs]: the caller tells their helpers to ask in the terminal, then [resolve]s them. */
    fun expired(
        now: Long,
        timeoutMs: Long,
    ): List<AgentRequest> = all.flatMap { it.requests }.filter { now - it.asked >= timeoutMs }

    /** Notes the user dismissed [id]'s notification; null if it's gone. */
    fun dismiss(id: Int): AgentSession? {
        val session = byId(id) ?: return null
        val next = session.copy(dismissed = true)
        all = all.map { if (it.id == id) next else it }
        return next
    }

    /** Drops the sessions whose terminal is gone, by its pid; returns them. */
    fun prune(alive: (Int) -> Boolean): List<AgentSession> {
        val gone = all.filter { !alive(it.window) }
        if (gone.isNotEmpty()) all = all - gone.toSet()
        return gone
    }

    fun clear(): List<AgentSession> = all.also { all = emptyList() }
}
