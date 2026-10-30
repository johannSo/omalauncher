package dev.omalauncher.pp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.LocalNetwork
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.linux.AuthorizedKey
import dev.omalauncher.pp.linux.JobState
import dev.omalauncher.pp.linux.LocalServer
import dev.omalauncher.pp.linux.Ssh
import dev.omalauncher.pp.linux.SshSettings
import dev.omalauncher.pp.linux.guestServers
import dev.omalauncher.pp.linux.urlHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** settings › linux's row for [SshScreen]: whether sshd is on, and the command to log in with. */
@Composable
fun SshRow(
    runtime: Runtime,
    onOpen: () -> Unit,
) {
    val ssh = runtime.ssh
    LaunchedEffect(Unit) { ssh.load() }
    val command by produceState<String?>(null, ssh.listening, ssh.settings.port) {
        value = withContext(Dispatchers.IO) { ssh.commands().firstOrNull()?.second }
    }
    val status =
        when {
            !ssh.settings.enabled -> stringResource(R.string.ssh_row_off)
            ssh.listening && command != null -> stringResource(R.string.ssh_row_on, command!!)
            else -> stringResource(R.string.ssh_row_starting)
        }
    Column(Modifier.card()) {
        ActionsRow(actions = { BracketAction(stringResource(R.string.ssh_open), Palette.accent, onClick = onOpen) }) {
            Column {
                BasicText(stringResource(R.string.ssh_row_title), style = Mono.copy(fontSize = TextSize.Item))
                BasicText(status, style = Mono.copy(color = if (ssh.listening) Palette.green else Palette.muted, fontSize = TextSize.Small))
            }
        }
    }
}

/**
 * The SSH server into Linux (on, off, how to connect, keys, password login) and the servers Linux listens on,
 * to open in the browser or share on the LAN.
 */
@Composable
fun SshScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val ssh = runtime.ssh
    LaunchedEffect(Unit) { ssh.load() }
    OverlayPanel(stringResource(R.string.ssh_title), onDismiss, contentWidth = ReadingWidth) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerCard(ssh)
            if (ssh.settings.enabled) {
                LocalNetworkHint()
                ConnectCard(ssh)
                KeysCard(ssh)
                PasswordCard(ssh)
            }
            ServersCard(runtime)
        }
    }
}

