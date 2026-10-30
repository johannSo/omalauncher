package dev.omalauncher.pp.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class JobPlanTest {
    private val utc = ZoneId.of("UTC")
    private val daily = Job(id = "d", prompt = "p", schedule = Schedule.parse("daily at 07:00"))

    private fun t(s: String) = Instant.parse(s)

    @Test fun dueOnceItsTimeHasCome() {
        val after = t("2026-06-10T06:00:00Z")
        assertFalse(JobPlan.due(daily, after, t("2026-06-10T06:59:59Z"), utc))
        assertTrue(JobPlan.due(daily, after, t("2026-06-10T07:00:00Z"), utc))
        assertEquals(t("2026-06-10T07:00:00Z"), JobPlan.wake(daily, after, t("2026-06-10T06:30:00Z"), utc))
    }

    @Test fun missedRunsAreMadeUpOnce() {
        // Off for three days: due once, and once handled, not due again until the next 07:00.
        val after = t("2026-06-10T06:00:00Z")
        val now = t("2026-06-13T12:00:00Z")
        assertTrue(JobPlan.due(daily, after, now, utc))
        assertFalse(JobPlan.due(daily, now, now, utc))
        assertEquals(t("2026-06-14T07:00:00Z"), JobPlan.wake(daily, now, now, utc))
    }

    @Test fun aWaitingJobIsLookedAtAgainAtItsNextRun() {
        // Due at 07:00 and waiting for Wi-Fi: the alarm isn't for the past, but for tomorrow's run.
        val after = t("2026-06-10T06:00:00Z")
        val now = t("2026-06-10T08:00:00Z")
        assertTrue(JobPlan.due(daily, after, now, utc))
        assertEquals(t("2026-06-11T07:00:00Z"), JobPlan.wake(daily, after, now, utc))
    }

    @Test fun offAndManualJobsNeverComeDue() {
        val after = t("2026-06-10T06:00:00Z")
        val now = t("2026-06-12T06:00:00Z")
        assertFalse(JobPlan.due(daily.copy(enabled = false), after, now, utc))
        assertNull(JobPlan.wake(daily.copy(enabled = false), after, now, utc))
        assertFalse(JobPlan.due(daily.copy(schedule = null), after, now, utc))
        assertNull(JobPlan.wake(daily.copy(schedule = null), after, now, utc))
    }

    @Test fun aJobsOwnZone() {
        val tokyo = daily.copy(zone = "Asia/Tokyo")
        assertEquals(ZoneId.of("Asia/Tokyo"), JobPlan.zoneOf(tokyo, utc))
        assertEquals(t("2026-06-10T22:00:00Z"), JobPlan.wake(tokyo, t("2026-06-10T12:00:00Z"), t("2026-06-10T12:00:00Z"), utc))
        // One that isn't a zone falls back to the phone's.
        assertEquals(utc, JobPlan.zoneOf(daily.copy(zone = "Nowhere/Else"), utc))
    }

    @Test fun firstSeenOrClockPutBackStartsNow() {
        val now = t("2026-06-10T12:00:00Z")
        assertEquals(now, JobPlan.after(null, now))
        assertEquals(now, JobPlan.after(t("2026-06-11T12:00:00Z"), now))
        assertEquals(t("2026-06-09T12:00:00Z"), JobPlan.after(t("2026-06-09T12:00:00Z"), now))
    }

    @Test fun outcomes() {
        assertEquals(Outcome.Ok, Outcome.of(0, stopped = false))
        assertEquals(Outcome.Failed, Outcome.of(1, stopped = false))
        assertEquals(Outcome.TimedOut, Outcome.of(124, stopped = false))
        assertEquals(Outcome.Stopped, Outcome.of(143, stopped = true))
    }

    @Test fun statusAndHistoryRoundTrip() {
        val run = JobRun(1_000, 1_071, Outcome.Ok, 0, "all good", manual = true)
        assertEquals(listOf(run), JobRun.list(JobRun.listJson(listOf(run)).toString()))
        assertEquals(emptyList<JobRun>(), JobRun.list("not json"))
        val status = JobStatus(Activity.Running, next = 2_000, last = run, output = "out")
        val json = status.toJson(daily)
        assertEquals("running", json.getString("state"))
        assertEquals(71, json.getLong("duration"))
        assertEquals("daily at 07:00", json.getString("schedule"))
        // Read back: the last run and its output; what it's doing now isn't kept across a restart.
        val back = JobStatus.of(json.toString())!!
        assertEquals(run.copy(manual = false), back.last)
        assertEquals("out", back.output)
        assertEquals(Activity.Idle, back.activity)
        assertNull(JobStatus.of(JobStatus().toJson(daily).toString())!!.last)
    }
}
