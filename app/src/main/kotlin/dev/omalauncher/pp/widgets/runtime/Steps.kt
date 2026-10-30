package dev.omalauncher.pp.widgets.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import dev.omalauncher.pp.upgrade.Versioned
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Null
import dev.omalauncher.pp.widgets.lang.Num
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import dev.omalauncher.pp.widgets.lang.Value
import dev.omalauncher.pp.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The steps sensor batches what it counts this long, so a walk wakes the phone every few seconds, not every step. */
private const val BATCH_US = 10_000_000

/** How often a reading is written down while walking. One that isn't loses nothing: the next one counts from the last kept. */
private const val SAVE_MS = 60_000L

private const val VERSION = 1

private const val DAYS_SHOWN = 7

/**
 * `source s = android.steps`: today's steps from the phone's step counter, once the user allows physical activity
 * (`permission("steps")`). The counter counts since boot; [StepLog] turns it into days, kept in `files/steps.json` so today
 * survives a reboot and a restart. It's only read while a widget shows it.
 */
class Steps(
    private val context: Context,
) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val counter: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val file = File(context.filesDir, "steps.json")

    /** Reading, recording and saving one at a time. */
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private var log: StepLog? = null
    private var loaded = false
    private var savedAt = 0L

    private val granted get() = WidgetPermissions.granted(context, "steps")

    private fun log(): StepLog? {
        if (!loaded) {
            loaded = true
            log = Versioned.readFile(file, VERSION)?.let { runCatching { StepLog.fromJson(it) }.getOrNull() }
        }
        return log
    }

    /** What [log] knows, for a polled source or before the sensor's first reading. */
    fun value(): Value {
        val available = counter != null && granted
        val today = LocalDate.now()
        val log = if (available) synchronized(this) { log() } else null
        val days = log?.last(today, DAYS_SHOWN) ?: (DAYS_SHOWN - 1 downTo 0).map { today.minusDays(it.toLong()).toString() to null }
        return Obj(
            mapOf(
                "available" to bool(available),
                "sensor" to bool(counter != null),
                "today" to if (available) Num((log?.on(today.toString()) ?: 0L).toDouble()) else Null,
                "days" to Lst(days.map { (d, n) -> Obj(mapOf("date" to Str(d), "steps" to (n?.let { Num(it.toDouble()) } ?: Null))) }),
            ),
        )
    }

    private fun record(steps: Long) =
        synchronized(this) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val day = LocalDate.now(zone)
            val bootedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now - SystemClock.elapsedRealtime()), zone).toLocalDate()
            val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
            val before = log()
            val after = StepLog.record(before, steps, day.toString(), boot, bootedAt == day)
            log = after
            if (now - savedAt >= SAVE_MS || before?.day != after.day) {
                savedAt = now
                runCatching { Versioned.writeFile(file, VERSION, after.toJson()) }
            }
        }

    /** Listens to the counter while allowed and collected, and moves on to a new day at midnight with nothing walked yet. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<Value> =
        WidgetPermissions.changes
            .map { counter != null && granted }
            .distinctUntilChanged()
            .flatMapLatest { if (it) readings() else flowOf(Unit) }
            .map { withContext(worker) { value() } }
            .distinctUntilChanged()

    private fun readings(): Flow<Unit> =
        callbackFlow {
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val steps = event.values.firstOrNull()?.toLong() ?: return
                        launch {
                            record(steps)
                            trySend(Unit)
                        }
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {}
                }
            sensors.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US)
            trySend(Unit)
            val midnight =
                launch {
                    while (true) {
                        val now = LocalDateTime.now()
                        delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 1000)
                        trySend(Unit)
                    }
                }
            awaitClose {
                midnight.cancel()
                sensors.unregisterListener(listener)
                // What was counted since the last save, kept for the next start.
                synchronized(this@Steps) { log?.let { runCatching { Versioned.writeFile(file, VERSION, it.toJson()) } } }
            }
        }
            // Readings are recorded and the log saved off the main thread; the sensor calls back on the main looper anyway.
            .flowOn(worker)
}
