package dev.omalauncher.pp.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.theme.TileStyle
import dev.omalauncher.pp.tiles.AgentTile
import dev.omalauncher.pp.tiles.LinuxTile
import dev.omalauncher.pp.tiles.TerminalTile

/**
 * The launcher's quick settings tiles: what each does, a way to add it (Android 13+ asks with its own dialog; before
 * that, only the shade's edit mode adds tiles), and what the ask tile opens.
 */
@Composable
fun QuickTilesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    OverlayPanel(stringResource(R.string.qs_tiles_title), onDismiss, contentWidth = ReadingWidth) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TileRow(LinuxTile::class.java, R.string.qs_tiles_linux, R.string.qs_tiles_linux_about, R.drawable.qs_linux)
            TileRow(TerminalTile::class.java, R.string.qs_tiles_terminal, R.string.qs_tiles_terminal_about, R.drawable.qs_terminal)
            TileRow(AgentTile::class.java, R.string.qs_tiles_ask, R.string.qs_tiles_ask_about, R.drawable.qs_agent)
            AskRow(runtime)
            BasicText(
                stringResource(R.string.qs_tiles_hint),
                Modifier.padding(top = 4.dp, start = 14.dp, end = 14.dp),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
}

/** One tile: its name and what it does, and [ add ] on Android 13+, with what came of it under. */
@Composable
private fun TileRow(
    service: Class<*>,
    @StringRes name: Int,
    @StringRes about: Int,
    @DrawableRes icon: Int,
) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<Int?>(null) }
    val label = stringResource(name)
    Row(Modifier.card(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = Mono.copy(fontSize = TextSize.Item))
            BasicText(stringResource(about), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            result?.let(::resultText)?.let { (text, ok) ->
                BasicText(stringResource(text), style = Mono.copy(color = if (ok) Palette.green else Palette.red, fontSize = TextSize.Small))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            BracketAction(stringResource(R.string.qs_tiles_add), Palette.accent) {
                requestAdd(context, ComponentName(context, service), label, icon) { result = it }
            }
        }
    }
}

@RequiresApi(33)
private fun requestAdd(
    context: android.content.Context,
    component: ComponentName,
    label: String,
    @DrawableRes icon: Int,
    then: (Int) -> Unit,
) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(component, label, Icon.createWithResource(context, icon), context.mainExecutor, then)
}

/** What Android said to [StatusBarManager.requestAddTileService], and whether that's good news; nothing for a "no". */
private fun resultText(code: Int): Pair<Int, Boolean>? =
    when (code) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.qs_tiles_added to true
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.qs_tiles_already_added to true
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
        else -> R.string.qs_tiles_add_failed to false
    }

/**
 * What the ask tile opens: the bar to type into, or listening. Voice asks for the microphone as it's picked, and stays
 * on typing if that's refused; allowed later and taken back, the tile types again, which the row says.
 */
@Composable
private fun AskRow(runtime: Runtime) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val picked = LauncherStyle.style[TileStyle.ASK_AGENT]
    fun pick(value: String) = runtime.style.update { it.with(TileStyle.ASK_AGENT, value) }
    val mic =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pick(TileStyle.VOICE)
        }
    // Read as it's drawn: settings comes back to the front after the permission's dialog, and draws again.
    val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val choices = listOf(TileStyle.TYPE to stringResource(R.string.qs_tiles_ask_type), TileStyle.VOICE to stringResource(R.string.qs_tiles_ask_voice))
    val current = choices.firstOrNull { it.first == picked } ?: choices.first()
    Row(Modifier.card(onClick = { open = !open }), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(R.string.qs_tiles_ask_opens), style = Mono.copy(fontSize = TextSize.Item))
            if (picked == TileStyle.VOICE && !micAllowed) {
                BasicText(stringResource(R.string.qs_tiles_ask_no_mic), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(current.second, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                ChoicePopup(choices, current, BelowAnchorEnd, onDismiss = { open = false }, onPick = { (value, _) ->
                    if (value == TileStyle.VOICE && !micAllowed) mic.launch(Manifest.permission.RECORD_AUDIO) else pick(value)
                }, label = { it.second })
            }
        }
    }
}
