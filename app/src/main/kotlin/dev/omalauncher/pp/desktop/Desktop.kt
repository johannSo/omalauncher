package dev.omalauncher.pp.desktop

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.theme.DesktopStyle
import dev.omalauncher.pp.wayland.XdgToplevel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** A display other than the phone's own that the desktop could open on; [allowed] is false where Android won't let it. */
data class Monitor(
    val id: Int,
    val name: String,
    val allowed: Boolean,
)

/**
 * The tiling desktop: Linux windows side by side in one activity ([DesktopActivity]) instead of each in its own task.
 * It opens on a monitor (see [DesktopStyle.EXTERNAL]), where the phone's screen keeps working beside it, or on this
 * screen when it's large and [DesktopStyle.LARGE_SCREENS] asks for it. While it's up, new Linux windows open in it;
 * on phones with neither, nothing changes and every window keeps its own task.
 *
 * The windows' places live here in [tiling], not in the activity, so they outlive it moving to another display. When
 * it goes away (the monitor unplugged, its card swiped from Recents) its windows open as tasks of their own again.
 * Main thread only.
 */
class Desktop(
    private val context: Context,
    private val runtime: Runtime,
) : DisplayManager.DisplayListener {
    private val wm get() = runtime.wm
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val scope = MainScope()

    /** Where each window is; read through [changes], since it isn't snapshot state itself. */
    val tiling = Tiling<XdgToplevel>()

    /** Bumped on every change to [tiling], so what reads it recomposes. */
    var changes by mutableIntStateOf(0)
        private set

    /** The activity showing the desktop, once it's up. */
    var host by mutableStateOf<DesktopActivity?>(null)
        private set

    /** The display the desktop is on its way to, until its activity shows up. */
    private var starting: Int? = null

    /** Monitors plugged in now. */
    val monitors = mutableStateListOf<Monitor>()

    /** Opens a window in a task of its own, as before the desktop: the runtime's way, wrapped by [install]. */
    private var asTask: (String) -> Unit = {}

    private val style get() = runtime.style.style

    /** This screen is a tablet's or an unfolded foldable's: 600dp or more both ways. */
    val largeScreen get() = context.resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP

    /** New windows open in the desktop on this screen. */
    private val onThisScreen get() = style[DesktopStyle.LARGE_SCREENS] && largeScreen

    /** Puts the desktop between new windows and their tasks, and starts watching for monitors; call once the window manager's hooks are set. */
    fun install() {
        asTask = wm.openAppWindow
        wm.openAppWindow = { handle -> if (!take(handle)) asTask(handle) }
        displays.registerDisplayListener(this, main)
        refreshMonitors()
        // Plugged in before the launcher started.
        monitors.forEach(::arrived)
        // A window closed in the desktop goes from it.
        scope.launch {
            snapshotFlow { wm.appWindows.toSet() }.collect { open ->
                val gone = tiling.windows.filterNot { it in open }
                if (gone.isEmpty()) return@collect
                gone.forEach(tiling::forget)
                changed()
                refocus()
            }
        }
        // A client asking for fullscreen (F11, mpv's f) gets it, and back; only as it asks, so Super+F still decides between.
        scope.launch {
            var before = emptySet<XdgToplevel>()
            snapshotFlow { changes.let { tiling.windows.filter { it.wantsFullscreen }.toSet() } }.collect { wanting ->
                for (w in (wanting - before) + (before - wanting)) {
                    if (w in tiling && tiling.isFullscreen(w) != (w in wanting)) tiling.toggleFullscreen(w)
                }
                before = wanting
                changed()
            }
        }
    }

    fun changed() {
        changes++
    }

    /** A new window: into the desktop, if it's up or should be, rather than a task of its own. */
    private fun take(handle: String): Boolean {
        val window = wm.windowByHandle(handle) ?: return false
        val display = host?.displayId ?: starting ?: Display.DEFAULT_DISPLAY.takeIf { onThisScreen } ?: return false
        place(window)
        if (!show(display)) {
            tiling.forget(window)
            changed()
            return false
        }
        return true
    }

    /** Puts [window] on the current workspace, or its parent's; a dialog (a parent, or a fixed size) floats. */
    private fun place(window: XdgToplevel) {
        val parentWs = window.parent?.let(tiling::workspaceOf)
        val (minW, minH) = window.minSize
        val fixed = minW > 0 && minH > 0 && window.minSize == window.maxSize
        tiling.add(window, parentWs ?: tiling.current, floating = window.parent != null || fixed)
        tiling.focus(window)
        changed()
        refocus()
    }

    /** The keys go to the desktop's focused window, if the desktop has them now; else that waits for it to get them. */
    private fun refocus() {
        host?.takeIf { it.hasWindowFocus() }?.syncFocus()
    }

    /** Brings the desktop on [display] to the front, starting it there if it isn't up; false if it can't be. */
    private fun show(display: Int): Boolean {
        val h = host
        if (h != null && h.displayId == display) {
            if (display == Display.DEFAULT_DISPLAY) h.toFront()
            return true
        }
        if (starting == display) return true
        return start(display)
    }

    /**
     * Shows [window], which the desktop has, as [Runtime.launch] brings an app's window back: its workspace, focused,
     * and the desktop in front if it's on this screen. False if it isn't the desktop's.
     */
    fun bringToFront(window: XdgToplevel): Boolean {
        if (window !in tiling) return false
        tiling.focus(window)
        changed()
        refocus()
        host?.let { if (it.displayId == Display.DEFAULT_DISPLAY) it.toFront() }
        return true
    }

    /** Opens the desktop on [displayId]; false if Android won't, or there's no such display. */
    fun start(displayId: Int): Boolean {
        val intent = DesktopActivity.intent(context, displayId)
        if (displayId != Display.DEFAULT_DISPLAY && !allowedOn(displayId)) return false
        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
            starting = displayId
            // Refused quietly (a background start Android blocked): the windows waiting for it get tasks after all.
            main.removeCallbacks(gaveUp)
            main.postDelayed(gaveUp, START_TIMEOUT_MS)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "can't open the desktop on display $displayId", e)
            false
        }
    }

    private val gaveUp =
        Runnable {
            if (host != null || starting == null) return@Runnable
            Log.w(TAG, "the desktop didn't open on display $starting")
            starting = null
            handBack()
        }

    /** Whether Android lets an activity of ours on [displayId]; some phones keep a monitor to mirroring. */
    private fun allowedOn(displayId: Int): Boolean {
        val display = displays.getDisplay(displayId) ?: return false
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .isActivityStartAllowedOnDisplay(context, display.displayId, DesktopActivity.intent(context, displayId))
        }.getOrDefault(false)
    }

    /** [activity] shows the desktop now; one showing it elsewhere goes, its windows staying here. */
    internal fun attach(activity: DesktopActivity) {
        val old = host
        host = activity
        starting = null
        main.removeCallbacks(gaveUp)
        notifications.cancel(NOTIFICATION)
        if (old != null && old !== activity) old.finishAndRemoveTask()
    }

    /** [activity] is going: if it was the desktop and isn't coming back, its windows get tasks of their own. */
    internal fun detach(activity: DesktopActivity) {
        if (host !== activity) return
        host = null
        if (activity.isChangingConfigurations) return
        handBack()
    }

    /** Every window in the desktop opens in a task of its own again, as on a phone. */
    private fun handBack() {
        val windows = tiling.windows
        if (windows.isEmpty()) return
        windows.forEach(tiling::forget)
        changed()
        // After the desktop's views let go of them; see WindowRenderer's owners.
        main.post { windows.filter { it in wm.appWindows }.forEach { w -> wm.handleOf(w)?.let(asTask) } }
    }

    private fun refreshMonitors() {
        val now =
            displays
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .filter { it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRIVATE == 0 }
                .map { Monitor(it.displayId, it.name.orEmpty(), allowedOn(it.displayId)) }
        if (now != monitors.toList()) {
            monitors.clear()
            monitors += now
        }
    }

    /** A monitor plugged in: the desktop opens on it, or is offered, as [DesktopStyle.EXTERNAL] says. */
    private fun arrived(monitor: Monitor) {
        if (!monitor.allowed || host?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true) return
        when (style[DesktopStyle.EXTERNAL]) {
            DesktopStyle.AUTO -> if (!start(monitor.id)) offer(monitor)
            DesktopStyle.ASK -> offer(monitor)
        }
    }

    /** A notification that opens the desktop on [monitor] when tapped. */
    private fun offer(monitor: Monitor) {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.desktop_notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(monitor.id).toBundle()
        val open =
            PendingIntent.getActivity(
                context,
                monitor.id,
                DesktopActivity.intent(context, monitor.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                options,
            )
        val notification =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.desktop_notification_title, monitor.name))
                .setContentText(context.getString(R.string.desktop_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        runCatching { notifications.notify(NOTIFICATION, notification) }
    }

    override fun onDisplayAdded(displayId: Int) {
        val before = monitors.map { it.id }.toSet()
        refreshMonitors()
        monitors.firstOrNull { it.id == displayId && it.id !in before }?.let(::arrived)
    }

    override fun onDisplayRemoved(displayId: Int) {
        refreshMonitors()
        if (monitors.isEmpty()) notifications.cancel(NOTIFICATION)
        if (starting == displayId) {
            starting = null
            main.removeCallbacks(gaveUp)
            handBack()
        }
        // Android would move the desktop onto the phone's screen; its windows go back to being tasks instead.
        host?.takeIf { it.displayId == displayId }?.finishAndRemoveTask()
    }

    override fun onDisplayChanged(displayId: Int) = refreshMonitors()

    private companion object {
        const val TAG = "desktop"
        const val CHANNEL = "desktop"
        const val NOTIFICATION = 0x6465736b
        const val LARGE_SCREEN_DP = 600
        const val START_TIMEOUT_MS = 5_000L
    }
}
