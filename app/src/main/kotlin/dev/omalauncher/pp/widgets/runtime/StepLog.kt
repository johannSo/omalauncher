package dev.omalauncher.pp.widgets.runtime

import org.json.JSONObject
import java.time.LocalDate

/**
 * Today's steps, worked out from Android's step counter, which counts every step since the phone booted and starts over at 0
 * with each boot. Each reading adds what the counter moved since the one before, so a reboot loses only the steps between the
 * last reading and the shutdown, and today survives it. A new day starts at 0 and keeps the last [KEEP] days' totals.
 *
 * Readings only come while a widget shows the steps: the steps walked since the one before go to the day of the reading that
 * counts them, so after a night without one, the evening's last steps land in the morning.
 */
data class StepLog(
    /** The day [steps] were walked on, `2026-10-30`. */
    val day: String,
    val steps: Long,
    /** The counter at the last reading. */
    val counter: Long,
    /** Android's boot count at the last reading, -1 where the phone doesn't say. */
    val boot: Int,
    /** Earlier days' totals by day. */
    val days: Map<String, Long> = emptyMap(),
) {
    /** The steps walked on [day]: none yet on a day that has had no reading. */
    fun on(day: String) = if (day == this.day) steps else days[day]

    /** The [n] days up to [today], oldest first, each with its steps, null for a day nothing was counted on. */
    fun last(
        today: LocalDate,
        n: Int,
    ): List<Pair<String, Long?>> =
        (n - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong()).toString()
            // Today is 0 rather than unknown: the counter is being read.
            d to (on(d) ?: if (back == 0) 0L else null)
        }

    fun toJson(): JSONObject =
        JSONObject()
            .put("day", day)
            .put("steps", steps)
            .put("counter", counter)
            .put("boot", boot)
            .put("days", JSONObject(days))

    companion object {
        const val KEEP = 30

        /**
         * The log after the counter read [counter] on [day]. [boot] is Android's boot count; [bootedToday] says whether the phone
         * started on [day], so that the very first reading can count from the boot rather than from 0.
         */
        fun record(
            log: StepLog?,
            counter: Long,
            day: String,
            boot: Int,
            bootedToday: Boolean,
        ): StepLog {
            if (log == null) return StepLog(day, if (bootedToday) counter else 0, counter, boot)
            // The counter starts over at 0 as the phone boots: all it has counted since is new. A phone without a boot count
            // shows it by the counter going back.
            val rebooted = (boot != -1 && log.boot != -1 && boot != log.boot) || counter < log.counter
            val moved = if (rebooted) counter else counter - log.counter
            if (day == log.day) return log.copy(steps = log.steps + moved, counter = counter, boot = boot)
            val days = (log.days + (log.day to log.steps)).toSortedMap().entries.toList().takeLast(KEEP).associate { it.key to it.value }
            return StepLog(day, moved, counter, boot, days)
        }

        fun fromJson(o: JSONObject): StepLog {
            val days = o.optJSONObject("days")
            return StepLog(
                day = o.getString("day"),
                steps = o.getLong("steps"),
                counter = o.getLong("counter"),
                boot = o.optInt("boot", -1),
                days = days?.keys()?.asSequence()?.mapNotNull { k -> days.optLong(k, -1).takeIf { it >= 0 }?.let { k to it } }?.toMap().orEmpty(),
            )
        }
    }
}