@Composable
private fun ServerCard(ssh: Ssh) {
    val setup = ssh.setup
    val status =
        when {
            setup is JobState.Working -> stringResource(R.string.linux_job_working, stringResource(setup.action))
            ssh.failed -> stringResource(R.string.ssh_failed)
            !ssh.settings.enabled -> stringResource(R.string.ssh_off)
            ssh.listening -> stringResource(R.string.ssh_on, ssh.settings.port)
            else -> stringResource(R.string.ssh_starting)
        }
    Column(Modifier.card()) {
        ActionsRow(
            actions = {
                // Keys on one wait out its job, for the one it ends with.
                val passed = Modifier.takesPassedKeys().passesKeys()
                when {
                    setup is JobState.Working -> {}
                    ssh.settings.enabled -> {
                        if (ssh.failed) BracketAction(stringResource(R.string.ssh_retry), Palette.accent, passed, ssh::retry)
                        BracketAction(stringResource(R.string.ssh_turn_off), Palette.red, passed, ssh::turnOff)
                    }
                    else -> BracketAction(stringResource(R.string.ssh_turn_on), Palette.accent, passed, ssh::turnOn)
                }
            },
        ) {
            Column {
                BasicText(stringResource(R.string.ssh_server), style = Mono.copy(fontSize = TextSize.Item))
                BasicText(status, style = Mono.copy(color = if (ssh.listening) Palette.green else if (ssh.failed) Palette.red else Palette.muted, fontSize = TextSize.Small))
            }
        }
        if (!ssh.settings.enabled && setup !is JobState.Working) {
            Spacer(Modifier.height(6.dp))
            BasicText(stringResource(R.string.ssh_offer), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        JobLog(setup)
        if (ssh.failed) JobLog(JobState.Failed(ssh.log))
    }
}

/** Android 17 keeps apps off the local network until allowed; sshd and shared servers can't answer without it. */
@Composable
private fun LocalNetworkHint() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(LocalNetwork.missing(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { missing = LocalNetwork.missing(context) }
    if (!missing) return
    Column(Modifier.card()) {
        ActionsRow(actions = { BracketAction(stringResource(R.string.ssh_allow), Palette.accent) { ask.launch(LocalNetwork.PERMISSION) } }) {
            BasicText(stringResource(R.string.ssh_local_network), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
        }
    }
}

/** The ssh command for each of the phone's addresses, a QR code of the one picked, the host key and the port. */
@Composable
private fun ConnectCard(ssh: Ssh) {
    val context = LocalContext.current
    val port = ssh.settings.port
    val commands by produceState(emptyList<Pair<String, String>>(), port) {
        // Wi-Fi comes and goes while the screen is open.
        while (true) {
            value = withContext(Dispatchers.IO) { ssh.commands().map { (a, c) -> a.iface to c } }
            delay(5_000)
        }
    }
    var picked by remember { mutableStateOf(0) }
    var editingPort by remember { mutableStateOf(false) }
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(stringResource(R.string.ssh_connect), style = Mono.copy(fontSize = TextSize.Item))
        if (commands.isEmpty()) {
            BasicText(stringResource(R.string.ssh_no_network), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        commands.forEachIndexed { i, (iface, command) ->
            ActionsRow(
                Modifier.clickable { picked = i },
                actions = { BracketAction(stringResource(R.string.ssh_copy), Palette.accent) { copy(context, command) } },
            ) {
                Column {
                    BasicText(command, style = Mono.copy(color = if (i == picked) Palette.accent else Palette.foreground))
                    BasicText(iface, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
            }
        }
        commands.getOrNull(picked)?.let { (_, command) ->
            val code = remember(command) { qrCode(command) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCode(code, Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(1f).clip(LauncherStyle.shape(8.dp)))
            }
        }
        ssh.fingerprint?.let {
            BasicText(stringResource(R.string.ssh_fingerprint, it), style = Mono.copy(fontSize = TextSize.Small))
            BasicText(stringResource(R.string.ssh_fingerprint_hint), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        ActionsRow(actions = { BracketAction(stringResource(R.string.ssh_change), Palette.accent) { editingPort = true } }) {
            BasicText(stringResource(R.string.ssh_port, port), style = Mono.copy(color = Palette.muted))
        }
    }
    if (editingPort) {
        TextDialog(
            title = stringResource(R.string.ssh_port_title),
            initial = port.toString(),
            confirm = stringResource(R.string.ssh_set),
            hint = stringResource(R.string.ssh_port_hint, SshSettings.MIN_PORT),
            onDismiss = { editingPort = false },
        ) { text ->
            val new = text.toIntOrNull()
            // A port out of range keeps the dialog open; its hint says which.
            if (new != null && SshSettings.validPort(new)) {
                editingPort = false
                if (new != port) ssh.setPort(new)
            }
        }
    }
}

@Composable
private fun KeysCard(ssh: Ssh) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    fun add(text: String) =
        scope.launch {
            val (result, count) = ssh.addKeys(text)
            message =
                when (result) {
                    Ssh.Added.Some -> context.resources.getQuantityString(R.plurals.ssh_keys_added, count, count)
                    Ssh.Added.AlreadyThere -> context.getString(R.string.ssh_key_there)
                    Ssh.Added.PrivateKey -> context.getString(R.string.ssh_key_private)
                    Ssh.Added.NoKey -> context.getString(R.string.ssh_key_none)
                }
        }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val text = withContext(Dispatchers.IO) { readSmallText(context, uri) }
                    if (text != null) add(text) else message = context.getString(R.string.ssh_key_none)
                }
            }
        }
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(stringResource(R.string.ssh_keys), style = Mono.copy(fontSize = TextSize.Item))
        if (ssh.keys.isEmpty()) BasicText(stringResource(R.string.ssh_keys_none), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        for (key in ssh.keys) key(key.blob) {
            // Removal takes a second tap: a key removed by mistake may be the only way in.
            val confirm = remember { FocusRequester() }
            ActionsRow(
                actions = {
                    if (removing == key.blob) {
                        BracketAction(stringResource(R.string.ssh_remove_confirm), Palette.red, Modifier.focusRequester(confirm).passesKeys()) {
                            removing = null
                            scope.launch { ssh.removeKey(key) }
                        }
                    } else {
                        BracketAction(stringResource(R.string.ssh_remove), Palette.red, Modifier.takesPassedKeys().passesKeys(confirm)) { removing = key.blob }
                    }
                },
            ) {
                Column {
                    BasicText(key.comment.ifEmpty { stringResource(R.string.ssh_key_no_comment) }, style = Mono.copy(fontSize = TextSize.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText("${key.type} ${key.fingerprint}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row {
            BracketAction(stringResource(R.string.ssh_paste_key), Palette.accent) { pasting = true }
            BracketAction(stringResource(R.string.ssh_key_file), Palette.accent) { pick.launch(arrayOf("*/*")) }
        }
        BasicText(stringResource(R.string.ssh_keys_share_hint), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        message?.let { BasicText(it, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small)) }
    }
    if (pasting) {
        KeyDialog(
            onDismiss = { pasting = false },
            onAdd = {
                pasting = false
                add(it)
            },
        )
    }
}

@Composable
private fun PasswordCard(ssh: Ssh) {
    val scope = rememberCoroutineScope()
    var setting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val on = ssh.settings.password
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionsRow(
            actions = {
                if (on) {
                    BracketAction(stringResource(R.string.ssh_change), Palette.accent) { setting = true }
                    BracketAction(stringResource(R.string.ssh_turn_off), Palette.red, onClick = ssh::turnOffPassword)
                } else {
                    BracketAction(stringResource(R.string.ssh_password_allow), Palette.accent) { setting = true }
                }
            },
        ) {
            Column {
                BasicText(stringResource(R.string.ssh_password), style = Mono.copy(fontSize = TextSize.Item))
                BasicText(stringResource(if (on) R.string.ssh_password_on else R.string.ssh_password_off), style = Mono.copy(color = if (on) Palette.red else Palette.muted, fontSize = TextSize.Small))
            }
        }
        BasicText(stringResource(R.string.ssh_password_warning), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        if (failed) BasicText(stringResource(R.string.ssh_password_failed), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
    }
    if (setting) {
        PasswordDialog(
            onDismiss = { setting = false },
            onSet = { password ->
                setting = false
                scope.launch { failed = !ssh.setPassword(password) }
            },
        )
    }
}

/** The ports Linux listens on, polled while the screen is open, with what each can do. */
@Composable
private fun ServersCard(runtime: Runtime) {
    val context = LocalContext.current
    val ssh = runtime.ssh
    // Null until the first look; then null in the pair when Android hides them.
    val servers by produceState<Pair<Boolean, List<LocalServer>?>>(false to null) {
        while (true) {
            value = true to withContext(Dispatchers.IO) { runCatching { guestServers(runtime.proot.loader) }.getOrNull() }
            delay(3_000)
        }
    }
    var portField by remember { mutableStateOf("") }
    var shareFailed by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(stringResource(R.string.ssh_servers), style = Mono.copy(fontSize = TextSize.Item))
        BasicText(stringResource(R.string.ssh_servers_hint), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        val (looked, list) = servers
        // sshd is on the card above.
        val shown = list?.filterNot { it.process?.label == "sshd" }
        when {
            !looked -> {}
            shown == null -> BasicText(stringResource(R.string.ssh_servers_hidden), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            shown.isEmpty() -> BasicText(stringResource(R.string.ssh_servers_none), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        for (server in shown.orEmpty()) key(server.port) {
            val shared = ssh.shares.shared[server.port]
            val lanUrls = if (server.onLan) remember(server.port) { LocalNetwork.lanAddresses().map { "http://${urlHost(it.address)}:${server.port}" } } else shared.orEmpty()
            Column {
                ActionsRow(
                    actions = {
                        BracketAction(stringResource(R.string.ssh_open), Palette.accent) { openUrl(context, "http://localhost:${server.port}") }
                        when {
                            shared != null -> BracketAction(stringResource(R.string.ssh_stop_sharing), Palette.red) { ssh.shares.stop(server.port) }
                            server.loopbackOnly ->
                                BracketAction(stringResource(R.string.ssh_share_lan), Palette.accent) {
                                    scope.launch {
                                        val urls = withContext(Dispatchers.IO) { ssh.shares.share(server.port, server.loopback, LocalNetwork.lanAddresses().map { it.address }) }
                                        shareFailed = if (urls.isEmpty()) server.port else null
                                    }
                                }
                        }
                    },
                ) {
                    Column {
                        BasicText(":${server.port}  ${server.process?.label.orEmpty()}", style = Mono.copy(fontSize = TextSize.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val reach =
                            when {
                                server.onLan -> stringResource(R.string.ssh_server_lan)
                                shared != null -> stringResource(R.string.ssh_shared_at, shared.joinToString(", "))
                                server.loopbackOnly -> stringResource(R.string.ssh_server_local)
                                else -> stringResource(R.string.ssh_server_bound, server.addresses.joinToString(", ") { it.hostAddress.orEmpty() })
                            }
                        BasicText(reach, style = Mono.copy(color = if (server.onLan || shared != null) Palette.green else Palette.muted, fontSize = TextSize.Small))
                    }
                }
                for (url in lanUrls.take(2)) {
                    ActionsRow(
                        actions = {
                            BracketAction(stringResource(R.string.ssh_copy), Palette.accent) { copy(context, url) }
                            BracketAction(stringResource(R.string.ssh_share_url), Palette.accent) { shareText(context, url) }
                        },
                    ) { BasicText(url, style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Small)) }
                }
                if (shareFailed == server.port) BasicText(stringResource(R.string.ssh_share_failed), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            }
        }
        if (looked && list == null) {
            // Open one by its number when the list can't be had.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Field(portField, { new -> portField = new.filter(Char::isDigit).take(5) }, stringResource(R.string.ssh_port_field), KeyboardType.Number)
                }
                BracketAction(stringResource(R.string.ssh_open), Palette.accent) {
                    portField.toIntOrNull()?.takeIf { it in 1..65535 }?.let { openUrl(context, "http://localhost:$it") }
                }
            }
        }
    }
}

/** A multi-line field for public keys: pasted from a computer, several at once. */
@Composable
private fun KeyDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DialogFrame(onDismiss) {
        BasicText(stringResource(R.string.ssh_key_title), style = Mono.copy(fontSize = TextSize.Title))
        BasicText(stringResource(R.string.ssh_key_hint), style = Mono.copy(color = Palette.muted))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = Mono.copy(fontSize = TextSize.Small, color = Palette.foreground),
            cursorBrush = SolidColor(Palette.accent),
            minLines = 4,
            maxLines = 8,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(LauncherStyle.shape(12.dp))
                    .background(Palette.background)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction(stringResource(R.string.ssh_cancel), Palette.muted, onClick = onDismiss)
            BracketAction(stringResource(R.string.ssh_add), Palette.accent) { if (text.isNotBlank()) onAdd(text) }
        }
    }
}

/** Root's new password, hidden unless shown; at least [MIN_PASSWORD] characters. */
@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onSet: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ok = text.length >= MIN_PASSWORD
    DialogFrame(onDismiss) {
        BasicText(stringResource(R.string.ssh_password_title), style = Mono.copy(fontSize = TextSize.Title))
        BasicText(stringResource(R.string.ssh_password_warning), style = Mono.copy(color = Palette.red))
        BasicText(stringResource(R.string.ssh_password_hint, MIN_PASSWORD), style = Mono.copy(color = Palette.muted))
        BasicTextField(
            value = text,
            onValueChange = { new -> text = new.filterNot { it == '\n' || it == '\r' } },
            singleLine = true,
            textStyle = Mono.copy(fontSize = TextSize.Large, color = Palette.foreground),
            cursorBrush = SolidColor(Palette.accent),
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ok) onSet(text) }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(LauncherStyle.shape(12.dp))
                    .background(Palette.background)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction(stringResource(if (shown) R.string.ssh_hide else R.string.ssh_show), Palette.muted) { shown = !shown }
            BracketAction(stringResource(R.string.ssh_cancel), Palette.muted, onClick = onDismiss)
            BracketAction(stringResource(R.string.ssh_set), if (ok) Palette.accent else Palette.muted) { if (ok) onSet(text) }
        }
    }
}

/** A small one-line field on the card. */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    type: KeyboardType,
) {
    val style = Mono.copy(color = Palette.foreground)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth().clip(LauncherStyle.shape(8.dp)).background(Palette.background).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = Palette.muted))
                inner()
            }
        },
    )
}

private const val MIN_PASSWORD = 8

private fun copy(
    context: Context,
    text: String,
) = context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh", text))

private fun openUrl(
    context: Context,
    url: String,
) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private fun shareText(
    context: Context,
    text: String,
) = runCatching {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A picked or shared file's text, if it's small enough to be keys (a .pub is under 1 KB). */
internal fun readSmallText(
    context: Context,
    uri: Uri,
): String? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() <= MAX_KEY_FILE) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (out.size() > MAX_KEY_FILE) null else out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

private const val MAX_KEY_FILE = 64 * 1024

/** Asks before keys shared from another app go into authorized_keys ([dev.omalauncher.pp.SshKeyShareActivity]). */
@Composable
internal fun SshKeyShareDialog(
    keys: List<AuthorizedKey>,
    privateKey: Boolean,
    /** Linux is installed: there is a home for the keys. */
    linux: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogFrame(onDismiss) {
        BasicText(stringResource(R.string.ssh_share_title), style = Mono.copy(fontSize = TextSize.Title))
        val problem =
            when {
                !linux -> R.string.ssh_share_no_linux
                privateKey -> R.string.ssh_key_private
                keys.isEmpty() -> R.string.ssh_key_none
                else -> null
            }
        if (problem != null) {
            BasicText(stringResource(problem), style = Mono.copy(color = Palette.red))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BracketAction(stringResource(R.string.ssh_cancel), Palette.muted, onClick = onDismiss)
            }
            return@DialogFrame
        }
        for (key in keys) {
            Column {
                BasicText(key.comment.ifEmpty { stringResource(R.string.ssh_key_no_comment) }, style = Mono.copy(fontSize = TextSize.Large), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("${key.type} ${key.fingerprint}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        BasicText(stringResource(R.string.ssh_share_body), style = Mono.copy(color = Palette.muted))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction(stringResource(R.string.ssh_cancel), Palette.muted, onClick = onDismiss)
            BracketAction(stringResource(R.string.ssh_add), Palette.accent, onClick = onAdd)
        }
    }
}
