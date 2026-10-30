package dev.omalauncher.pp.focus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.ui.AppHit
import dev.omalauncher.pp.ui.BelowAnchorEnd
import dev.omalauncher.pp.ui.BracketAction
import dev.omalauncher.pp.ui.ChoicePopup
import dev.omalauncher.pp.ui.ConfirmDialog
import dev.omalauncher.pp.ui.DialogFrame
import dev.omalauncher.pp.ui.Heading
import dev.omalauncher.pp.ui.Mono
import dev.omalauncher.pp.ui.OptionHint
import dev.omalauncher.pp.ui.OverlayPanel
import dev.omalauncher.pp.ui.Palette
import dev.omalauncher.pp.ui.ReadingWidth
import dev.omalauncher.pp.ui.SearchField
import dev.omalauncher.pp.ui.StyleOption
import dev.omalauncher.pp.ui.TextDialog
import dev.omalauncher.pp.ui.TextSize
import dev.omalauncher.pp.ui.allApps
import dev.omalauncher.pp.ui.card
import dev.omalauncher.pp.widgets.runtime.MediaListener
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** The limits a timer offers, in minutes. */
private val LIMITS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)

/** How long [ turn on ] offers to keep a profile on; null is until turned off. */
private val DURATIONS = listOf(null, 25, 60, 120, 240)

/** A schedule's times, every half hour. */
private val TIMES = (0 until 48).map { LocalTime.of(it / 2, it % 2 * 30) }

/** What the screen needs the user to allow, looked at again while it shows: they're granted in Android's settings. */
private data class Access(
    val usage: Boolean,
    val dnd: Boolean,
    val guard: Boolean,
)

/**
 * Settings › focus: the focus profiles, turned on and off, made and edited; the app timers; and what both need Android
 * to allow: usage access for timers, switching Do Not Disturb for profiles that ask for it, and the opt-in
 * [FocusGuard] for apps opened outside the launcher. Everything here is focus.toml, which agents may edit too.
 */
@Composable
fun FocusScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val focus = runtime.focus
    val context = LocalContext.current
    var access by remember { mutableStateOf(Access(focus.screenTime.granted(), focus.dndGranted(), FocusGuard.enabled(context))) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            access = Access(focus.screenTime.granted(), focus.dndGranted(), FocusGuard.enabled(context))
        }
    }
    var editing by remember { mutableStateOf<String?>(null) }
    editing?.let { name ->
        val profile = focus.config.profiles.firstOrNull { it.name == name }
        if (profile != null) return ProfileEditor(runtime, profile, access, onRenamed = { editing = it }, onDone = { editing = null })
    }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    // The app a timer is being set for, by package: from the add button's list, or from an app's long-press menu.
    var timerFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focus.pickingTimer) {
        focus.pickingTimer?.let { timerFor = it }
        focus.pickingTimer = null
    }
    var pickingApp by remember { mutableStateOf(false) }

    OverlayPanel(stringResource(R.string.focus_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Heading(R.string.focus_profiles) { BracketAction(stringResource(R.string.focus_new_profile), Palette.accent) { naming = true } }
            Hint(stringResource(R.string.focus_profiles_hint))
            val active = focus.active
            for (p in focus.config.profiles) {
                ProfileRow(p, active, onOn = { minutes -> focus.turnOn(p.name, minutes) }, onOff = focus::turnOff, onEdit = { editing = p.name }, onDelete = { deleting = p.name })
            }

            Heading(R.string.focus_timers) { BracketAction(stringResource(R.string.focus_add_timer), Palette.accent) { pickingApp = true } }
            Hint(stringResource(R.string.focus_timers_hint))
            if (!access.usage) {
                Hint(stringResource(R.string.focus_usage_needed), Palette.red)
                BracketAction(stringResource(R.string.focus_allow), Palette.accent) { focus.screenTime.openSettings() }
            }
            val resources = LocalResources.current
            for (timer in focus.config.timers) {
                Row(Modifier.card(onClick = { timerFor = timer.app }).padding(vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(focus.label(timer.app), style = Mono.copy(fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val limit = duration(resources, timer.minutes * 60_000L)
                        BasicText(
                            if (access.usage) stringResource(R.string.focus_timer_used, duration(resources, focus.screenTime.usedToday(timer.app)), limit) else limit,
                            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                        )
                    }
                    BracketAction(stringResource(R.string.focus_remove), Palette.muted) { focus.update { it.withTimer(timer.app, null) } }
                }
                Spacer(Modifier.height(8.dp))
            }

            Heading(R.string.focus_outside)
            Hint(stringResource(R.string.focus_guard_explain))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(stringResource(if (access.guard) R.string.focus_guard_on else R.string.focus_guard_off), Modifier.padding(end = 8.dp), style = Mono.copy(color = if (access.guard) Palette.accent else Palette.muted))
                BracketAction(stringResource(R.string.focus_guard_open), Palette.accent) { FocusGuard.openSettings(context) }
            }

            for (problem in focus.errors) Hint(problem, Palette.red)
            Hint(stringResource(R.string.focus_file_hint))
        }
    }

    if (naming) {
        val taken = stringResource(R.string.focus_name_taken)
        var error by remember { mutableStateOf<String?>(null) }
        TextDialog(stringResource(R.string.focus_new_profile), "", stringResource(R.string.focus_create), hint = error, onDismiss = { naming = false }) { name ->
            if (name.equals(FocusRules.OFF, ignoreCase = true) || focus.config.profile(name) != null) {
                error = taken
            } else {
                focus.update { it.withProfile(null, FocusProfile(name)) }
                naming = false
                editing = name
            }
        }
    }
    deleting?.let { name ->
        ConfirmDialog(stringResource(R.string.focus_delete_title, name), stringResource(R.string.focus_delete_message), stringResource(R.string.focus_delete), onDismiss = { deleting = null }) {
            focus.update { it.withoutProfile(name) }
            deleting = null
        }
    }
    if (pickingApp) {
        AppPickerDialog(runtime, onDismiss = { pickingApp = false }) { pkg ->
            pickingApp = false
            timerFor = pkg
        }
    }
    timerFor?.let { pkg ->
        LimitDialog(focus.label(pkg), focus.config.timer(pkg)?.minutes, onDismiss = { timerFor = null }) { minutes ->
            focus.update { it.withTimer(pkg, minutes) }
            timerFor = null
            // A timer counts screen time: ask for usage access now, where it's needed, not before.
            if (minutes != null && !focus.screenTime.granted()) focus.screenTime.openSettings()
        }
    }
}

