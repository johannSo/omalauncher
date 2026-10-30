package dev.omalauncher.pp.search

import dev.omalauncher.pp.theme.SearchStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The web search's URLs, its style.toml key, and reading pacman's sync databases. */
class SearchSourcesTest {
    @Test fun webSearchUrls() {
        assertEquals("https://duckduckgo.com/?q=owl%20widgets", WebSearch.url("duckduckgo", "owl widgets"))
        assertEquals("https://www.google.com/search?q=a%26b%3Dc", WebSearch.url("Google", "a&b=c"))
        assertEquals("https://search.example/?q=%C3%A9t%C3%A9", WebSearch.url("https://search.example/?q=%s", "été"))
        // One that isn't an engine or a URL with %s falls back to the default.
        assertEquals("https://duckduckgo.com/?q=x", WebSearch.url("altavista", "x"))
        assertTrue(WebSearch.isValid("kagi"))
        assertTrue(WebSearch.isValid("https://x.test/?q=%s"))
        assertFalse(WebSearch.isValid("https://x.test/"))
        assertFalse(WebSearch.isValid("ftp://x.test/%s"))
    }

    @Test fun engineSetting() {
        assertEquals("duckduckgo", SearchStyle.ENGINE.default)
        assertEquals("google", SearchStyle.ENGINE.decode("Google"))
        assertEquals("https://x.test/?q=%s", SearchStyle.ENGINE.decode("https://x.test/?q=%s"))
        try {
            SearchStyle.ENGINE.decode("altavista")
            fail("altavista isn't an engine")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("duckduckgo"))
        }
        // Contacts stay off until settings › search turns them on and asks for the permission.
        assertFalse(SearchStyle.CONTACTS.default)
    }

    /** A tar entry, as pacman's databases hold them: a 512-byte header, then the data padded to 512 bytes. */
    private fun ByteArrayOutputStream.entry(
        name: String,
        data: ByteArray,
        type: Char = '0',
    ) {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "%011o".format(data.size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257)
        write(h)
        write(data)
        write(ByteArray((512 - data.size % 512) % 512))
    }

    @Test fun readsSyncDatabases() {
        val tar = ByteArrayOutputStream()
        tar.entry("btop-1.3.2-1/", ByteArray(0), '5')
        tar.entry("btop-1.3.2-1/desc", "%FILENAME%\nbtop-1.3.2-1-aarch64.pkg.tar.xz\n\n%NAME%\nbtop\n\n%VERSION%\n1.3.2-1\n\n%DESC%\nA monitor of resources\n".toByteArray())
        // A name too long for the header comes in a GNU long-name entry before it.
        val long = "a-package-with-a-name-long-enough-to-need-gnu-long-names-in-its-tar-header-xxxxxxxxxxxxxxx-1.0-1/desc"
        tar.entry("././@LongLink", long.toByteArray(), 'L')
        tar.entry(long.take(100), "%NAME%\na-package-with-a-name-long-enough\n%VERSION%\n1.0-1\n".toByteArray())
        tar.entry("foot-1.20-1/files", "%FILES%\nusr/bin/foot\n".toByteArray())
        tar.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        val pkgs = parseSyncDb(ByteArrayInputStream(gz.toByteArray()), "extra")
        assertEquals(listOf("btop", "a-package-with-a-name-long-enough"), pkgs.map { it.name })
        assertEquals("1.3.2-1", pkgs[0].version)
        assertEquals("A monitor of resources", pkgs[0].description)
        assertEquals("extra", pkgs[0].repo)
        assertEquals("", pkgs[1].description)
    }
}
