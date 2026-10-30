package dev.omalauncher.pp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.GestureAccessibility
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.theme.GestureAction
import dev.omalauncher.pp.theme.needsAccessibility
import dev.omalauncher.pp.widgets.WidgetSpec
import dev.omalauncher.pp.widgets.editWidgetCommand

/** What a gesture's list offers: an action to set, or one that asks for what it takes first (which app, the command). */
private sealed interface Pick {
    data class Set(
        val action: GestureAction,
    ) : Pick

    data class Ask(
        val what: Asked,
    ) : Pick
}

/** The actions that take something the user types or picks. */
private enum class Asked { App, Linux, Terminal, Agent }

/** The [Asked] that [action] was made from, so its row in the list is marked as picked. */
private fun askedFor(action: GestureAction) =
    when (action) {
        is GestureAction.OpenApp, is GestureAction.OpenLinuxApp -> Asked.App
        is GestureAction.RunLinux -> Asked.Linux
        is GestureAction.RunInTerminal -> Asked.Terminal
        is GestureAction.AskAgent -> Asked.Agent
        else -> null
    }

/**
 * Gesture settings: what each of home's gestures does, picked from a dropdown of actions and panels (owl files to edit
 * with the agent); an app, a command or a prompt is asked for once picked. Lock and recent apps need the accessibility
 * service, which a screen of its own explains before the user turns it on.
 */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    /** Opens the agent on a panel, as settings › pages does on a face. */
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    val gestures = runtime.gestures
    val connected by GestureAccessibility.connected.collectAsState()
    var explaining by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf<HomeGesture?>(null) }
    var asking by remember { mutableStateOf<Pair<HomeGesture, Asked>?>(null) }
    // A panel that doesn't compile is still listed, marked, so a pick that stopped working shows why.
    val panels =
        runtime.widgets.panels().map {
            Pick.Set(GestureAction.OpenPanel(it.file.name)) to if (it.program == null) stringResource(R.string.gestures_has_errors, it.name) else it.name
        }
    val nothing = Pick.Set(GestureAction.None) to stringResource(R.string.gestures_nothing)
    // Android's own, then the launcher's screens.
    val system =
        listOf(
            GestureAction.Shade to R.string.fork_gestures_shade,
            GestureAction.QuickSettings to R.string.gesture_action_quick_settings,
            GestureAction.Recents to R.string.gesture_action_recents,
            GestureAction.Lock to R.string.gesture_action_lock,
            GestureAction.Pages to R.string.gesture_action_pages,
        ).map { (action, label) -> Pick.Set(action) to stringResource(label) }
    val asks =
        listOf(
            Asked.App to R.string.gesture_ask_app,
            Asked.Linux to R.string.gesture_ask_linux,
            Asked.Terminal to R.string.gesture_ask_terminal,
            Asked.Agent to R.string.gesture_ask_agent,
        ).map { (what, label) -> Pick.Ask(what) to stringResource(label) }
    val drawer = Pick.Set(GestureAction.Drawer) to stringResource(R.string.gesture_action_drawer)

    fun pick(
        gesture: HomeGesture,
        pick: Pick,
    ) {
        when (pick) {
            is Pick.Set -> {
                gestures[gesture] = pick.action
                if (pick.action.needsAccessibility && !GestureAccessibility.connected.value) explaining = true
            }
            is Pick.Ask -> if (pick.what == Asked.App) pickingApp = gesture else asking = gesture to pick.what
        }
    }

    OverlayPanel(stringResource(R.string.gestures_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!connected && HomeGesture.entries.any { gestures[it].needsAccessibility }) {
                Row(Modifier.card(onClick = { explaining = true }), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(stringResource(R.string.gesture_accessibility_off), Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Small))
                    BasicText("[ ${stringResource(R.string.gesture_accessibility_turn_on)} ]", Modifier.padding(start = 8.dp), style = Mono.copy(color = Palette.accent))
                }
                Spacer(Modifier.height(8.dp))
            }
            for (gesture in HomeGesture.entries) {
                val current = gestures[gesture]
                // Swiping up, a panel is what the drawer shows, so they come first, and "drawer" would be one of them again.
                val choices = if (gesture == HomeGesture.SwipeUp) panels + nothing + system + asks else listOf(nothing, drawer) + system + panels + asks
                val selected = choices.firstOrNull { it.first == Pick.Set(current) } ?: askedFor(current)?.let { a -> choices.firstOrNull { it.first == Pick.Ask(a) } }
                // A panel not among them (removed since): shown as it's saved, so the user sees it and picks another.
                val label =
                    when {
                        current is GestureAction.OpenPanel && choices.none { it.first == Pick.Set(current) } -> stringResource(R.string.gestures_missing, current.file)
                        else -> runtime.actionLabel(current)
                    }
                GestureRow(stringResource(gesture.label), choices, selected, label) { pick(gesture, it) }
                runtime.widgets.panels().firstOrNull { it.file.name == (current as? GestureAction.OpenPanel)?.file }?.let { EditFile(it, onAgent) }
                Spacer(Modifier.height(8.dp))
            }
            BasicText(
                stringResource(R.string.gestures_hint),
                Modifier.padding(top = 4.dp, start = 14.dp, end = 14.dp),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            BasicText(
                stringResource(R.string.gesture_hint_more),
                Modifier.padding(top = 8.dp, start = 14.dp, end = 14.dp, bottom = 8.dp),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }

    pickingApp?.let { gesture ->
        BackHandler { pickingApp = null }
        AppPicker(runtime, stringResource(R.string.gesture_pick_app_title, stringResource(gesture.label)), onDismiss = { pickingApp = null }) {
            pickingApp = null
            gestures[gesture] = it
        }
    }
    asking?.let { (gesture, what) ->
        val current = gestures[gesture]
        val (title, hint, initial) =
            when (what) {
                Asked.Linux -> Triple(R.string.gesture_linux_title, R.string.gesture_linux_hint, (current as? GestureAction.RunLinux)?.command)
                Asked.Terminal -> Triple(R.string.gesture_terminal_title, R.string.gesture_terminal_hint, (current as? GestureAction.RunInTerminal)?.command)
                else -> Triple(R.string.gesture_agent_title, R.string.gesture_agent_hint, (current as? GestureAction.AskAgent)?.prompt)
            }
        TextDialog(
            title = stringResource(title, stringResource(gesture.label)),
            initial = initial.orEmpty(),
            confirm = stringResource(R.string.gesture_save),
            hint = stringResource(hint),
            onDismiss = { asking = null },
        ) { text ->
            asking = null
            gestures[gesture] =
                when (what) {
                    Asked.Linux -> GestureAction.RunLinux(text)
                    Asked.Terminal -> GestureAction.RunInTerminal(text)
                    else -> GestureAction.AskAgent(text)
                }
        }
    }
    if (explaining) {
        BackHandler { explaining = false }
        AccessibilityExplanation(connected, onDismiss = { explaining = false })
    }
}

