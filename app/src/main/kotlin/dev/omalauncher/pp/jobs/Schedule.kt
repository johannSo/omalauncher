package dev.omalauncher.pp.jobs

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a job runs: cron's five fields (minute, hour, day of month, month, day of week), which every way of writing a
 * schedule comes down to. [parse] reads cron itself and the friendlier forms jobs.toml allows ("daily at 07:00",
 * "weekdays at 9", "every 15m"); [next] finds the run after a moment, in a time zone.
 */
class Schedule private constructor(
    /** What it was parsed from, as written. */
    val text: String,
    /** Usually one; "daily at 07:00, 19:30" takes one per minute. */
    private val crons: List<Cron>,
) {
    class Error(
        message: String,
    ) : Exception(message)

    /**
     * The first run strictly after [after] in [zone]; null if there's none in the next eight years (Feb 30th).
     *
     * Daylight saving time: a time the clock skips (02:30 as it jumps from 02:00 to 03:00) runs as the jump ends,
     * shifted by the gap (03:30), once. A time the clock shows twice (02:30 as it goes back from 03:00 to 02:00) runs
     * the first time only, unless the job runs every hour, which then runs in both of those hours.
     */
    fun next(
        after: Instant,
        zone: ZoneId,
    ): Instant? = crons.mapNotNull { it.next(after, zone) }.minOrNull()

    override fun equals(other: Any?) = other is Schedule && other.text == text

    override fun hashCode() = text.hashCode()

    override fun toString() = text

    private class Cron(
        private val minutes: List<Int>,
        private val hours: List<Int>,
        private val days: Set<Int>,
        private val months: Set<Int>,
        /** 0 is Sunday, as in cron. */
        private val weekdays: Set<Int>,
        /** Cron's rule: with both restricted, a day matches either; with one `*`, only the other decides. */
        private val anyDay: Boolean,
        private val anyWeekday: Boolean,
    ) {
        /** `*` hours: the job runs every hour the clock shows, so in both of the hours a change back to winter time repeats. */
        private val everyHour = hours.size == 24

        private fun matches(date: LocalDate): Boolean {
            if (date.monthValue !in months) return false
            val day = date.dayOfMonth in days
            val weekday = date.dayOfWeek.value % 7 in weekdays
            return when {
                anyDay && anyWeekday -> true
                anyDay -> weekday
                anyWeekday -> day
                else -> day || weekday
            }
        }

        fun next(
            after: Instant,
            zone: ZoneId,
        ): Instant? {
            val rules = zone.rules
            // From the day before the date the clock shows then: a run shifted out of a gap lands later than the local
            // time it's for, so one from the day before can still be the next.
            var date = LocalDateTime.ofInstant(after, zone).toLocalDate().minusDays(1)
            val end = date.plusYears(8)
            var best: Instant? = null
            // The earliest of a day's runs, not the first in the clock's order: in the hour a change back to winter time
            // repeats, 02:15 the first time comes before 02:00 the second. Where a zone goes back across midnight, a
            // day's last runs can even come after the next day's first, so the day after the first with any counts too.
            var last = end
            while (date <= last) {
                if (matches(date)) {
                    for (h in hours) {
                        for (m in minutes) {
                            val local = date.atTime(h, m)
                            val offsets = rules.getValidOffsets(local)
                            val instants =
                                when (offsets.size) {
                                    // In a gap: ofLocal moves it later by the gap's length.
                                    0 -> listOf(ZonedDateTime.ofLocal(local, zone, null).toInstant())
                                    1 -> listOf(local.toInstant(offsets[0]))
                                    // Shown twice: the earlier (the larger offset), and for every-hour jobs the later too.
                                    else -> {
                                        val first = local.toInstant(offsets.maxBy { it.totalSeconds })
                                        if (everyHour) listOf(first, local.toInstant(offsets.minBy { it.totalSeconds })) else listOf(first)
                                    }
                                }
                            for (t in instants) if (t > after && (best == null || t < best)) best = t
                        }
                    }
                    if (best != null && last == end) last = date.plusDays(1)
                }
                date = date.plusDays(1)
            }
            return best
        }
    }

    companion object {
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val WEEKDAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

        private val TIME = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
        private val INTERVAL = Regex("""every\s*(\d+)\s*(m|min|mins|minutes?|h|hours?)""")
        private val WORD = Regex("""[a-z]+""")

        /**
         * Reads a schedule; throws [Error] saying what's wrong. Case and extra spaces don't matter.
         *
         * - cron: `0 7 * * 1-5`, with lists, ranges, steps (`0-30/10`) and month and day names; `@hourly`, `@daily`,
         *   `@weekly`, `@monthly`, `@yearly`
         * - `hourly`, `hourly at :15`
         * - `daily at 07:00`, `daily at 7am, 19:30`, `every day at 7`
         * - `weekdays at 9`, `weekends at 10:00`, `mon,wed,fri at 18:30`, `sunday at 20:00`
         * - `every 15m` and `every 2h`: steps that divide an hour or a day, from the top of the hour or midnight
         */
        fun parse(text: String): Schedule {
            val s = text.trim().lowercase().replace(Regex("\\s+"), " ")
            if (s.isEmpty()) throw Error("the schedule is empty")
            val lines =
                when {
                    s.startsWith("@") -> listOf(alias(s))
                    s.split(' ').size == 5 && s.first().let { it.isDigit() || it == '*' } -> listOf(s)
                    else -> friendly(s)
                }
            val schedule = Schedule(text.trim(), lines.map(::cron))
            // Feb 30th, Apr 31st: never.
            if (schedule.next(Instant.EPOCH, ZoneId.of("UTC")) == null) throw Error("\"${text.trim()}\" never runs")
            return schedule
        }

        private fun alias(s: String) =
            when (s) {
                "@hourly" -> "0 * * * *"
                "@daily", "@midnight" -> "0 0 * * *"
                "@weekly" -> "0 0 * * 0"
                "@monthly" -> "0 0 1 * *"
                "@yearly", "@annually" -> "0 0 1 1 *"
                else -> throw Error("unknown schedule \"$s\": try @hourly, @daily, @weekly, @monthly or @yearly")
            }

        /** The friendly forms, as cron lines. */
        private fun friendly(s: String): List<String> {
            INTERVAL.matchEntire(s)?.let { m ->
                val n = m.groupValues[1].toInt()
                return listOf(
                    if (m.groupValues[2].startsWith("h")) {
                        if (n !in 1..24 || 24 % n != 0) throw Error("every ${n}h doesn't divide a day: use 1, 2, 3, 4, 6, 8, 12 or 24 hours")
                        if (n == 24) "0 0 * * *" else "0 */$n * * *"
                    } else {
                        if (n !in 1..60 || 60 % n != 0) throw Error("every ${n}m doesn't divide an hour: use 1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30 or 60 minutes")
                        if (n == 60) "0 * * * *" else "*/$n * * * *"
                    },
                )
            }
            if (s == "hourly") return listOf("0 * * * *")
            if (s.startsWith("hourly at ")) {
                val minute = s.removePrefix("hourly at ").trim().removePrefix(":").toIntOrNull()
                if (minute == null || minute !in 0..59) throw Error("hourly at takes the minute, like hourly at :15")
                return listOf("$minute * * * *")
            }
            if (s == "daily") return listOf("0 0 * * *")
            val at = s.indexOf(" at ")
            if (at < 0) throw Error("unknown schedule \"$s\": try daily at 07:00, weekdays at 9, hourly, every 15m or a cron line like 0 7 * * *")
            val weekdays = weekdays(s.substring(0, at).trim())
            val times = s.substring(at + 4).split(',').map { time(it.trim()) }
            // A line per minute: one line's hours and minutes cross, which would add runs nobody asked for.
            return times.groupBy({ it.second }, { it.first }).map { (minute, hours) -> "$minute ${hours.distinct().sorted().joinToString(",")} * * $weekdays" }
        }

        /** "7", "07:00", "7am", "7:30 pm" as hour and minute. */
        private fun time(t: String): Pair<Int, Int> {
            val m = TIME.matchEntire(t) ?: throw Error("\"$t\" isn't a time: write it like 07:00, 7:30 or 7am")
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            when (m.groupValues[3]) {
                "am" -> if (hour in 1..12) hour %= 12 else throw Error("\"$t\" isn't a time")
                "pm" -> if (hour in 1..12) hour = hour % 12 + 12 else throw Error("\"$t\" isn't a time")
            }
            if (hour !in 0..23 || minute !in 0..59) throw Error("\"$t\" isn't a time of day")
            return hour to minute
        }

        /** The days before "at" as cron's day-of-week field. */
        private fun weekdays(days: String): String =
            when (days) {
                "daily", "every day", "everyday" -> "*"
                "weekdays", "every weekday" -> "1-5"
                "weekends", "every weekend" -> "0,6"
                else -> {
                    val words = days.removePrefix("every ").removePrefix("on ").split(',', ' ').filter { it.isNotBlank() && it != "and" }
                    if (words.isEmpty()) throw Error("say which days before at: daily, weekdays, weekends or day names")
                    words.map { w -> weekday(w) ?: throw Error("\"$w\" isn't a day: use daily, weekdays, weekends or mon, tue, …") }.distinct().sorted().joinToString(",")
                }
            }

        /** A day's name, short or long, or a plural ("mondays"). */
        private fun weekday(word: String): Int? {
            if (!WORD.matches(word) || word.length < 3) return null
            val i = WEEKDAYS.indexOf(word.take(3))
            if (i < 0) return null
            val full = DayOfWeek.of(if (i == 0) 7 else i).name.lowercase()
            return i.takeIf { full.startsWith(word) || word == full + "s" }
        }

        private fun cron(line: String): Cron {
            val f = line.split(' ')
            if (f.size != 5) throw Error("a cron line has five fields: minute hour day month weekday")
            return Cron(
                minutes = field(f[0], 0..59, "minute").toList(),
                hours = field(f[1], 0..23, "hour").toList(),
                days = field(f[2], 1..31, "day of the month"),
                months = field(f[3], 1..12, "month", MONTHS, 1),
                // 7 is Sunday too.
                weekdays = field(f[4], 0..7, "weekday", WEEKDAYS, 0).map { it % 7 }.toSet(),
                anyDay = f[2] == "*" || f[2] == "?",
                anyWeekday = f[4] == "*" || f[4] == "?",
            )
        }

        /** One cron field: `*`, a number or name, `a-b`, any of those with a step (`/15`), and lists of them. Sorted. */
        private fun field(
            text: String,
            range: IntRange,
            what: String,
            names: List<String> = emptyList(),
            firstName: Int = 0,
        ): Set<Int> {
            fun value(v: String): Int {
                val n = v.toIntOrNull() ?: names.indexOf(v.take(3)).takeIf { it >= 0 && v.length >= 3 }?.plus(firstName)
                if (n == null || n !in range) throw Error("\"$v\" isn't a $what (${range.first}-${range.last})")
                return n
            }
            val out = sortedSetOf<Int>()
            for (part in text.split(',')) {
                if (part.isEmpty()) throw Error("an empty $what in \"$text\"")
                val span = part.substringBefore('/')
                val stepText = part.substringAfter('/', "").takeIf { '/' in part }
                val step = stepText?.let { it.toIntOrNull()?.takeIf { s -> s > 0 } ?: throw Error("\"$it\" isn't a step in \"$text\"") } ?: 1
                val (from, to) =
                    when {
                        span == "*" || span == "?" -> range.first to range.last
                        '-' in span -> span.split('-', limit = 2).let { value(it[0]) to value(it[1]) }
                        // "5/15": from 5 to the end, every 15.
                        stepText != null -> value(span) to range.last
                        else -> value(span).let { it to it }
                    }
                if (from > to) throw Error("\"$part\" runs backwards")
                for (i in from..to step step) out += i
            }
            return out
        }
    }
}
