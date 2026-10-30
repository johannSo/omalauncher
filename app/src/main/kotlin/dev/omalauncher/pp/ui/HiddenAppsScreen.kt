package dev.omalauncher.pp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.widgets.HomeItem
import dev.omalauncher.pp.widgets.Shortcut
import dev.omalauncher.pp.widgets.app

/**
 * Settings › hidden apps: every app but the private space's, the hidden ones first, each to show or hide and to
 * rename, and whether typing a hidden app's full name in the bar still finds it. It's all apps.toml's
 * ([dev.omalauncher.pp.AppTweakStore]), which agents edit too.
 */
@Composable
fun HiddenAppsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val tweaks = runtime.appTweaks
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<AppHit?>(null) }
    // The private space hides its apps itself; they keep its rules.
    val apps by remember(runtime) { derivedStateOf { allApps(runtime).filterNot { it.privateSpace }.sortedBy { it.label.lowercase() } } }
    val q = query.trim().lowercase()
    val (hidden, shown) = apps.filter { q.isEmpty() || q in it.label.lowercase() || q in it.own.lowercase() }.partition { it.hidden }

    OverlayPanel(stringResource(R.string.app_tweaks_title), onDismiss, contentWidth = ReadingWidth) {
        SearchField(query, onChange = { query = it }, onGo = {}, placeholder = stringResource(R.string.app_tweaks_search))
        BasicText(
            stringResource(R.string.app_tweaks_intro),
            Modifier.padding(vertical = 8.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        StyleOption(
            R.string.app_tweaks_find_hidden,
            listOf(stringResource(R.string.app_tweaks_on), stringResource(R.string.app_tweaks_off)),
            if (tweaks.tweaks.findHidden) 0 else 1,
        ) { tweaks.findHidden(it == 0) }
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((title, list) in listOf(R.string.app_tweaks_hidden_section to hidden, R.string.app_tweaks_shown_section to shown)) {
                if (list.isEmpty()) continue
                item(key = "title:$title") {
                    BasicText(stringResource(title), Modifier.padding(top = 8.dp), style = Mono.copy(color = Palette.muted))
                }
                items(list, key = { it.key }) { hit ->
                    AppTweakRow(runtime, hit, onRename = { renaming = hit }, onHide = { tweaks.hide(hit.key, it) })
                }
            }
            if (hidden.isEmpty() && shown.isEmpty()) {
                item(key = "none") { BasicText(stringResource(R.string.app_tweaks_none), style = Mono.copy(color = Palette.muted)) }
            }
        }
    }
    renaming?.let { RenameAppDialog(runtime, it, onDismiss = { renaming = null }) }
}

/** Settings' row for this screen: how many apps are hidden. */
@Composable
internal fun hiddenAppsSubtitle(runtime: Runtime): String {
    val count = runtime.appTweaks.tweaks.apps.values.count { it.hidden == true }
    return if (count == 0) stringResource(R.string.app_tweaks_settings_none) else pluralStringResource(R.plurals.app_tweaks_settings_subtitle, count, count)
}

/** An app in settings › hidden apps: its icon and name, what kind it is and the name it had, then rename and hide or show. */
@Composable
private fun AppTweakRow(
    runtime: Runtime,
    hit: AppHit,
    onRename: () -> Unit,
    onHide: (Boolean) -> Unit,
) {
    ActionsRow(
        Modifier.card(10.dp),
        actions = {
            BracketAction(stringResource(R.string.app_tweaks_rename), Palette.accent, onClick = onRename)
            if (hit.hidden) {
                BracketAction(stringResource(R.string.app_tweaks_show), Palette.accent) { onHide(false) }
            } else {
                BracketAction(stringResource(R.string.app_tweaks_hide), Palette.muted) { onHide(true) }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, mask) = hit.icons(runtime)
            val mono = LauncherStyle.monochromeIcons
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                val picture = if (mono) mask else icon
                if (picture != null) {
                    Image(picture, hit.spoken, Modifier.size(36.dp), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
                } else {
                    BasicText(initials(hit.label), style = Mono.copy(color = Palette.accent))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText(hit.label, style = Mono.copy(fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind =
                    when (hit) {
                        is AppHit.Linux -> stringResource(R.string.app_tweaks_kind_linux)
                        is AppHit.Owl -> stringResource(R.string.app_tweaks_kind_owl)
                        is AppHit.Shortcut -> stringResource(R.string.app_tweaks_kind_shortcut)
                        is AppHit.Android ->
                            when (hit.profileKind) {
                                "work" -> stringResource(R.string.app_tweaks_kind_work)
                                "clone" -> stringResource(R.string.app_tweaks_kind_clone)
                                else -> null
                            }
                    }
                val was = if (hit.own != hit.label) stringResource(R.string.app_tweaks_own_name, hit.own) else null
                val sub = listOfNotNull(kind, was).joinToString(" · ")
                if (sub.isNotEmpty()) BasicText(sub, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Asks for the name [hit] goes by everywhere; [ own name ] gives it back its own once it has another. */
@Composable
internal fun RenameAppDialog(
    runtime: Runtime,
    hit: AppHit,
    onDismiss: () -> Unit,
) {
    TextDialog(
        stringResource(R.string.app_tweaks_rename_title, hit.own),
        hit.label,
        stringResource(R.string.app_tweaks_rename_confirm),
        hint = stringResource(R.string.app_tweaks_rename_hint),
        onDismiss = onDismiss,
        actions = {
            if (hit.label != hit.own) {
                BracketAction(stringResource(R.string.app_tweaks_rename_reset), Palette.muted) {
                    runtime.appTweaks.rename(hit.key, null, hit.own)
                    onDismiss()
                }
            }
        },
    ) { name ->
        runtime.appTweaks.rename(hit.key, name, hit.own)
        onDismiss()
    }
}

/** The app a home tile opens, as the drawer lists it, for its menu to rename; null for a widget or folder, or an app that's gone. */
internal fun appHitOf(
    runtime: Runtime,
    item: HomeItem,
): AppHit? {
    val s = item as? Shortcut ?: return null
    val apps = runtime.androidApps
    return when {
        s.component != null -> apps.pinned(s.component, s.profile)?.let { AppHit.Android(it) }
        s.androidShortcut != null -> apps.shortcuts.firstOrNull { it.ref == s.androidShortcut && it.profile == s.profile }?.let { AppHit.Shortcut(it) }
        s.linux != null -> AppHit.Linux(runtime.linuxApps.apps.firstOrNull { it.id == s.linux.id } ?: s.linux)
        s.owl != null -> runtime.widgets.specs.app(s.owl)?.let { AppHit.Owl(it) }
        else -> null
    }?.takeIf { !it.privateSpace }
}
