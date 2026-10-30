package dev.omalauncher.pp.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigTest {
    private val ed25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGqb113F9NTWai6/qdAqjrD4HLBgUXNjPSwagKTkO9aX you@laptop"
    private val rsa =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQD8BcFJJL1oYYvz+7b8WNZYdKJg/KWCFDMNxncJ/mUuIjbPiwt0IeVGwBgRue3Kshbn3mjMcCASzJ49Sxz5VXeI3mNOVgOZQNnK+aNpOzv2KpKNQdJS6gf/TIXjrvkT/2P1aPE1/XCMx0FxyeQ8IWPkZlW4hv2NKYHB0i25+uD+15MRNv8lPZyNn+XO/Y0W+UB2RA3YA+BMQvYhA7w62aWQ4B3bzqgcGsf9iv0a6Dbwx4GSojynmps3ucCqfq73qAjXtVKn3fak0NeamImP3aTdL1qAzcNV+Y3BBiCdArKjjScYyC9XQeIjDzYVrtWv6jtHGpwlx0cz7ShyqKVB3OYj rsa@box"

    @Test fun settingsRoundTrip() {
        val s = SshSettings(enabled = true, port = 2222, password = true)
        assertEquals(s, SshSettings.parse(s.toToml()))
        assertEquals(SshSettings(), SshSettings.parse(""))
    }

    @Test fun badSettingsKeepDefaults() {
        assertEquals(SshSettings(enabled = true), SshSettings.parse("enabled = true\nport = 22\n"))
        assertEquals(SshSettings(enabled = true), SshSettings.parse("enabled = true\nport = 70000\n"))
        assertEquals(SshSettings(), SshSettings.parse("this isn't toml ["))
    }

    @Test fun keyOnlyConfig() {
        val config = sshdConfig(SshSettings(enabled = true), listOf("HOME=/root", "PATH=/usr/bin:/root/.local/bin", "TZ=Europe/Berlin", "TERM=xterm"))
        val lines = config.lines()
        assertTrue("Port 8022" in lines)
        assertTrue("PasswordAuthentication no" in lines)
        assertTrue("PermitRootLogin prohibit-password" in lines)
        assertTrue("UsePAM no" in lines)
        assertTrue("AllowUsers root" in lines)
        assertTrue("StrictModes no" in lines)
        // One SetEnv line (sshd only reads the first), without what sshd sets itself.
        assertEquals(listOf("SetEnv PATH=/usr/bin:/root/.local/bin TZ=Europe/Berlin"), lines.filter { it.startsWith("SetEnv") })
    }

    @Test fun passwordConfig() {
        val lines = sshdConfig(SshSettings(enabled = true, port = 2200, password = true), emptyList()).lines()
        assertTrue("Port 2200" in lines)
        assertTrue("PasswordAuthentication yes" in lines)
        assertTrue("PermitRootLogin yes" in lines)
        assertTrue("PermitEmptyPasswords no" in lines)
        assertTrue(lines.none { it.startsWith("SetEnv") })
    }

    @Test fun envQuoting() {
        val config = sshdConfig(SshSettings(), listOf("A=b c", "Q=say \"hi\"", "BACK=a\\b", "NOEQUALS"))
        assertEquals("SetEnv \"A=b c\"", config.lines().single { it.startsWith("SetEnv") })
    }

    @Test fun command() {
        assertEquals("ssh -p 8022 root@192.168.1.5", sshCommand(8022, "192.168.1.5"))
        assertEquals("ssh root@10.0.0.2", sshCommand(22, "10.0.0.2"))
    }

    @Test fun fingerprintsMatchSshKeygen() {
        assertEquals("SHA256:12eMh2UvuL969SkzZj0RyhQDdB2DWBVa/fqYM1NIe/4", AuthorizedKeys.parseLine(ed25519)!!.fingerprint)
        assertEquals("SHA256:e1WQuZwk3/cDBqr7R4qRcmeQPWhNEAcppGXLYSJFwWc", AuthorizedKeys.parseLine(rsa)!!.fingerprint)
    }

    @Test fun parseLines() {
        val key = AuthorizedKeys.parseLine(ed25519)!!
        assertEquals("ssh-ed25519", key.type)
        assertEquals("you@laptop", key.comment)
        // Options before the key, no comment, surrounding blanks.
        val withOptions = AuthorizedKeys.parseLine("""  from="10.0.0.*",no-pty ${ed25519.substringBeforeLast(' ')}  """)!!
        assertEquals(key.blob, withOptions.blob)
        assertEquals("", withOptions.comment)
        assertNull(AuthorizedKeys.parseLine("# a comment"))
        assertNull(AuthorizedKeys.parseLine(""))
        // Cut short, or a blob of another type than it says.
        assertNull(AuthorizedKeys.parseLine(ed25519.substring(0, 40)))
        assertNull(AuthorizedKeys.parseLine("ssh-rsa " + key.blob))
        assertNull(AuthorizedKeys.parseLine("ssh-ed25519 not-base64!"))
    }

    @Test fun addKeepsWhatsThere() {
        val file = "# mine\n$ed25519\ngarbage line\n"
        val (out, count) = AuthorizedKeys.add(file, AuthorizedKeys.parse("$ed25519\n\n$rsa\n$rsa"))
        assertEquals(1, count)
        assertEquals("$file$rsa\n", out)
        assertEquals(0, AuthorizedKeys.add(out, AuthorizedKeys.parse(rsa)).second)
        // A file without a final newline gets one before the new key.
        assertEquals("$ed25519\n$rsa\n", AuthorizedKeys.add(ed25519, AuthorizedKeys.parse(rsa)).first)
        assertEquals("$rsa\n", AuthorizedKeys.add("", AuthorizedKeys.parse(rsa)).first)
    }

    @Test fun remove() {
        val file = "# mine\n$ed25519\nno-pty $rsa\n"
        val blob = AuthorizedKeys.parseLine(rsa)!!.blob
        assertEquals("# mine\n$ed25519\n", AuthorizedKeys.remove(file, blob))
        assertEquals(file, AuthorizedKeys.remove(file, "AAAA"))
    }

    @Test fun privateKeys() {
        assertTrue(AuthorizedKeys.looksPrivate("-----BEGIN OPENSSH PRIVATE KEY-----\nb3Blbn…"))
        assertFalse(AuthorizedKeys.looksPrivate(ed25519))
        assertTrue(AuthorizedKeys.parse("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n").isEmpty())
    }
}
