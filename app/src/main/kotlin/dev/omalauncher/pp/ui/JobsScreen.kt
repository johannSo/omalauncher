package dev.omalauncher.pp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.jobs.Activity
import dev.omalauncher.pp.jobs.HEADLESS_AGENTS
import dev.omalauncher.pp.jobs.Job
import dev.omalauncher.pp.jobs.JobAlarm
import dev.omalauncher.pp.jobs.JobPlan
import dev.omalauncher.pp.jobs.JobStatus
import dev.omalauncher.pp.jobs.JobsFile
import dev.omalauncher.pp.jobs.Notify
import dev.omalauncher.pp.jobs.Outcome
import dev.omalauncher.pp.jobs.Permissions
import dev.omalauncher.pp.jobs.Schedule
import dev.omalauncher.pp.linux.AGENTS
import dev.omalauncher.pp.linux.LinuxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Scheduled agent jobs (see [dev.omalauncher.pp.jobs.Jobs]): each one's schedule and last run, to run now, edit or
 * delete; [ new ] adds one. What's here is jobs.toml, which agents edit too, so the list follows the file.
 */
@Composable
fun JobsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val store = runtime.jobs
    val context = LocalContext.current
    val linux = runtime.bootstrap.state.collectAsState().value
    // A job being edited, and the id it had (null for a new one).
    var editing by remember { mutableStateOf<Pair<Job, String?>?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }
    OverlayPanel(
        stringResource(R.string.jobs_title),
        onDismiss,
        actions = {
            if (store.readable) {
                BracketAction(stringResource(R.string.jobs_new), Palette.accent) { editing = Job(id = "", schedule = Schedule.parse("daily at 07:00")) to null }
            }
        },
        contentWidth = ReadingWidth,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                BasicText(stringResource(R.string.jobs_intro), Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                when (linux) {
                    LinuxState.Installed -> {}
                    LinuxState.Off -> Note(stringResource(R.string.jobs_linux_off), warning)
                    LinuxState.NotInstalled -> Note(stringResource(R.string.jobs_no_linux), warning)
                    else -> {}
                }
                if (!store.exact) {
                    Note(stringResource(R.string.jobs_exact), Palette.muted) {
                        // Android 12+ only: before, exact alarms need no grant.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            BracketAction(stringResource(R.string.jobs_allow), Palette.accent) {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                                }
                            }
                        }
                    }
                }
                NotificationsNote(store.jobs.any { it.notify != Notify.Never })
                for (error in store.errors) BasicText(error, Modifier.padding(vertical = 2.dp), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                if (store.jobs.isEmpty()) BasicText(stringResource(R.string.jobs_empty), Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted))
            }
            items(store.jobs, key = { it.id }) { job ->
                JobRow(
                    job = job,
                    status = store.statuses[job.id] ?: JobStatus(),
                    live = store.live[job.id].orEmpty(),
                    canRun = linux == LinuxState.Installed,
                    canEdit = store.readable,
                    output = { withContext(Dispatchers.IO) { store.output(job.id) } },
                    onRun = { store.runNow(job.id) },
                    onStop = { store.stop(job.id) },
                    onEdit = { editing = job to job.id },
                    onDelete = { deleting = job },
                )
            }
        }
    }
    editing?.let { (job, id) ->
        JobEditor(
            runtime,
            job,
            isNew = id == null,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                store.save(if (id == null) saved.copy(id = store.newId(saved.name)) else saved, id)
            },
        )
    }
    deleting?.let { job ->
        ConfirmDialog(
            title = stringResource(R.string.jobs_delete_title, job.name),
            message = stringResource(R.string.jobs_delete_message),
            confirm = stringResource(R.string.jobs_delete),
            onDismiss = { deleting = null },
        ) {
            deleting = null
            store.delete(job.id)
        }
    }
}

/** The theme's yellow, for what waits or needs care; Palette has no name for it. */
private val warning get() = Palette.named("yellow") ?: Palette.accent

/** A line about the jobs as a whole, with what fixes it after it. */
@Composable
private fun Note(
    text: String,
    color: Color,
    action: @Composable () -> Unit = {},
) {
    ActionsRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), actions = { action() }) {
        BasicText(text, style = Mono.copy(color = color, fontSize = TextSize.Small))
    }
}

