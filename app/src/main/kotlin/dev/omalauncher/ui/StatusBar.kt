package dev.omalauncher.ui

import android.content.Context
import android.util.Log

/**
 * Pulls down Android's notification shade, as stock launchers do on a swipe down (from Omalauncher+). The method is
 * hidden from the SDK but callable, behind the normal EXPAND_STATUS_BAR permission, which needs no prompt; a phone
 * that blocks it does nothing.
 */
fun expandNotificationShade(context: Context) {
    try {
        val service = context.getSystemService("statusbar") ?: return
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
    } catch (e: Exception) {
        Log.w("gestures", "cannot pull down the notification shade", e)
    }
}
