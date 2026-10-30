package dev.omalauncher.pp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.theme.AgentStatusStyle

/** Settings › agents' switch for sessions' notifications: style.toml's `agents.status`, which agents can set too. */
@Composable
fun AgentStatusSetting(runtime: Runtime) {
    val on = runtime.style.style[AgentStatusStyle.STATUS]
    ActionsRow(
        Modifier.card(10.dp),
        actions = {
            BracketAction(
                stringResource(if (on) R.string.agent_status_on else R.string.agent_status_off),
                if (on) Palette.green else Palette.muted,
            ) { runtime.style.update { it.with(AgentStatusStyle.STATUS, !on) } }
        },
    ) {
        Column {
            BasicText(stringResource(R.string.agent_status_setting), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(stringResource(R.string.agent_status_setting_description), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
}
