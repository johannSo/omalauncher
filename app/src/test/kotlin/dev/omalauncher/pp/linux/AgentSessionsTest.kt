package dev.omalauncher.pp.linux

import dev.omalauncher.pp.widgets.lang.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionsTest {
    private fun hook(
        event: String,
        extra: String = "",
        session: String = "s1",
    ) = """{"session_id": "$session", "cwd": "/root/proj", "hook_event_name": "$event"$extra}"""

    private fun event(
        name: String,
        extra: String = "",
        session: String = "s1",
    ) = AgentEvent.parse(hook(name, extra, session))!!

    @Test fun parsesAReport() {
        val m = AgentMessage.parse("report\nclaude\n42 41 7\nwait\n{\"a\":\n1}") as AgentMessage.Report
        assertEquals("claude", m.agent)
        assertEquals(listOf(42, 41, 7), m.pids)
        assertTrue(m.waits)
        assertEquals("{\"a\":\n1}", m.payload)
        assertFalse((AgentMessage.parse("report\ncodex\n5\n-\n{}") as AgentMessage.Report).waits)
    }

    @Test fun parsesAnAnswer() {
        assertEquals(AgentMessage.Answer("123-4", true), AgentMessage.parse("answer\n123-4\nallow\n"))
        assertEquals(AgentMessage.Answer("123-4", false), AgentMessage.parse("answer\n123-4\ndeny\n"))
        assertNull(AgentMessage.parse("answer\n123-4\nmaybe\n"))
    }

    /** A request id becomes a file name in the inbox. */
    @Test fun refusesIdsThatCouldNameAPath() {
        assertNull(AgentMessage.parse("answer\n../../x\nallow\n"))
        assertNull(AgentMessage.parse("report\n../claude\n1\n-\n{}"))
        assertNull(AgentMessage.parse("nonsense"))
        assertNull(AgentMessage.parse("report\nclaude\n1"))
    }

    @Test fun readsClaudeCodesHooks() {
        assertEquals(AgentEvent.Kind.Start, event("SessionStart", """, "source": "startup"""").kind)
        val prompt = event("UserPromptSubmit", """, "prompt": "fix the **tests**\nplease"""")
        assertEquals(AgentEvent.Kind.Prompt, prompt.kind)
        assertEquals("fix the tests please", prompt.summary)
        assertEquals("/root/proj", prompt.cwd)
        val tool = event("PreToolUse", """, "tool_name": "Bash", "tool_input": {"command": "npm test\necho hi", "description": "Runs the tests"}""")
        assertEquals("Bash: npm test", tool.summary)
        val asks = event("PermissionRequest", """, "tool_name": "Edit", "tool_input": {"file_path": "/root/proj/a.kt", "old_string": "x"}""")
        assertEquals(AgentEvent.Kind.Permission, asks.kind)
        assertEquals("Edit: /root/proj/a.kt", asks.summary)
        assertEquals(AgentEvent.Kind.ToolDone, event("PostToolUse").kind)
        assertEquals(AgentEvent.Kind.PermissionPrompt, event("Notification", """, "notification_type": "permission_prompt", "message": "Claude needs your permission"""").kind)
        assertEquals(AgentEvent.Kind.Input, event("Notification", """, "notification_type": "idle_prompt", "message": "waiting"""").kind)
        assertNull(AgentEvent.parse(hook("Notification", """, "notification_type": "auth_success"""")))
        val stop = event("Stop", """, "stop_hook_active": false, "last_assistant_message": "All 12 tests pass."""")
        assertEquals(AgentEvent.Kind.Done, stop.kind)
        assertEquals("All 12 tests pass.", stop.summary)
        assertNull(AgentEvent.parse(hook("Stop", """, "stop_hook_active": true""")))
        assertEquals(AgentEvent.Kind.End, event("SessionEnd", """, "reason": "prompt_input_exit"""").kind)
        assertNull(AgentEvent.parse(hook("SubagentStop")))
        assertNull(AgentEvent.parse("not json"))
        assertNull(AgentEvent.parse("""{"hook_event_name": "Stop"}"""))
    }

    @Test fun readsCodexsNotify() {
        val e = AgentEvent.parse("""{"type": "agent-turn-complete", "thread-id": "t1", "turn-id": "u", "cwd": "/root/x", "input-messages": ["hi"], "last-assistant-message": "Done!"}""")!!
        assertEquals(AgentEvent.Kind.Done, e.kind)
        assertEquals("t1", e.session)
        assertEquals("Done!", e.summary)
        assertTrue(e.fromNotify)
        assertNull(AgentEvent.parse("""{"type": "something-else"}"""))
    }

    @Test fun describesTools() {
        assertEquals("shell: git status", AgentEvent.describeTool("shell", Json.parse("""{"command": ["git", "status"]}""")))
        assertEquals("WebFetch: https://x.org", AgentEvent.describeTool("WebFetch", Json.parse("""{"url": "https://x.org", "prompt": "read"}""")))
        assertEquals("apply_patch: Edits a.kt", AgentEvent.describeTool("apply_patch", Json.parse("""{"description": "Edits a.kt", "patch": "…"}""")))
        assertEquals("mcp__x__y", AgentEvent.describeTool("mcp__x__y", Json.parse("{}")))
        assertNull(AgentEvent.describeTool(null, null))
    }

    @Test fun cutsLongSummaries() {
        val s = AgentEvent.summarize("word ".repeat(200))!!
        assertEquals(AgentEvent.SUMMARY_MAX, s.length)
        assertTrue(s.endsWith("…"))
        assertNull(AgentEvent.summarize("  ** \n"))
    }

    @Test fun findsTheLastAssistantMessageInATranscript() {
        val tail =
            """
            sistant","message":{"content":"cut off"}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"First answer."}]}}
            {"type":"user","message":{"role":"user","content":"next"}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","name":"Bash"},{"type":"text","text":"Second answer."}]}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","name":"Bash"}]}}
            """.trimIndent()
        assertEquals("Second answer.", AgentEvent.lastAssistantText(tail))
        assertNull(AgentEvent.lastAssistantText("""{"type":"user"}"""))
    }

    @Test fun aSessionGoesFromStartToDone() {
        val m = AgentSessions()
        val start = m.report("claude", event("SessionStart"), 100, null, 1)!!
        assertFalse(start.alert)
        assertEquals(AgentPhase.Input, start.session.phase)
        assertEquals(100, start.session.window)
        val running = m.report("claude", event("UserPromptSubmit", """, "prompt": "go""""), 100, null, 2)!!
        assertFalse(running.alert)
        assertEquals(AgentPhase.Running, running.session.phase)
        assertEquals(2L, running.session.since)
        val tool = m.report("claude", event("PreToolUse", """, "tool_name": "Read", "tool_input": {"file_path": "/a"}"""), 100, null, 3)!!
        assertEquals("Read: /a", tool.session.summary)
        assertEquals(2L, tool.session.since)
        val done = m.report("claude", event("Stop", """, "last_assistant_message": "ok""""), 100, null, 4)!!
        assertTrue(done.alert)
        assertEquals(AgentPhase.Done, done.session.phase)
        assertEquals("ok", done.session.summary)
        // Idle after done is the same wait: no second sound.
        val idle = m.report("claude", event("Notification", """, "notification_type": "idle_prompt", "message": "waiting""""), 100, null, 5)!!
        assertFalse(idle.alert)
        assertEquals(1, m.all.size)
        val end = m.report("claude", event("SessionEnd"), 100, null, 6)!!
        assertTrue(end.removed)
        assertTrue(m.all.isEmpty())
        assertNull(m.report("claude", event("SessionEnd"), 100, null, 7))
    }

    @Test fun sessionsAreKeptApartByAgentAndId() {
        val m = AgentSessions()
        val a = m.report("claude", event("SessionStart", session = "a"), 100, null, 1)!!.session
        val b = m.report("claude", event("SessionStart", session = "b"), 200, null, 1)!!.session
        val c = m.report("codex", event("SessionStart", session = "a"), 300, null, 1)!!.session
        assertEquals(3, setOf(a.id, b.id, c.id).size)
        m.report("claude", event("UserPromptSubmit", session = "b"), 200, null, 2)
        assertEquals(AgentPhase.Input, m.byId(a.id)!!.phase)
        assertEquals(AgentPhase.Running, m.byId(b.id)!!.phase)
    }

    @Test fun aPermissionRequestWaitsForItsAnswer() {
        val m = AgentSessions()
        m.report("claude", event("UserPromptSubmit"), 100, null, 1)
        val ask = m.report("claude", event("PermissionRequest", """, "tool_name": "Bash", "tool_input": {"command": "rm -rf build"}"""), 100, "r1", 2)!!
        assertTrue(ask.alert)
        assertEquals(AgentPhase.Permission, ask.session.phase)
        assertEquals("r1", ask.session.request!!.id)
        assertEquals("Bash: rm -rf build", ask.session.summary)
        // A subagent's tool meanwhile doesn't hide the question.
        val other = m.report("claude", event("PreToolUse", """, "tool_name": "Read", "tool_input": {"file_path": "/x"}"""), 100, null, 3)!!
        assertEquals(AgentPhase.Permission, other.session.phase)
        assertEquals("Bash: rm -rf build", other.session.summary)
        assertFalse(other.alert)
        // Another one asks too: both wait, the first is answered first.
        m.report("claude", event("PermissionRequest", """, "tool_name": "Write", "tool_input": {"file_path": "/y"}"""), 100, "r2", 4)
        val first = m.resolve("r1", 5, answered = true)!!
        assertEquals("r2", first.session.request!!.id)
        assertEquals(AgentPhase.Permission, first.session.phase)
        assertEquals("Write: /y", first.session.summary)
        val second = m.resolve("r2", 6, answered = true)!!
        assertNull(second.session.request)
        assertEquals(AgentPhase.Running, second.session.phase)
        // Answered twice, or a request no one knows: nothing.
        assertNull(m.resolve("r2", 7, answered = true))
    }

    @Test fun anAnswerReachesOnlyTheSessionThatAsked() {
        val m = AgentSessions()
        m.report("claude", event("PermissionRequest", """, "tool_name": "Bash"""", session = "a"), 100, "ra", 1)
        m.report("claude", event("PermissionRequest", """, "tool_name": "Bash"""", session = "b"), 200, "rb", 1)
        val change = m.resolve("rb", 2, answered = true)!!
        assertEquals("b", change.session.session)
        assertEquals("ra", m.all.first { it.session == "a" }.request!!.id)
    }

    @Test fun aRequestGivenUpOnLeavesTheQuestionToTheTerminal() {
        val m = AgentSessions()
        m.report("claude", event("PermissionRequest", """, "tool_name": "Bash""""), 100, "r1", 1_000)
        assertTrue(m.expired(60_000, 120_000).isEmpty())
        assertEquals(listOf("r1"), m.expired(121_000, 120_000).map { it.id })
        val change = m.resolve("r1", 121_000, answered = false)!!
        assertEquals(AgentPhase.Permission, change.session.phase)
        assertNull(change.session.request)
        // Claude's own prompt in the terminal: still the same wait, no second sound.
        assertFalse(m.report("claude", event("Notification", """, "notification_type": "permission_prompt""""), 100, null, 122_000)!!.alert)
    }

    @Test fun theEndOfATurnDropsItsQuestions() {
        val m = AgentSessions()
        m.report("claude", event("PermissionRequest", """, "tool_name": "Bash""""), 100, "r1", 1)
        val done = m.report("claude", event("Stop"), 100, null, 2)!!
        assertEquals(listOf("r1"), done.dropped.map { it.id })
        assertNull(done.session.request)
        m.report("claude", event("PermissionRequest", """, "tool_name": "Bash""""), 100, "r2", 3)
        assertEquals(listOf("r2"), m.report("claude", event("SessionEnd"), 100, null, 4)!!.dropped.map { it.id })
    }

    @Test fun codexsNotifyJoinsItsSessionInTheSameTerminal() {
        val m = AgentSessions()
        val s = m.report("codex", event("UserPromptSubmit", session = "hooks-id"), 100, null, 1)!!.session
        m.report("codex", event("UserPromptSubmit", session = "elsewhere"), 200, null, 1)
        val notify = AgentEvent.parse("""{"type": "agent-turn-complete", "thread-id": "thread", "last-assistant-message": "done"}""")!!
        val change = m.report("codex", notify, 100, null, 2)!!
        assertEquals(s.id, change.session.id)
        assertEquals(AgentPhase.Done, change.session.phase)
        assertTrue(change.alert)
        // The hooks' Stop for the same turn: no second sound.
        assertFalse(m.report("codex", event("Stop", session = "hooks-id"), 100, null, 3)!!.alert)
        // Without hooks, notify makes its own session.
        assertEquals("thread", m.report("codex", notify, 300, null, 4)!!.session.session)
        assertEquals(3, m.all.size)
    }

    @Test fun dismissedComesBackWithNews() {
        val m = AgentSessions()
        val s = m.report("claude", event("UserPromptSubmit"), 100, null, 1)!!.session
        assertTrue(m.dismiss(s.id)!!.dismissed)
        assertTrue(m.report("claude", event("PreToolUse", """, "tool_name": "Read""""), 100, null, 2)!!.session.dismissed)
        assertFalse(m.report("claude", event("Stop"), 100, null, 3)!!.session.dismissed)
    }

    @Test fun closedTerminalsEndTheirSessions() {
        val m = AgentSessions()
        m.report("claude", event("SessionStart", session = "a"), 100, null, 1)
        m.report("claude", event("SessionStart", session = "b"), 200, null, 1)
        assertEquals(listOf("a"), m.prune { it == 200 }.map { it.session })
        assertEquals(listOf("b"), m.all.map { it.session })
    }

    @Test fun widgetsReadASession() {
        val m = AgentSessions()
        val s = m.report("claude", event("PermissionRequest", """, "tool_name": "Bash""""), 100, "r1", 5_000)!!.session
        val v = Json.write(s.value("Claude Code"))!!
        assertEquals(
            """{"key":"agent:${s.id}","agent":"claude","name":"Claude Code","status":"permission","summary":"Bash","cwd":"/root/proj","since":5,"request":"r1","icon":"icon:l:agent-claude"}""",
            v,
        )
    }
}
