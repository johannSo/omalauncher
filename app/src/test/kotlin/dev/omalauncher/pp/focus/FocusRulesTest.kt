package dev.omalauncher.pp.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class FocusRulesTest {
    // 2026-10-30 is a Friday.
    private fun at(text: String) = LocalDateTime.parse(text)

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val work = FocusProfile("work", apps = setOf("com.slack", "linux:foot"), dnd = true, schedule = WeeklySchedule(weekdays, LocalTime.of(9, 0), LocalTime.of(17, 0)))
    private val night = FocusProfile("night", block = setOf("com.instagram.android"), schedule = WeeklySchedule(setOf(DayOfWeek.FRIDAY), LocalTime.of(22, 0), LocalTime.of(7, 0)))
    private val read = FocusProfile("read", apps = setOf("org.koreader"))

    @Test fun scheduleWindowsIncludingOvernight() {
        val s = work.schedule!!
        assertEquals(at("2026-10-30T09:00") to at("2026-10-30T17:00"), s.window(at("2026-10-30T09:00")))
        assertNull(s.window(at("2026-10-30T17:00")))
        assertNull(s.window(at("2026-10-31T10:00")))
        val n = night.schedule!!
        // Friday night runs into Saturday morning, though Saturday isn't one of its days.
        assertEquals(at("2026-10-30T22:00") to at("2026-10-31T07:00"), n.window(at("2026-10-31T03:00")))
        assertNull(n.window(at("2026-10-30T21:59")))
        assertNull(n.window(at("2026-11-01T03:00")))
        assertEquals(at("2026-11-02T09:00"), s.nextStart(at("2026-10-30T09:30")))
        assertEquals(at("2026-10-30T22:00"), n.nextStart(at("2026-10-30T12:00")))
    }

    @Test fun handSetProfileWinsOverScheduleUntilItsEnd() {
        val config = FocusConfig(listOf(work, read), active = "read", until = at("2026-10-30T10:00"))
        assertEquals("read", FocusRules.active(config, at("2026-10-30T09:30"))!!.profile.name)
        val after = FocusRules.active(config, at("2026-10-30T10:00"))!!
        assertEquals("work", after.profile.name)
        assertTrue(after.scheduled)
        assertEquals(at("2026-10-30T17:00"), after.until)
        assertNull(FocusRules.active(config, at("2026-10-30T18:00")))
    }

    @Test fun turningOffAScheduledProfileWaitsForItsNextWindow() {
        val config = FocusConfig(listOf(work))
        val off = FocusRules.turnOff(config, at("2026-10-30T11:00"))
        assertEquals(at("2026-10-30T17:00"), off.pausedUntil)
        assertNull(FocusRules.active(off, at("2026-10-30T16:59")))
        assertEquals("work", FocusRules.active(off, at("2026-11-02T09:00"))!!.profile.name)
        // Turned on by hand again, the pause goes.
        val on = FocusRules.turnOn(off, "WORK", 25, at("2026-10-30T12:00"))
        assertEquals("work", on.active)
        assertEquals(at("2026-10-30T12:25"), on.until)
        assertNull(on.pausedUntil)
        // Off outside any window leaves no pause behind.
        assertNull(FocusRules.turnOff(FocusConfig(listOf(work), active = "work"), at("2026-10-31T12:00")).pausedUntil)
    }

    @Test fun allowListsAndBlockLists() {
        assertTrue(FocusRules.allows(null, listOf("anything")))
        assertTrue(FocusRules.allows(work, listOf("com.slack")))
        assertTrue(FocusRules.allows(work, listOf("linux:foot")))
        assertFalse(FocusRules.allows(work, listOf("com.instagram.android")))
        assertFalse(FocusRules.allows(night, listOf("com.instagram.android")))
        assertTrue(FocusRules.allows(night, listOf("com.slack")))
        // A profile that lists nothing lets nothing through: it's a profile of no apps, not of all; one that blocks nothing, all.
        assertFalse(FocusRules.allows(FocusProfile("empty"), listOf("com.slack")))
        assertTrue(FocusRules.allows(FocusProfile("open", block = emptySet()), listOf("com.slack")))
    }

    @Test fun timersAndTheirGrants() {
        val config = FocusConfig(timers = listOf(AppTimer("com.instagram.android", 30)))
        val now = at("2026-10-30T12:00")
        val ids = listOf("com.instagram.android")
        assertEquals(Verdict.Allowed, FocusRules.verdict(config, now, ids, "com.instagram.android", { 29 * 60_000L }))
        assertEquals(Verdict.TimeUp(30, 30 * 60_000L), FocusRules.verdict(config, now, ids, "com.instagram.android", { 30 * 60_000L }))
        // One more minute moves the limit, and is spent like the rest.
        assertEquals(Verdict.Allowed, FocusRules.verdict(config, now, ids, "com.instagram.android", { 30 * 60_000L }, extraMinutes = 1))
        assertTrue(FocusRules.verdict(config, now, ids, "com.instagram.android", { 31 * 60_000L }, extraMinutes = 1) is Verdict.TimeUp)
        assertEquals(Verdict.Allowed, FocusRules.verdict(config, now, ids, "com.instagram.android", { 99 * 60_000L }, ignored = true))
        // No timer: usage isn't even read.
        assertEquals(Verdict.Allowed, FocusRules.verdict(config, now, listOf("com.slack"), "com.slack", { error("read") }))
    }

    @Test fun focusComesBeforeTimers() {
        val config = FocusConfig(listOf(work), timers = listOf(AppTimer("com.instagram.android", 30)))
        val now = at("2026-10-30T12:00")
        assertEquals(Verdict.Hidden("work"), FocusRules.verdict(config, now, listOf("com.instagram.android"), "com.instagram.android", { 0L }))
        assertEquals(Verdict.Allowed, FocusRules.verdict(config, now, listOf("com.instagram.android"), "com.instagram.android", { 0L }, once = true))
    }

    @Test fun nextChangeIsTheNearestBoundary() {
        val config = FocusConfig(listOf(work, night), active = "read", until = at("2026-10-30T18:30"))
        assertEquals(at("2026-10-30T17:00"), FocusRules.nextChange(config, at("2026-10-30T12:00")))
        assertEquals(at("2026-10-30T18:30"), FocusRules.nextChange(config, at("2026-10-30T17:00")))
        assertEquals(at("2026-10-30T22:00"), FocusRules.nextChange(config, at("2026-10-30T19:00")))
    }

    @Test fun barCommands() {
        val profiles = listOf(work, read, FocusProfile("deep work"))
        assertEquals(FocusCommand.Off, FocusRules.command("focus off", profiles))
        assertEquals(FocusCommand.On("work", null), FocusRules.command("  Focus  WORK ", profiles))
        assertEquals(FocusCommand.On("read", 25), FocusRules.command("focus read 25m", profiles))
        assertEquals(FocusCommand.On("deep work", 90), FocusRules.command("focus deep work 1h30", profiles))
        assertEquals(FocusCommand.On("read", null), FocusRules.command("focus re", profiles))
        // Not one profile, or not a command at all: search and the agent get it.
        assertNull(FocusRules.command("focus", profiles))
        assertNull(FocusRules.command("focus on the bug in main.rs", profiles))
        assertNull(FocusRules.command("refocus work", profiles))
        assertNull(FocusRules.command("focus d", profiles.map { it.copy(name = "d" + it.name) }))
    }

    @Test fun durations() {
        assertEquals(25, FocusRules.minutes("25m"))
        assertEquals(120, FocusRules.minutes("2h"))
        assertEquals(90, FocusRules.minutes("1h30m"))
        assertEquals(45, FocusRules.minutes("45"))
        assertNull(FocusRules.minutes("h"))
        assertNull(FocusRules.minutes("0"))
        assertNull(FocusRules.minutes("soon"))
    }
}
