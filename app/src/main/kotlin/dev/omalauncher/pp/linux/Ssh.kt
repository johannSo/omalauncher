package dev.omalauncher.pp.linux

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.pp.LanAddress
import dev.omalauncher.pp.LocalNetwork
import dev.omalauncher.pp.R
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The SSH server into the guest (settings › linux › ssh): OpenSSH's sshd under proot, on an unprivileged port.
 * It runs while Linux does: started with Linux when it's on, ended with it, and started again when it exits on
 * its own. Login is root's, with a key from ~/.ssh/authorized_keys, or with a password when the user set one.
 * [shares] puts the guest's localhost servers on the LAN.
 */
class Ssh(
    private val context: Context,
    private val proot: Proot,
    private val pacman: Pacman,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "ssh job failed", e) })

    private val settingsFile get() = File(proot.launcherConfig, "ssh.toml")
    private val keysFile get() = proot.hostFile(AUTHORIZED_KEYS)

    var settings by mutableStateOf(SshSettings())
        private set

    /** Setting sshd up: installing openssh, making host keys. */
    var setup by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** sshd said it's listening. */
    var listening by mutableStateOf(false)
        private set

    /** sshd exited at once three times in a row and isn't started again until asked; [log] says why. */
    var failed by mutableStateOf(false)
        private set

    /** sshd's last lines: its listening line, logins, errors. */
    var log by mutableStateOf<List<String>>(emptyList())
        private set

    /** The host key's fingerprint, for the user to compare with what ssh asks on the first login. */
    var fingerprint by mutableStateOf<String?>(null)
        private set

    var keys by mutableStateOf<List<AuthorizedKey>>(emptyList())
        private set

    private val _notice = MutableStateFlow<String?>(null)

    /** The command to log in with while sshd listens, for the Linux notification; null otherwise. */
    val notice: StateFlow<String?> = _notice

    val shares = LanShares()

    /** Linux is up; sshd only runs then. */
    private var linux = false
    private var supervisor: Job? = null

    @Volatile private var process: Process? = null

    /** Linux is up: reads the settings and starts sshd if it's on. */
    fun linuxStarted() {
        linux = true
        scope.launch {
            load()
            shareTarget(settings.enabled)
            if (settings.enabled && prepare()) serve()
        }
    }

    /**
     * Linux is shutting down: sshd and the LAN shares end, the setting stays. [removed]: Linux is deleted, and
     * with it the settings and keys; sharing a key to the launcher isn't offered any more.
     */
    fun linuxStopping(removed: Boolean = false) {
        linux = false
        stop()
        shares.stopAll()
        if (removed) {
            settings = SshSettings()
            keys = emptyList()
            fingerprint = null
            shareTarget(false)
        }
    }

    /** Reads the settings, the keys and the host key's fingerprint from the guest. */
    suspend fun load() =
        withContext(Dispatchers.IO) {
            settings = runCatching { SshSettings.parse(settingsFile.readText()) }.getOrDefault(SshSettings())
            keys = runCatching { AuthorizedKeys.parse(keysFile.readText()) }.getOrDefault(emptyList())
            fingerprint = runCatching { AuthorizedKeys.parseLine(proot.hostFile("$HOST_KEY.pub").readText())?.fingerprint }.getOrNull()
        }

    /** Installs and sets up sshd if need be, then starts it, and with every Linux start from now on. */
    fun turnOn() {
        if (!linux || setup is JobState.Working) return
        scope.launch {
            failed = false
            save(settings.copy(enabled = true))
            shareTarget(true)
            if (prepare()) serve() else save(settings.copy(enabled = false))
        }
    }

    /** Stops sshd, which ends open sessions, and keeps it off. */
    fun turnOff() {
        scope.launch {
            save(settings.copy(enabled = false))
            shareTarget(false)
            stop()
            failed = false
        }
    }

    /** sshd again after it [failed]. */
    fun retry() = turnOn()

    /** Listens on [port] from now on; sshd starts again on it, which ends open sessions. */
    fun setPort(port: Int) {
        if (!SshSettings.validPort(port)) return
        scope.launch {
            save(settings.copy(port = port))
            restart()
        }
    }

    /**
     * Sets root's password to [password] and lets it log in with it. The password goes to openssl on stdin,
     * so it's never in a command line, and only its SHA-512 crypt hash is stored, in /etc/shadow.
     */
    suspend fun setPassword(password: String): Boolean {
        if (password.isEmpty() || '\n' in password) return false
        val ok =
            withContext(Dispatchers.IO) {
                runCatching {
                    val p = proot.process("h=\$(openssl passwd -6 -stdin) && [ -n \"\$h\" ] && usermod -p \"\$h\" root").start()
                    p.outputStream.bufferedWriter().use { it.write(password + "\n") }
                    val out = p.inputStream.bufferedReader().readText()
                    (p.waitFor() == 0).also { if (!it) Log.w(TAG, "setting the password failed: $out") }
                }.getOrDefault(false)
            }
        if (ok) {
            save(settings.copy(password = true))
            restart()
        }
        return ok
    }

    /**
     * Keys only again. Root's password becomes `*`, which matches no password; not `!`, which sshd without
     * PAM takes for a locked account and refuses keys for too.
     */
    fun turnOffPassword() {
        scope.launch {
            save(settings.copy(password = false))
            withContext(Dispatchers.IO) { runCatching { proot.run("usermod -p '*' root") } }
            restart()
        }
    }

    /** What [addKeys] made of what it was given. */
    enum class Added { Some, AlreadyThere, PrivateKey, NoKey }

    /** Adds the public keys in [text] (one per line, as in a .pub file or GitHub's `<user>.keys`); with the count. */
    suspend fun addKeys(text: String): Pair<Added, Int> =
        withContext(Dispatchers.IO) {
            if (AuthorizedKeys.looksPrivate(text)) return@withContext Added.PrivateKey to 0
            val new = AuthorizedKeys.parse(text)
            if (new.isEmpty()) return@withContext Added.NoKey to 0
            val file = keysFile
            val (written, count) = AuthorizedKeys.add(runCatching { file.readText() }.getOrDefault(""), new)
            if (count > 0) {
                file.parentFile?.mkdirs()
                file.writeText(written)
            }
            keys = AuthorizedKeys.parse(written)
            (if (count > 0) Added.Some else Added.AlreadyThere) to count
        }

    suspend fun removeKey(key: AuthorizedKey) =
        withContext(Dispatchers.IO) {
            val file = keysFile
            val text = runCatching { file.readText() }.getOrNull() ?: return@withContext
            val written = AuthorizedKeys.remove(text, key.blob)
            file.writeText(written)
            keys = AuthorizedKeys.parse(written)
        }

    /** The command to log in at each of the phone's LAN addresses. */
    fun commands(): List<Pair<LanAddress, String>> = LocalNetwork.lanAddresses().map { it to sshCommand(settings.port, it.host) }

    private suspend fun save(new: SshSettings) {
        settings = new
        withContext(Dispatchers.IO) {
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(new.toToml())
        }
    }

    /**
     * Makes sure sshd can start: openssh installed (a full sync, like any install), host keys made, root's
     * ~/.ssh there, root's password not `!`. Skipped when all of it is in place, so a Linux start doesn't
     * pay for a proot run. False if it failed; [setup] has the log.
     */
    private suspend fun prepare(): Boolean {
        val ready =
            withContext(Dispatchers.IO) {
                proot.hostFile("/usr/bin/sshd").isFile && proot.hostFile(HOST_KEY).isFile && keysFile.isFile
            }
        if (ready) return true
        val script =
            listOf(
                "set -e",
                "[ -x /usr/bin/sshd ] || { ${pacmanInstall(listOf("openssh"))}; }",
                "ssh-keygen -A",
                // Privilege separation's chroot; the filesystem package has it, but nothing stops its removal.
                "mkdir -p /var/empty",
                "mkdir -p /root/.ssh && chmod 700 /root/.ssh && touch $AUTHORIZED_KEYS && chmod 600 $AUTHORIZED_KEYS",
                "case \"\$(awk -F: '\$1 == \"root\" { print \$2 }' /etc/shadow)\" in '!'*) usermod -p '*' root ;; esac",
            ).joinToString("\n")
        val (ok, _) = proot.trackedJob(R.string.ssh_job_setup, script, pacman.lock) { setup = it }
        pacman.revision.intValue++
        load()
        return ok
    }

    /** Runs sshd until Linux stops or it's turned off, starting it again when it exits; see [failed]. */
    private fun serve() {
        if (!linux || supervisor?.isActive == true) return
        supervisor =
            scope.launch(Dispatchers.IO) {
                var quickExits = 0
                while (isActive && linux && settings.enabled) {
                    val config = proot.hostFile(SSHD_CONFIG)
                    config.parentFile?.mkdirs()
                    config.writeText(sshdConfig(settings, proot.guestEnvironment()))
                    val tail = OutputTail(50)
                    val started = SystemClock.elapsedRealtime()
                    // -D: stays in front, so it's ours to watch and end. -e: logs to stderr, which is merged in.
                    val p =
                        runCatching { proot.process("exec /usr/bin/sshd -D -e -f $SSHD_CONFIG").start() }.getOrElse {
                            log = listOf(it.message ?: it.toString())
                            failed = true
                            break
                        }
                    process = p
                    runCatching {
                        p.inputStream.bufferedReader().use { reader ->
                            forEachOutputLine(reader) { line ->
                                if (tail.add(line)) log = tail.toList()
                                if (!listening && "Server listening on" in line) {
                                    listening = true
                                    _notice.value = commands().firstOrNull()?.second ?: sshCommand(settings.port, "…")
                                }
                            }
                        }
                    }
                    p.waitFor()
                    process = null
                    listening = false
                    _notice.value = null
                    if (!isActive || !linux || !settings.enabled) break
                    Log.w(TAG, "sshd exited: ${tail.toList().takeLast(3)}")
                    quickExits = if (SystemClock.elapsedRealtime() - started < 10_000) quickExits + 1 else 0
                    if (quickExits >= 3) {
                        failed = true
                        break
                    }
                    delay(2_000L * quickExits)
                }
            }
    }

    private fun stop() {
        supervisor?.cancel()
        supervisor = null
        process?.let(::killTree)
        process = null
        listening = false
        _notice.value = null
    }

    /** sshd with the new settings: stopped, and started again if it's on. */
    private suspend fun restart() {
        val old = supervisor
        stop()
        old?.join()
        if (settings.enabled && linux) serve()
    }

    /**
     * The "add as SSH key" share target, only in the share sheet while the server is on: otherwise every
     * text shared on the phone would offer it.
     */
    private fun shareTarget(on: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, "dev.omalauncher.pp.SshKeyShareActivity"),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private companion object {
        const val TAG = "ssh"
    }
}
