package dev.omalauncher.pp.focus

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dev.omalauncher.pp.App

/**
 * Focus mode and app timers for apps opened outside the launcher: from a notification, another app, Recents. Off
 * unless the user turns it on in Android's accessibility settings, which settings › focus explains first. It reads
 * only which app's window comes to the front (no window content, no keys), asks [Focus.check] as the launcher does,
 * and shows the time's-up screen over an app that may not be open; while an app with a timer is in front, it checks
 * again as the time runs out. The phone's own screens (System UI, settings, the phone app, the keyboard) are never
 * stopped, so it can always be turned off again.
 */
class FocusGuard : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val focus get() = (application as App).runtime.focus

    /** The app in front now, and the check waiting for its time to run out. */
    private var front: String? = null
    private val recheck = Runnable { front?.let(::checkFront) }

    /** Apps with a launcher entry, asked once each: only those are apps someone opens. */
    private val launchable = HashMap<String, Boolean>()

    /** Asked once while it runs: a keyboard or phone app picked meanwhile is let through when it's next started. */
    private val allowed by lazy { alwaysAllowed(this) }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == front || exempt(pkg)) return
        front = pkg
        main.removeCallbacks(recheck)
        focus.foreground(pkg)
        checkFront(pkg)
    }

    private fun checkFront(pkg: String) {
        val verdict = focus.check(pkg)
        if (verdict != Verdict.Allowed) {
            // Once it's let through, the screen's grant brings it back here through the next event.
            front = null
            focus.stop(pkg, verdict, open = null)
            return
        }
        // Still on its timer: look again as its time runs out, if it's in front then.
        val left = focus.left(pkg) ?: return
        main.postDelayed(recheck, left.coerceIn(5_000L, 3_600_000L))
    }

    /** The launcher itself, the phone's own screens, the keyboard and what isn't an app from a launcher are never stopped. */
    private fun exempt(pkg: String): Boolean {
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return true
        if (pkg in allowed) return true
        return launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null } == false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(recheck)
        super.onDestroy()
    }

    companion object {
        /** Settings, the phone app and the keyboard in use: what the user needs to call for help, or to turn this off. */
        private fun alwaysAllowed(context: Context): Set<String> {
            val pm = context.packageManager
            val settings = pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
            val dialer = runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
            val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            val imes = runCatching { context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName } }.getOrDefault(emptyList())
            return setOfNotNull(settings, dialer, keyboard, "com.android.settings") + imes
        }

        /** Whether the user turned it on in Android's accessibility settings. */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, FocusGuard::class.java)
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Android's accessibility settings, where it's turned on and off. */
        fun openSettings(context: Context) {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
