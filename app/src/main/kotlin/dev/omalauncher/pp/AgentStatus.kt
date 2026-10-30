package dev.omalauncher.pp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.FileObserver
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.pp.linux.AGENTS
import dev.omalauncher.pp.linux.AgentEvent
import dev.omalauncher.pp.linux.AgentHooks
import dev.omalauncher.pp.linux.AgentMessage
import dev.omalauncher.pp.linux.AgentSession
import dev.omalauncher.pp.linux.AgentSessions
import dev.omalauncher.pp.linux.Proot
import dev.omalauncher.pp.theme.AgentStatusStyle
import dev.omalauncher.pp.upgrade.Managed
import dev.omalauncher.pp.upgrade.Policy
import dev.omalauncher.pp.upgrade.writeAtomically
import dev.omalauncher.pp.wayland.XdgToplevel
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Value
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "agent-status"

/**
 * Coding agents in terminals report to Android. Their hooks (Claude Code's in ~/.claude/settings.json, Codex's in
 * ~/.codex/hooks.json and its notify) run the guest helper `omalauncher-agent-status` (assets/agent-status.sh),
 * which drops a request into an inbox in the runtime dir, like owl-send does into the widgets' (see
 * [AgentMessage] for its format). Each session gets a notification, updated in place, and widgets read them in
 * `android.agents`' `sessions`. A permission request's helper waits on a FIFO of its own for Allow or Deny from the
 * notification, so an answer reaches only the hook that asked; after `agents.approve-seconds` it's told to leave
 * the question to the terminal.
 *
 * A session belongs to the terminal it runs in: the Wayland client among the helper's ancestors. Reports from
 * agents outside a terminal (`claude -p` in a widget's command) are ignored. Main thread, but for file work.
 */
