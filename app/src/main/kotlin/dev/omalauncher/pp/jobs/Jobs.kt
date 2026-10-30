package dev.omalauncher.pp.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.pp.LauncherData
import dev.omalauncher.pp.R
import dev.omalauncher.pp.SettingsActivity
import dev.omalauncher.pp.linux.AGENTS
import dev.omalauncher.pp.linux.AgentStore
import dev.omalauncher.pp.linux.LinuxState
import dev.omalauncher.pp.linux.OutputTail
import dev.omalauncher.pp.linux.Proot
import dev.omalauncher.pp.linux.forEachOutputLine
import dev.omalauncher.pp.registerNotExported
import dev.omalauncher.pp.theme.Toml
import dev.omalauncher.pp.ui.SettingsPage
import dev.omalauncher.pp.upgrade.Managed
import dev.omalauncher.pp.upgrade.Policy
import dev.omalauncher.pp.upgrade.SyncedFile
import dev.omalauncher.pp.upgrade.writeAtomically
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as Task
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Scheduled agent jobs: jobs.toml in the launcher's data (Linux's ~/.config/omalauncher/jobs/jobs.toml), which agents
 * and the jobs screen edit; when each is due, its agent runs headless in the guest, and what it printed and how it
 * ended go into the job's folder next to the file, for the screen, notifications and widgets.
 *
 * The schedule is kept with AlarmManager, not WorkManager: a job "daily at 07:00" means seven, and WorkManager's work
 * waits for Doze's maintenance windows, hours apart overnight, while setExactAndAllowWhileIdle fires through Doze.
 * Android drops alarms at a reboot and an app update, so [JobReceiver] sets the next one again then, and so does every
 * start of the launcher. A run missed meanwhile (the phone was off, Linux was shut down, there was no Wi-Fi) is made
 * up once as soon as it can be. While a job runs, Linux's foreground service keeps the process, and a wake lock the
 * CPU. Main thread.
 */
