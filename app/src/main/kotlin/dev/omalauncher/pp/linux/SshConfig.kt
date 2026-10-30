package dev.omalauncher.pp.linux

import dev.omalauncher.pp.theme.Toml
import java.security.MessageDigest
import java.util.Base64

/**
 * The SSH server's settings, in the guest's ~/.config/omalauncher/ssh.toml so an agent can change them too
 * (settings › linux › ssh writes the same file). Read whenever sshd starts.
 */
data class SshSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    /** Password login on top of keys; root's password is the one set in settings. */
    val password: Boolean = false,
) {
    fun toToml(): String =
        Toml.write(
            linkedMapOf("enabled" to enabled, "port" to port, "password" to password),
            header =
                "# The SSH server into this Linux: settings › linux › ssh. sshd reads it when it starts;\n" +
                    "# turn it off and on there (or shut Linux down and start it) after editing.\n" +
                    "# port: $MIN_PORT-65535, Android lets apps listen on unprivileged ports only.\n" +
                    "# password: true lets root log in with the password set in settings, not only with a key.\n\n",
        )

    companion object {
        const val DEFAULT_PORT = 8022

        /** Ports below 1024 need a privilege Android never gives apps. */
        const val MIN_PORT = 1024

        /** [text] read leniently: what's missing or wrong keeps its default, so a typo never locks anyone out. */
        fun parse(text: String): SshSettings {
            val values = runCatching { Toml.parse(text) }.getOrNull() ?: return SshSettings()
            val port = (values["port"] as? Double)?.toInt()?.takeIf { validPort(it) } ?: DEFAULT_PORT
            return SshSettings(values["enabled"] as? Boolean ?: false, port, values["password"] as? Boolean ?: false)
        }

        fun validPort(port: Int) = port in MIN_PORT..65535
    }
}

/** The guest path of sshd's configuration: its own file, so Arch's /etc/ssh/sshd_config stays the package's. */
const val SSHD_CONFIG = "/etc/ssh/sshd_config.omalauncher"

/** Where the user's keys go; sshd's default AuthorizedKeysFile for root. */
const val AUTHORIZED_KEYS = "${Proot.GUEST_HOME}/.ssh/authorized_keys"

/** The host key whose fingerprint settings shows; every client offers ed25519 first. */
const val HOST_KEY = "/etc/ssh/ssh_host_ed25519_key"

/**
 * sshd's configuration for [settings], made for proot:
 * - Privilege separation can't be turned off since OpenSSH 7.5. Its chroot and setuid work under proot's fake
 *   root (`-0`), which emulates chroot into a directory of the guest.
 * - No PAM: Arch's stack runs pam_systemd and pam_loginuid, which need a real init and /proc/self/loginuid.
 *   Without it sshd checks keys and root's password in /etc/shadow itself.
 * - No StrictModes: the files belong to the app's own Android user, and proot reports any owner as root, so
 *   the check says nothing; a mode that's off would only lock the user out.
 * - Root only: Arch Linux ARM ships an `alarm` user with the password `alarm`.
 * - [environment] (the launcher's guest environment) goes to every session on one SetEnv line (sshd uses
 *   only the first): PATH with mise's tools, TZ, and WAYLAND_DISPLAY, so a GUI app started over SSH opens
 *   on the phone. Values sshd can't quote are left out.
 */
fun sshdConfig(
    settings: SshSettings,
    environment: List<String>,
): String {
    val env =
        environment
            .filter { '=' in it && '"' !in it && '\\' !in it && it.substringBefore('=') !in SSHD_SETS }
            .joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
    return buildString {
        appendLine("# Written by OmaLauncher++ from ~/.config/omalauncher/ssh.toml each time sshd starts; edits here are lost.")
        appendLine("Port ${settings.port}")
        for (type in listOf("ed25519", "ecdsa", "rsa")) appendLine("HostKey /etc/ssh/ssh_host_${type}_key")
        appendLine("PidFile none")
        appendLine("UsePAM no")
        appendLine("AllowUsers root")
        appendLine("PermitRootLogin ${if (settings.password) "yes" else "prohibit-password"}")
        appendLine("PubkeyAuthentication yes")
        appendLine("AuthorizedKeysFile .ssh/authorized_keys")
        appendLine("PasswordAuthentication ${if (settings.password) "yes" else "no"}")
        appendLine("KbdInteractiveAuthentication no")
        appendLine("PermitEmptyPasswords no")
        appendLine("StrictModes no")
        appendLine("UseDNS no")
        appendLine("PrintMotd no")
        appendLine("PrintLastLog no")
        appendLine("LoginGraceTime 30")
        appendLine("MaxAuthTries 4")
        appendLine("X11Forwarding no")
        appendLine("AllowTcpForwarding yes")
        appendLine("Subsystem sftp /usr/lib/ssh/sftp-server")
        if (env.isNotEmpty()) appendLine("SetEnv $env")
    }
}