/** Android 13+: a job's notification needs the permission, asked for here since a job may be the first thing to notify. */
@Composable
private fun NotificationsNote(wanted: Boolean) {
    if (!wanted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) return
    Note(stringResource(R.string.jobs_notifications), Palette.muted) {
        BracketAction(stringResource(R.string.jobs_allow), Palette.accent) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

@Composable
private fun JobRow(
    job: Job,
    status: JobStatus,
    live: List<String>,
    canRun: Boolean,
    canEdit: Boolean,
    output: suspend () -> String,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = status.activity == Activity.Running || status.activity == Activity.Queued
    Column(Modifier.card(10.dp)) {
        ActionsRow(
            actions = {
                when {
                    busy -> BracketAction(stringResource(R.string.jobs_stop), Palette.red, Modifier.passesKeys().takesPassedKeys(), onStop)
                    canRun -> BracketAction(stringResource(R.string.jobs_run_now), Palette.green, Modifier.passesKeys().takesPassedKeys(), onRun)
                }
                if (canEdit) {
                    BracketAction(stringResource(R.string.jobs_edit), Palette.accent, onClick = onEdit)
                    BracketAction(stringResource(R.string.jobs_delete), Palette.red, onClick = onDelete)
                }
            },
        ) {
            Column {
                BasicText(job.name, style = Mono.copy(fontSize = TextSize.Item, color = if (job.enabled) Palette.foreground else Palette.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(describe(job, status), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        when (status.activity) {
            Activity.Running -> {
                BasicText(stringResource(R.string.jobs_running), Modifier.padding(top = 6.dp), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
                for (line in live.takeLast(4)) BasicText(line, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Activity.Queued -> BasicText(stringResource(R.string.jobs_queued), Modifier.padding(top = 6.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            Activity.Waiting -> BasicText(status.reason.orEmpty(), Modifier.padding(top = 6.dp), style = Mono.copy(color = warning, fontSize = TextSize.Small))
            Activity.Idle -> {}
        }
        LastRun(status, output)
    }
}

/** The agent, the schedule, when it runs next and what it waits for: "claude · weekdays at 07:00 · next Mon 07:00 · Wi-Fi". */
@Composable
private fun describe(
    job: Job,
    status: JobStatus,
): String {
    val parts = ArrayList<String>()
    parts += job.command?.let { "$ ${it.take(40)}" } ?: job.agent?.let { bin -> AGENTS.firstOrNull { it.bin == bin }?.name ?: bin } ?: stringResource(R.string.jobs_default_agent)
    parts += job.schedule?.text ?: stringResource(R.string.jobs_manual)
    when {
        !job.enabled -> parts += stringResource(R.string.jobs_off)
        status.next != null -> parts += stringResource(R.string.jobs_next, whenText(status.next))
    }
    if (job.wifi) parts += stringResource(R.string.jobs_wifi)
    if (job.charging) parts += stringResource(R.string.jobs_charging)
    if (job.permissions == Permissions.Full) parts += stringResource(R.string.jobs_full_access)
    return parts.joinToString(" · ")
}

/** How the last run went and the end of what it printed; a tap shows all of it. */
@Composable
private fun LastRun(
    status: JobStatus,
    output: suspend () -> String,
) {
    val run = status.last
    if (run == null) {
        BasicText(stringResource(R.string.jobs_never_ran), Modifier.padding(top = 6.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ago = DateUtils.getRelativeTimeSpanString(run.finished * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    val took = duration(run.duration)
    val (text, color) =
        when (run.outcome) {
            Outcome.Ok -> stringResource(R.string.jobs_result_ok, ago, took) to Palette.green
            Outcome.Failed -> stringResource(R.string.jobs_result_failed, ago, took, run.exit?.toString() ?: "–") to Palette.red
            Outcome.TimedOut -> stringResource(R.string.jobs_result_timeout, ago, took) to Palette.red
            Outcome.Stopped -> stringResource(R.string.jobs_result_stopped, ago, took) to Palette.muted
        }
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        BasicText(text, style = Mono.copy(color = color, fontSize = TextSize.Small))
        if (expanded) {
            val whole by produceState(status.output, run.started) { value = output().ifEmpty { status.output } }
            CodeBlock(whole.ifEmpty { "–" })
            BasicText(stringResource(R.string.jobs_output_less), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        } else if (run.summary.isNotEmpty()) {
            BasicText(run.summary, style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Small), maxLines = 3, overflow = TextOverflow.Ellipsis)
            BasicText(stringResource(R.string.jobs_output), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
}

/** A time in the next days as the phone writes it: "Mon 07:00", or "Mon 7:00 AM". */
private fun whenText(epochSecond: Long): String {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEjmm")
    return java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault()))
}

/** "45s", "3m 20s", "1h 5m". */
private fun duration(seconds: Long): String =
    when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
    }

/** A job's settings, over the list: [onSave] gets the job as edited, once it reads. */
@Composable
private fun JobEditor(
    runtime: Runtime,
    job: Job,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Job) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(job.name) }
    var agent by rememberSaveable { mutableStateOf(job.agent) }
    var prompt by rememberSaveable { mutableStateOf(job.prompt) }
    var command by rememberSaveable { mutableStateOf(job.command.orEmpty()) }
    var schedule by rememberSaveable { mutableStateOf(job.schedule?.text ?: Job.MANUAL) }
    var dir by rememberSaveable { mutableStateOf(job.dir) }
    var wifi by rememberSaveable { mutableStateOf(job.wifi) }
    var charging by rememberSaveable { mutableStateOf(job.charging) }
    var notify by rememberSaveable { mutableStateOf(job.notify) }
    var permissions by rememberSaveable { mutableStateOf(job.permissions) }
    var timeout by rememberSaveable { mutableStateOf(if (job.timeoutMinutes % 60 == 0) "${job.timeoutMinutes / 60}h" else "${job.timeoutMinutes}m") }
    var enabled by rememberSaveable { mutableStateOf(job.enabled) }
    var problem by remember { mutableStateOf<String?>(null) }

    val parsed = remember(schedule) { if (schedule.trim().equals(Job.MANUAL, ignoreCase = true)) Result.success(null) else runCatching { Schedule.parse(schedule) } }
    val installed = runtime.agents.installed
    val defaultName = runtime.agents.default?.name
    val nameMissing = stringResource(R.string.jobs_error_name)
    val promptMissing = stringResource(R.string.jobs_error_prompt)
    val timeoutWrong = stringResource(R.string.jobs_error_timeout)

    fun save() {
        val minutes = JobsFile.minutes(timeout)?.takeIf { it in 1..24 * 60 }
        val sched = parsed.getOrNull()
        problem =
            when {
                name.isBlank() -> nameMissing
                prompt.isBlank() && command.isBlank() && job.promptFile == null -> promptMissing
                parsed.isFailure -> parsed.exceptionOrNull()?.message
                minutes == null -> timeoutWrong
                else -> null
            }
        if (problem != null || minutes == null) return
        onSave(
            job.copy(
                name = name.trim(),
                agent = agent,
                prompt = prompt.trim(),
                command = command.trim().ifEmpty { null },
                schedule = sched,
                dir = dir.trim().ifEmpty { "~" },
                wifi = wifi,
                charging = charging,
                notify = notify,
                permissions = permissions,
                timeoutMinutes = minutes,
                enabled = enabled,
            ),
        )
    }

    OverlayPanel(
        stringResource(if (isNew) R.string.jobs_new_title else R.string.jobs_edit_title),
        onDismiss,
        actions = { BracketAction(stringResource(R.string.jobs_save), Palette.green, onClick = ::save) },
        contentWidth = ReadingWidth,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).keyScrolled()) {
            Field(stringResource(R.string.jobs_field_name), name, { name = it })
            AgentOption(agent, installed.map { it.bin }, defaultName) { agent = it }
            agent?.let { bin ->
                if (bin !in HEADLESS_AGENTS && command.isBlank()) {
                    OptionHint(stringResource(R.string.jobs_not_headless, AGENTS.firstOrNull { it.bin == bin }?.name ?: bin), Modifier.padding(bottom = 4.dp))
                }
            }
            Field(stringResource(R.string.jobs_field_prompt), prompt, { prompt = it }, singleLine = false)
            job.promptFile?.let { OptionHint(stringResource(R.string.jobs_prompt_file, it), Modifier.padding(bottom = 4.dp)) }
            Field(stringResource(R.string.jobs_field_schedule), schedule, { schedule = it })
            val next = parsed.getOrNull()?.next(Instant.now(), JobPlan.zoneOf(job, ZoneId.systemDefault()))
            OptionHint(
                when {
                    parsed.isFailure -> parsed.exceptionOrNull()?.message.orEmpty()
                    next != null -> stringResource(R.string.jobs_schedule_next, whenText(next.epochSecond))
                    else -> stringResource(R.string.jobs_schedule_hint)
                },
                Modifier.padding(bottom = 4.dp),
            )
            Field(stringResource(R.string.jobs_field_dir), dir, { dir = it })
            StyleOption(R.string.jobs_field_network, listOf(stringResource(R.string.jobs_any), stringResource(R.string.jobs_wifi)), if (wifi) 1 else 0) { wifi = it == 1 }
            StyleOption(R.string.jobs_field_power, listOf(stringResource(R.string.jobs_any), stringResource(R.string.jobs_charging)), if (charging) 1 else 0) { charging = it == 1 }
            StyleOption(
                R.string.jobs_field_notify,
                listOf(stringResource(R.string.jobs_notify_always), stringResource(R.string.jobs_notify_failure), stringResource(R.string.jobs_notify_never)),
                Notify.entries.indexOf(notify),
            ) { notify = Notify.entries[it] }
            StyleOption(
                R.string.jobs_field_permissions,
                listOf(stringResource(R.string.jobs_permissions_safe), stringResource(R.string.jobs_permissions_full)),
                Permissions.entries.indexOf(permissions),
            ) { permissions = Permissions.entries[it] }
            BasicText(
                stringResource(if (permissions == Permissions.Full) R.string.jobs_permissions_full_hint else R.string.jobs_permissions_safe_hint),
                Modifier.padding(bottom = 4.dp),
                style = Mono.copy(color = if (permissions == Permissions.Full) warning else Palette.muted, fontSize = TextSize.Small),
            )
            Field(stringResource(R.string.jobs_field_timeout), timeout, { timeout = it })
            StyleOption(R.string.jobs_field_enabled, listOf(stringResource(R.string.jobs_on), stringResource(R.string.jobs_off)), if (enabled) 0 else 1) { enabled = it == 0 }
            Field(stringResource(R.string.jobs_field_command), command, { command = it })
            OptionHint(stringResource(R.string.jobs_command_hint), Modifier.padding(bottom = 4.dp))
            problem?.let { BasicText(it, Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.red)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                BracketAction(stringResource(R.string.jobs_cancel), Palette.muted, onClick = onDismiss)
                BracketAction(stringResource(R.string.jobs_save), Palette.green, onClick = ::save)
            }
        }
    }
}

/** Which agent runs it: the launcher's default, or one of the installed ones; one set in jobs.toml but not installed is listed too. */
@Composable
private fun AgentOption(
    picked: String?,
    installed: List<String>,
    defaultName: String?,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = listOf<String?>(null) + (installed + listOfNotNull(picked)).distinct()
    val defaultLabel = defaultName?.let { stringResource(R.string.jobs_default_agent_named, it) } ?: stringResource(R.string.jobs_default_agent)
    fun label(bin: String?) = if (bin == null) defaultLabel else AGENTS.firstOrNull { it.bin == bin }?.name ?: bin
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(R.string.jobs_field_agent))
        Box(Modifier.weight(1f, fill = false)) {
            Row(
                Modifier
                    .frame(1.dp, Palette.border, radius = 12.dp)
                    .clickable { open = !open }
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(label(picked), Modifier.weight(1f, fill = false), style = Mono.copy(color = Palette.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 6.dp))
            }
            if (open) ChoicePopup(options, picked, BelowAnchorStart, onDismiss = { open = false }, onPick = onPick, label = ::label)
        }
    }
}

/** A labeled text field; one line unless [singleLine] is false, then a few lines that grow. */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
        OptionLabel(label, if (singleLine) Modifier else Modifier.padding(top = 8.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            textStyle = Mono.copy(color = Palette.foreground),
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(capitalization = if (singleLine) KeyboardCapitalization.None else KeyboardCapitalization.Sentences, autoCorrectEnabled = !singleLine),
            modifier =
                Modifier
                    .weight(1f)
                    .then(if (singleLine) Modifier.leavesUpDown() else Modifier.heightIn(max = 240.dp))
                    .frame(1.dp, Palette.border, radius = 12.dp)
                    .background(Palette.background)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
