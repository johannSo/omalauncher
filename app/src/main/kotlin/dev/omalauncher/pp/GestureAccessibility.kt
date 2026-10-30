package dev.omalauncher.pp

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The accessibility service that lets home's gestures lock the phone and show recent apps: Android lets only an
 * accessibility service do either for another app's screen (GLOBAL_ACTION_LOCK_SCREEN, which turns the screen off as
 * the power button does, and GLOBAL_ACTION_RECENTS). The user turns it on in Android's settings, after settings ›
 * gestures says what it does. It asks for no events and can't read the screen (res/xml/gesture_accessibility.xml):
 * it only acts when a gesture asks it to.
 */
class GestureAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        service = this
        connectedFlow.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (service === this) service = null
        connectedFlow.value = false
    }

    companion object {
        private const val TAG = "gestures"

        @Volatile private var service: GestureAccessibility? = null

        private val connectedFlow = MutableStateFlow(false)

        /** Whether Android has the service running, so [lock] and [recents] work; it follows the user turning it on or off. */
        val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

        /** Locks the phone, its screen going off; false while the service isn't on. */
        fun lock() = perform(GLOBAL_ACTION_LOCK_SCREEN)

        /** Shows Android's recent apps; false while the service isn't on. */
        fun recents() = perform(GLOBAL_ACTION_RECENTS)

        private fun perform(action: Int): Boolean {
            val s = service ?: return false
            return runCatching { s.performGlobalAction(action) }.onFailure { Log.w(TAG, "cannot perform global action $action", it) }.getOrDefault(false)
        }

        /** Android's list of accessibility services, where the user turns this one on. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "cannot open accessibility settings", it) }
        }

        /**
         * The launcher's app info: since Android 13 a sideloaded app's accessibility service is a restricted setting, greyed
         * out until "allow restricted settings" in this screen's ⋮ menu.
         */
        fun openAppInfo(context: Context) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "cannot open app info", it) }
        }
    }
}
