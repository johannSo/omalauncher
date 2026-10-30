package dev.omalauncher.pp.linux

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import dev.omalauncher.pp.upgrade.Managed
import dev.omalauncher.pp.upgrade.Policy
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "notifications"

/** Restarts of a crashing dbus-daemon back off up to this. */
private const val MAX_BACKOFF_MS = 60_000L

/** Where the guest's notifications show: Android's shade, see LinuxNotifications. */
interface NotificationDisplay {
    /** Shows notification [id], or [update]s the one shown under it in place. */
    fun show(
        id: Int,
        notification: LinuxNotification,
        update: Boolean,
    )

    fun hide(id: Int)
}

/**
 * The guest's session bus and its notification server. A dbus-daemon runs in the guest at
 * $XDG_RUNTIME_DIR/bus (the launcher's runtime dir, see [Proot.runtimeDir]), and the launcher
 * connects to it as a client that owns org.freedesktop.Notifications, so `notify-send`, GTK apps
 * and build tools post to [display] with nothing running in the guest but the bus itself.
 */
class NotificationServer(
    private val proot: Proot,
    private val display: NotificationDisplay,
    private val version: String,
) {
    private val socketFile = File(proot.runtimeDir, "bus")

    /** Notifications showing now, by id. Guarded by itself. */
    private val active = HashMap<Int, LinuxNotification>()
    private val expiries = HashMap<Int, ScheduledFuture<*>>()
    private var lastId = 0
    private val timers = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "notification-timers").apply { isDaemon = true } }

    @Volatile private var connection: DBusConnection? = null

    @Volatile private var socket: LocalSocket? = null

    /** Whether the bus should run; off while Linux is shut down or removed. */
    @Volatile private var running = false
    private var started = false

    /** Starts the bus and the server, or resumes them after [stop]. */
    fun start() {
        running = true
        if (started) return
        started = true
        thread(name = "dbus-daemon", isDaemon = true) { superviseDaemon() }
        thread(name = "notifications", isDaemon = true) { serve() }
    }

    /**
     * Stops restarting the bus (the caller ends it along with the other guest processes) and takes
     * the notifications down: their apps are gone, so their actions would go nowhere.
     */
    fun stop() {
        running = false
        runCatching { socket?.close() }
        val ids = synchronized(active) { active.keys.toList().also { active.clear() } }
        for (id in ids) {
            synchronized(active) { expiries.remove(id)?.cancel(false) }
            display.hide(id)
        }
    }

    /** Our bus config; the stock session.conf only allows EXTERNAL, which fails under proot (see [DBusConnection.connect]). */
    fun managed(): List<Managed> =
        listOf(
            Managed.text(File(proot.rootfs, CONFIG.removePrefix("/")), Policy.Owned) {
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <!-- omalauncher: the session bus; the launcher shows its notifications (see NotificationServer.kt).
                     proot fakes root, but the kernel reports every peer's real uid, so EXTERNAL can't match:
                     clients fall back to ANONYMOUS. Only the app's own uid can reach the socket. -->
                <busconfig>
                  <type>session</type>
                  <listen>unix:path=${Proot.GUEST_RUNTIME_DIR}/bus</listen>
                  <auth>EXTERNAL</auth>
                  <auth>ANONYMOUS</auth>
                  <allow_anonymous/>
                  <standard_session_servicedirs/>
                  <policy context="default">
                    <allow user="*"/>
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent() + "\n"
            },
        )

    /** Keeps dbus-daemon running, like AudioBridge does PulseAudio. libdbus aborts without a machine id, which the image lacks. */
    private fun superviseDaemon() {
        var backoff = 1000L
        while (true) {
            while (!running) Thread.sleep(1000)
            val started = SystemClock.elapsedRealtime()
            val code =
                runCatching {
                    proot.run(
                        "rm -f ${Proot.GUEST_RUNTIME_DIR}/bus; dbus-uuidgen --ensure=/etc/machine-id >/dev/null 2>&1; " +
                            "exec dbus-daemon --nofork --nopidfile --nosyslog --config-file=$CONFIG",
                    ) { Log.i(TAG, "[dbus-daemon] $it") }
                }.getOrElse { -1 }
            if (SystemClock.elapsedRealtime() - started > MAX_BACKOFF_MS) backoff = 1000L
            Log.w(TAG, "dbus-daemon exited $code; restarting in ${backoff / 1000} s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Connects to the bus whenever it's up, and answers its calls. */
    private fun serve() {
        while (true) {
            while (!running || !socketFile.exists()) Thread.sleep(500)
            try {
                LocalSocket().use { s ->
                    socket = s
                    s.connect(LocalSocketAddress(socketFile.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    val conn = DBusConnection(s.inputStream, s.outputStream)
                    conn.connect()
                    // Replaces a notification daemon the user started, and comes back if one takes the name over.
                    conn.call(DBusConnection.BUS_NAME, DBusConnection.BUS_PATH, DBusConnection.BUS_NAME, "RequestName", "su", listOf(NAME, REPLACE_EXISTING))
                    connection = conn
                    Log.i(TAG, "serving $NAME as ${conn.uniqueName}")
                    while (true) handle(conn, conn.read() ?: break)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "bus connection failed", e)
            } finally {
                connection = null
                socket = null
            }
            Thread.sleep(1000)
        }
    }

    private fun handle(
        conn: DBusConnection,
        m: DBus.Message,
    ) {
        if (m.type != DBus.METHOD_CALL) return
        try {
            when ("${m.iface}.${m.member}") {
                "$NAME.Notify" -> {
                    if (m.signature != LinuxNotification.NOTIFY_SIGNATURE) return conn.error(m, INVALID_ARGS, "Notify takes ${LinuxNotification.NOTIFY_SIGNATURE}")
                    val (replaces, notification) = LinuxNotification.fromNotify(m.body)
                    val (id, update) = add(replaces, notification)
                    conn.reply(m, "u", listOf(id))
                    runCatching { display.show(id, notification, update) }.onFailure { Log.w(TAG, "notification $id not shown", it) }
                    expireLater(id, notification)
                }
                "$NAME.CloseNotification" -> {
                    close((m.body.firstOrNull() as? Long)?.toInt() ?: 0, CloseReason.CLOSED)
                    conn.reply(m)
                }
                "$NAME.GetCapabilities" -> conn.reply(m, "as", listOf(CAPABILITIES))
                "$NAME.GetServerInformation" -> conn.reply(m, "ssss", listOf("OmaLauncher++", "OmaLauncher++", version, "1.2"))
                "org.freedesktop.DBus.Introspectable.Introspect" -> conn.reply(m, "s", listOf(INTROSPECTION))
                "org.freedesktop.DBus.Peer.Ping" -> conn.reply(m)
                else -> conn.error(m, "org.freedesktop.DBus.Error.UnknownMethod", "No method ${m.member} on ${m.iface}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${m.member} failed", e)
            conn.error(m, "org.freedesktop.DBus.Error.Failed", e.message ?: e.toString())
        }
    }

    /** Files [n] under its id: replaces_id's, a notification's with the same stack tag, or a new one. */
    private fun add(
        replaces: Int,
        n: LinuxNotification,
    ): Pair<Int, Boolean> =
        synchronized(active) {
            val tagged = n.stackTag?.let { tag -> active.entries.firstOrNull { it.value.appName == n.appName && it.value.stackTag == tag }?.key }
            val id = if (replaces != 0) replaces else tagged ?: nextId()
            val update = active.put(id, n) != null
            expiries.remove(id)?.cancel(false)
            id to update
        }

    /** Closes [id] once [n]'s lifetime is up, unless it's been replaced by then; after it shows, so it can't go first. */
    private fun expireLater(
        id: Int,
        n: LinuxNotification,
    ) {
        val ms = n.lifetimeMs ?: return
        synchronized(active) {
            if (active[id] !== n) return
            expiries[id] = timers.schedule({ close(id, CloseReason.EXPIRED) }, ms, TimeUnit.MILLISECONDS)
        }
    }

    private fun nextId(): Int {
        do {
            lastId = if (lastId == Int.MAX_VALUE) 1 else lastId + 1
        } while (lastId in active)
        return lastId
    }

    /** Takes [id] down and tells its app why; nothing if it's gone already. */
    private fun close(
        id: Int,
        reason: Int,
    ) {
        synchronized(active) {
            active.remove(id) ?: return
            expiries.remove(id)?.cancel(false)
        }
        display.hide(id)
        emit("NotificationClosed", "uu", listOf(id, reason))
    }

    private fun emit(
        member: String,
        signature: String,
        values: List<Any?>,
    ) {
        val conn = connection ?: return
        runCatching { conn.signal(PATH, NAME, member, signature, values) }.onFailure { Log.w(TAG, "$member not sent", it) }
    }

    /** The user swiped [id] away. */
    fun dismissed(id: Int) = close(id, CloseReason.DISMISSED)

    /**
     * The user pressed [id]'s action [key], or tapped it ([LinuxNotification.DEFAULT_ACTION]). It closes
     * unless it's resident; returns it, or null if it's gone.
     */
    fun invoke(
        id: Int,
        key: String,
    ): LinuxNotification? {
        val n = synchronized(active) { active[id] } ?: return null
        if (n.actions.any { it.key == key }) emit("ActionInvoked", "us", listOf(id, key))
        if (!n.resident) close(id, CloseReason.DISMISSED)
        return n
    }

    private companion object {
        const val NAME = "org.freedesktop.Notifications"
        const val PATH = "/org/freedesktop/Notifications"
        const val CONFIG = "/usr/local/share/omalauncher/dbus-session.conf"
        const val REPLACE_EXISTING = 0x2
        const val INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"

        /** No body-hyperlinks: Android's notifications can't open a link in their text. */
        val CAPABILITIES = listOf("actions", "body", "body-markup", "icon-static", "persistence")

        val INTROSPECTION =
            """
            <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
             "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
            <node>
              <interface name="$NAME">
                <method name="Notify">
                  <arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="s" direction="in"/>
                  <arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="as" direction="in"/>
                  <arg type="a{sv}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="out"/>
                </method>
                <method name="CloseNotification"><arg type="u" direction="in"/></method>
                <method name="GetCapabilities"><arg type="as" direction="out"/></method>
                <method name="GetServerInformation">
                  <arg type="s" direction="out"/><arg type="s" direction="out"/><arg type="s" direction="out"/><arg type="s" direction="out"/>
                </method>
                <signal name="NotificationClosed"><arg type="u"/><arg type="u"/></signal>
                <signal name="ActionInvoked"><arg type="u"/><arg type="s"/></signal>
              </interface>
              <interface name="org.freedesktop.DBus.Introspectable">
                <method name="Introspect"><arg type="s" direction="out"/></method>
              </interface>
              <interface name="org.freedesktop.DBus.Peer">
                <method name="Ping"/>
              </interface>
            </node>
            """.trimIndent()
    }
}