/** What settings' row for this screen says: the profile on, or off, and how many app timers there are. */
@Composable
fun focusSubtitle(runtime: Runtime): String {
    val focus = runtime.focus
    val on = focus.active?.profile?.name?.let { stringResource(R.string.focus_subtitle_on, it) } ?: stringResource(R.string.focus_status_off)
    val timers = focus.config.timers.size
    return if (timers == 0) on else on + " · " + pluralStringResource(R.plurals.focus_timers_count, timers, timers)
}

/** A muted note under a heading; [color] for one that needs doing. */
@Composable
private fun Hint(
    text: String,
    color: Color = Palette.muted,
) = BasicText(text, Modifier.padding(bottom = 8.dp), style = Mono.copy(color = color, fontSize = TextSize.Small))

/** A profile: its name and what it does, whether it's on, and [ turn on ] (for how long) or [ turn off ], [ edit ], [ delete ]. */
@Composable
private fun ProfileRow(
    p: FocusProfile,
    active: ActiveFocus?,
    onOn: (Int?) -> Unit,
    onOff: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val on = active?.profile?.name == p.name
    val resources = LocalResources.current
    Column(Modifier.card(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(p.name, Modifier.weight(1f), style = Mono.copy(color = if (on) Palette.accent else Palette.foreground, fontSize = TextSize.Item))
            if (on) {
                val until = active?.until?.let { FocusFile.formatTime(it.toLocalTime()) }
                BasicText(
                    when {
                        until == null -> stringResource(R.string.focus_status_on)
                        active.scheduled -> stringResource(R.string.focus_status_scheduled, until)
                        else -> stringResource(R.string.focus_status_until, until)
                    },
                    style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small),
                )
            }
        }
        BasicText(summary(p), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (on) {
                BracketAction(stringResource(R.string.focus_turn_off), Palette.accent, onClick = onOff)
            } else {
                var choosing by remember { mutableStateOf(false) }
                Box {
                    BracketAction(stringResource(R.string.focus_turn_on), Palette.accent) { choosing = true }
                    if (choosing) {
                        val always = stringResource(R.string.focus_until_off)
                        ChoicePopup(DURATIONS, null, BelowAnchorEnd, onDismiss = { choosing = false }, onPick = onOn, label = { m -> m?.let { resources.getString(R.string.focus_for, duration(resources, it * 60_000L)) } ?: always })
                    }
                }
            }
            BracketAction(stringResource(R.string.focus_edit), Palette.muted, onClick = onEdit)
            BracketAction(stringResource(R.string.focus_delete), Palette.red, onClick = onDelete)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** "3 apps · do not disturb · mon–fri 09:00–17:00", what a profile does in a line. */
@Composable
private fun summary(p: FocusProfile): String {
    val parts = ArrayList<String>()
    parts +=
        if (p.block != null) {
            pluralStringResource(R.plurals.focus_blocks_apps, p.block.size, p.block.size)
        } else {
            pluralStringResource(R.plurals.focus_allows_apps, p.apps.size, p.apps.size)
        }
    if (p.dnd) parts += stringResource(R.string.focus_dnd)
    p.schedule?.let { s -> parts += scheduleText(s) }
    return parts.joinToString(" · ")
}

private fun scheduleText(s: WeeklySchedule): String {
    val days = DayOfWeek.entries.filter { it in s.days }.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    return "$days ${FocusFile.formatTime(s.from)}–${FocusFile.formatTime(s.to)}"
}

/**
 * A profile's settings: its name, whether its apps are the ones it lets through or the ones it blocks, Do Not Disturb,
 * a weekly schedule, and the apps themselves, ticked in a list that narrows as you type.
 */
@Composable
private fun ProfileEditor(
    runtime: Runtime,
    profile: FocusProfile,
    access: Access,
    onRenamed: (String) -> Unit,
    onDone: () -> Unit,
) {
    val focus = runtime.focus
    fun change(next: FocusProfile) = focus.update { it.withProfile(profile.name, next) }
    var renaming by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val blocking = profile.block != null
    // What's ticked is the block list, or the apps it lets through; switching between them starts the other empty.
    val ticked = profile.block ?: profile.apps
    val apps = remember(runtime.androidApps.apps.size, runtime.linuxApps.apps.size) { allApps(runtime).filter { !it.privateSpace }.distinctBy { Focus.ids(it).first() }.sortedBy { it.label.lowercase() } }
    val shown = apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    // Back to the profiles, not out of settings: settings' own handler came first, so this one goes first.
    BackHandler(onBack = onDone)

    OverlayPanel(profile.name, onDone, contentWidth = ReadingWidth, onBack = onDone) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            item {
                Row {
                    BracketAction(stringResource(R.string.focus_rename), Palette.accent) { renaming = true }
                }
                StyleOption(R.string.focus_apps_mode, listOf(stringResource(R.string.focus_apps_only), stringResource(R.string.focus_apps_except)), if (blocking) 1 else 0) { i ->
                    if ((i == 1) != blocking) change(if (i == 1) profile.copy(apps = emptySet(), block = emptySet()) else profile.copy(block = null))
                }
                StyleOption(R.string.focus_dnd, listOf(stringResource(R.string.focus_yes), stringResource(R.string.focus_no)), if (profile.dnd) 0 else 1) { i -> change(profile.copy(dnd = i == 0)) }
                if (profile.dnd && !access.dnd && MediaListener.connected == null) {
                    OptionHint(stringResource(R.string.focus_dnd_needed))
                    BracketAction(stringResource(R.string.focus_allow), Palette.accent, Modifier.padding(start = 96.dp)) { focus.openDndSettings() }
                }
                ScheduleOptions(profile.schedule) { change(profile.copy(schedule = it)) }
                Heading(R.string.focus_apps)
                SearchField(query, { query = it }, {}, stringResource(R.string.focus_search_apps))
                Spacer(Modifier.height(8.dp))
            }
            items(shown, key = { it.key }) { hit ->
                val id = Focus.ids(hit).first()
                val on = id in ticked
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val next = if (on) ticked - id else ticked + id
                        change(if (blocking) profile.copy(block = next) else profile.copy(apps = next))
                    }.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(if (on) "[x] " else "[ ] ", style = Mono.copy(color = if (on) Palette.accent else Palette.muted))
                    BasicText(hit.label, Modifier.weight(1f), style = Mono.copy(color = if (on) Palette.foreground else Palette.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hit !is AppHit.Android) BasicText(kindWord(hit), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
            }
        }
    }
    if (renaming) {
        val taken = stringResource(R.string.focus_name_taken)
        var error by remember { mutableStateOf<String?>(null) }
        TextDialog(stringResource(R.string.focus_rename), profile.name, stringResource(R.string.focus_rename), hint = error, onDismiss = { renaming = false }) { name ->
            val other = focus.config.profile(name)
            if (name.equals(FocusRules.OFF, ignoreCase = true) || (other != null && other.name != profile.name)) {
                error = taken
            } else {
                change(profile.copy(name = name))
                renaming = false
                onRenamed(name)
            }
        }
    }
}

/** What kind of app [hit] is, beside its name in the editor's list: Android's go without. */
@Composable
private fun kindWord(hit: AppHit) =
    when (hit) {
        is AppHit.Linux -> stringResource(R.string.focus_kind_linux)
        is AppHit.Owl -> stringResource(R.string.focus_kind_owl)
        else -> stringResource(R.string.focus_kind_shortcut)
    }

/** None, or weekly: the days it starts on, and from and to, each picked from the half hours. */
@Composable
private fun ScheduleOptions(
    schedule: WeeklySchedule?,
    onChange: (WeeklySchedule?) -> Unit,
) {
    StyleOption(R.string.focus_schedule, listOf(stringResource(R.string.focus_schedule_none), stringResource(R.string.focus_schedule_weekly)), if (schedule == null) 0 else 1) { i ->
        onChange(if (i == 0) null else schedule ?: WeeklySchedule(DayOfWeek.entries.take(5).toSet(), LocalTime.of(9, 0), LocalTime.of(17, 0)))
    }
    val s = schedule ?: return
    Row(Modifier.padding(start = 96.dp).horizontalScroll(rememberScrollState())) {
        for (day in DayOfWeek.entries) {
            val on = day in s.days
            BasicText(
                day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                Modifier
                    .background(if (on) Palette.accent else Color.Transparent)
                    .clickable { onChange(s.copy(days = if (on) s.days - day else s.days + day)) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                style = Mono.copy(color = if (on) Palette.background else Palette.foreground),
            )
        }
    }
    Row(Modifier.padding(start = 96.dp), verticalAlignment = Alignment.CenterVertically) {
        TimePick(stringResource(R.string.focus_schedule_from), s.from) { onChange(s.copy(from = it)) }
        TimePick(stringResource(R.string.focus_schedule_to), s.to) { onChange(s.copy(to = it)) }
    }
    if (s.days.isEmpty()) OptionHint(stringResource(R.string.focus_schedule_no_days))
}

@Composable
private fun TimePick(
    label: String,
    time: LocalTime,
    onPick: (LocalTime) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        BracketAction("$label ${FocusFile.formatTime(time)}", Palette.accent) { open = true }
        if (open) ChoicePopup(TIMES, time.takeIf { it in TIMES }, BelowAnchorEnd, onDismiss = { open = false }, onPick = onPick, label = FocusFile::formatTime, minWidth = 120.dp)
    }
}

/** The phone's Android apps, by name, narrowed as you type; picking one sets its timer next. */
@Composable
private fun AppPickerDialog(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val apps = remember(runtime.androidApps.apps.size) { runtime.androidApps.apps.filter { it.profile == null }.distinctBy { it.component.packageName }.sortedBy { it.label.lowercase() } }
    DialogFrame(onDismiss) {
        BasicText(stringResource(R.string.focus_pick_app), style = Mono.copy(fontSize = TextSize.Title))
        SearchField(query, { query = it }, {}, stringResource(R.string.focus_search_apps))
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }, key = { it.key }) { app ->
                BasicText(
                    app.label,
                    Modifier.fillMaxWidth().clickable { onPick(app.component.packageName) }.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = Mono.copy(fontSize = TextSize.Item),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction(stringResource(R.string.files_cancel), Palette.muted, onClick = onDismiss)
        }
    }
}

/** [label]'s daily limit, from [LIMITS], or none (null), which removes its timer. */
@Composable
private fun LimitDialog(
    label: String,
    current: Int?,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    val resources = LocalResources.current
    DialogFrame(onDismiss) {
        BasicText(stringResource(R.string.focus_limit_title, label), style = Mono.copy(fontSize = TextSize.Title))
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            for (m in listOf<Int?>(null) + LIMITS + listOfNotNull(current?.takeIf { it !in LIMITS })) {
                val picked = m == current
                Row(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    BasicText(if (picked) "● " else "  ", style = Mono.copy(color = Palette.accent))
                    BasicText(m?.let { duration(resources, it * 60_000L) } ?: stringResource(R.string.focus_no_limit), style = Mono.copy(color = if (picked) Palette.accent else Palette.foreground))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction(stringResource(R.string.files_cancel), Palette.muted, onClick = onDismiss)
        }
    }
}
