package dev.omalauncher.pp.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class McpServerTest {
    private val calls = ArrayList<JSONObject>()

    private val server =
        McpServer(
            listOf(
                McpTool(
                    "echo",
                    "Says it back.",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put("times", JSONObject().put("type", "integer"))
                                .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("loud", "quiet"))))
                                .put("value", JSONObject().put("type", JSONArray(listOf("string", "null")))),
                        ).put("required", JSONArray(listOf("text")))
                        .put("additionalProperties", false),
                    readOnly = true,
                ) { args ->
                    calls += args
                    args.getString("text").repeat(args.optInt("times", 1))
                },
                McpTool("json", "Answers JSON.", JSONObject().put("type", "object")) { JSONObject().put("a", 1) },
                McpTool("refuses", "Always says no.", JSONObject().put("type", "object")) { throw ToolError("no, because") },
                McpTool("crashes", "Breaks.", JSONObject().put("type", "object")) { throw IllegalStateException("boom") },
            ),
            "1.2.3",
            "Use the tools.",
        )

    private fun request(
        id: Any,
        method: String,
        params: JSONObject? = null,
    ) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).apply { params?.let { put("params", it) } }.toString()

    private fun answer(line: String) = JSONObject(server.handle(line)!!)

    private fun call(
        name: String,
        args: JSONObject = JSONObject(),
    ) = answer(request(7, "tools/call", JSONObject().put("name", name).put("arguments", args))).getJSONObject("result")

    private fun text(result: JSONObject) = result.getJSONArray("content").getJSONObject(0).getString("text")

    @Test fun initializeAgreesOnTheClientsVersionWhenKnownAndOursOtherwise() {
        val known = answer(request(1, "initialize", JSONObject().put("protocolVersion", "2025-03-26"))).getJSONObject("result")
        assertEquals("2025-03-26", known.getString("protocolVersion"))
        assertEquals("omalauncher", known.getJSONObject("serverInfo").getString("name"))
        assertEquals("1.2.3", known.getJSONObject("serverInfo").getString("version"))
        assertEquals("Use the tools.", known.getString("instructions"))
        assertTrue(known.getJSONObject("capabilities").has("tools"))

        val future = answer(request(2, "initialize", JSONObject().put("protocolVersion", "2099-01-01"))).getJSONObject("result")
        assertEquals("2025-06-18", future.getString("protocolVersion"))
    }

    @Test fun answersKeepTheRequestsId() {
        val byString = answer(request("abc", "ping"))
        assertEquals("2.0", byString.getString("jsonrpc"))
        assertEquals("abc", byString.getString("id"))
        assertEquals(0, byString.getJSONObject("result").length())
        assertEquals(42, answer(request(42, "ping")).getInt("id"))
    }

    @Test fun notificationsAndResponsesGetNoAnswer() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":3}}"""))
        assertNull(server.handle("""{"jsonrpc":"2.0","id":5,"result":{}}"""))
    }

    @Test fun listsToolsWithTheirSchemas() {
        val tools = answer(request(3, "tools/list")).getJSONObject("result").getJSONArray("tools")
        assertEquals(4, tools.length())
        val echo = tools.getJSONObject(0)
        assertEquals("echo", echo.getString("name"))
        assertEquals("Says it back.", echo.getString("description"))
        assertEquals("object", echo.getJSONObject("inputSchema").getString("type"))
        assertTrue(echo.getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(tools.getJSONObject(1).getJSONObject("annotations").getBoolean("readOnlyHint"))
    }

    @Test fun callsATool() {
        val result = call("echo", JSONObject().put("text", "hi").put("times", 2))
        assertFalse(result.getBoolean("isError"))
        assertEquals("hihi", text(result))
        assertEquals("text", result.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals(JSONObject().put("a", 1).toString(), JSONObject(text(call("json"))).toString())
    }

    @Test fun aToolsOwnErrorIsAResultTheAgentCanRead() {
        val refused = call("refuses")
        assertTrue(refused.getBoolean("isError"))
        assertEquals("no, because", text(refused))
        val crashed = call("crashes")
        assertTrue(crashed.getBoolean("isError"))
        assertTrue(text(crashed), text(crashed).contains("boom"))
    }

    @Test fun badArgumentsNeverReachTheTool() {
        for (
        (args, why) in listOf(
            JSONObject() to "missing argument text",
            JSONObject().put("text", 3) to "argument text takes string",
            JSONObject().put("text", "a").put("times", 1.5) to "argument times takes integer",
            JSONObject().put("text", "a").put("mode", "shouty") to "argument mode takes one of loud, quiet",
            JSONObject().put("text", "a").put("extra", true) to "unknown argument extra",
        )
        ) {
            val result = call("echo", args)
            assertTrue(result.getBoolean("isError"))
            assertTrue("${text(result)} for $args", text(result).startsWith(why))
        }
        assertTrue(calls.isEmpty())
        assertFalse(call("echo", JSONObject().put("text", "a").put("value", JSONObject.NULL)).getBoolean("isError"))
        assertFalse(call("echo", JSONObject().put("text", "a").put("times", 3.0)).getBoolean("isError"))
    }

    @Test fun protocolErrorsHaveJsonRpcCodes() {
        fun code(line: String) = JSONObject(server.handle(line)!!).getJSONObject("error").getInt("code")
        assertEquals(McpServer.PARSE_ERROR, code("{not json"))
        assertEquals(McpServer.INVALID_REQUEST, code("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]"""))
        assertEquals(McpServer.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":1}"""))
        assertEquals(McpServer.METHOD_NOT_FOUND, code(request(1, "resources/list")))
        assertEquals(McpServer.INVALID_PARAMS, code(request(1, "tools/call", JSONObject().put("name", "nope"))))
        assertEquals(McpServer.INVALID_PARAMS, code(request(1, "tools/call")))
        assertTrue(JSONObject(server.handle("{not json")!!).isNull("id"))
    }

    @Test fun servesAStreamALineAtATime() {
        val input =
            listOf(
                request(1, "initialize", JSONObject().put("protocolVersion", "2025-06-18")),
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                "",
                request(2, "tools/call", JSONObject().put("name", "echo").put("arguments", JSONObject().put("text", "héllo"))),
            ).joinToString("\n")
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(input.toByteArray()), out)
        val lines = out.toString(Charsets.UTF_8.name()).trimEnd('\n').split("\n").map(::JSONObject)
        assertEquals(listOf(1, 2), lines.map { it.getInt("id") })
        assertEquals("héllo", text(lines[1].getJSONObject("result")))
    }

    @Test fun anOverlongLineIsRefusedAndTheNextStillServed() {
        val input = "x".repeat(100) + "\n" + request(9, "ping") + "\n"
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(input.toByteArray()), out, maxLine = 60)
        val lines = out.toString().trimEnd('\n').split("\n").map(::JSONObject)
        assertEquals(McpServer.PARSE_ERROR, lines[0].getJSONObject("error").getInt("code"))
        assertEquals(9, lines[1].getInt("id"))
    }
}
