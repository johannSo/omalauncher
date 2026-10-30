package dev.omalauncher.pp.desktop

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.omalauncher.pp.App
import dev.omalauncher.pp.InstantActivity
import dev.omalauncher.pp.R
import dev.omalauncher.pp.theme.DesktopStyle
import dev.omalauncher.pp.ui.ImagePicker
import dev.omalauncher.pp.ui.MicPermission
import dev.omalauncher.pp.ui.Palette

/**
 * The tiling desktop's window (see [Desktop]): on a monitor, or on this screen in place of a task per Linux window.
 * Android also starts it as the home of a display that gets a launcher of its own (SECONDARY_HOME), while
 * OmaLauncher++ is the home app.
 *
 * Its keys are Hyprland's, with Super: 1-9 switch workspace, Shift+1-9 move the focused window there, the arrows
 * move the focus and Shift+arrows swap windows, F fullscreen, V floating; W closes and Enter opens a terminal as in
 * any Linux window. Everything else goes to the focused window.
 */
class DesktopActivity : InstantActivity() {
    private val desktop get() = runtime.desktop
    private val imagePicker = ImagePicker(this) { runtime }
    private val mic = MicPermission(this)

    /** Super is down: floating windows then move with the mouse, and resize with its right button, as in Hyprland. */
    internal var superHeld by mutableStateOf(false)
        private set

    /** Where windows go, in pixels, as last laid out; the keys that move focus by place go by it. */
    internal var area = Box(0, 0, 1, 1)

    /** The display this is on. */
    val displayId: Int
        get() =
            if (Build.VERSION.SDK_INT >= 30) {
                display?.displayId ?: Display.DEFAULT_DISPLAY
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.displayId
            }

    /**
     * View pixels per Linux logical pixel: on this screen the output's, as for every other window (0); on a monitor
     * [DesktopStyle.SCALE], or else the largest whole fraction of the output's scale the monitor's density reaches, so
     * apps' pixels shrink by whole steps and are averaged rather than picked.
     */
    internal val windowScale: Int
        get() {
            if (displayId == Display.DEFAULT_DISPLAY) return 0
            val output = runtime.wm.scale
            runtime.style.style[DesktopStyle.SCALE].takeIf { it > 0 }?.let { return it }
            val density = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            return (density downTo 1).first { output % it == 0 }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started as a display's home during a backup, when the process has a plain Application and no runtime.
        if (application !is App) {
            finish()
            return
        }
        desktop.attach(this)
        setInstantContent {
            DesktopScreen(runtime, desktop, this)
            // On this screen Back leaves it running in Recents, like a Linux window's; a monitor has nowhere to go back to.
            BackHandler { if (displayId == Display.DEFAULT_DISPLAY) backInstantly() }
            val title = stringResource(R.string.desktop_title)
            LaunchedEffect(title, Palette.accent) { describeTask(title, null) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (application is App) runtime.screenStarted()
    }

    override fun onResume() {
        super.onResume()
        runtime.keyboard.imagePicker = imagePicker
        runtime.dictation.askMic = mic::ask
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) syncFocus() else superHeld = false
    }

    /** Keys held as the window goes, like Super, would stay down for it: Android sends their lift elsewhere. */
    override fun onPause() {
        super.onPause()
        superHeld = false
        runtime.keyboard.releaseHeld()
        if (runtime.keyboard.imagePicker === imagePicker) runtime.keyboard.imagePicker = null
        runtime.dictation.askMic = null
    }

    override fun onStop() {
        super.onStop()
        runtime.screenStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (application is App) desktop.detach(this)
    }

    /** Brings this desktop's task to the front of this screen, as a new window opens in it. */
    fun toFront() {
        val tasks = getSystemService(ActivityManager::class.java).appTasks
        runCatching { tasks.firstOrNull { it.taskInfo?.taskId == taskId }?.moveToFront() }
    }

    /**
     * Gives the keys to the desktop's focused window: this window has them now. Linux has one keyboard focus, and the
     * phone's screen beside a monitor may have taken it for a window or home of its own.
     */
    internal fun syncFocus() {
        val w = desktop.tiling.focused()?.takeIf { it in runtime.wm.appWindows }
        if (runtime.wm.focused !== w) runtime.wm.focus(w)
    }

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT) superHeld = down
        if (down && event.isMetaPressed && binding(event)) return true
        if (down && event.repeatCount == 0) syncFocus()
        if (runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** The desktop's own Super bindings; false for a key that isn't one, which then goes on like any other. */
    private fun binding(event: KeyEvent): Boolean {
        val tiling = desktop.tiling
        val focused = tiling.focused()
        val first = event.repeatCount == 0
        val workspace = event.keyCode - KeyEvent.KEYCODE_1
        val direction = ARROWS[event.keyCode]
        when {
            workspace in 0 until tiling.count ->
                if (!first) {
                    Unit
                } else if (event.isShiftPressed) {
                    focused?.let { tiling.moveTo(it, workspace) }
                } else {
                    tiling.switchTo(workspace)
                }
            direction != null -> if (event.isShiftPressed) tiling.swapToward(direction, area) else tiling.focusToward(direction, area)
            event.keyCode == KeyEvent.KEYCODE_F -> if (first) focused?.let(tiling::toggleFullscreen)
            event.keyCode == KeyEvent.KEYCODE_V -> if (first) focused?.let { tiling.toggleFloating(it, area) }
            else -> return false
        }
        desktop.changed()
        syncFocus()
        return true
    }

    companion object {
        private const val EXTRA_DISPLAY = "display"

        private val ARROWS =
            mapOf(
                KeyEvent.KEYCODE_DPAD_LEFT to Direction.Left,
                KeyEvent.KEYCODE_DPAD_RIGHT to Direction.Right,
                KeyEvent.KEYCODE_DPAD_UP to Direction.Up,
                KeyEvent.KEYCODE_DPAD_DOWN to Direction.Down,
            )

        /** Opens the desktop in a task of its own; [displayId] is where it's meant to go, which the launch options say. */
        fun intent(
            context: Context,
            displayId: Int,
        ): Intent =
            Intent(context, DesktopActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_DISPLAY, displayId)
    }
}
