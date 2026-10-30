package dev.omalauncher.pp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.omalauncher.pp.R
import dev.omalauncher.pp.widgets.ExternalValues

/**
 * settings › values from other apps: whether Tasker and the like may set values in widgets that take them (a
 * `source x = external`, see [ExternalValues]). Off until the user picks allow; Android's own data, not style.toml,
 * so nothing in Linux can turn it on.
 */
@Composable
internal fun otherAppsRow(): SettingsRow {
    val context = LocalContext.current
    val store = remember { ExternalValues.get(context) }
    val on = store.allowed
    val allow = stringResource(R.string.owl_intents_allow)
    val off = stringResource(R.string.owl_intents_deny)
    return SettingsRow(R.string.owl_intents_setting, stringResource(if (on) R.string.owl_intents_setting_on else R.string.owl_intents_setting_off)) {}.apply {
        menu = { close -> ChoicePopup(listOf(true, false), on, BelowAnchorEnd, onDismiss = close, onPick = store::allow, label = { if (it) allow else off }) }
    }
}
