package dev.omalauncher.pp.jobs

import dev.omalauncher.pp.theme.Toml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsFileTest {
    private val sample =
        """
        [[job]]
        id = "morning"
        name = "Morning summary"
        agent = "claude"
        schedule = "weekdays at 07:00"
        dir = "~/projects/blog"
        prompt = "Summarize yesterday.\nBe brief."
        wifi = true
        notify = "failure"
        permissions = "full"
        timeout = "2h"
        future-key = "kept"

        [[job]]
        name = "Nightly build"
        schedule = "0 3 * * *"
        command = "make -C ~/src"
        charging = true
        timeout = 45
        enabled = false
        tz = "Europe/Berlin"

        [[job]]
        id = "manual"
        schedule = "manual"
        prompt_file = "prompts/check.md"
        """.trimIndent()

    @Test fun readsEveryKey() {
        val read = JobsFile.read(sample)
        assertEquals(listOf("future-key: unknown, ignored").map { "job 1 (morning): $it" }, read.problems)
        val (morning, nightly, manual) = read.jobs
        assertEquals("morning", morning.id)
        assertEquals("Morning summary", morning.name)
        assertEquals("claude", morning.agent)
        assertEquals("weekdays at 07:00", morning.schedule?.text)
        assertEquals("~/projects/blog", morning.dir)
        assertEquals("Summarize yesterday.\nBe brief.", morning.prompt)
        assertTrue(morning.wifi)
        assertFalse(morning.charging)
        assertEquals(Notify.Failure, morning.notify)
        assertEquals(Permissions.Full, morning.permissions)
        assertEquals(120, morning.timeoutMinutes)
        assertTrue(morning.enabled)

        // An id from the name, defaults for what's left out.
        assertEquals("nightly-build", nightly.id)
        assertNull(nightly.agent)
        assertEquals("make -C ~/src", nightly.command)
        assertEquals("~", nightly.dir)
        assertEquals(45, nightly.timeoutMinutes)
        assertFalse(nightly.enabled)
        assertEquals("Europe/Berlin", nightly.zone)
        assertEquals(Notify.Always, nightly.notify)
        assertEquals(Permissions.Safe, nightly.permissions)

        assertNull(manual.schedule)
        assertEquals("prompts/check.md", manual.promptFile)
        assertEquals("manual", manual.name)
    }

    @Test fun aBrokenJobIsLeftOutAndTheOthersStay() {
        val read =
            JobsFile.read(
                """
                stray = 1

                [[job]]
                id = "ok"
                schedule = "hourly"
                prompt = "hi"

                [[job]]
                id = "bad-schedule"
                schedule = "sometimes"
                prompt = "hi"

                [[job]]
                id = "no-prompt"
                schedule = "hourly"

                [[job]]
                id = "ok"
                schedule = "daily"
                prompt = "again"

                [[job]]
                id = "zone"
                schedule = "daily"
                prompt = "hi"
                tz = "Mars/Olympus"

                [[job]]
                id = "wrong-types"
                schedule = "daily"
                prompt = "hi"
                wifi = "yes"
                """.trimIndent(),
            )
        assertEquals(listOf("ok"), read.jobs.map { it.id })
        assertEquals(6, read.problems.size)
        assertTrue(read.problems[0], read.problems[0].startsWith("stray: unknown"))
        assertTrue(read.problems.joinToString("\n"), read.problems.all { it.startsWith("stray") || "left out" in it })
        assertTrue(read.problems[1], "schedule: unknown schedule" in read.problems[1])
        assertTrue(read.problems[3], "taken" in read.problems[3])
    }

    @Test fun anEmptyFileHasNoJobs() {
        assertEquals(JobsRead(emptyList(), emptyList()), JobsFile.read(""))
    }

    @Test fun writesBackWhatItReads() {
        val table = Toml.parse(sample)
        val read = JobsFile.read(table)
        // Saving a job from the screen keeps the others and the keys this launcher doesn't know.
        val morning = read.jobs[0].copy(name = "Mornings", wifi = false)
        val written = JobsFile.write(JobsFile.update(table, "morning", morning))
        assertTrue(written, written.startsWith(JobsFile.HEADER))
        val again = JobsFile.read(written)
        assertEquals(listOf(morning) + read.jobs.drop(1), again.jobs)
        assertTrue(written, "future-key = \"kept\"" in written)
        assertEquals(read.problems, again.problems)
    }

    @Test fun addsRenamesAndRemoves() {
        val table = Toml.parse(sample)
        val added = Job(id = "new", prompt = "x", schedule = Schedule.parse("every 30m"))
        assertEquals(listOf("morning", "nightly-build", "manual", "new"), JobsFile.read(JobsFile.update(table, "new", added)).jobs.map { it.id })
        // A job read by its name is found by the id it got from it.
        val renamed = JobsFile.read(table).jobs[1].copy(id = "nightly")
        assertEquals(listOf("morning", "nightly", "manual"), JobsFile.read(JobsFile.update(table, "nightly-build", renamed)).jobs.map { it.id })
        val removed = JobsFile.update(table, "morning", null)
        assertEquals(listOf("nightly-build", "manual"), JobsFile.read(removed).jobs.map { it.id })
        assertFalse(JobsFile.update(JobsFile.update(removed, "nightly-build", null), "manual", null).containsKey(JobsFile.TABLE))
    }

    /** Agents copy the README's example: it has to be a job that reads without a problem. */
    @Test fun theReadmesExampleReads() {
        val readme = java.io.File("src/main/assets/jobs/README.md").readText()
        val toml = readme.substringAfter("```toml\n").substringBefore("```")
        val read = JobsFile.read(toml)
        assertEquals(emptyList<String>(), read.problems)
        assertEquals(listOf("morning"), read.jobs.map { it.id })
        // Every schedule in its table reads too.
        val table = readme.substringAfter("## Schedules").substringBefore("## Permissions")
        val schedules = Regex("""^\| (.+?) \|""", RegexOption.MULTILINE).findAll(table).map { it.groupValues[1] }.drop(1)
        val written = schedules.flatMap { cell -> Regex("`([^`]+)`").findAll(cell).map { it.groupValues[1] } }.filter { it != Job.MANUAL }.toList()
        assertEquals(17, written.size)
        for (s in written) Schedule.parse(s)
    }

    @Test fun durationsAndIds() {
        assertEquals(30, JobsFile.minutes("30m"))
        assertEquals(30, JobsFile.minutes("30"))
        assertEquals(120, JobsFile.minutes("2h"))
        assertEquals(2, JobsFile.minutes("90s"))
        assertEquals(1, JobsFile.minutes("5s"))
        assertNull(JobsFile.minutes("soon"))
        assertEquals("morning-summary", JobsFile.slug("Morning summary!"))
        assertEquals("job", JobsFile.freeId("", emptyList()))
        assertEquals("morning-3", JobsFile.freeId("Morning", listOf("morning", "morning-2")))
    }
}
