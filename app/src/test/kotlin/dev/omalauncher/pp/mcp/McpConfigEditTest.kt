package dev.omalauncher.pp.mcp

import dev.omalauncher.pp.mcp.McpConfigEdit.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigEditTest {
    private val entry = """{"type": "stdio", "command": "omalauncher-mcp", "args": []}"""

    private fun written(r: Result) = (r as Result.Write).text

    private fun add(text: String?) = McpConfigEdit.json(text, "mcpServers", "omalauncher", entry)

    @Test fun createsAConfigWhenThereIsNone() {
        for (text in listOf(null, "", "  \n")) {
            val json = JSONObject(written(add(text)))
            assertEquals("omalauncher-mcp", json.getJSONObject("mcpServers").getJSONObject("omalauncher").getString("command"))
        }
    }

    @Test fun addsTheSectionKeepingEverythingElse() {
        val before = "{\n  \"numStartups\": 12,\n  \"theme\": \"dark\",\n  \"projects\": {\"/root\": {\"allowedTools\": []}}\n}\n"
        val after = written(add(before))
        val json = JSONObject(after)
        assertEquals(12, json.getInt("numStartups"))
        assertEquals("dark", json.getString("theme"))
        assertTrue(json.getJSONObject("projects").has("/root"))
        assertTrue(json.getJSONObject("mcpServers").has("omalauncher"))
        // The user's text is all still there, as it was.
        assertTrue(after.contains("\"numStartups\": 12,\n  \"theme\": \"dark\""))
    }

    @Test fun addsToServersAlreadyThere() {
        val before = """{"mcpServers": {"github": {"command": "gh-mcp"}}, "x": 1}"""
        val servers = JSONObject(written(add(before))).getJSONObject("mcpServers")
        assertEquals("gh-mcp", servers.getJSONObject("github").getString("command"))
        assertEquals("omalauncher-mcp", servers.getJSONObject("omalauncher").getString("command"))
        val empty = JSONObject(written(add("""{"mcpServers": {}}""")))
        assertTrue(empty.getJSONObject("mcpServers").has("omalauncher"))
        assertTrue(JSONObject(written(add("{}"))).getJSONObject("mcpServers").has("omalauncher"))
    }

    @Test fun leavesAServerTheUserChangedAlone() {
        assertEquals(Result.Present, add("""{"mcpServers": {"omalauncher": {"command": "my-own", "disabled": true}}}"""))
    }

    @Test fun onlyLooksAtTheTopLevelSection() {
        // A nested "mcpServers" (Claude keeps one per project) isn't the user-level one.
        val before = """{"projects": {"/root": {"mcpServers": {"omalauncher": {}}}}}"""
        assertTrue(JSONObject(written(add(before))).getJSONObject("mcpServers").has("omalauncher"))
    }

    @Test fun takesJsoncWithCommentsAndTrailingCommas() {
        val before = "{\n  // my models\n  \"model\": \"x\", /* keep */\n  \"mcp\": {\n    \"a\": {\"type\": \"local\",},\n  },\n}\n"
        val after = written(McpConfigEdit.json(before, "mcp", "omalauncher", """{"type": "local"}"""))
        assertTrue(after.contains("// my models"))
        assertTrue(after.contains("/* keep */"))
        assertTrue(after.indexOf("\"omalauncher\"") in after.indexOf("\"mcp\"")..after.indexOf("\"a\""))
    }

    @Test fun refusesWhatItCantReadSafely() {
        for (text in listOf("[1, 2]", "{\"a\": }", "{\"a\": 1", "{\"mcpServers\": []}", "{\"a\": 1} trailing", "{\"a\": nope}")) {
            assertTrue(text, add(text) is Result.Unreadable)
        }
    }

    @Test fun keysWithEscapesAndStringsWithBraces() {
        val before = """{"a\"}": "}{", "mcpServers": {"xA": "{"}}"""
        val servers = JSONObject(written(add(before))).getJSONObject("mcpServers")
        assertTrue(servers.has("xA"))
        assertTrue(servers.has("omalauncher"))
    }

    private fun toml(text: String?) = McpConfigEdit.toml(text, "omalauncher", "command = \"omalauncher-mcp\"")

    @Test fun appendsACodexTable() {
        assertEquals("[mcp_servers.omalauncher]\ncommand = \"omalauncher-mcp\"\n", written(toml(null)))
        val before = "model = \"gpt-5\"\n\n[projects.\"/root\"]\ntrust_level = \"trusted\""
        assertEquals("$before\n\n[mcp_servers.omalauncher]\ncommand = \"omalauncher-mcp\"\n", written(toml(before)))
    }

    @Test fun findsTheCodexServerHoweverItsWritten() {
        for (
        text in listOf(
            "[mcp_servers.omalauncher]\ncommand = \"x\"",
            "[mcp_servers.\"omalauncher\"]\nenabled = false",
            "[ mcp_servers . omalauncher . env ]\nA = \"1\"",
            "[mcp_servers]\nomalauncher.command = \"x\"",
            "[mcp_servers]\nomalauncher = { command = \"x\" }",
            "mcp_servers.omalauncher.command = \"x\"",
        )
        ) {
            assertEquals(text, Result.Present, toml(text))
        }
    }

    @Test fun ignoresLookalikesInStringsAndOtherTables() {
        val before = "notes = \"\"\"\n[mcp_servers.omalauncher]\n\"\"\"\n[other]\nomalauncher = 1\n[mcp_servers.other]\ncommand = \"y\""
        assertTrue(toml(before) is Result.Write)
    }

    @Test fun wontAddATableAfterAnInlineOne() {
        assertTrue(toml("mcp_servers = { other = { command = \"y\" } }") is Result.Unreadable)
    }
}
