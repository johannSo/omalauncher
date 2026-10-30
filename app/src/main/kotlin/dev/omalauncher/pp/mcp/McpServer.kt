package dev.omalauncher.pp.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A tool coding agents can call: [run] gets the call's arguments and returns text, a JSONObject or a JSONArray. */
class McpTool(
    val name: String,
    val description: String,
    /** A JSON Schema of the arguments; [McpServer] checks the required ones and the simple types before [run]. */
    val inputSchema: JSONObject,
    /** Whether it only reads; agents may skip asking before calling such a tool. */
    val readOnly: Boolean = false,
    val run: (JSONObject) -> Any,
)

/** What a tool says went wrong with its call: the agent gets [message] as the result, marked as an error, and can fix the call. */
class ToolError(
    message: String,
) : Exception(message)

/**
 * The Model Context Protocol over one stdio-like stream: JSON-RPC 2.0, a message per line (see McpBridge for the
 * transport). Answers initialize, ping, tools/list and tools/call; notifications get no answer. Calls run one at a
 * time, on the caller's thread.
 */
class McpServer(
    private val tools: List<McpTool>,
    private val version: String,
    private val instructions: String,
) {
    private val byName = tools.associateBy { it.name }

    /**
     * Answers [input]'s messages on [output] until [input] ends. A line past [maxLine] bytes is skipped (with a parse
     * error) rather than read into memory whole.
     */
    fun serve(
        input: InputStream,
        output: OutputStream,
        maxLine: Int = MAX_LINE,
    ) {
        val reader = BufferedInputStream(input)
        val line = ByteArrayOutputStream()
        var tooLong = false
        while (true) {
            val b = reader.read()
            if (b != -1 && b != '\n'.code) {
                if (line.size() < maxLine) line.write(b) else tooLong = true
                continue
            }
            val text = line.toString(Charsets.UTF_8.name()).trim()
            line.reset()
            val answer = if (tooLong) error(null, PARSE_ERROR, "message over ${maxLine shr 20} MB") else text.takeIf { it.isNotEmpty() }?.let(::handle)
            tooLong = false
            if (answer != null) {
                output.write((answer + "\n").toByteArray())
                output.flush()
            }
            if (b == -1) return
        }
    }

    /** The answer to the message [line], or null when it needs none (a notification, a response). */
    fun handle(line: String): String? {
        val message =
            try {
                JSONObject(line)
            } catch (e: JSONException) {
                // An array is a batch, which 2025-06-18 dropped; anything else doesn't parse.
                val batch = line.trimStart().startsWith("[")
                return error(null, if (batch) INVALID_REQUEST else PARSE_ERROR, if (batch) "batches aren't supported" else "not JSON: ${e.message}")
            }
        val id = message.opt("id")?.takeIf { it != JSONObject.NULL }
        val method = message.opt("method") as? String
        // A response to a request of ours; we send none, so there's nothing to match it with.
        if (method == null) return if (id != null && !message.has("result") && !message.has("error")) error(id, INVALID_REQUEST, "no method") else null
        if (id == null) return null // notifications/initialized, notifications/cancelled: nothing to answer.
        if (id !is String && id !is Number) return error(null, INVALID_REQUEST, "id must be a string or a number")
        val params = message.opt("params") as? JSONObject ?: JSONObject()
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", JSONArray(tools.map(::describe))))
            "tools/call" -> {
                val name = params.opt("name") as? String ?: return error(id, INVALID_PARAMS, "tools/call needs the tool's name")
                val tool = byName[name] ?: return error(id, INVALID_PARAMS, "no tool named $name; see tools/list")
                result(id, call(tool, params.opt("arguments") as? JSONObject ?: JSONObject()))
            }
            else -> error(id, METHOD_NOT_FOUND, "no method $method")
        }
    }

    private fun initialize(params: JSONObject): JSONObject {
        val asked = params.opt("protocolVersion") as? String
        return JSONObject()
            .put("protocolVersion", if (asked in VERSIONS) asked else VERSIONS.first())
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", NAME).put("title", "OmaLauncher++").put("version", version))
            .put("instructions", instructions)
    }

    private fun describe(tool: McpTool) =
        JSONObject()
            .put("name", tool.name)
            .put("description", tool.description)
            .put("inputSchema", tool.inputSchema)
            .put("annotations", JSONObject().put("readOnlyHint", tool.readOnly))

    /** A tool's result; what went wrong in it is a result too, with isError, so the agent sees why and can retry. */
    private fun call(
        tool: McpTool,
        args: JSONObject,
    ): JSONObject {
        val text =
            try {
                checkArguments(tool.inputSchema, args)?.let { return toolResult(it, isError = true) }
                when (val out = tool.run(args)) {
                    is JSONObject -> out.toString(2)
                    is JSONArray -> out.toString(2)
                    else -> out.toString()
                }
            } catch (e: ToolError) {
                return toolResult(e.message.orEmpty(), isError = true)
            } catch (e: Exception) {
                Log.w(TAG, "${tool.name} failed", e)
                return toolResult("${tool.name} failed: $e", isError = true)
            }
        return toolResult(text, isError = false)
    }

    private fun toolResult(
        text: String,
        isError: Boolean,
    ) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    private fun result(
        id: Any,
        result: JSONObject,
    ) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    private fun error(
        id: Any?,
        code: Int,
        message: String,
    ) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    companion object {
        private const val TAG = "mcp"

        /** The server's name, as agents' configs list it. */
        const val NAME = "omalauncher"

        /** Writes carry whole owl files and layouts; nothing an agent sends comes near this. */
        const val MAX_LINE = 16 shl 20

        /** Newest first: the one we answer with when the client asks for one we don't know. */
        val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        /**
         * What's wrong with [args] for [schema], or null: missing required properties and values of the wrong simple
         * type. Enough for the schemas here; the tools check the rest themselves.
         */
        fun checkArguments(
            schema: JSONObject,
            args: JSONObject,
        ): String? {
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val required = schema.optJSONArray("required") ?: JSONArray()
            for (i in 0 until required.length()) {
                val key = required.getString(i)
                if (!args.has(key)) return "missing argument $key"
            }
            for (key in args.keys()) {
                val property =
                    properties.optJSONObject(key)
                        ?: if (schema.optBoolean("additionalProperties", true)) continue else return "unknown argument $key; it takes ${properties.keys().asSequence().joinToString()}"
                val types =
                    when (val t = property.opt("type")) {
                        is String -> listOf(t)
                        is JSONArray -> (0 until t.length()).map { t.getString(it) }
                        else -> continue
                    }
                val value = args.get(key)
                if (types.none { matches(it, value) }) return "argument $key takes ${types.joinToString(" or ")}"
                (property.opt("enum") as? JSONArray)?.let { values ->
                    if ((0 until values.length()).none { values.get(it) == value }) return "argument $key takes one of ${(0 until values.length()).joinToString { values.get(it).toString() }}"
                }
            }
            return null
        }

        private fun matches(
            type: String,
            value: Any,
        ) = when (type) {
            "string" -> value is String
            "boolean" -> value is Boolean
            "number" -> value is Number
            "integer" -> value is Number && value.toDouble().let { it == Math.rint(it) }
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "null" -> value == JSONObject.NULL
            else -> true
        }
    }
}
