package dev.omalauncher.pp.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    private fun at(
        local: String,
        zone: ZoneId,
    ): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    /** The next [n] runs after [from] (local time in [zone]), as local times there with their offsets. */
    private fun runs(
        schedule: String,
        from: String,
        zone: ZoneId,
        n: Int = 1,
    ): List<String> {
        val s = Schedule.parse(schedule)
        var t = at(from, zone)
        return (1..n).map {
            t = s.next(t, zone)!!
            ZonedDateTime.ofInstant(t, zone).toOffsetDateTime().toString()
        }
    }

    private fun invalid(schedule: String) {
        try {
            Schedule.parse(schedule)
            fail("\"$schedule\" parsed")
        } catch (e: Schedule.Error) {
            assertTrue(e.message!!, e.message!!.isNotEmpty())
        }
    }

    @Test fun dailyAtATime() {
        assertEquals(listOf("2026-06-10T07:00+02:00", "2026-06-11T07:00+02:00"), runs("daily at 07:00", "2026-06-10T06:59", berlin, 2))
        // Strictly after: a run at the very moment isn't the next.
        assertEquals(listOf("2026-06-11T07:00+02:00"), runs("daily at 07:00", "2026-06-10T07:00", berlin))
        assertEquals(listOf("2026-06-10T07:00+02:00"), runs("every day at 7am", "2026-06-10T00:00", berlin))
        assertEquals(listOf("2026-06-10T19:30+02:00"), runs("Daily at 7:30 PM", "2026-06-10T08:00", berlin))
        assertEquals(listOf("2026-06-11T00:00+02:00"), runs("daily", "2026-06-10T08:00", berlin))
    }

    @Test fun severalTimesADay() {
        assertEquals(
            listOf("2026-06-10T07:00+02:00", "2026-06-10T19:00+02:00", "2026-06-11T07:00+02:00"),
            runs("daily at 07:00, 19:00", "2026-06-10T06:00", berlin, 3),
        )
        // Different minutes don't cross into 07:45 and 19:00.
        assertEquals(
            listOf("2026-06-10T07:00+02:00", "2026-06-10T19:45+02:00", "2026-06-11T07:00+02:00"),
            runs("daily at 7:00, 19:45", "2026-06-10T06:00", berlin, 3),
        )
    }

    @Test fun weekdaysAndWeekends() {
        // 2026-06-12 is a Friday.
        assertEquals(listOf("2026-06-12T09:00+02:00", "2026-06-15T09:00+02:00"), runs("weekdays at 9", "2026-06-12T08:00", berlin, 2))
        assertEquals(listOf("2026-06-13T10:30+02:00", "2026-06-14T10:30+02:00", "2026-06-20T10:30+02:00"), runs("weekends at 10:30", "2026-06-12T08:00", berlin, 3))
        assertEquals(listOf("2026-06-12T18:30+02:00", "2026-06-15T18:30+02:00", "2026-06-17T18:30+02:00"), runs("mon,wed,fri at 18:30", "2026-06-12T08:00", berlin, 3))
        assertEquals(listOf("2026-06-14T20:00+02:00"), runs("sunday at 20:00", "2026-06-12T08:00", berlin))
        assertEquals(listOf("2026-06-15T08:00+02:00"), runs("Mondays at 8", "2026-06-12T08:00", berlin))
        assertEquals(listOf("2026-06-16T08:00+02:00"), runs("on tues and thurs at 8", "2026-06-12T08:00", berlin))
    }

    @Test fun hourlyAndSteps() {
        assertEquals(listOf("2026-06-10T09:00Z", "2026-06-10T10:00Z"), runs("hourly", "2026-06-10T08:10", utc, 2))
        assertEquals(listOf("2026-06-10T08:15Z", "2026-06-10T09:15Z"), runs("hourly at :15", "2026-06-10T08:10", utc, 2))
        assertEquals(listOf("2026-06-10T08:15Z", "2026-06-10T08:30Z"), runs("every 15m", "2026-06-10T08:10", utc, 2))
        assertEquals(listOf("2026-06-10T10:00Z", "2026-06-10T12:00Z"), runs("every 2h", "2026-06-10T08:10", utc, 2))
        assertEquals(listOf("2026-06-11T00:00Z"), runs("every 24h", "2026-06-10T08:10", utc))
    }

    @Test fun cron() {
        assertEquals(listOf("2026-06-12T07:00Z", "2026-06-15T07:00Z"), runs("0 7 * * 1-5", "2026-06-11T08:00", utc, 2))
        assertEquals(listOf("2026-06-10T08:20Z", "2026-06-10T08:30Z", "2026-06-10T09:00Z"), runs("0-30/10 * * * *", "2026-06-10T08:15", utc, 3))
        assertEquals(listOf("2026-07-01T00:00Z"), runs("@monthly", "2026-06-10T08:00", utc))
        assertEquals(listOf("2027-01-01T00:00Z"), runs("@yearly", "2026-06-10T08:00", utc))
        assertEquals(listOf("2026-06-14T00:00Z"), runs("@weekly", "2026-06-10T08:00", utc))
        // Names, and 7 as Sunday.
        assertEquals(listOf("2026-12-06T12:00Z"), runs("0 12 * dec sun", "2026-06-10T08:00", utc))
        assertEquals(listOf("2026-06-14T12:00Z"), runs("0 12 * * 7", "2026-06-10T08:00", utc))
        // Both day fields set: either matches (the 13th, or the Monday the 15th).
        assertEquals(listOf("2026-06-13T06:00Z", "2026-06-15T06:00Z"), runs("0 6 13 * mon", "2026-06-10T08:00", utc, 2))
        // Feb 29th: only in leap years.
        assertEquals(listOf("2028-02-29T00:00Z"), runs("0 0 29 2 *", "2026-06-10T08:00", utc))
    }

    @Test fun mistakesSayWhat() {
        invalid("")
        invalid("sometimes")
        invalid("daily at 25:00")
        invalid("daily at 7:61")
        invalid("funday at 7")
        invalid("every 7m")
        invalid("every 5h")
        invalid("hourly at :75")
        invalid("60 * * * *")
        invalid("0 7 * *")
        invalid("0 7 * * 1-8")
        invalid("0 7 5-1 * *")
        invalid("*/0 * * * *")
        invalid("@fortnightly")
        // Feb 30th never comes.
        invalid("0 0 30 2 *")
    }

    @Test fun timeZones() {
        val s = Schedule.parse("daily at 07:00")
        val from = Instant.parse("2026-06-10T12:00:00Z")
        assertEquals(Instant.parse("2026-06-11T05:00:00Z"), s.next(from, berlin))
        assertEquals(Instant.parse("2026-06-11T11:00:00Z"), s.next(from, newYork))
        assertEquals(Instant.parse("2026-06-11T07:00:00Z"), s.next(from, utc))
        // Kathmandu's +05:45, and a weekday seen from a zone where it's already the next day.
        assertEquals(Instant.parse("2026-06-11T01:15:00Z"), s.next(from, ZoneId.of("Asia/Kathmandu")))
        assertEquals(Instant.parse("2026-06-12T17:00:00Z"), Schedule.parse("sat at 07:00").next(from, ZoneId.of("Pacific/Kiritimati")))
    }

    @Test fun springForwardRunsWhenTheClockJumps() {
        // Berlin, 2026-03-29: 02:00 becomes 03:00. 02:30 doesn't exist; it runs at 03:30, once.
        assertEquals(listOf("2026-03-28T02:30+01:00", "2026-03-29T03:30+02:00", "2026-03-30T02:30+02:00"), runs("daily at 02:30", "2026-03-28T00:00", berlin, 3))
        // An hourly job: 01:30, then 03:30 (for 02:30) and not again for 03:30.
        assertEquals(
            listOf("2026-03-29T01:30+01:00", "2026-03-29T03:30+02:00", "2026-03-29T04:30+02:00"),
            runs("30 * * * *", "2026-03-29T01:00", berlin, 3),
        )
        // A time after the gap is untouched.
        assertEquals(listOf("2026-03-29T07:00+02:00"), runs("daily at 07:00", "2026-03-29T00:00", berlin))
        // New York, 2026-03-08 at 02:00.
        assertEquals(listOf("2026-03-08T03:15-04:00"), runs("daily at 2:15", "2026-03-08T00:00", newYork))
    }

    @Test fun fallBackRunsOnceUnlessEveryHour() {
        // Berlin, 2026-10-25: 03:00 goes back to 02:00, so 02:30 comes twice; a daily job runs the first time only.
        assertEquals(listOf("2026-10-25T02:30+02:00", "2026-10-26T02:30+01:00"), runs("daily at 02:30", "2026-10-25T00:00", berlin, 2))
        // An every-hour job runs in both 02:xx hours.
        assertEquals(
            listOf("2026-10-25T01:30+02:00", "2026-10-25T02:30+02:00", "2026-10-25T02:30+01:00", "2026-10-25T03:30+01:00"),
            runs("30 * * * *", "2026-10-25T01:00", berlin, 4),
        )
        assertEquals(4, runs("every 15m", "2026-10-25T02:00", berlin, 8).count { it.startsWith("2026-10-25T02:") && it.endsWith("+01:00") })
        // From the second 02:10, the daily job's 02:30 has had its run.
        val secondTwoTen = LocalDateTime.parse("2026-10-25T02:10").atZone(berlin).withLaterOffsetAtOverlap().toInstant()
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"), Schedule.parse("daily at 02:30").next(secondTwoTen, berlin))
    }
}