class Jobs(
    private val context: Context,
    private val proot: Proot,
    private val agents: AgentStore,
    private val linux: StateFlow<LinuxState>,
    /** Starts the launcher, and with it Linux, as opening it does: for a job due while the process wasn't running. */
    private val startLauncher: () -> Unit,
) {
    val dir = LauncherData.jobsDir(context)

    /** jobs.toml as read, broken jobs and unknown keys too, so a save from the screen keeps them. */
    private var table = LinkedHashMap<String, Any>()

    private val synced = SyncedFile(File(dir, JobsFile.NAME), File(dir, ERRORS), "the jobs") { JobsFile.write(table) }

    /** The jobs that read fine, in the file's order. */
    var jobs by mutableStateOf(emptyList<Job>())
        private set

    /** Whether jobs.toml parses; until it does, the screen doesn't save over it. */
    var readable by mutableStateOf(true)
        private set

    /** What errors.txt says: jobs left out of jobs.toml, and why. */
    val errors get() = synced.errors

    /** Each job's status by id: what it's doing, when it runs next, its last run. */
    val statuses = mutableStateMapOf<String, JobStatus>()

    /** Whether the alarm goes off on time while the phone sleeps; see [JobAlarm.exact]. */
    var exact by mutableStateOf(JobAlarm.exact(context))
        private set

    /** The end of a running job's output, as it comes. */
    val live = mutableStateMapOf<String, List<String>>()

    /** When each job's last run was handled (ran, or was made up), and what schedule that was for. */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "job failed", e) })

    /** One job at a time: agents share the phone's few cores, its memory, and often a repository. */
    private val turn = Mutex()

    /** Jobs queued or running, by id; cancelling one that waits for its turn takes it out. */
    private val tasks = HashMap<String, Task>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val stopped = ConcurrentHashMap.newKeySet<String>()
    private var started = false

    init {
        // Small, and read before the first frame of the jobs screen, like style.toml.
        synced.open()?.let(::read)
        for (job in jobs) loadStatus(job.id)
    }

    /** Watches jobs.toml and Linux, and sets the next alarm; [check]s runs what's due. Again does nothing. */
    fun start() {
        if (started) return
        started = true
        synced.start { text ->
            read(text)
            for (job in jobs) if (job.id !in statuses) loadStatus(job.id)
            check()
        }
        // Linux coming up runs what waited for it; its first value does the first check. Not every step of an install.
        scope.launch { linux.map { it.javaClass }.distinctUntilChanged().collect { check() } }
    }

    /** An alarm went off, or the phone booted: what's due runs, and the next alarm is set. */
    fun wake() {
        start()
        check()
    }

    private fun read(text: String) {
        val parsed =
            try {
                Toml.parse(text)
            } catch (e: Toml.Error) {
                readable = false
                synced.readable = false
                synced.setProblems(listOf("${JobsFile.NAME} ${e.message}; no job changed, and the jobs screen won't save until it's fixed"))
                return
            }
        readable = true
        synced.readable = true
        val read = JobsFile.read(parsed)
        table = parsed
        jobs = read.jobs
        synced.setProblems(read.problems)
    }

    /**
     * Runs the jobs that are due and can run now, marks those that wait and why, and sets the alarm for the next one.
     * Cheap: called whenever something they wait for may have changed.
     */
    fun check() {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        var wake: Instant? = null
        val waits = HashSet<Condition>()
        for (job in jobs) {
            val after = afterOf(job, now)
            val at = JobPlan.wake(job, after, now, zone)
            if (at != null) wake = wake?.let { minOf(it, at) } ?: at
            val next = at?.epochSecond
            if (job.id in tasks) {
                setStatus(job.id) { it.copy(next = next) }
                continue
            }
            if (!JobPlan.due(job, after, now, zone)) {
                setStatus(job.id) { it.copy(activity = Activity.Idle, reason = null, next = next) }
                continue
            }
            val blocked = blocker(job)
            if (blocked == null) {
                setAfter(job, now)
                setStatus(job.id) { it.copy(next = JobPlan.next(job, now, zone)?.epochSecond) }
                enqueue(job, manual = false)
            } else {
                waits += blocked
                setStatus(job.id) { it.copy(activity = Activity.Waiting, reason = context.getString(blocked.reason), next = next) }
            }
        }
        exact = JobAlarm.exact(context)
        JobAlarm.set(context, wake)
        watch(waits)
        writeIndex()
    }

    /** What a due job waits for, before it can run; null if nothing. */
    private fun blocker(job: Job): Condition? {
        when (linux.value) {
            LinuxState.Installed -> {}
            LinuxState.Off -> return Condition.LinuxOff
            LinuxState.NotInstalled, LinuxState.Removing -> return Condition.NoLinux
            is LinuxState.Failed -> return Condition.LinuxFailed
            // The process had ended: starting the launcher starts Linux; its state coming up checks again.
            LinuxState.Checking, is LinuxState.Installing -> {
                startLauncher()
                return Condition.LinuxStarting
            }
        }
        if (job.wifi && !onWifi()) return Condition.Wifi
        if (job.charging && !pluggedIn()) return Condition.Charging
        return null
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** On a charger, charging or full: the sticky battery broadcast says, without a receiver. */
    private fun pluggedIn(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private var network: ConnectivityManager.NetworkCallback? = null
    private var power: android.content.BroadcastReceiver? = null

    /** Listens for Wi-Fi and a charger while jobs wait for them; Linux's own state is collected all along. */
    private fun watch(waits: Set<Condition>) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (Condition.Wifi in waits && network == null) {
            network =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        n: Network,
                        caps: NetworkCapabilities,
                    ) {
                        scope.launch { check() }
                    }
                }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
        } else if (Condition.Wifi !in waits && network != null) {
            runCatching { cm.unregisterNetworkCallback(network!!) }
            network = null
        }
        if (Condition.Charging in waits && power == null) {
            power = context.registerNotExported(IntentFilter(Intent.ACTION_POWER_CONNECTED)) { scope.launch { check() } }
        } else if (Condition.Charging !in waits && power != null) {
            runCatching { context.unregisterReceiver(power) }
            power = null
        }
    }

    /** Runs [id] now, whatever its schedule and conditions say; Linux has to be running. */
    fun runNow(id: String) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        if (linux.value != LinuxState.Installed) return
        // A scheduled run waiting for Wi-Fi or a charger is this one.
        val now = Instant.now()
        if (JobPlan.due(job, afterOf(job, now), now, ZoneId.systemDefault())) setAfter(job, now)
        enqueue(job, manual = true)
    }

    /** Stops [id]: one waiting for its turn doesn't run, a running one's agent is ended. */
    fun stop(id: String) {
        val process = processes[id]
        if (process != null) {
            stopped += id
            process.destroy()
        } else {
            tasks.remove(id)?.cancel()
            setStatus(id) { it.copy(activity = Activity.Idle) }
            writeIndex()
        }
    }

    private fun enqueue(
        job: Job,
        manual: Boolean,
    ) {
        if (job.id in tasks) return
        setStatus(job.id) { it.copy(activity = Activity.Queued, reason = null) }
        tasks[job.id] =
            scope.launch {
                try {
                    turn.withLock {
                        setStatus(job.id) { it.copy(activity = Activity.Running) }
                        writeIndex()
                        run(job, manual)
                    }
                } finally {
                    tasks.remove(job.id)
                    live.remove(job.id)
                    setStatus(job.id) { it.copy(activity = Activity.Idle) }
                    check()
                }
            }
        writeIndex()
    }

    /** What a run left: its exit code (null if it never started), its output, and how it ended. */
    private class Ran(
        val exit: Int?,
        val output: String,
        val outcome: Outcome,
    )

    private suspend fun run(
        job: Job,
        manual: Boolean,
    ) {
        val started = System.currentTimeMillis() / 1000
        Log.i(TAG, "${job.id}: running${if (manual) " by hand" else ""}")
        val ran = withContext(Dispatchers.IO) { execute(job) }
        val finished = System.currentTimeMillis() / 1000
        val run = JobRun(started, finished, ran.outcome, ran.exit, summaryOf(ran.output), manual)
        Log.i(TAG, "${job.id}: ${ran.outcome.key} (${ran.exit}) after ${run.duration}s")
        withContext(Dispatchers.IO) { record(job, run, ran.output) }
        setStatus(job.id) { it.copy(last = run, output = ran.output.takeLast(JobStatus.OUTPUT_CHARS)) }
        notify(job, run)
    }

    /** Runs [job] in the guest and waits for it to end. IO thread. */
    private fun execute(job: Job): Ran {
        fun fail(message: String) = Ran(null, message, Outcome.Failed)
        val prompt = promptOf(job) ?: return fail(context.getString(R.string.jobs_error_prompt_file, job.promptFile))
        val invocation =
            job.command ?: run {
                val bin = job.agent ?: agents.default?.bin ?: return fail(context.getString(R.string.jobs_error_no_agent))
                val agent = AGENTS.firstOrNull { it.bin == bin }
                if (agent != null && !agents.isInstalled(agent)) return fail(context.getString(R.string.jobs_error_agent_missing, agent.name))
                headlessInvocation(bin, prompt, job.permissions) ?: return fail(context.getString(R.string.jobs_error_not_headless, agent?.name ?: bin))
            }
        val script = jobScript(job, invocation, prompt, guestResults(job.id))
        val lock =
            context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omalauncher:job").apply {
                setReferenceCounted(false)
                acquire((job.timeoutMinutes + 2) * 60_000L)
            }
        stopped -= job.id
        val output = OutputText()
        val tail = OutputTail(40)
        var shown = 0L
        return try {
            val process = proot.process(script).start()
            processes[job.id] = process
            // timeout in the guest ends it; this is for a proot that hangs regardless.
            val watchdog =
                scope.launch(Dispatchers.IO) {
                    delay((job.timeoutMinutes + 1) * 60_000L)
                    process.destroyForcibly()
                }
            process.inputStream.bufferedReader().use { reader ->
                forEachOutputLine(reader) { raw ->
                    output.add(raw)
                    if (tail.add(cleanLine(raw))) {
                        val t = System.currentTimeMillis()
                        if (t - shown > 250) {
                            shown = t
                            live[job.id] = tail.toList()
                        }
                    }
                }
            }
            val exit = process.waitFor()
            watchdog.cancel()
            Ran(exit, output.text(), Outcome.of(exit, job.id in stopped))
        } catch (e: Exception) {
            Log.w(TAG, "${job.id}: can't run", e)
            output.add(e.message ?: e.toString())
            Ran(null, output.text(), if (job.id in stopped) Outcome.Stopped else Outcome.Failed)
        } finally {
            processes.remove(job.id)
            stopped -= job.id
            if (lock.isHeld) lock.release()
        }
    }

    /** The prompt, read from prompt_file when the job names one; null if that can't be read. IO thread. */
    private fun promptOf(job: Job): String? {
        val path = job.promptFile ?: return job.prompt
        val guest =
            when {
                path.startsWith("/") -> path
                path.startsWith("~/") -> Proot.GUEST_HOME + path.removePrefix("~")
                else -> "$GUEST_DIR_ABS/$path"
            }
        val file = proot.guestFile(guest)?.takeIf { it.isFile && it.length() <= MAX_PROMPT } ?: return null
        return runCatching { file.readText() }.getOrNull()?.let { text -> listOf(job.prompt, text).filter { it.isNotBlank() }.joinToString("\n\n") }
    }

    /** The job's folder of results, on the host. */
    private fun resultsDir(id: String) = File(dir, id)

    private fun guestResults(id: String) = "$GUEST_DIR_ABS/$id"

    /** Writes the run into the job's folder: output.txt, runs/, history.json and status.json. IO thread. */
    private fun record(
        job: Job,
        run: JobRun,
        output: String,
    ) {
        val folder = resultsDir(job.id)
        runCatching {
            writeAtomically(File(folder, OUTPUT), output.toByteArray())
            val runs = File(folder, RUNS)
            writeAtomically(File(runs, "${run.started}.txt"), output.toByteArray())
            runs.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name.removeSuffix(".txt").toLongOrNull() ?: 0 }.drop(KEPT_OUTPUTS).forEach { it.delete() }
            val history = File(folder, HISTORY)
            val runsBefore = JobRun.list(history.takeIf { it.isFile }?.readText())
            writeAtomically(history, JobRun.listJson((listOf(run) + runsBefore).take(KEPT_RUNS)).toString(2).toByteArray())
        }.onFailure { Log.w(TAG, "${job.id}: can't save the run", it) }
    }

    /** status.json as it was, when the launcher starts: the last run and its output. */
    private fun loadStatus(id: String) {
        val text = runCatching { File(resultsDir(id), STATUS).takeIf { it.isFile }?.readText() }.getOrNull()
        statuses[id] = JobStatus.of(text) ?: JobStatus()
    }

    private fun setStatus(
        id: String,
        change: (JobStatus) -> JobStatus,
    ) {
        val old = statuses[id] ?: JobStatus()
        val new = change(old)
        if (new != old) statuses[id] = new
    }

    /** The texts written last, by file, so an unchanged status isn't written again. */
    private val written = HashMap<File, String>()

    /** One write at a time, in order: a status written late mustn't land over a newer one. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Each job's status.json and the folder's status.json, all jobs in the file's order, for widgets. */
    private fun writeIndex() {
        val all = jobs.map { job -> job to (statuses[job.id] ?: JobStatus()).toJson(job) }
        val index = JSONObject().put("jobs", JSONArray().apply { all.forEach { put(it.second) } })
        scope.launch(writer) {
            fun put(
                file: File,
                text: String,
            ) {
                if (written[file] == text) return
                runCatching { writeAtomically(file, text.toByteArray()) }
                    .onSuccess { written[file] = text }
                    .onFailure { Log.w(TAG, "can't write $file", it) }
            }
            for ((job, status) in all) put(File(resultsDir(job.id), STATUS), status.toString(2))
            put(File(dir, STATUS), index.toString(2))
        }
    }

    /** When [job]'s last run was handled; a job seen for the first time, or with a new schedule, starts from [now]. */
    private fun afterOf(
        job: Job,
        now: Instant,
    ): Instant {
        val saved = prefs.getLong(AFTER + job.id, -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
        val same = prefs.getString(PLAN + job.id, null) == planOf(job)
        val after = JobPlan.after(saved.takeIf { same }, now)
        if (after != saved || !same) setAfter(job, after)
        return after
    }

    private fun setAfter(
        job: Job,
        at: Instant,
    ) {
        prefs.edit().putLong(AFTER + job.id, at.toEpochMilli()).putString(PLAN + job.id, planOf(job)).apply()
    }

    /** What a change of resets the job's last run to now: switched on, or a new schedule, doesn't make up runs from before. */
    private fun planOf(job: Job) = "${job.enabled}|${job.schedule?.text}|${job.zone}"

    /** Adds [job], or replaces the job [oldId] with it; jobs.toml is saved a moment later. */
    fun save(
        job: Job,
        oldId: String? = null,
    ) {
        if (!readable) return
        table = JobsFile.update(table, oldId ?: job.id, job)
        jobs = JobsFile.read(table).jobs
        if (oldId != null && oldId != job.id) {
            statuses.remove(oldId)?.let { statuses[job.id] = it }
            scope.launch(Dispatchers.IO) { resultsDir(oldId).takeIf { it.isDirectory }?.renameTo(resultsDir(job.id)) }
            prefs.edit().remove(AFTER + oldId).remove(PLAN + oldId).apply()
        }
        synced.save()
        check()
    }

    /** Removes [id] from jobs.toml, with its results. */
    fun delete(id: String) {
        if (!readable) return
        stop(id)
        table = JobsFile.update(table, id, null)
        jobs = JobsFile.read(table).jobs
        statuses.remove(id)
        prefs.edit().remove(AFTER + id).remove(PLAN + id).apply()
        scope.launch(Dispatchers.IO) { runCatching { LauncherData.deleteTree(resultsDir(id)) } }
        synced.save()
        check()
    }

    /** A free id for a new job named [name]. */
    fun newId(name: String) = JobsFile.freeId(name, jobs.map { it.id })

    /** The whole of a job's last output, for the screen. IO thread. */
    fun output(id: String): String = runCatching { File(resultsDir(id), OUTPUT).readText() }.getOrDefault("")

    /** README.md for agents, next to jobs.toml. */
    fun managed(): List<Managed> = listOf(Managed.asset(context, "jobs/README.md", File(dir, "README.md"), Policy.Owned))

    private fun notify(
        job: Job,
        run: JobRun,
    ) {
        val wanted =
            when (job.notify) {
                Notify.Always -> true
                Notify.Failure -> run.outcome != Outcome.Ok
                Notify.Never -> false
            }
        // Stopped by hand: whoever stopped it knows.
        if (!wanted || run.outcome == Outcome.Stopped) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.jobs_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val title =
            context.getString(
                when (run.outcome) {
                    Outcome.Ok -> R.string.jobs_notify_ok
                    Outcome.TimedOut -> R.string.jobs_notify_timeout
                    else -> R.string.jobs_notify_failed
                },
                job.name,
            )
        val open =
            PendingIntent.getActivity(
                context,
                job.id.hashCode(),
                SettingsActivity.intent(context, SettingsPage.Jobs, null),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text = statuses[job.id]?.output?.takeLast(NOTIFICATION_CHARS)?.trim().orEmpty().ifEmpty { run.summary }
        val n =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(run.summary.lineSequence().lastOrNull { it.isNotBlank() } ?: run.summary)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        // One per job: the next run's replaces the last.
        nm.notify(NOTIFICATION_TAG, job.id.hashCode(), n)
    }

    /** What a due job can wait for, and how its status says so. */
    private enum class Condition(
        val reason: Int,
    ) {
        LinuxOff(R.string.jobs_wait_linux_off),
        NoLinux(R.string.jobs_wait_no_linux),
        LinuxFailed(R.string.jobs_wait_linux_failed),
        LinuxStarting(R.string.jobs_wait_linux_starting),
        Wifi(R.string.jobs_wait_wifi),
        Charging(R.string.jobs_wait_charging),
    }

    companion object {
        private const val TAG = "jobs"
        private const val PREFS = "jobs"
        private const val AFTER = "after."
        private const val PLAN = "plan."
        private const val CHANNEL = "jobs"
        private const val NOTIFICATION_TAG = "job"
        private const val NOTIFICATION_CHARS = 1500

        const val ERRORS = "errors.txt"
        const val STATUS = "status.json"
        const val OUTPUT = "output.txt"
        const val HISTORY = "history.json"
        const val RUNS = "runs"

        /** Runs history.json lists, and outputs runs/ keeps. */
        const val KEPT_RUNS = 20
        const val KEPT_OUTPUTS = 5

        private const val MAX_PROMPT = 256 * 1024L

        /** Where Linux sees the jobs folder, relative to the guest home. */
        const val GUEST_DIR = ".config/omalauncher/jobs"
        private const val GUEST_DIR_ABS = Proot.GUEST_HOME + "/" + GUEST_DIR
    }
}

/**
 * A run's output as it's kept: lines without escapes, a progress bar at its last redraw, and only the last
 * [max] characters of a run that prints more.
 */
internal class OutputText(
    private val max: Int = 256 * 1024,
) {
    private val text = StringBuilder()

    /** A line that ended in a carriage return, drawn over by the next unless that's the rest of a CRLF. */
    private var redrawn: String? = null

    fun add(raw: String) {
        val before = redrawn
        redrawn = null
        when {
            // The \n of a \r\n: the line stands.
            before != null && raw.isEmpty() -> append(before)
            raw.endsWith('\r') -> redrawn = cleanLine(raw)
            // Blank lines count once there's something above them.
            raw.isNotEmpty() || text.isNotEmpty() -> append(cleanLine(raw))
        }
    }

    private fun append(line: String) {
        text.append(line).append('\n')
        if (text.length > max * 2) text.delete(0, text.length - max)
    }

    fun text(): String {
        redrawn?.let(::append)
        redrawn = null
        return text.takeLast(max).toString().trimEnd()
    }
}
