package dev.omalauncher.pp.jobs

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.omalauncher.pp.App
import dev.omalauncher.pp.linux.LinuxState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** The one alarm that wakes the launcher for the next job due; see [Jobs] for why an alarm. */
object JobAlarm {
    const val ACTION = "dev.omalauncher.pp.action.RUN_JOBS"

    /** What was last set, so a check that changes nothing doesn't call AlarmManager again. Main thread. */
    private var set: Pair<Long?, Boolean>? = null

    /**
     * Whether alarms go off on time while the phone sleeps. Android 12 grants exact alarms; Android 13 and later ask
     * the user (settings › apps › alarms & reminders), and without them a job can run some minutes late in Doze.
     */
    fun exact(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Wakes the launcher at [at], or no more with null; replaces the alarm set before. */
    fun set(
        context: Context,
        at: Instant?,
    ) {
        val exact = exact(context)
        val wanted = at?.toEpochMilli() to exact
        if (wanted == set) return
        set = wanted
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 0, Intent(context, JobReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        when {
            at == null -> am.cancel(intent)
            // Through Doze, on time; and it lets the launcher start Linux's foreground service from the background.
            exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
        Log.i("jobs", "next alarm: ${at ?: "none"}${if (at != null && !exact) " (inexact)" else ""}")
    }

    /** Forgets what was set: after it went off, a reboot, an update, or exact alarms granted, the alarm is set again whatever it was. */
    fun reset() {
        set = null
    }
}

/**
 * The jobs' alarm going off, and what makes Android drop it or its time mean something else: a reboot, an update of
 * the app, the clock or the time zone changed, exact alarms granted. Each looks at the jobs again: what's due runs (the
 * launcher and Linux start for it if they aren't running), and the next alarm is set.
 */
class JobReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Ours has gone off, so there's none set; the others drop it or change what its time means.
        JobAlarm.reset()
        val runtime = (context.applicationContext as App).runtime
        runtime.jobs.wake()
        // Kept a few seconds, while Linux starts for a job: the alarm lets its foreground service start from the
        // background, and after that the service keeps the process.
        val pending = goAsync()
        MainScope().launch {
            withTimeoutOrNull(WAIT_MS) { runtime.bootstrap.state.first { it != LinuxState.Checking } }
            pending.finish()
        }
    }

    private companion object {
        const val WAIT_MS = 8_000L
    }
}
