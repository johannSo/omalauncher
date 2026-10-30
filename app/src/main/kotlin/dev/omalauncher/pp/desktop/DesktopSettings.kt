package dev.omalauncher.pp.desktop

import android.view.Display
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.theme.DesktopStyle
import dev.omalauncher.pp.ui.BelowAnchorEnd
import dev.omalauncher.pp.ui.BracketAction
import dev.omalauncher.pp.ui.ChoicePopup
import dev.omalauncher.pp.ui.Glyph
import dev.omalauncher.pp.ui.Mono
import dev.omalauncher.pp.ui.OverlayPanel
import dev.omalauncher.pp.ui.Palette
import dev.omalauncher.pp.ui.ReadingWidth
import dev.omalauncher.pp.ui.Symbol
import dev.omalauncher.pp.ui.TextSize
import dev.omalauncher.pp.ui.card

/** What settings' desktop row says: where the desktop is, or what opens it. */
@Composable
fun desktopSubtitle(runtime: Runtime): String {
    val desktop = runtime.desktop
    val host = desktop.host
    return when {
        host != null && host.displayId != Display.DEFAULT_DISPLAY -> stringResource(R.string.desktop_on_monitor)
        host != null -> stringResource(R.string.desktop_on_screen)
        else -> stringResource(R.string.desktop_settings_subtitle)
    }
}

/**
 * Settings › desktop: when the tiling desktop opens ([DesktopStyle.EXTERNAL], [DesktopStyle.LARGE_SCREENS]), the
 * monitors plugged in now with a way to open it on each, and its keys. The look is style.toml's `[desktop]`.
 */
@Composable
fun DesktopSettingsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val desktop = runtime.desktop
    val store = runtime.style
    val style = store.style
    OverlayPanel(stringResource(R.string.desktop_settings_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val external =
                listOf(
                    DesktopStyle.ASK to stringResource(R.string.desktop_external_ask),
                    DesktopStyle.AUTO to stringResource(R.string.desktop_external_auto),
                    DesktopStyle.OFF to stringResource(R.string.desktop_external_off),
                )
            ChoiceRow(stringResource(R.string.desktop_external), external, style[DesktopStyle.EXTERNAL]) { v -> store.update { it.with(DesktopStyle.EXTERNAL, v) } }
            Spacer(Modifier.height(8.dp))
            val onOff = listOf(true to stringResource(R.string.desktop_on), false to stringResource(R.string.desktop_off))
            ChoiceRow(stringResource(R.string.desktop_large_screens), onOff, style[DesktopStyle.LARGE_SCREENS]) { v -> store.update { it.with(DesktopStyle.LARGE_SCREENS, v) } }
            Hint(stringResource(if (desktop.largeScreen) R.string.desktop_large_screens_hint else R.string.desktop_large_screens_small))
            Spacer(Modifier.height(16.dp))
            val host = desktop.host
            if (desktop.monitors.isEmpty()) Hint(stringResource(R.string.desktop_no_monitor))
            for (monitor in desktop.monitors) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(monitor.name, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
                    when {
                        host?.displayId == monitor.id -> BasicText(stringResource(R.string.desktop_open_there), style = Mono.copy(color = Palette.muted))
                        monitor.allowed -> BracketAction(stringResource(R.string.desktop_open), Palette.accent) { desktop.start(monitor.id) }
                        else -> BasicText(stringResource(R.string.desktop_not_allowed), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    }
                }
            }
            if (host == null) {
                Row(Modifier.padding(horizontal = 6.dp)) {
                    BracketAction(stringResource(R.string.desktop_open_here), Palette.accent) { desktop.start(Display.DEFAULT_DISPLAY) }
                }
            }
            Hint(stringResource(R.string.desktop_keys))
            Hint(stringResource(R.string.desktop_style_hint))
        }
    }
}

@Composable
private fun Hint(text: String) =
    BasicText(text, Modifier.padding(top = 8.dp, start = 14.dp, end = 14.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/** A setting and its value; tapping it lists the others under it. [choices] are (value, label). */
@Composable
private fun <T> ChoiceRow(
    label: String,
    choices: List<Pair<T, String>>,
    current: T,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val picked = choices.first { it.first == current }
    Row(Modifier.card(onClick = { open = !open }), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(picked.second, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) ChoicePopup(choices, picked, BelowAnchorEnd, onDismiss = { open = false }, onPick = { onPick(it.first) }, label = { it.second })
        }
    }
}