/** [edit] starts the agent on [spec]'s file, as one edits a face or a widget. The whole line takes it, file name too: the
 * keys' arrows then stop on it between the gestures, where a short [ edit ] at its start lost to the full row past it.
 */
@Composable
private fun EditFile(
    spec: WidgetSpec,
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    Row(
        Modifier
            .padding(start = 6.dp, top = 2.dp)
            .fillMaxWidth()
            .clickable { onAgent("Edit ${spec.name}") { editWidgetCommand(it, spec) } }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("[ ${stringResource(R.string.gestures_edit)} ]", Modifier.padding(end = 8.dp), style = Mono.copy(color = Palette.accent))
        BasicText(spec.file.name, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}

/** A gesture and what it does ([current]); tapping that lists the others under it. [choices] are (pick, label). */
@Composable
private fun GestureRow(
    label: String,
    choices: List<Pair<Pick, String>>,
    selected: Pair<Pick, String>?,
    current: String,
    onPick: (Pick) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.card(onClick = { open = !open }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.padding(end = 12.dp), style = Mono.copy(fontSize = TextSize.Item))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A command or a prompt can be long: one line, cut short, and the arrow still shows.
                BasicText(current, Modifier.weight(1f, fill = false), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                ChoicePopup(choices, selected, BelowAnchorEnd, onDismiss = { open = false }, onPick = { onPick(it.first) }, label = { it.second })
            }
        }
    }
}

/** An app for a gesture to open: the drawer's Android apps and Linux's, by name, the search field first. */
@Composable
private fun AppPicker(
    runtime: Runtime,
    title: String,
    onDismiss: () -> Unit,
    onPick: (GestureAction) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val android = stringResource(R.string.gesture_app_android)
    val linux = stringResource(R.string.gesture_app_linux)
    // The launcher's own profile only: an action names no other.
    val apps =
        remember(runtime.androidApps.apps.toList(), runtime.linuxApps.apps.toList()) {
            (
                runtime.androidApps.apps.filter { it.profile == null && !it.privateSpace }.map { Triple(it.label, android, GestureAction.OpenApp(it.component.flattenToShortString())) } +
                    runtime.linuxApps.apps.map { Triple(it.name, linux, GestureAction.OpenLinuxApp(it.id)) }
            ).sortedBy { it.first.lowercase() }
        }
    val shown = apps.filter { query.isBlank() || it.first.contains(query.trim(), ignoreCase = true) }
    OverlayPanel(title, onDismiss, contentWidth = ReadingWidth) {
        SearchField(query, { query = it }, onGo = { shown.firstOrNull()?.let { onPick(it.third) } }, placeholder = stringResource(R.string.gesture_search_apps))
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            BasicText(stringResource(R.string.gesture_no_apps), Modifier.padding(14.dp), style = Mono.copy(color = Palette.muted))
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.third.encoded }) { (name, kind, action) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(action) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(name, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(kind, Modifier.padding(start = 8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
            }
        }
    }
}

/**
 * What the accessibility service does and doesn't, before the user turns it on in Android's settings: Android's own
 * warning there is about what any service could do. It follows the service coming on while it shows.
 */
@Composable
private fun AccessibilityExplanation(
    connected: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    OverlayPanel(stringResource(R.string.gesture_accessibility_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(
                stringResource(if (connected) R.string.gesture_accessibility_on else R.string.gesture_accessibility_is_off),
                style = Mono.copy(color = if (connected) Palette.green else Palette.red, fontSize = TextSize.Item),
            )
            for (text in listOf(R.string.gesture_accessibility_what, R.string.gesture_accessibility_not, R.string.gesture_accessibility_steps)) {
                BasicText(stringResource(text), style = Mono.copy(color = Palette.foreground))
            }
            Column {
                if (!connected) {
                    BracketAction(stringResource(R.string.gesture_accessibility_open), Palette.accent) { GestureAccessibility.openSettings(context) }
                    BracketAction(stringResource(R.string.gesture_accessibility_app_info), Palette.muted) { GestureAccessibility.openAppInfo(context) }
                }
                BracketAction(stringResource(R.string.gesture_accessibility_done), if (connected) Palette.accent else Palette.muted, onClick = onDismiss)
            }
        }
    }
}
