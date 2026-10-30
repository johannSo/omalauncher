package dev.omalauncher.pp

import dev.omalauncher.pp.theme.Toml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTweaksTest {
    private val whatsapp = "a:com.whatsapp/.Main"
    private val workWhatsapp = "a:com.whatsapp/.Main@10"

    private fun read(text: String) = AppTweaksFile.decode(Toml.parse(text))

    @Test fun fileKeysGoFromActivityToPackage() {
        assertEquals(listOf("com.whatsapp/.Main", "com.whatsapp"), AppTweaks.fileKeys(whatsapp))
        // Another profile's copy is only ever its own: the package names the launcher's own profile's.
        assertEquals(listOf("com.whatsapp/.Main@10"), AppTweaks.fileKeys(workWhatsapp))
        assertEquals(listOf("linux:firefox"), AppTweaks.fileKeys("l:firefox"))
        assertEquals(listOf("owl:notes.owl"), AppTweaks.fileKeys("o:notes.owl"))
        assertEquals(listOf("shortcut:com.brave.browser/##site"), AppTweaks.fileKeys("s:com.brave.browser/##site"))
    }

    @Test fun normalizesWhatAgentsWrite() {
        assertEquals("com.whatsapp", AppTweaks.normalize(" com.whatsapp "))
        assertEquals("com.whatsapp/.Main", AppTweaks.normalize("com.whatsapp/com.whatsapp.Main"))
        assertEquals("com.whatsapp/.Main@10", AppTweaks.normalize("com.whatsapp/com.whatsapp.Main@10"))
        assertEquals("com.whatsapp/org.other.Main", AppTweaks.normalize("com.whatsapp/org.other.Main"))
        // The drawer's keys and layout.toml's prefixes.
        assertEquals("com.whatsapp/.Main", AppTweaks.normalize("a:com.whatsapp/.Main"))
        assertEquals("com.whatsapp/.Main", AppTweaks.normalize("app:com.whatsapp/.Main"))
        assertEquals("linux:firefox", AppTweaks.normalize("l:firefox"))
        assertEquals("owl:notes.owl", AppTweaks.normalize("o:notes.owl"))
        assertEquals("shortcut:pkg/#3#a:b", AppTweaks.normalize("s:pkg/#3#a:b"))
        assertNull(AppTweaks.normalize(""))
        assertNull(AppTweaks.normalize("linux:"))
        assertNull(AppTweaks.normalize("http://example.com"))
        assertNull(AppTweaks.normalize("com.whatsapp/"))
        assertNull(AppTweaks.normalize("whats app"))
    }

    @Test fun aPackageCountsForEachOfItsIcons() {
        val t = AppTweaks(mapOf("com.whatsapp" to AppTweak(hidden = true, name = "chat")))
        assertTrue(t.hidden(whatsapp))
        assertTrue(t.hidden("a:com.whatsapp/.Other"))
        assertEquals("chat", t.label(whatsapp, "WhatsApp"))
        // Not the work profile's copy.
        assertFalse(t.hidden(workWhatsapp))
        assertEquals("WhatsApp", t.label(workWhatsapp, "WhatsApp"))
    }

    @Test fun anActivitysOwnEntryComesFirstField() {
        val t = AppTweaks(linkedMapOf("com.whatsapp/.Main" to AppTweak(hidden = false), "com.whatsapp" to AppTweak(hidden = true, name = "chat")))
        assertFalse(t.hidden(whatsapp))
        // Saying nothing of its name, it takes the package's.
        assertEquals("chat", t.label(whatsapp, "WhatsApp"))
        assertTrue(t.hidden("a:com.whatsapp/.Other"))
    }

    @Test fun labelsFallBackToTheirOwn() {
        val t = AppTweaks(mapOf("linux:firefox" to AppTweak(name = "web")))
        assertEquals("web", t.label("l:firefox", "Firefox"))
        assertEquals("Files", t.label("l:files", "Files"))
        assertFalse(t.hidden("l:firefox"))
    }

    @Test fun hidingWritesWhereTheLayoutWouldAndShowingDropsTheEntry() {
        val hidden = AppTweaks().hide(whatsapp, true, AppTweaks.preferredFileKey(whatsapp, alone = true))
        assertEquals(mapOf("com.whatsapp" to AppTweak(hidden = true)), hidden.apps)
        val two = AppTweaks().hide(whatsapp, true, AppTweaks.preferredFileKey(whatsapp, alone = false))
        assertEquals(mapOf("com.whatsapp/.Main" to AppTweak(hidden = true)), two.apps)
        assertEquals(emptyMap<String, AppTweak>(), hidden.hide(whatsapp, false, "com.whatsapp").apps)
        assertEquals(emptyMap<String, AppTweak>(), two.hide(whatsapp, false).apps)
    }

    @Test fun editsTheEntryTheAppAlreadyHas() {
        // An agent wrote the activity; the menu, which would write the package, changes that one instead.
        val t = AppTweaks(mapOf("com.whatsapp/.Main" to AppTweak(name = "chat")))
        val hidden = t.hide(whatsapp, true, "com.whatsapp")
        assertEquals(mapOf("com.whatsapp/.Main" to AppTweak(hidden = true, name = "chat")), hidden.apps)
        assertTrue(hidden.hidden(whatsapp))
    }

    @Test fun showingOneIconOfAHiddenPackageOverridesIt() {
        val t = AppTweaks(mapOf("com.whatsapp" to AppTweak(hidden = true)))
        // The package's entry has two icons under it: this one gets its own, the other stays hidden.
        val shown = t.hide(whatsapp, false, "com.whatsapp/.Main")
        assertFalse(shown.hidden(whatsapp))
        assertTrue(shown.hidden("a:com.whatsapp/.Other"))
        assertEquals(AppTweak(hidden = false), shown.apps["com.whatsapp/.Main"])
        // Hidden again, it says nothing the package doesn't: the entry goes.
        assertEquals(mapOf("com.whatsapp" to AppTweak(hidden = true)), shown.hide(whatsapp, true, "com.whatsapp/.Main").apps)
    }

    @Test fun renamingAndBack() {
        val t = AppTweaks().rename("l:firefox", "  web ", "Firefox")
        assertEquals(mapOf("linux:firefox" to AppTweak(name = "web")), t.apps)
        assertEquals("web", t.label("l:firefox", "Firefox"))
        // Its own name, blank or null: the entry goes.
        assertEquals(emptyMap<String, AppTweak>(), t.rename("l:firefox", "Firefox", "Firefox").apps)
        assertEquals(emptyMap<String, AppTweak>(), t.rename("l:firefox", " ", "Firefox").apps)
        assertEquals(emptyMap<String, AppTweak>(), t.rename("l:firefox", null, "Firefox").apps)
        // Renaming keeps it hidden.
        val both = AppTweaks().hide("o:notes.owl", true).rename("o:notes.owl", "jot", "Notes")
        assertEquals(AppTweak(hidden = true, name = "jot"), both.apps["owl:notes.owl"])
    }

    @Test fun findsHiddenAppsOnlyByTheirFullName() {
        val t = AppTweaks()
        assertTrue(t.finds(" whatsapp ", "WhatsApp"))
        assertTrue(t.finds("whatsapp", "chat", "WhatsApp"))
        assertFalse(t.finds("whats", "WhatsApp"))
        assertFalse(t.finds("", ""))
        assertFalse(t.withFindHidden(false).finds("whatsapp", "WhatsApp"))
    }

    @Test fun tileKeysAsDrawerKeys() {
        assertEquals("a:com.whatsapp/.Main@10", AppTweaks.drawerKeyOfTile("app:com.whatsapp/.Main@10"))
        assertEquals("l:firefox", AppTweaks.drawerKeyOfTile("linux:firefox"))
        assertEquals("o:notes.owl", AppTweaks.drawerKeyOfTile("owl:notes.owl"))
        assertEquals("s:pkg/##site", AppTweaks.drawerKeyOfTile("shortcut:pkg/##site"))
        assertNull(AppTweaks.drawerKeyOfTile("widget:3"))
    }

    @Test fun readsTheFile() {
        val decoded =
            read(
                """
                find-hidden = false

                [[app]]
                key = "com.android.chrome"
                hidden = true

                [[app]]
                key = "l:org.mozilla.firefox"
                name = "web"
                """.trimIndent(),
            )
        assertEquals(emptyList<String>(), decoded.problems)
        val t = decoded.tweaks
        assertFalse(t.findHidden)
        assertTrue(t.hidden("a:com.android.chrome/com.google.android.apps.chrome.Main"))
        assertEquals("web", t.label("l:org.mozilla.firefox", "Firefox"))
    }

    @Test fun anEmptyFileHidesNothing() {
        val decoded = read("")
        assertEquals(AppTweaks(), decoded.tweaks)
        assertTrue(decoded.tweaks.findHidden)
        assertEquals(emptyList<String>(), decoded.problems)
    }

    @Test fun saysWhatItCouldntRead() {
        val decoded =
            read(
                """
                find-hidden = "yes"

                [[app]]
                hidden = true

                [[app]]
                key = "not an app"

                [[app]]
                key = "com.a"
                hidden = "yes"
                colour = "red"

                [[app]]
                key = "com.b"
                name = "one"

                [[app]]
                key = "com.b"
                name = "two"
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                "find-hidden: should be true or false",
                "[[app]] 1: needs a key = \"…\"",
                "[[app]] 2: \"not an app\" isn't an app's key",
                "com.a: hidden should be true or false",
                "com.a: unknown key colour",
                "com.b is listed twice; the last one counts",
            ),
            decoded.problems,
        )
        assertTrue(decoded.tweaks.findHidden)
        assertEquals(mapOf("com.b" to AppTweak(name = "two")), decoded.tweaks.apps)
    }

    @Test fun writesWhatItReadsAndKeepsUnknownKeys() {
        val table = Toml.parse("future = 3\n")
        val tweaks = AppTweaks().hide(whatsapp, true, "com.whatsapp").rename("l:firefox", "web \"quoted\"", "Firefox").withFindHidden(false)
        val text = Toml.write(AppTweaksFile.encode(table, tweaks), AppTweaksFile.HEADER)
        assertTrue(text.startsWith("# "))
        val again = Toml.parse(text)
        assertEquals(3.0, again["future"])
        assertEquals(AppTweaksFile.Decoded(tweaks, emptyList()), AppTweaksFile.decode(again))
        // Nothing hidden or renamed: no [[app]] at all.
        assertFalse("[[app]]" in Toml.write(AppTweaksFile.encode(emptyMap(), AppTweaks())))
    }
}
