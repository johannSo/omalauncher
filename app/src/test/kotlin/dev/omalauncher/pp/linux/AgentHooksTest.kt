package dev.omalauncher.pp.linux

import dev.omalauncher.pp.widgets.lang.Json
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class AgentHooksTest {
    private val ours = AgentHooks.command("claude")

    private fun hooks(text: String) = (Json.parse(text) as Obj).fields["hooks"] as Obj

    private fun commands(
        text: String,
        event: String,
    ) = ((hooks(text).fields[event] as Lst).items).flatMap { g -> ((g as Obj).fields["hooks"] as Lst).items.map { ((it as Obj).fields["command"] as Str).v } }

    @Test fun writesANewFile() {
        val text = AgentHooks.merge(null, "claude", AgentHooks.CLAUDE_EVENTS, 120)!!
        assertEquals(AgentHooks.CLAUDE_EVENTS, hooks(text).fields.keys.toList())
        assertEquals(listOf(ours), commands(text, "Stop"))
        assertTrue("2-space indent", text.startsWith("{\n  \"hooks\": {\n    \"SessionStart\": [\n"))
        // The permission hook outwaits the launcher's own timeout.
        val handler = ((((hooks(text).fields["PermissionRequest"] as Lst).items[0] as Obj).fields["hooks"] as Lst).items[0] as Obj).fields
        assertEquals("150", handler["timeout"].toString())
        assertNull("nothing to take out of no file", AgentHooks.merge(null, "claude", emptyList(), 120))
    }

    @Test fun keepsTheUsersSettingsAndHooks() {
        val user =
            """
            {
              "model": "opus",
              "permissions": {"allow": ["Bash(ls:*)"]},
              "hooks": {
                "PostToolUse": [{"matcher": "Edit|Write", "hooks": [{"type": "command", "command": "~/lint.sh"}]}],
                "Stop": [{"hooks": [{"type": "command", "command": "notify-send done"}]}]
              },
              "statusLine": {"type": "command", "command": "~/.claude/status.sh"}
            }
            """.trimIndent()
        val text = AgentHooks.merge(user, "claude", AgentHooks.CLAUDE_EVENTS, 120)!!
        val root = (Json.parse(text) as Obj).fields
        assertEquals(listOf("model", "permissions", "hooks", "statusLine"), root.keys.toList())
        assertEquals(listOf("~/lint.sh", ours), commands(text, "PostToolUse"))
        assertEquals(listOf("notify-send done", ours), commands(text, "Stop"))
        assertEquals((Json.parse(user) as Obj).fields["permissions"], root["permissions"])
        assertTrue("/ unescaped", "\"~/.claude/status.sh\"" in text)
        // Again: nothing to change.
        assertNull(AgentHooks.merge(text, "claude", AgentHooks.CLAUDE_EVENTS, 120))
        // A new timeout changes only ours.
        val longer = AgentHooks.merge(text, "claude", AgentHooks.CLAUDE_EVENTS, 300)!!
        assertEquals(listOf("notify-send done", ours), commands(longer, "Stop"))
        assertTrue("\"timeout\": 330" in longer)
        // Turned off: the user's file as it was, but for formatting.
        val off = AgentHooks.merge(longer, "claude", emptyList(), 120)!!
        assertEquals(Json.parse(user), Json.parse(off))
        assertNull(AgentHooks.merge(off, "claude", emptyList(), 120))
    }

    @Test fun takingOursOutOfAFileOfOnlyOursLeavesNoHooks() {
        val text = AgentHooks.merge("{}", "codex", AgentHooks.CODEX_EVENTS, 120)!!
        assertEquals("{}\n", AgentHooks.merge(text, "codex", emptyList(), 120))
    }

    @Test fun leavesWhatItCantReadAlone() {
        assertNull(AgentHooks.merge("{ \"hooks\": ", "claude", AgentHooks.CLAUDE_EVENTS, 120))
        assertNull(AgentHooks.merge("// comments\n{}", "claude", AgentHooks.CLAUDE_EVENTS, 120))
        assertNull(AgentHooks.merge("[1]", "claude", AgentHooks.CLAUDE_EVENTS, 120))
        assertNull(AgentHooks.merge("""{"hooks": "nope"}""", "claude", AgentHooks.CLAUDE_EVENTS, 120))
        // An event of a shape it doesn't know is skipped, the rest still go in.
        val text = AgentHooks.merge("""{"hooks": {"Stop": "mine"}}""", "claude", AgentHooks.CLAUDE_EVENTS, 120)!!
        assertEquals(Str("mine"), hooks(text).fields["Stop"])
        assertEquals(listOf(ours), commands(text, "SessionStart"))
    }

    @Test fun prettyPrintsLikeClaudeCode() {
        assertEquals("{\n  \"a\": [\n    1,\n    \"x\\\"y\"\n  ],\n  \"b\": {},\n  \"c\": []\n}", AgentHooks.pretty(Json.parse("""{"a":[1,"x\"y"],"b":{},"c":[]}""")))
    }

    // The helper itself, assets/agent-status.sh, run with bash against an inbox of the test's.

    private val script = File("src/main/assets/agent-status.sh")

    private fun helper(
        inbox: File,
        vararg args: String,
    ) = ProcessBuilder(listOf("bash", script.path) + args).apply { environment()["OMALAUNCHER_AGENT_INBOX"] = inbox.path }

    private fun requests(inbox: File) = inbox.listFiles().orEmpty().filter { it.name.endsWith(".req") }

    @Test fun theHelperReportsAnEvent() {
        val inbox = createTempDirectory("inbox").toFile()
        val payload = hook("PreToolUse")
        val p = helper(inbox, "claude").redirectInput(ProcessBuilder.Redirect.PIPE).start()
        p.outputStream.use { it.write(payload.toByteArray()) }
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        val req = requests(inbox).single()
        val message = AgentMessage.parse(req.readText()) as AgentMessage.Report
        assertEquals("claude", message.agent)
        assertEquals(payload, message.payload)
        assertTrue(!message.waits)
        // Its own pid, then its parent's: this JVM.
        assertEquals(ProcessHandle.current().pid().toInt(), message.pids[1])
        assertTrue(AgentMessage.ID.matches(req.name.removeSuffix(".req")))
    }

    @Test fun theHelperTakesCodexsNotifyFromItsArguments() {
        val inbox = createTempDirectory("inbox").toFile()
        val json = """{"type":"agent-turn-complete","thread-id":"t"}"""
        val p = helper(inbox, "codex", json).start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(json, (AgentMessage.parse(requests(inbox).single().readText()) as AgentMessage.Report).payload)
    }

    @Test fun theHelperDoesNothingWhileReportsAreOff() {
        val gone = File(createTempDirectory("inbox").toFile(), "missing")
        val p = helper(gone, "claude").start()
        p.outputStream.close()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        assertTrue(!gone.exists())
    }

    @Test fun theHelperWaitsForAnAnswerToAPermissionRequest() {
        for ((answer, expected) in listOf("allow" to "\"behavior\":\"allow\"", "deny" to "\"behavior\":\"deny\"", "ask" to null)) {
            val inbox = createTempDirectory("inbox").toFile()
            val p = helper(inbox, "claude").start()
            p.outputStream.use { it.write(hook("PermissionRequest").toByteArray()) }
            val deadline = System.currentTimeMillis() + 10_000
            while (requests(inbox).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val req = requests(inbox).single()
            assertTrue((AgentMessage.parse(req.readText()) as AgentMessage.Report).waits)
            val fifo = File(inbox, req.name.removeSuffix(".req") + ".fifo")
            assertTrue(fifo.exists())
            assertTrue("still waiting", p.isAlive)
            fifo.writeText("$answer\n")
            assertTrue(p.waitFor(10, TimeUnit.SECONDS))
            val out = p.inputStream.bufferedReader().readText()
            if (expected == null) assertEquals("", out) else assertTrue(out, expected in out && "\"hookEventName\":\"PermissionRequest\"" in out)
            assertTrue("cleans up its FIFO", !fifo.exists())
        }
    }

    @Test fun theHelperPassesOnAnAnswer() {
        val inbox = createTempDirectory("inbox").toFile()
        assertTrue(helper(inbox, "answer", "123-4", "allow").start().waitFor(10, TimeUnit.SECONDS))
        assertEquals(AgentMessage.Answer("123-4", true), AgentMessage.parse(requests(inbox).single().readText()))
        val bad = helper(inbox, "answer", "123-4", "yes").start()
        assertTrue(bad.waitFor(10, TimeUnit.SECONDS))
        assertEquals(2, bad.exitValue())
    }

    private fun hook(event: String) = """{"session_id": "s", "cwd": "/root", "hook_event_name": "$event", "tool_name": "Bash", "tool_input": {"command": "ls"}}"""
}
