package dev.omalauncher.pp.focus

import dev.omalauncher.pp.focus.UsageEvent.Kind.Paused
import dev.omalauncher.pp.focus.UsageEvent.Kind.Resumed
import dev.omalauncher.pp.focus.UsageEvent.Kind.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTallyTest {
    private val home = "dev.omalauncher.pp"

    private fun e(
        t: Long,
        kind: UsageEvent.Kind,
        pkg: String = "",
        cls: String = "Main",
    ) = UsageEvent(t, kind, pkg, cls)

    private fun tally(
        vararg events: UsageEvent,
        start: Long = 0,
        end: Long = 1000,
    ) = UsageTally.tally(events.toList(), start, end) { it.pkg == home }

    @Test fun countsTimeInFrontAndOpens() {
        val t =
            tally(
                e(10, Resumed, "a"),
                e(40, Paused, "a"),
                e(40, Resumed, home),
                e(50, Paused, home),
                e(50, Resumed, "a"),
                e(70, Paused, "a"),
                e(70, Resumed, "b"),
                e(100, Paused, "b"),
            )
        assertEquals(AppTally(50, 2), t["a"])
        assertEquals(AppTally(30, 1), t["b"])
        // The home screen isn't anyone's use.
        assertEquals(null, t[home])
    }

    @Test fun anAppsSecondActivityIsntAnotherOpen() {
        val t = tally(e(10, Resumed, "a", "One"), e(20, Resumed, "a", "Two"), e(30, Paused, "a", "One"), e(60, Paused, "a", "Two"))
        assertEquals(AppTally(50, 1), t["a"])
    }

    @Test fun theScreenGoingOffEndsTheTimeAndComingBackIsntAnOpen() {
        val t = tally(e(10, Resumed, "a"), e(30, ScreenOff), e(35, Paused, "a"), e(100, Resumed, "a"), e(120, Paused, "a"))
        assertEquals(AppTally(40, 1), t["a"])
    }

    @Test fun anAppInFrontAcrossMidnightCountsFromTheDaysStartAndUpToNow() {
        val t = tally(e(150, Paused, "a"), e(150, Resumed, "b"), start = 100, end = 300)
        assertEquals(AppTally(50, 0), t["a"])
        assertEquals(AppTally(150, 1), t["b"])
    }

    @Test fun aPauseAfterOneAlreadyCountedAddsNothing() {
        val t = tally(e(10, Resumed, "a"), e(20, Paused, "a"), e(25, Paused, "a"))
        assertEquals(AppTally(10, 1), t["a"])
    }
}
