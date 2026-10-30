package dev.omalauncher.pp.focus

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's screen time per app, from Android's usage events: what `android.usage` shows and app timers count. Reading
 * them is a special access the user grants in Android's settings ([settingsIntent]), asked for only where a widget or
 * a timer needs it.
 */
class ScreenTime(
    private val context: Context,
) {
    private val manager = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)

    /** The last tally and when it was made: a launch and a widget a moment apart read it once. */
    @Volatile private var cached: Pair<Long, Map<String, AppTally>>? = null

    /** Whether the user let the launcher read usage. */
    fun granted(): Boolean {
        val mode = runCatching { appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) }.getOrDefault(AppOpsManager.MODE_ERRORED)
        // Default defers to the permission, which device policy may grant.
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT && context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    /** Android's usage access screen, at this launcher where the phone can open it there. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings() {
        // Some phones only open the list, without a package.
        runCatching { context.startActivity(settingsIntent()) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "no usage access settings", it) }
    }

    /**
     * Today's time in front and opens per package, since local midnight; null without usage access. Reads a day of
     * events: not on the main thread, unless for one launch.
     */
    fun today(now: Long = System.currentTimeMillis()): Map<String, AppTally>? {
        cached?.let { (at, tally) -> if (now - at in 0 until FRESH_MS && sameDay(at, now)) return tally }
        if (!granted()) return null
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = ArrayList<UsageEvent>()
        try {
            val read = manager.queryEvents(start, now) ?: return null
            val e = UsageEvents.Event()
            while (read.hasNextEvent()) {
                read.getNextEvent(e)
                val kind =
                    when (e.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Kind.Resumed
                        UsageEvents.Event.ACTIVITY_PAUSED -> UsageEvent.Kind.Paused
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEvent.Kind.ScreenOff
                        else -> continue
                    }
                events += UsageEvent(e.timeStamp, kind, e.packageName.orEmpty(), e.className.orEmpty())
            }
        } catch (e: SecurityException) {
            // Taken back since granted() asked.
            Log.w(TAG, "usage access went", e)
            return null
        }
        val own = context.packageName
        // The home screen and the time's-up screen are the launcher's, not use of an app; Linux windows and settings are.
        val tally = UsageTally.tally(events, start, now) { it.pkg == own && (it.cls.endsWith(".MainActivity") || it.cls.endsWith(".TimesUpActivity")) }
        cached = now to tally
        return tally
    }

    /** How long [pkg] was in front today, in milliseconds; 0 without usage access. */
    fun usedToday(pkg: String): Long = today()?.get(pkg)?.ms ?: 0L

    /** Forgets the last tally, so the next read is the phone's now. */
    fun invalidate() {
        cached = null
    }

    private fun sameDay(
        a: Long,
        b: Long,
    ): Boolean {
        val zone = ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    }

    private companion object {
        const val TAG = "ScreenTime"
        const val FRESH_MS = 5_000L
    }
}
