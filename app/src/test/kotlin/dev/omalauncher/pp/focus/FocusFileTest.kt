package dev.omalauncher.pp.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class FocusFileTest {
    @Test fun roundTrips() {
        val config =
            FocusConfig(
                profiles =
                    listOf(
                        FocusProfile("work", apps = setOf("com.slack", "linux:foot"), dnd = true, schedule = WeeklySchedule(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(9, 0), LocalTime.of(17, 30))),
                        FocusProfile("evening", block = setOf("com.instagram.android")),
                        FocusProfile("nothing"),
                        FocusProfile("blocks nothing", block = emptySet()),
                    ),
                timers = listOf(AppTimer("com.instagram.android", 30), AppTimer("com.reddit.frontpage", 15)),
                active = "work",
                until = LocalDateTime.parse("2026-10-30T17:00"),
            )
        val text = FocusFile.write(config)
        val back = FocusFile.read(text)
        assertEquals(emptyList<String>(), back.problems)
        assertEquals(config, back.config)
        assertTrue(text, text.contains("[[profile]]") && text.contains("[[timer]]") && text.contains("until = \"2026-10-30 17:00\""))
    }

    @Test fun anEmptyConfigWritesAndReadsBack() {
        val back = FocusFile.read(FocusFile.write(FocusConfig()))
        assertEquals(FocusConfig(), back.config)
        assertEquals(emptyList<String>(), back.problems)
    }

    @Test fun skipsWhatItCantReadAndSaysWhy() {
        val back =
            FocusFile.read(
                """
                active = "gone"
                [[profile]]
                name = "work"
                days = ["mon", "someday"]
                from = "9:00"
                to = "noon"
                [[profile]]
                name = "Work"
                [[profile]]
                name = "off"
                [[timer]]
                app = "com.x"
                minutes = 0
                [[timer]]
                minutes = 5
                [[timer]]
                app = "com.y"
                minutes = 10
                """.trimIndent(),
            )
        assertEquals(listOf("work"), back.config.profiles.map { it.name })
        assertNull(back.config.profiles.single().schedule)
        assertEquals(listOf(AppTimer("com.y", 10)), back.config.timers)
        assertNull(back.config.active)
        val problems = back.problems.joinToString("\n")
        for (part in listOf("someday", "noon", "named twice", "keeps for itself", "timer 1", "timer 2", "no profile \"gone\"")) {
            assertTrue("$part in:\n$problems", problems.contains(part))
        }
    }

    @Test fun activeMatchesWhateverTheCaseAndTimersEdit() {
        val back = FocusFile.read("active = \"WORK\"\n[[profile]]\nname = \"work\"\napps = [\"a\"]\n")
        assertEquals("work", back.config.active)
        val c = back.config.withTimer("a", 10).withTimer("b", 5).withTimer("a", 20)
        assertEquals(listOf(AppTimer("a", 20), AppTimer("b", 5)), c.timers)
        assertEquals(listOf(AppTimer("b", 5)), c.withTimer("a", null).timers)
        val renamed = c.withProfile("work", FocusProfile("deep"))
        assertEquals("deep", renamed.active)
        assertNull(renamed.withoutProfile("deep").active)
    }
}
