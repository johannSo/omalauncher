package dev.omalauncher.pp.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class JobCommandTest {
    private fun sh(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    @Test fun everyHeadlessAgentHasACommand() {
        for (bin in HEADLESS_AGENTS) for (p in Permissions.entries) assertTrue("$bin $p", headlessInvocation(bin, "hi", p) != null)
        assertNull(headlessInvocation("grok", "hi", Permissions.Safe))
        assertNull(headlessInvocation("agy", "hi", Permissions.Full))
    }

    /** The prompt reaches the agent as one argument, whatever it holds, and never as an option. */
    @Test fun thePromptIsOneLiteralArgument() {
        val prompt = "-Explain \"it's quoted\"; \$(exit 17)\nand keep \$HOME literal."
        for (p in Permissions.entries) {
            val invocation = headlessInvocation("claude", prompt, p)!!.removePrefix("IS_SANDBOX=1 ")
            val (code, last) = sh("set -- $invocation\nfor a; do l=\"\$a\"; done\nprintf '%s' \"\$l\"")
            assertEquals(last, 0, code)
            assertEquals(" $prompt", last)
        }
    }

    @Test fun claudeFullAccessRunsAsRootInTheSandboxMode() {
        assertEquals("IS_SANDBOX=1 claude -p --dangerously-skip-permissions 'x'", headlessInvocation("claude", "x", Permissions.Full))
        assertEquals("claude -p --permission-mode dontAsk 'x'", headlessInvocation("claude", "x", Permissions.Safe))
        assertEquals("codex exec --skip-git-repo-check --sandbox read-only 'x'", headlessInvocation("codex", "x", Permissions.Safe))
    }

    @Test fun theScriptRunsInTheFolderWithTheJobsVariables() {
        val home = createTempDirectory("job-home").toFile()
        val work = File(home, "my projects/blog").apply { mkdirs() }
        try {
            val job = Job(id = "morning", name = "Morning 'summary'", prompt = "p", dir = "~/my projects/blog", timeoutMinutes = 1)
            val invocation = "pwd; printf '%s|%s|%s|%s\\n' \"\$JOB_ID\" \"\$JOB_NAME\" \"\$JOB_PROMPT\" \"\$JOB_RESULTS\"; read x || echo no-input"
            val (code, out) = sh(jobScript(job, invocation, "the \$prompt", "/root/.config/omalauncher/jobs/morning"), mapOf("HOME" to home.path))
            assertEquals(out, 0, code)
            assertEquals(listOf(work.path, "morning|Morning 'summary'|the \$prompt|/root/.config/omalauncher/jobs/morning", "no-input"), out.trim().lines())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun aMissingFolderFailsTheRun() {
        val job = Job(id = "x", prompt = "p", dir = "/no/such/folder")
        val (code, _) = sh(jobScript(job, "echo ran", "p", "/r"))
        assertEquals(1, code)
    }

    @Test fun theTimeoutEndsIt() {
        val job = Job(id = "x", prompt = "p", timeoutMinutes = 1)
        // A minute is the shortest timeout; the script's own number is checked rather than waited out.
        assertTrue(jobScript(job, "sleep 1", "p", "/r").contains("timeout -k 10 60 sh -c 'sleep 1'"))
        assertEquals(Outcome.TimedOut, Outcome.of(sh("timeout 0.2 sleep 5").first, stopped = false))
    }

    @Test fun folders() {
        assertEquals("\"\$HOME\"", guestDirWord("~"))
        assertEquals("\"\$HOME\"/'a b'", guestDirWord("~/a b"))
        assertEquals("'/srv/x'", guestDirWord("/srv/x"))
    }

    @Test fun outputIsKeptWithoutEscapesOrRedraws() {
        val out = OutputText()
        for (raw in listOf("\u001B[1;32mgreen\u001B[0m", "10%\r", "50%\r", "100%", "windows\r", "", "", "end")) out.add(raw)
        assertEquals("green\n100%\nwindows\n\nend", out.text())
        val small = OutputText(max = 10)
        repeat(20) { small.add("line $it") }
        assertTrue(small.text(), small.text().endsWith("line 19") && small.text().length <= 10)
    }

    @Test fun summaries() {
        assertEquals("b\nc\nd", summaryOf("a\n\nb\nc\n  \nd\n"))
        assertEquals("…" + "x".repeat(9), summaryOf("x".repeat(50), max = 10))
        assertEquals("", summaryOf(""))
        assertEquals("title", cleanLine("\u001B]0;title\u0007title"))
    }
}
