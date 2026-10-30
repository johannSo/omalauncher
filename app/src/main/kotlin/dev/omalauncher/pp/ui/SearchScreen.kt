package dev.omalauncher.pp.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.linux.LinuxState
import dev.omalauncher.pp.search.BarSearch
import dev.omalauncher.pp.search.ContactSearch
import dev.omalauncher.pp.search.WebSearch
import dev.omalauncher.pp.theme.SearchStyle

/** Lets [BarSearch] read the bar's text and leave the drawer as opening an app does; Launcher calls it once. */
internal fun BarSearch.bind(state: LauncherState) {
    query = { state.query }
    leave = {
        state.query = ""
        state.closeNow()
    }
    showPackages = { state.show(SettingsPage.Packages) }
}

/**
 * Settings › search: which kinds of results the bar lists besides apps, and its web search engine, all in style.toml's
 * [search]. Turning contacts on asks for the contacts permission, which nothing else does.
 */
@Composable
fun SearchScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val style = runtime.style
    val linux = runtime.bootstrap.state.collectAsState().value == LinuxState.Installed
    var granted by remember { mutableStateOf(context.checkSelfPermission(ContactSearch.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            refused = !ok
            if (ok) style.update { it.with(SearchStyle.CONTACTS, true) }
        }
    OverlayPanel(stringResource(R.string.bar_search_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            BasicText(stringResource(R.string.bar_search_intro), Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            Heading(R.string.bar_search_kinds)
            Flag(R.string.bar_search_calculator, R.string.style_on, R.string.style_off, style, SearchStyle.CALCULATOR)
            Flag(R.string.bar_search_settings, R.string.style_on, R.string.style_off, style, SearchStyle.SETTINGS)
            Flag(R.string.bar_search_shortcuts, R.string.style_on, R.string.style_off, style, SearchStyle.SHORTCUTS)
            // On only with the permission: turning it on asks for it first, and a permission taken back since shows it off.
            val contacts = style.style[SearchStyle.CONTACTS] && granted
            StyleOption(R.string.bar_search_contacts, listOf(stringResource(R.string.style_on), stringResource(R.string.style_off)), if (contacts) 0 else 1) { i ->
                when {
                    i == 1 -> style.update { it.with(SearchStyle.CONTACTS, false) }
                    granted -> style.update { it.with(SearchStyle.CONTACTS, true) }
                    else -> ask.launch(ContactSearch.PERMISSION)
                }
            }
            // After two refusals Android stops asking; the switch is then in the app's own settings.
            if (refused) {
                OptionHint(stringResource(R.string.bar_search_contacts_denied))
                if ((context as? Activity)?.shouldShowRequestPermissionRationale(ContactSearch.PERMISSION) == false) {
                    BracketAction(stringResource(R.string.bar_search_contacts_allow), Palette.accent, Modifier.padding(start = 96.dp)) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            Flag(R.string.bar_search_files, R.string.style_on, R.string.style_off, style, SearchStyle.FILES)
            if (!linux) OptionHint(stringResource(R.string.bar_search_needs_linux))
            Flag(R.string.bar_search_packages, R.string.style_on, R.string.style_off, style, SearchStyle.PACKAGES)
            if (!linux) OptionHint(stringResource(R.string.bar_search_needs_linux))
            Flag(R.string.bar_search_web, R.string.style_on, R.string.style_off, style, SearchStyle.WEB)
            val engines = WebSearch.ENGINES.keys.toList()
            val engine = style.style[SearchStyle.ENGINE]
            // A URL set in style.toml shows as custom, under the engines.
            StyleOption(R.string.bar_search_engine, engines, engines.indexOf(engine), engine) { i -> style.update { it.with(SearchStyle.ENGINE, engines[i]) } }
            OptionHint(stringResource(R.string.bar_search_engine_hint), Modifier.padding(bottom = 12.dp))
        }
    }
}