class AgentStatus(
    private val context: Context,
    private val runtime: Runtime,
) {
    private val proot get() = runtime.proot
    private val inbox = File(proot.runtimeDir, INBOX)
    private val model = AgentSessions()
    private val notifications = AgentNotifications(context).also { it.cancelAll() }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "failed", e) })

    /** The sessions, for Compose and widgets; replaced on every change. */
    var sessions by mutableStateOf<List<AgentSession>>(emptyList())
        private set

    private val enabled get() = runtime.style.style[AgentStatusStyle.STATUS]
    private val timeoutMs get() = runtime.style.style[AgentStatusStyle.APPROVE_SECONDS] * 1000L

    /** Request files in the order they came, so a tool's start is never taken after its end. */
    private val queue = Channel<String>(Channel.UNLIMITED)

    @Suppress("unused") // Held so it isn't collected, which would stop the events.
    private var observer: FileObserver? = null
    private var started = false
    private var watch: Job? = null

    /** The helper; it runs from every agent's hooks, and quits at once while [inbox] is missing. */
    fun managed(): List<Managed> =
        listOf(Managed.asset(context, "agent-status.sh", File(proot.rootfs, AgentHooks.HELPER.removePrefix("/")), Policy.Owned, executable = true))

    /** Linux is up: listens while `agents.status` is on, and follows the setting. */
    fun start() {
        if (started) return
        started = true
        scope.launch { for (name in queue) receive(name) }
        scope.launch {
            snapshotFlow { enabled to runtime.style.style[AgentStatusStyle.APPROVE_SECONDS] }.collect { (on, _) -> turn(on) }
        }
        // A window that closed ends the sessions in it; tiles' terminals are checked by [watchSessions].
        scope.launch { snapshotFlow { runtime.wm.appWindows.size }.collect { prune() } }
    }

    private suspend fun turn(on: Boolean) {
        if (on) {
            withContext(Dispatchers.IO) { inbox.mkdirs() }
            if (observer == null) {
                observer =
                    object : FileObserver(inbox, CLOSE_WRITE or MOVED_TO) {
                        override fun onEvent(
                            event: Int,
                            path: String?,
                        ) {
                            if (path != null && path.endsWith(".req")) queue.trySend(path)
                        }
                    }.also { it.startWatching() }
            }
        } else {
            observer?.stopWatching()
            observer = null
            // Helpers still waiting leave their question to the terminal.
            val waiting = model.all.flatMap { it.requests }
            withContext(Dispatchers.IO) {
                for (r in waiting) reply(r.id, ASK)
                runCatching { LauncherData.deleteTree(inbox) }
            }
            for (s in model.clear()) {
                posting.remove(s.id)?.cancel()
                notifications.cancel(s.id)
            }
            publish()
        }
        syncHooks()
    }

    /**
     * Puts the hooks into the agents' configs, or takes ours out with `agents.status` off or the agent removed. Each
     * file is the user's: only our entries change, and only when they have to.
     */
    fun syncHooks() {
        if (!started) return
        val on = enabled
        val wait = runtime.style.style[AgentStatusStyle.APPROVE_SECONDS]
        val installed = runtime.agents.installed.map { it.bin }
        scope.launch(Dispatchers.IO) {
            hook("$GUEST_HOME/.claude/settings.json", "claude", if (on && "claude" in installed) AgentHooks.CLAUDE_EVENTS else emptyList(), wait)
            hook("$GUEST_HOME/.codex/hooks.json", "codex", if (on && "codex" in installed) AgentHooks.CODEX_EVENTS else emptyList(), wait)
        }
    }

    private fun hook(
        guestPath: String,
        agent: String,
        events: List<String>,
        wait: Int,
    ) {
        // Followed inside the guest, so a settings.json linked from a dotfiles repo is changed where it lives.
        val file = proot.guestFile(guestPath) ?: return
        runCatching {
            val text = if (file.exists()) file.readText() else null
            val next = AgentHooks.merge(text, agent, events, wait) ?: return
            writeAtomically(file, next.toByteArray())
            Log.i(TAG, "${if (events.isEmpty()) "removed" else "wrote"} the hooks in $guestPath")
        }.onFailure { Log.w(TAG, "cannot update $guestPath", it) }
    }

    private suspend fun receive(name: String) {
        val file = File(inbox, name)
        val text =
            withContext(Dispatchers.IO) {
                runCatching { if (file.length() <= MAX_REQUEST) file.readText() else null }.getOrNull().also { file.delete() }
            } ?: return
        val id = name.removeSuffix(".req")
        when (val message = AgentMessage.parse(text)) {
            is AgentMessage.Report -> report(id, message)
            is AgentMessage.Answer -> answer(message.request, message.allow)
            null -> Log.w(TAG, "ignoring $name")
        }
    }

    private suspend fun report(
        id: String,
        report: AgentMessage.Report,
    ) {
        val request = id.takeIf { report.waits && AgentMessage.ID.matches(it) }
        var event = withContext(Dispatchers.Default) { AgentEvent.parse(report.payload) }
        val window = windowOf(report.pids)
        if (!enabled || event == null || window == null) {
            if (request != null) withContext(Dispatchers.IO) { reply(request, ASK) }
            return
        }
        // Claude Code before Stop said its last message: the transcript's end has it.
        val transcript = event.transcript
        if (event.kind == AgentEvent.Kind.Done && event.summary == null && transcript != null) {
            event = event.copy(summary = withContext(Dispatchers.IO) { lastMessage(transcript) })
        }
        val change = model.report(report.agent, event, window, request.takeIf { event.kind == AgentEvent.Kind.Permission }, now())
        if (request != null && change?.session?.requests?.none { it.id == request } != false) withContext(Dispatchers.IO) { reply(request, ASK) }
        change ?: return
        if (change.dropped.isNotEmpty()) withContext(Dispatchers.IO) { change.dropped.forEach { reply(it.id, ASK) } }
        show(change.session, change.alert, change.removed)
        watchSessions()
    }

    /**
     * Answers request [request] of session [sessionId] (any session, when null): from the notification, a widget or
     * `omalauncher-agent-status answer`. Nothing happens once it's been answered, given up on, or isn't that session's.
     */
    fun answer(
        request: String,
        allow: Boolean,
        sessionId: Int? = null,
    ) {
        val session = model.all.firstOrNull { s -> s.requests.any { it.id == request } } ?: return
        if (sessionId != null && session.id != sessionId) return
        scope.launch {
            val delivered = withContext(Dispatchers.IO) { reply(request, if (allow) ALLOW else DENY) }
            model.resolve(request, now(), answered = delivered)?.let { show(it.session, alert = false) }
        }
    }

    /** The user swiped [id]'s notification away. */
    fun dismissed(id: Int) {
        model.dismiss(id)?.let { publish() }
    }

    /**
     * Opens the terminal session [id] runs in: its window's task, or home for a terminal tile. False if the
     * session or its terminal is gone.
     */
    fun open(id: Int): Boolean {
        val session = model.byId(id) ?: return false
        val window =
            synchronized(runtime.server) {
                runtime.server.clients.firstOrNull { it.pid == session.window }?.all(XdgToplevel::class.java)?.firstOrNull()
            }
        if (window == null) {
            prune()
            return false
        }
        if (window in runtime.wm.appWindows) {
            if (!runtime.bringToFront(window)) runtime.wm.handleOf(window)?.let(runtime.wm.openAppWindow)
        } else {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runtime.terminals.refocus(window)
        }
        return true
    }

    /** `open(key)` from a widget, for keys from `android.agents`' `sessions`; false for other keys. */
    fun open(key: String): Boolean {
        if (!key.startsWith(AgentSession.KEY_PREFIX)) return false
        key.removePrefix(AgentSession.KEY_PREFIX).toIntOrNull()?.let(::open)
        return true
    }

    /** `android.agents`' `sessions`, oldest first. */
    fun value(): Value = Lst(sessions.map { it.value(agentName(it.agent)) })

    private fun show(
        session: AgentSession,
        alert: Boolean,
        removed: Boolean = false,
    ) {
        publish()
        posting.remove(session.id)?.cancel()
        when {
            removed -> notifications.cancel(session.id)
            session.dismissed -> Unit
            alert -> notifications.post(session, agentName(session.agent), alert = true)
            // Tool after tool: Android drops updates past a few a second, so a quiet one waits for the next.
            else ->
                posting[session.id] =
                    scope.launch {
                        delay(QUIET_POST_MS)
                        posting.remove(session.id)
                        model.byId(session.id)?.takeIf { !it.dismissed }?.let { notifications.post(it, agentName(it.agent), alert = false) }
                    }
        }
    }

    /** Quiet updates about to be posted, by session. */
    private val posting = HashMap<Int, Job>()

    private fun publish() {
        sessions = model.all
    }

    /** The terminal among [pids]: the first that is a Wayland client, as its pid. */
    private fun windowOf(pids: List<Int>): Int? {
        val clients = clientPids()
        return pids.firstOrNull { it in clients }
    }

    private fun clientPids() = synchronized(runtime.server) { runtime.server.clients.mapTo(HashSet()) { it.pid } }

    private fun prune() {
        val clients = clientPids()
        val gone = model.prune { it in clients }
        if (gone.isEmpty()) return
        val waiting = gone.flatMap { it.requests }
        if (waiting.isNotEmpty()) scope.launch(Dispatchers.IO) { waiting.forEach { reply(it.id, ASK) } }
        gone.forEach {
            posting.remove(it.id)?.cancel()
            notifications.cancel(it.id)
        }
        publish()
    }

    /**
     * While there are sessions: every few seconds, drops requests whose hook stopped waiting (the agent asked in the
     * terminal and got its answer there) or that waited too long; less often, sessions whose terminal is gone.
     */
    private fun watchSessions() {
        if (watch?.isActive == true) return
        watch =
            scope.launch {
                var ticks = 0
                while (model.all.isNotEmpty()) {
                    delay(REQUEST_CHECK_MS)
                    val now = now()
                    val expired = model.expired(now, timeoutMs).map { it.id }.toSet()
                    val waiting = model.all.flatMap { it.requests }.map { it.id }
                    if (waiting.isNotEmpty()) {
                        val gone =
                            withContext(Dispatchers.IO) {
                                waiting.filter { id -> if (id in expired) true.also { reply(id, ASK) } else !waits(id) }
                            }
                        for (id in gone) model.resolve(id, now(), answered = false)?.let { show(it.session, alert = false) }
                    }
                    if (++ticks % (PRUNE_MS / REQUEST_CHECK_MS).toInt() == 0) prune()
                }
            }
    }

    /** Whether the helper behind request [id] still waits: its FIFO has a reader, itself, until it's done. */
    private fun waits(id: String): Boolean =
        try {
            Os.close(Os.open(File(inbox, "$id.fifo").path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0))
            true
        } catch (e: ErrnoException) {
            false
        }

    /**
     * Gives request [id]'s helper its [answer]. False when it no longer waits: then nothing reads the FIFO, and
     * a non-blocking open says so (ENXIO) rather than hanging.
     */
    private fun reply(
        id: String,
        answer: String,
    ): Boolean {
        if (!AgentMessage.ID.matches(id)) return false
        val fifo = File(inbox, "$id.fifo")
        return try {
            val fd = Os.open(fifo.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
            try {
                val bytes = "$answer\n".toByteArray()
                Os.write(fd, bytes, 0, bytes.size) == bytes.size
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            false
        }
    }

    /** The last thing the assistant said, from the end of the transcript at guest path [transcript]. */
    private fun lastMessage(transcript: String): String? {
        val file = proot.guestFile(transcript)?.takeIf { it.isFile } ?: return null
        return runCatching {
            RandomAccessFile(file, "r").use { f ->
                val start = (f.length() - TRANSCRIPT_TAIL).coerceAtLeast(0)
                f.seek(start)
                val bytes = ByteArray((f.length() - start).toInt())
                f.readFully(bytes)
                // The first line may be cut off; it doesn't parse and is skipped.
                AgentEvent.lastAssistantText(bytes.decodeToString())
            }
        }.getOrNull()
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        /** The inbox in the runtime dir; the guest sees it under /run/user/0. */
        const val INBOX = "agent-status"
        private const val GUEST_HOME = Proot.GUEST_HOME
        private const val ALLOW = "allow"
        private const val DENY = "deny"

        /** Anything else: the helper prints nothing, and the agent asks in the terminal. */
        private const val ASK = "ask"

        /** A Write tool's whole file comes along in its hook's JSON; past this it's not worth reading. */
        private const val MAX_REQUEST = 8L shl 20
        private const val TRANSCRIPT_TAIL = 256L shl 10
        private const val REQUEST_CHECK_MS = 2_000L
        private const val PRUNE_MS = 30_000L
        private const val QUIET_POST_MS = 700L

        fun agentName(bin: String) = AGENTS.firstOrNull { it.bin == bin }?.name ?: bin
    }
}

/** Allow, Deny and swiping a session's notification away. */
class AgentStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = (context.applicationContext as App).runtime.agentStatus
        val id = intent.getIntExtra(AgentNotifications.EXTRA_SESSION, -1)
        when (intent.action) {
            AgentNotifications.ACTION_ALLOW, AgentNotifications.ACTION_DENY -> {
                val request = intent.getStringExtra(AgentNotifications.EXTRA_REQUEST) ?: return
                status.answer(request, intent.action == AgentNotifications.ACTION_ALLOW, id)
            }
            AgentNotifications.ACTION_DISMISS -> status.dismissed(id)
        }
    }
}

/**
 * Opens a session's terminal from its notification. Android 12+ lets only an activity started by the tap
 * itself bring a task to the front, so this one does it, without a window of its own, and goes.
 */
class AgentSessionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = (application as App).runtime
        val id = intent.getIntExtra(AgentNotifications.EXTRA_SESSION, -1)
        // The session ended meanwhile: home, where its tile or the terminals' cards are.
        if (!runtime.agentStatus.open(id)) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