/** Variables sshd sets for each session itself, from the user and the client. */
private val SSHD_SETS = setOf("HOME", "USER", "LOGNAME", "SHELL", "TERM", "MAIL", "SSH_AUTH_SOCK")

/** The command that logs in from another computer. */
fun sshCommand(
    port: Int,
    host: String,
) = if (port == 22) "ssh root@$host" else "ssh -p $port root@$host"

/** A public key on a line of authorized_keys or a .pub file: `[options] type base64 [comment]`. */
data class AuthorizedKey(
    val type: String,
    /** The key itself, base64 as written; what tells two keys apart. */
    val blob: String,
    val comment: String,
) {
    /** As `ssh-keygen -l` prints it: SHA256 of the key, base64 without padding. */
    val fingerprint get() = sshFingerprint(blob)
}

/** `SHA256:…` of a key given as the base64 of its wire form, as ssh prints fingerprints. */
fun sshFingerprint(blob: String): String {
    val bytes = runCatching { Base64.getDecoder().decode(blob) }.getOrDefault(ByteArray(0))
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/**
 * Reads and edits ~/.ssh/authorized_keys as text. Lines that aren't keys (comments, blank lines, a key type
 * we don't know) are kept as they are; options before a key (`from="…"`, `command="…"`) stay with it.
 */
object AuthorizedKeys {
    /** What OpenSSH accepts, security keys (sk-) included. DSA is gone from OpenSSH 10. */
    private val TYPES =
        listOf(
            "ssh-ed25519",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com",
            "sk-ecdsa-sha2-nistp256@openssh.com",
        )

    private val KEY = Regex("""(?:^|\s)(${TYPES.joinToString("|") { Regex.escape(it) }})\s+([A-Za-z0-9+/]+={0,3})(?:\s+(.*))?$""")

    /** The key on [line], or null for a comment, a blank line or anything else. */
    fun parseLine(line: String): AuthorizedKey? {
        val text = line.trim()
        if (text.isEmpty() || text.startsWith('#')) return null
        val m = KEY.find(text) ?: return null
        val (type, blob, comment) = m.destructured
        // The blob starts with its own type: a line cut short or a mangled paste doesn't count as a key.
        if (blobType(blob) != type) return null
        return AuthorizedKey(type, blob, comment.trim())
    }

    /** Every key in [text], in order, each once. */
    fun parse(text: String): List<AuthorizedKey> = text.lines().mapNotNull(::parseLine).distinctBy { it.blob }

    /** Whether [text] holds a private key: someone pasted the wrong half of the pair. */
    fun looksPrivate(text: String) = "PRIVATE KEY-----" in text

    /** [file] with [keys] appended that it doesn't hold yet, and how many those were. */
    fun add(
        file: String,
        keys: List<AuthorizedKey>,
    ): Pair<String, Int> {
        val have = parse(file).mapTo(HashSet()) { it.blob }
        val new = keys.filter { have.add(it.blob) }
        if (new.isEmpty()) return file to 0
        val base = if (file.isEmpty() || file.endsWith('\n')) file else file + "\n"
        return base + new.joinToString("") { k -> listOf(k.type, k.blob, k.comment).filter { it.isNotEmpty() }.joinToString(" ") + "\n" } to new.size
    }

    /** [file] without the lines that hold the key [blob]; everything else as it was. */
    fun remove(
        file: String,
        blob: String,
    ): String {
        val lines = file.split('\n')
        return lines.filterNot { parseLine(it)?.blob == blob }.joinToString("\n")
    }

    /**
     * The type named at the start of a key's wire form, a run of fields that are each a uint32 length and that
     * many bytes, the type first. Null unless the fields fill the blob exactly: a key cut short isn't one.
     */
    private fun blobType(blob: String): String? {
        val bytes = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return null
        val fields = ArrayList<IntRange>()
        var at = 0
        while (at < bytes.size) {
            if (bytes.size - at < 4) return null
            val len = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
            if (len < 0 || len > bytes.size - at - 4) return null
            fields += at + 4 until at + 4 + len
            at += 4 + len
        }
        if (fields.size < 2 || fields[0].isEmpty()) return null
        return String(bytes, fields[0].first, fields[0].count(), Charsets.US_ASCII)
    }
}
