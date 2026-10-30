package dev.omalauncher.pp.linux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DBusTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun encode(
        signature: String,
        vararg values: Any?,
    ) = DBus.Writer().apply { write(signature, values.toList()) }.toByteArray()

    private fun decode(
        signature: String,
        bytes: ByteArray,
    ) = DBus.Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)).read(signature)

    @Test fun completeTypes() {
        assertEquals(listOf("s", "u", "s", "s", "s", "as", "a{sv}", "i"), DBus.types("susssasa{sv}i"))
        assertEquals(listOf("(iiibiiay)", "a(yv)", "aas"), DBus.types("(iiibiiay)a(yv)aas"))
    }

    @Test fun stringsAreLengthPrefixedAndTerminated() {
        assertArrayEquals(bytes(1, 0, 0, 0, 2, 0, 0, 0, 'a'.code, 'b'.code, 0), encode("ys", 1, "ab"))
    }

    /** A dict entry is aligned to 8 like a struct; the array's length counts from the first entry, not the padding. */
    @Test fun dictEntriesAlignToEight() {
        val expected = bytes(10, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 'k'.code, 0, 1, 'y'.code, 0, 2)
        assertArrayEquals(expected, encode("a{sv}", mapOf("k" to DBus.Variant("y", 2))))
    }

    @Test fun valuesRoundTrip() {
        val values =
            listOf(
                7, true, -2, 65535, -5, 4_000_000_000L, Long.MIN_VALUE, 1.5, "héllo", "/a/b", "a{sv}",
                DBus.Variant("as", listOf("x", "y")),
                listOf(1, "s"),
                mapOf("urgency" to DBus.Variant("y", 2), "image-data" to DBus.Variant("(iiibiiay)", listOf(1, 1, 4, true, 8, 4, bytes(1, 2, 3, 4)))),
            )
        val sig = "ybnqiuxdsogv(is)a{sv}"
        val back = decode(sig, encode(sig, *values.toTypedArray()))
        assertEquals(values.subList(0, 13), back.subList(0, 13))
        @Suppress("UNCHECKED_CAST")
        val hints = back[13] as Map<String, DBus.Variant>
        assertEquals(DBus.Variant("y", 2), hints["urgency"])
        val image = hints["image-data"]!!.value as List<*>
        assertEquals(listOf(1, 1, 4, true, 8, 4), image.subList(0, 6))
        assertArrayEquals(bytes(1, 2, 3, 4), image[6] as ByteArray)
    }

    @Test fun messagesRoundTrip() {
        val m =
            DBus.Message(
                DBus.METHOD_CALL, 9, path = "/org/freedesktop/Notifications", iface = "org.freedesktop.Notifications",
                member = "Notify", destination = "org.freedesktop.Notifications", sender = ":1.4",
                signature = LinuxNotification.NOTIFY_SIGNATURE,
                body = listOf("app", 0L, "", "Hi", "there", listOf("default", ""), mapOf<String, DBus.Variant>(), -1),
            )
        val bytes = DBus.encode(m)
        val back = DBus.read(ByteArrayInputStream(bytes))!!
        assertEquals(m, back.copy(body = m.body))
        assertEquals(m.body.subList(0, 6), back.body.subList(0, 6))
        assertEquals(-1, back.body[7])
        assertEquals(null, DBus.read(ByteArrayInputStream(ByteArray(0))))
    }

    /** Peers may send big-endian messages: the first byte says which. */
    @Test fun readsBigEndian() {
        val b = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        b.put('B'.code.toByte()).put(DBus.SIGNAL.toByte()).put(0).put(1).putInt(4).putInt(7)
        val fieldsAt = b.position()
        b.putInt(0) // fields length, below
        // (yv) fields, each aligned to 8: MEMBER "M", SIGNATURE "u".
        b.position(16)
        b.put(3).put(1).put('s'.code.toByte()).put(0).putInt(1).put('M'.code.toByte()).put(0)
        while (b.position() % 8 != 0) b.put(0)
        b.put(8).put(1).put('g'.code.toByte()).put(0).put(1).put('u'.code.toByte()).put(0)
        val fieldsEnd = b.position()
        b.putInt(fieldsAt, fieldsEnd - 16)
        while (b.position() % 8 != 0) b.put(0)
        b.putInt(42)
        val m = DBus.read(ByteArrayInputStream(b.array().copyOf(b.position())))!!
        assertEquals("M", m.member)
        assertEquals(7, m.serial)
        assertEquals(listOf(42L), m.body)
    }

    /**
     * Against a real dbus-daemon and GDBus, when the machine has them: authenticates with ANONYMOUS as under
     * proot, takes the notification name, answers gdbus's Notify, and a second client gets the signal.
     */
    @Test fun talksToARealBus() {
        val daemon = which("dbus-daemon")
        val gdbus = which("gdbus")
        assumeTrue("needs dbus-daemon and gdbus", daemon != null && gdbus != null)
        val dir = Files.createTempDirectory("dbus").toFile()
        val config = File(dir, "bus.conf")
        config.writeText(
            """
            <busconfig>
              <type>session</type>
              <listen>tcp:host=127.0.0.1,port=0</listen>
              <auth>ANONYMOUS</auth>
              <allow_anonymous/>
              <policy context="default"><allow user="*"/><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy>
            </busconfig>
            """.trimIndent(),
        )
        val bus = ProcessBuilder(daemon, "--nofork", "--nopidfile", "--nosyslog", "--print-address", "--config-file=${config.path}").start()
        try {
            val address = bus.inputStream.bufferedReader().readLine()
            val port = Regex("port=(\\d+)").find(address)!!.groupValues[1].toInt()
            val socket = Socket("127.0.0.1", port)
            val server = DBusConnection(socket.getInputStream(), socket.getOutputStream())
            server.connect()
            val name = "org.freedesktop.Notifications"
            assertEquals(listOf(1L), server.call(DBusConnection.BUS_NAME, DBusConnection.BUS_PATH, DBusConnection.BUS_NAME, "RequestName", "su", listOf(name, 2)).body)

            val listenerSocket = Socket("127.0.0.1", port)
            val listener = DBusConnection(listenerSocket.getInputStream(), listenerSocket.getOutputStream())
            listener.connect()
            listener.call(DBusConnection.BUS_NAME, DBusConnection.BUS_PATH, DBusConnection.BUS_NAME, "AddMatch", "s", listOf("type='signal',interface='$name'"))

            val client =
                ProcessBuilder(
                    gdbus, "call", "--address", address, "--dest", name, "--object-path", "/org/freedesktop/Notifications",
                    "--method", "$name.Notify", "make", "0", "dialog-information", "Build done", "<b>0</b> errors &amp; 2 warnings",
                    "[\"default\", \"\", \"log\", \"Show log\"]", "{\"urgency\": <byte 2>, \"desktop-entry\": <\"org.gnome.Builder\">}", "5000",
                ).redirectErrorStream(true).start()
            val call = generateSequence { server.read() }.first { it.type == DBus.METHOD_CALL && it.member == "Notify" }
            val (replaces, n) = LinuxNotification.fromNotify(call.body)
            assertEquals(0, replaces)
            assertEquals("Build done", n.summary)
            assertEquals(Urgency.Critical, n.urgency)
            assertEquals("org.gnome.Builder", n.desktopEntry)
            assertEquals(listOf(NotifyAction("log", "Show log")), n.buttons)
            assertEquals("0 errors & 2 warnings", NotifyMarkup.parse(n.body).text)
            server.reply(call, "u", listOf(17))
            assertEquals("(uint32 17,)", client.inputStream.bufferedReader().readText().trim())
            client.waitFor(10, TimeUnit.SECONDS)

            server.signal("/org/freedesktop/Notifications", name, "ActionInvoked", "us", listOf(17, "log"))
            val signal = generateSequence { listener.read() }.first { it.type == DBus.SIGNAL && it.member == "ActionInvoked" }
            assertEquals(listOf(17L, "log"), signal.body)
            assertEquals(server.uniqueName, signal.sender)
        } finally {
            bus.destroy()
            dir.deleteRecursively()
        }
    }

    private fun which(name: String) = System.getenv("PATH").orEmpty().split(':').map { File(it, name) }.firstOrNull { it.canExecute() }?.path
}
