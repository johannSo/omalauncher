package dev.omalauncher.pp.mcp

import dev.omalauncher.pp.theme.Setting
import dev.omalauncher.pp.theme.Style
import dev.omalauncher.pp.theme.StyleFile
import dev.omalauncher.pp.theme.Toml
import dev.omalauncher.pp.widgets.AndroidTile
import dev.omalauncher.pp.widgets.AppTile
import dev.omalauncher.pp.widgets.FacePage
import dev.omalauncher.pp.widgets.FolderTile
import dev.omalauncher.pp.widgets.HomeFile
import dev.omalauncher.pp.widgets.HomeLayout
import dev.omalauncher.pp.widgets.TilePage
import dev.omalauncher.pp.widgets.WidgetTile
import dev.omalauncher.pp.widgets.lang.CompileError
import dev.omalauncher.pp.widgets.lang.Compiler
import dev.omalauncher.pp.widgets.lang.Source
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the MCP tools check before they change anything: the same compiler, TOML reader and decoders the launcher
 * reads the files with, so a write that passes here is one the launcher takes. No Android in here.
 */
object LauncherChecks {
    private val WIDGET_FILE = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.owl""")

    /** [name] if it's a file the launcher loads from the widgets folder; hidden ones (the stock drawer) only when [hidden]. */
    fun widgetFile(
        name: String,
        hidden: Boolean = false,
    ): String {
        val ok = WIDGET_FILE.matches(name) || (hidden && name.startsWith(".") && WIDGET_FILE.matches(name.drop(1)))
        if (!ok) throw ToolError("$name: not a widget file name; the launcher loads <name>.owl files directly in the widgets folder (letters, digits, '.', '_' and '-')")
        return name
    }

    /** Compiles [source] as the launcher would: what it is, or where it fails. */
    fun compile(source: String): JSONObject =
        try {
            val p = Compiler.compile(source)
            JSONObject()
                .put("compiles", true)
                .put("kind", p.kind.word)
                .put("name", p.name)
                .put("warnings", JSONArray(p.warnings))
        } catch (e: CompileError) {
            JSONObject().put("compiles", false).put("error", Source(source).format(e))
        } catch (e: RuntimeException) {
            JSONObject().put("compiles", false).put("error", e.toString())
        }

    /**
     * What's wrong with layout.toml [text] before it goes on disk: TOML that doesn't parse, no pages, or what
     * [HomeFile.decode] reports. Whether the widgets and apps it names exist is for the launcher to say once it reads it.
     */
    fun layoutProblems(
        text: String,
        columns: Int,
    ): List<String> {
        val table =
            try {
                Toml.parse(text)
            } catch (e: Toml.Error) {
                return listOf("layout.toml ${e.message}")
            }
        if ("page" !in table) return listOf("no [[page]]: home's pages are [[page]] tables, left to right")
        return HomeFile.decode(table, columns).problems
    }

    /** layout.toml's pages as JSON: each a face, or tiles with their cells. */
    fun layoutJson(layout: HomeLayout): JSONArray =
        JSONArray(
            layout.pages.map { page ->
                when (page) {
                    is FacePage -> JSONObject().put("face", page.file)
                    is TilePage ->
                        JSONObject().put(
                            "tiles",
                            JSONArray(
                                page.tiles.map { tile ->
                                    val out =
                                        when (tile) {
                                            is WidgetTile -> JSONObject().put("widget", tile.file)
                                            is AppTile -> JSONObject().put("app", tile.app)
                                            is FolderTile -> JSONObject().put("folder", tile.name.orEmpty()).put("apps", JSONArray(tile.apps))
                                            is AndroidTile -> JSONObject().put("android", tile.provider).put("id", tile.id)
                                        }
                                    tile.cell?.let { c ->
                                        out.put("x", c.x).put("y", c.y)
                                        if (c.w > 0 && c.h > 0) out.put("w", c.w).put("h", c.h)
                                    }
                                    out
                                },
                            ),
                        )
                }
            },
        )

    /** The style setting called [key]; an unknown key names the sections there are. */
    fun setting(key: String): Setting<*> =
        StyleFile.ALL.firstOrNull { it.key == key }
            ?: throw ToolError("$key: not a setting; get_style lists them all (sections: ${StyleFile.ALL.map { it.key.substringBefore('.', "") }.filter { it.isNotEmpty() }.distinct().joinToString()})")

    /**
     * [style] with [key] set to [value], a JSON value as an agent sends it (null for the default), checked as
     * style.toml's value would be. Throws [ToolError] saying what the setting takes.
     */
    fun withValue(
        style: Style,
        key: String,
        value: Any?,
    ): Style {
        val setting = setting(key)
        if (value == null || value == JSONObject.NULL) return set(style, setting, setting.default)
        val toml: Any =
            when (value) {
                is String, is Boolean -> value
                is Number -> value.toDouble()
                else -> throw ToolError("$key takes a string, a number or a boolean")
            }
        val decoded =
            try {
                setting.decode(toml)
            } catch (e: IllegalArgumentException) {
                throw ToolError("$key ${e.message}")
            }
        return set(style, setting, decoded)
    }

    @Suppress("UNCHECKED_CAST")
    private fun set(
        style: Style,
        setting: Setting<*>,
        value: Any?,
    ) = style.with(setting as Setting<Any?>, value)

    /** A style.toml value as JSON: whole numbers as integers. */
    fun json(value: Any?): Any =
        when (value) {
            null -> JSONObject.NULL
            is Double -> if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong() else value
            is Int -> value
            else -> value.toString().takeIf { value !is Boolean } ?: value
        }
}
