package dev.omalauncher.pp.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GestureActionTest {
    @Test fun readsWords() {
        assertEquals(GestureAction.None, GestureAction.parse("none"))
        assertEquals(GestureAction.Drawer, GestureAction.parse("drawer"))
        assertEquals(GestureAction.Shade, GestureAction.parse("notification-shade"))
        assertEquals(GestureAction.QuickSettings, GestureAction.parse("quick-settings"))
        assertEquals(GestureAction.Pages, GestureAction.parse("pages"))
        assertEquals(GestureAction.Lock, GestureAction.parse("lock"))
        assertEquals(GestureAction.Recents, GestureAction.parse("recents"))
        // In any case, with spaces around, and the other names one might write.
        assertEquals(GestureAction.Lock, GestureAction.parse("  LOCK "))
        assertEquals(GestureAction.Shade, GestureAction.parse("shade"))
        assertEquals(GestureAction.Recents, GestureAction.parse("recent-apps"))
        assertEquals(GestureAction.None, GestureAction.parse("nothing"))
    }

    @Test fun readsPrefixes() {
        assertEquals(GestureAction.OpenPanel("x.owl"), GestureAction.parse("panel:x.owl"))
        assertEquals(GestureAction.OpenPanel("controls.owl"), GestureAction.parse("panel:controls"))
        assertEquals(GestureAction.OpenApp("org.mozilla.firefox"), GestureAction.parse("app:org.mozilla.firefox"))
        val app = GestureAction.parse("app:com.android.settings/.Settings") as GestureAction.OpenApp
        assertEquals("com.android.settings", app.packageName)
        assertEquals(".Settings", app.activity)
        assertNull(GestureAction.OpenApp("org.mozilla.firefox").activity)
        assertEquals(GestureAction.OpenLinuxApp("firefox"), GestureAction.parse("linux-app:firefox.desktop"))
        // What follows the prefix is kept as written, spaces and colons too.
        assertEquals(GestureAction.RunLinux("notify-send 'hi: there'"), GestureAction.parse("linux: notify-send 'hi: there'"))
        assertEquals(GestureAction.RunInTerminal("btop"), GestureAction.parse("Terminal:btop"))
        assertEquals(GestureAction.AskAgent("What's on today? Use ~/notes"), GestureAction.parse("agent:What's on today? Use ~/notes"))
    }

    /** Values from before actions: a panel's bare file name, as gestures.swipe-up = "drawer.owl" was. */
    @Test fun readsBarePanelNames() {
        assertEquals(GestureAction.OpenPanel("drawer.owl"), GestureAction.parse("drawer.owl"))
        assertEquals(GestureAction.OpenPanel("my-panel.owl"), GestureAction.parse("my-panel.owl"))
    }

    @Test fun refusesWhatIsntOne() {
        for (bad in listOf("", "  ", "lockscreen", "panel:", "panel:a/b.owl", "app:", "app:1abc", "app:pkg/", "app:pkg/a b", "linux:", "terminal:  ", "agent:", "linux-app:", ".owl", "dir/x.owl", "foo:bar")) {
            try {
                GestureAction.parse(bad)
                fail("parsed: \"$bad\"")
            } catch (e: IllegalArgumentException) {
                assertTrue(bad, e.message!!.isNotEmpty())
            }
            assertNull(GestureAction.parseOrNull(bad))
        }
        assertNull(GestureAction.parseOrNull(null))
    }

    @Test fun writesWhatItReads() {
        val all =
            listOf(
                GestureAction.None, GestureAction.Drawer, GestureAction.Shade, GestureAction.QuickSettings, GestureAction.Pages, GestureAction.Lock, GestureAction.Recents,
                GestureAction.OpenPanel("controls.owl"), GestureAction.OpenApp("org.mozilla.firefox"), GestureAction.OpenApp("com.android.settings/.Settings"),
                GestureAction.OpenLinuxApp("org.gnome.Nautilus"), GestureAction.RunLinux("notify-send hi"), GestureAction.RunInTerminal("make; exec bash"),
                GestureAction.AskAgent("summarize ~/todo.md"),
            )
        for (action in all) assertEquals(action.encoded, action, GestureAction.parse(action.encoded))
        assertEquals("panel:controls.owl", GestureAction.OpenPanel("controls.owl").encoded)
        assertEquals("app:com.android.settings/.Settings", GestureAction.OpenApp("com.android.settings/.Settings").encoded)
        assertEquals("linux:notify-send hi", GestureAction.RunLinux("notify-send hi").encoded)
        assertEquals("agent:hi", GestureAction.AskAgent("hi").encoded)
        assertEquals("lock", GestureAction.Lock.encoded)
    }

    @Test fun knowsWhatNeedsTheService() {
        assertTrue(GestureAction.Lock.needsAccessibility)
        assertTrue(GestureAction.Recents.needsAccessibility)
        assertTrue(!GestureAction.Shade.needsAccessibility)
        assertEquals("x.owl", GestureAction.OpenPanel("x.owl").panel)
        assertNull(GestureAction.Drawer.panel)
    }

    /** style.toml keeps an action as it's written back, so a file from before actions reads as it did, and a bad one is reported. */
    @Test fun styleFileReadsGestures() {
        val decoded =
            StyleFile.decode(
                mapOf(
                    "gestures.swipe-up" to "drawer.owl",
                    "gestures.swipe-down" to "controls.owl",
                    "gestures.double-tap" to "LOCK",
                    "gestures.pinch-in" to "panel:pages",
                    "gestures.two-finger-up" to "nonsense",
                    "gestures.pinch-out" to 3.0,
                ),
            )
        val style = decoded.style
        // drawer.owl is swipe up's default: nothing to keep in the file.
        assertEquals(Style.SWIPE_UP.default, style[Style.SWIPE_UP])
        assertEquals("panel:controls.owl", style[Style.SWIPE_DOWN])
        assertEquals("lock", style[GestureStyle.DOUBLE_TAP])
        assertEquals("panel:pages.owl", style[GestureStyle.PINCH_IN])
        assertEquals("none", style[GestureStyle.TWO_FINGER_UP])
        assertEquals("none", style[GestureStyle.PINCH_OUT])
        assertEquals(listOf("gestures.two-finger-up", "gestures.pinch-out"), decoded.problems.map { it.substringBefore(':') })
        val written = StyleFile.encode(emptyMap(), style)
        assertNull(written["gestures.swipe-up"])
        assertEquals("lock", written["gestures.double-tap"])
        assertEquals(setOf("controls.owl", "pages.owl", "drawer.owl"), GestureStyle.panels(style))
    }

    @Test fun defaultsListEveryGesture() {
        val defaults = StyleFile.defaultsText()
        for (key in listOf("swipe-up", "swipe-down", "double-tap", "two-finger-up", "two-finger-down", "pinch-in", "pinch-out")) {
            assertEquals(key, 1, Regex("^$key = ", RegexOption.MULTILINE).findAll(defaults).count())
        }
        assertEquals(1, Regex("\\[gestures]").findAll(defaults).count())
    }
}
