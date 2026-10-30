package dev.omalauncher.pp.linux

import dev.omalauncher.pp.linux.NotifyMarkup.Span
import dev.omalauncher.pp.linux.NotifyMarkup.Style
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationsTest {
    private fun notify(
        actions: List<String> = emptyList(),
        hints: Map<String, Any?> = emptyMap(),
        timeout: Int = -1,
        replaces: Long = 0,
        appName: String = "notify-send",
    ) = LinuxNotification.fromNotify(
        listOf(appName, replaces, "", "Summary", "Body", actions, hints.mapValues { (_, v) -> DBus.Variant("v", v) }, timeout),
    )

    @Test fun readsNotifyArguments() {
        val (replaces, n) =
            notify(
                actions = listOf("default", "Open", "reply", "Reply", "odd"),
                hints = mapOf("urgency" to 0, "desktop-entry" to "org.gnome.Nautilus.desktop", "image-path" to "/tmp/a.png", "resident" to true, "value" to 140, "x-dunst-stack-tag" to "volume"),
                replaces = 4,
            )
        assertEquals(4, replaces)
        assertEquals("notify-send", n.appName)
        assertEquals(listOf(NotifyAction("default", "Open"), NotifyAction("reply", "Reply")), n.actions)
        assertEquals(listOf(NotifyAction("reply", "Reply")), n.buttons)
        assertEquals(true, n.hasDefaultAction)
        assertEquals(Urgency.Low, n.urgency)
        assertEquals("org.gnome.Nautilus", n.desktopEntry)
        assertEquals("/tmp/a.png", n.imagePath)
        assertEquals(true, n.resident)
        assertEquals(100, n.progress)
        assertEquals("volume", n.stackTag)
    }

    @Test fun androidShowsThreeButtons() {
        val (_, n) = notify(actions = (1..5).flatMap { listOf("k$it", "Label $it") })
        assertEquals(listOf("k1", "k2", "k3"), n.buttons.map { it.key })
    }

    @Test fun lifetime() {
        assertEquals(5000L, notify(timeout = 5000).second.lifetimeMs)
        assertNull(notify(timeout = 0).second.lifetimeMs)
        assertNull(notify(timeout = -1).second.lifetimeMs)
        assertEquals(LinuxNotification.TRANSIENT_MS, notify(hints = mapOf("transient" to true)).second.lifetimeMs)
        assertEquals(3000L, notify(hints = mapOf("transient" to true), timeout = 3000).second.lifetimeMs)
        // Critical ones stay until dismissed.
        assertNull(notify(hints = mapOf("urgency" to 2), timeout = 5000).second.lifetimeMs)
    }

    @Test fun imageDataBecomesArgb() {
        val rgb = byteArrayOf(0xff.toByte(), 0, 0, 0, 0xff.toByte(), 0, /* row padding */ 9, 9)
        val (_, n) = notify(hints = mapOf("image-data" to listOf(2, 1, 8, false, 8, 3, rgb)))
        assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), n.image!!.argb())
        val rgba = NotifyImage(1, 2, 4, true, 8, 4, byteArrayOf(1, 2, 3, 0x80.toByte(), 4, 5, 6, 0))
        assertArrayEquals(intArrayOf(0x80010203.toInt(), 0x00040506), rgba.argb())
        // Rows that don't fit the data, or channels that don't fit has_alpha.
        assertNull(NotifyImage(2, 2, 8, false, 8, 3, rgb).argb())
        assertNull(NotifyImage(1, 1, 4, false, 8, 4, ByteArray(4)).argb())
        // The spec's older names.
        assertEquals(1, notify(hints = mapOf("icon_data" to listOf(1, 1, 3, false, 8, 3, ByteArray(3)))).second.image?.width)
    }

    @Test fun findsTheSendingApp() {
        val nautilus = LinuxApp("Files", "nautilus", false, "org.gnome.Nautilus", null)
        val firefox = LinuxApp("Firefox", "firefox", false, "firefox", null)
        val foot = LinuxApp("Foot", "foot", false, "foot", "foot")
        val apps = listOf(nautilus, firefox, foot)
        assertEquals(nautilus, findNotifyingApp(apps, "org.gnome.Nautilus", "nautilus"))
        assertEquals(nautilus, findNotifyingApp(apps, "nautilus", ""))
        assertEquals(firefox, findNotifyingApp(apps, null, "Firefox"))
        assertEquals(firefox, findNotifyingApp(apps, "", "firefox"))
        assertNull(findNotifyingApp(apps, null, "notify-send"))
        assertNull(findNotifyingApp(apps, null, ""))
    }

    @Test fun imagePaths() {
        assertEquals("/root/a b.png", notifyImagePath("file:///root/a%20b.png"))
        assertEquals("/usr/share/icons/x.svg", notifyImagePath("/usr/share/icons/x.svg"))
        assertEquals("/root/a b.png", notifyImagePath("file:///root/a b.png"))
        assertNull(notifyImagePath("dialog-information"))
    }

    @Test fun markupStyles() {
        val s = NotifyMarkup.parse("<b>Build</b> took <i>2 <u>min</u></i> — <a href=\"https://x.org\">log</a>")
        assertEquals("Build took 2 min — log", s.text)
        assertEquals(
            listOf(Span(0, 5, Style.Bold), Span(11, 16, Style.Italic), Span(13, 16, Style.Underline), Span(19, 22, Style.Link("https://x.org"))),
            s.spans,
        )
    }

    @Test fun markupEntitiesAndImages() {
        assertEquals("a & b < c > \"d\" 'e' é €", NotifyMarkup.parse("a &amp; b &lt; c &gt; &quot;d&quot; &apos;e&apos; &#233; &#x20AC;").text)
        assertEquals("[cat] line\nnext", NotifyMarkup.parse("<img src=\"/c.png\" alt=\"[cat]\"/> line<br>next").text)
        // Unknown entities and stray ampersands stay as written.
        assertEquals("R&D &nbsp;", NotifyMarkup.parse("R&D &nbsp;").text)
    }

    /** Pango tags are dropped; text that only looks like a tag stays, as unescaped bodies from scripts have it. */
    @Test fun markupLeniency() {
        assertEquals("red text", NotifyMarkup.parse("<span foreground=\"red\">red</span> text").text)
        assertEquals("List<String> is 1 < 2", NotifyMarkup.parse("List<String> is 1 < 2").text)
        val unclosed = NotifyMarkup.parse("<b>bold to the end")
        assertEquals(listOf(Span(0, 15, Style.Bold)), unclosed.spans)
        assertEquals("x", NotifyMarkup.parse("</i>x").text)
    }
}
