package dev.omalauncher.pp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.linux.JobState
import dev.omalauncher.pp.linux.LinuxState
import dev.omalauncher.pp.linux.SNAPSHOT_LOW_SPACE
import dev.omalauncher.pp.linux.SnapshotMeta
import dev.omalauncher.pp.linux.SnapshotReason
import dev.omalauncher.pp.linux.formatBytes
import dev.omalauncher.pp.theme.SnapshotStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Settings › linux's row for [SnapshotsScreen]: how many there are, or what they're for while there are none. */
@Composable
internal fun SnapshotsRow(
    runtime: Runtime,
    onOpen: () -> Unit,
) {
    val snapshots = runtime.snapshots
    LaunchedEffect(Unit) { snapshots.load() }
    val count = snapshots.list.size
    Row(Modifier.card(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(R.string.snapshots_title), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(
                if (count == 0) stringResource(R.string.snapshots_row_none) else pluralStringResource(R.plurals.snapshots_row, count, count),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
        Symbol(Glyph.ChevronRight, Palette.accent, fontSize = TextSize.Title)
    }
}

/**
 * Settings › linux › snapshots: the snapshots of the Linux system to roll back to ([dev.omalauncher.pp.linux.Snapshots]),
 * newest first, with their date, why they were taken and what deleting one frees; "snapshot now", restore and
 * delete; and style.toml's [SnapshotStyle] options.
 */
@Composable
fun SnapshotsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val state by runtime.bootstrap.state.collectAsState()
    if (state != LinuxState.Installed && state != LinuxState.Off) return LinuxScreen(runtime, onDismiss)
    val snapshots = runtime.snapshots
    var free by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { snapshots.load() }
    // Measured again once a snapshot came or went.
    LaunchedEffect(snapshots.list) { free = withContext(Dispatchers.IO) { runtime.proot.filesDir.usableSpace } }
    val job = snapshots.job
    val busy = snapshots.busy || runtime.shuttingDown || runtime.starting
    OverlayPanel(stringResource(R.string.snapshots_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            BasicText(stringResource(R.string.snapshots_body), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        when {
                            job is JobState.Working -> stringResource(R.string.linux_job_working, stringResource(job.action))
                            else -> stringResource(R.string.snapshots_storage, free?.let(::formatBytes) ?: "…", snapshots.stored?.let(::formatBytes) ?: "…")
                        },
                        Modifier.weight(1f),
                        style = Mono.copy(color = if (job is JobState.Working) Palette.accent else Palette.muted, fontSize = TextSize.Small),
                    )
                    if (!busy) {
                        BracketAction(stringResource(R.string.snapshots_take), Palette.accent, Modifier.takesPassedKeys().passesKeys()) {
                            snapshots.takeNow()
                        }
                    }
                }
                free?.takeIf { it < SNAPSHOT_LOW_SPACE }?.let {
                    BasicText(stringResource(R.string.snapshots_low_space), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                }
                snapshots.skipped?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
                JobLog(job)
            }
            Heading(R.string.snapshots_list)
            if (snapshots.list.isEmpty()) {
                BasicText(stringResource(R.string.snapshots_none), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in snapshots.list) {
                    key(s.id) {
                        SnapshotCard(runtime, s, snapshots.own[s.id], enabled = !busy)
                    }
                }
            }
            Heading(R.string.snapshots_options)
            val style = runtime.style
            Flag(R.string.snapshots_option_before_update, R.string.snapshots_option_on, R.string.snapshots_option_off, style, SnapshotStyle.BEFORE_UPDATE)
            val keeps = listOf(1, 2, 3, 5)
            val keep = style.style[SnapshotStyle.KEEP]
            StyleOption(R.string.snapshots_option_keep, keeps.map(Int::toString), keeps.indexOf(keep), keep.toString()) { i ->
                style.update { it.with(SnapshotStyle.KEEP, keeps[i]) }
            }
            OptionHint(stringResource(R.string.snapshots_option_keep_hint))
            Flag(R.string.snapshots_option_home, R.string.snapshots_option_home_in, R.string.snapshots_option_home_out, style, SnapshotStyle.HOME)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One snapshot: when and why it was taken and what it alone holds, with restore and delete, each asked first. */
@Composable
private fun SnapshotCard(
    runtime: Runtime,
    snapshot: SnapshotMeta,
    own: Long?,
    enabled: Boolean,
) {
    var asking by remember { mutableStateOf<String?>(null) }
    var withHome by remember { mutableStateOf(false) }
    // Keys go between an action and the cancel that takes its place.
    val action = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val date = remember(snapshot.createdAt) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(snapshot.createdAt)) }
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(date, style = Mono.copy(fontSize = TextSize.Item))
                val reason = stringResource(if (snapshot.reason == SnapshotReason.Update) R.string.snapshots_reason_update else R.string.snapshots_reason_manual)
                BasicText(
                    stringResource(R.string.snapshots_details, reason, own?.let(::formatBytes) ?: "…", formatBytes(snapshot.bytes)),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
            }
        }
        if (asking == null && enabled) {
            Row {
                BracketAction(stringResource(R.string.snapshots_restore), Palette.accent, Modifier.focusRequester(action).passesKeys(cancel)) { asking = RESTORE }
                BracketAction(stringResource(R.string.snapshots_delete), Palette.red, Modifier.passesKeys(cancel)) { asking = DELETE }
            }
        }
        when (asking) {
            RESTORE -> {
                BasicText(stringResource(R.string.snapshots_restore_ask), style = Mono.copy(color = Palette.red))
                BasicText(
                    stringResource(if (withHome) R.string.snapshots_restore_body_home else R.string.snapshots_restore_body),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
                if (snapshot.home) {
                    StyleOption(R.string.snapshots_restore_home, listOf(stringResource(R.string.snapshots_restore_home_keep), stringResource(R.string.snapshots_restore_home_back)), if (withHome) 1 else 0) {
                        withHome = it == 1
                    }
                }
                Row {
                    BracketAction(stringResource(R.string.snapshots_restore), Palette.red, Modifier.passesKeys()) {
                        asking = null
                        runtime.restoreSnapshot(snapshot.id, withHome)
                    }
                    BracketAction(stringResource(R.string.linux_cancel), Palette.muted, Modifier.focusRequester(cancel).passesKeys(action)) { asking = null }
                }
            }
            DELETE -> {
                BasicText(stringResource(R.string.snapshots_delete_ask), style = Mono.copy(color = Palette.red))
                Row {
                    BracketAction(stringResource(R.string.snapshots_delete), Palette.red, Modifier.passesKeys()) {
                        asking = null
                        runtime.snapshots.delete(snapshot.id)
                    }
                    BracketAction(stringResource(R.string.linux_cancel), Palette.muted, Modifier.focusRequester(cancel).passesKeys(action)) { asking = null }
                }
            }
        }
    }
}

private const val RESTORE = "restore"
private const val DELETE = "delete"
