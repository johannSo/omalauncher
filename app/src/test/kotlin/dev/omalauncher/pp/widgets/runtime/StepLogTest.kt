package dev.omalauncher.pp.widgets.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StepLogTest {
    private val day = "2026-10-30"

    @Test fun firstReadingCountsFromTheBootOnlyIfItWasToday() {
        assertEquals(0L, StepLog.record(null, 5000, day, 3, bootedToday = false).steps)
        assertEquals(5000L, StepLog.record(null, 5000, day, 3, bootedToday = true).steps)
    }

    @Test fun readingsAddWhatTheCounterMoved() {
        var log = StepLog.record(null, 1000, day, 3, false)
        log = StepLog.record(log, 1200, day, 3, false)
        log = StepLog.record(log, 1250, day, 3, false)
        assertEquals(250L, log.steps)
        assertEquals(1250L, log.counter)
    }

    @Test fun aRebootKeepsTodayAndCountsFromZero() {
        var log = StepLog.record(null, 1000, day, 3, false)
        log = StepLog.record(log, 1400, day, 3, false)
        // The counter went back: rebooted, everything since is new.
        log = StepLog.record(log, 50, day, 4, true)
        assertEquals(450L, log.steps)
        // Rebooted and already past where it was: the boot count tells.
        log = StepLog.record(log, 60, day, 4, true)
        log = StepLog.record(log, 2000, day, 5, true)
        assertEquals(460L + 2000, log.steps)
        // A phone without a boot count: only the counter going back says so.
        log = StepLog.record(log.copy(boot = -1), 10, day, -1, true)
        assertEquals(2470L, log.steps)
    }

    @Test fun aNewDayStartsOverAndKeepsTheLastOnes() {
        var log = StepLog.record(null, 0, "2026-10-29", 3, true)
        log = StepLog.record(log, 8000, "2026-10-29", 3, true)
        log = StepLog.record(log, 8100, day, 3, false)
        assertEquals(day, log.day)
        assertEquals(100L, log.steps)
        assertEquals(mapOf("2026-10-29" to 8000L), log.days)
        val week = log.last(LocalDate.parse(day), 3)
        assertEquals(listOf("2026-10-28" to null, "2026-10-29" to 8000L, day to 100L), week)
        // A day without a reading yet is 0 today, unknown before.
        assertEquals(0L, log.last(LocalDate.parse("2026-10-31"), 1).single().second)
        assertNull(log.on("2026-10-28"))
    }

    @Test fun keepsAMonth() {
        var log = StepLog.record(null, 0, "2026-01-01", 1, true)
        var date = LocalDate.parse("2026-01-01")
        repeat(40) {
            date = date.plusDays(1)
            log = StepLog.record(log, log.counter + 10, date.toString(), 1, false)
        }
        assertEquals(StepLog.KEEP, log.days.size)
        assertEquals(date.minusDays(1).toString(), log.days.keys.max())
    }

    @Test fun roundTripsThroughJson() {
        var log = StepLog.record(null, 0, "2026-10-29", 3, true)
        log = StepLog.record(log, 8000, day, 3, false)
        assertEquals(log, StepLog.fromJson(log.toJson()))
    }
}
