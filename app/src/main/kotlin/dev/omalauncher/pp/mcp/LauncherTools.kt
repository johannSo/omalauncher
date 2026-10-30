package dev.omalauncher.pp.mcp

import android.content.Context
import android.widget.Toast
import dev.omalauncher.pp.AppTweakStore
import dev.omalauncher.pp.LauncherData
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.linux.Proot
import dev.omalauncher.pp.theme.Style
import dev.omalauncher.pp.theme.StyleFile
import dev.omalauncher.pp.theme.StyleStore
import dev.omalauncher.pp.theme.Toml
import dev.omalauncher.pp.upgrade.writeAtomically
import dev.omalauncher.pp.widgets.AndroidWidget
import dev.omalauncher.pp.widgets.Folder
import dev.omalauncher.pp.widgets.HomeFile
import dev.omalauncher.pp.widgets.HomeStore
import dev.omalauncher.pp.widgets.Shortcut
import dev.omalauncher.pp.widgets.WidgetEngine
import dev.omalauncher.pp.widgets.WidgetItem
import dev.omalauncher.pp.widgets.lang.OwlKind
import dev.omalauncher.pp.widgets.lang.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The tools the MCP server offers: OmaLauncher++'s home, widgets, theme, style and apps. They read and write the
 * same files an agent could edit by hand (and owl-check reads), checking a write first ([LauncherChecks]); what
 * isn't a file (the apps, opening one, a toast) comes from [runtime]. Called on an MCP session's thread.
 */
class LauncherTools(
    private val context: Context,
    private val runtime: Runtime,
) {
    private val engine get() = runtime.widgets
    private val widgetsDir = LauncherData.widgetsDir(context)
    private val layoutFile = File(LauncherData.homeDir(context), HomeStore.FILE)
    private val styleFile = File(LauncherData.styleDir(context), StyleStore.FILE)

    /** Launcher state is the main thread's. */
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun guest(
        dir: String,
        name: String,
    ) = "${Proot.GUEST_HOME}/$dir/$name"

    fun all(): List<McpTool> =
        listOf(
            McpTool(
                "list_widgets",
                "Lists the owl files in ~/${WidgetEngine.GUEST_DIR}: home-screen widgets and faces, drawer apps, panels and key bars, " +
                    "with whether each compiles and its first error.",
                schema(),
                readOnly = true,
            ) { listWidgets() },
            McpTool(
                "read_widget",
                "Reads one owl file from the widgets folder, with the errors the launcher reported for it (compile errors, runtime problems, warnings).",
                schema("file" to prop("string", "The file name, like clock.owl (.stock-drawer.owl is the drawer as shipped)."), required = listOf("file")),
                readOnly = true,
            ) { readWidget(LauncherChecks.widgetFile(it.getString("file"), hidden = true)) },
            McpTool(
                "write_widget",
                "Creates or replaces an owl file in the widgets folder. The source is compiled first: if it doesn't compile, nothing is " +
                    "written and the error comes back with its line. Otherwise it's saved, the launcher loads it (a new widget goes on " +
                    "the page its page: says), and the problems found while loading come back. The language is documented in " +
                    "~/${WidgetEngine.GUEST_DIR}/README.md; call check_owl afterwards for a picture of it.",
                schema(
                    "file" to prop("string", "The file name, like weather.owl; drawer.owl is the app drawer, keybar.owl the terminal key bar."),
                    "source" to prop("string", "The whole owl source."),
                    required = listOf("file", "source"),
                ),
            ) { writeWidget(LauncherChecks.widgetFile(it.getString("file")), it.getString("source")) },
            McpTool(
                "check_owl",
                "Checks owl code. With source: compiles it, without saving anything. With file: like the owl-check command, waits " +
                    "until the launcher has loaded the file as saved and returns its errors, and (with preview) the path of a PNG " +
                    "of it drawn with live data; read that image to check the layout.",
                schema(
                    "file" to prop("string", "A file in the widgets folder, like clock.owl."),
                    "source" to prop("string", "Owl source to compile."),
                    "preview" to prop("boolean", "With file: also render a picture of it (default true). It can take a few seconds."),
                ),
                readOnly = true,
            ) { args ->
                when {
                    args.has("source") == args.has("file") -> throw ToolError("give file or source, one of them")
                    args.has("source") -> LauncherChecks.compile(args.getString("source"))
                    else -> checkFile(LauncherChecks.widgetFile(args.getString("file"), hidden = true), args.optBoolean("preview", true))
                }
            },
            McpTool(
                "get_home_layout",
                "Home's pages and tiles as ~/${HomeStore.GUEST_DIR}/${HomeStore.FILE} has them (the TOML text, and the same as JSON), " +
                    "with what the launcher couldn't apply. docs/home.md in the widgets folder explains the format.",
                schema(),
                readOnly = true,
            ) { homeLayout() },
            McpTool(
                "set_home_layout",
                "Replaces home's layout: the whole ${HomeStore.FILE}, as get_home_layout returns it, edited. It is checked " +
                    "before it's saved; home then moves to match, and what couldn't be applied (a widget or app that isn't there, " +
                    "overlapping tiles) comes back. Widgets left out stay where they are; apps left out come off home.",
                schema("toml" to prop("string", "The new ${HomeStore.FILE}, in full."), required = listOf("toml")),
            ) { setHomeLayout(it.getString("toml")) },
            McpTool(
                "list_pages",
                "Home's pages left to right as the phone shows them now: each page's number, its face or tiles (with app names), " +
                    "and which page is on screen.",
                schema(),
                readOnly = true,
            ) { listPages() },
            McpTool("list_themes", "The Omarchy themes to pick from, which is applied, and which are on the phone already.", schema(), readOnly = true) { listThemes() },
            McpTool("get_theme", "The theme applied now: its colors, its backgrounds and the one shown.", schema(), readOnly = true) { getTheme() },
            McpTool(
                "set_theme",
                "Applies an Omarchy theme to the launcher (and Linux's apps), like the theme menu; one not downloaded yet is fetched first.",
                schema(
                    "name" to prop("string", "The theme's name, as list_themes gives it (like tokyo-night)."),
                    "background" to prop("string", "One of the theme's backgrounds, as get_theme lists them; the first if left out."),
                    required = listOf("name"),
                ),
            ) { setTheme(it.getString("name"), it.optString("background").takeIf(String::isNotEmpty)) },
            McpTool(
                "get_style",
                "The launcher's style (~/${StyleStore.GUEST_DIR}/${StyleStore.FILE}): every setting with its value, default and what " +
                    "it does, and what the launcher ignored in the file.",
                schema(),
                readOnly = true,
            ) { getStyle() },
            McpTool(
                "set_style_value",
                "Changes one style setting, like home.columns or gestures.swipe-up; it is checked first and shows on the phone at once.",
                schema(
                    "key" to prop("string", "The setting's key, as get_style lists it."),
                    "value" to JSONObject().put("type", JSONArray(listOf("string", "number", "boolean", "null"))).put("description", "The new value; null puts the default back."),
                    required = listOf("key", "value"),
                ),
            ) { setStyleValue(it.getString("key"), it.get("value")) },
            McpTool(
                "list_apps",
                "The apps the launcher can open: the phone's Android apps, Linux's desktop apps and owl apps. Each key is what " +
                    "open_app and ${HomeStore.FILE}'s app = \"…\" take.",
                schema(
                    "query" to prop("string", "Only apps whose name or key contains this, ignoring case."),
                    "kind" to prop("string", "Only this kind of app.").put("enum", JSONArray(listOf("android", "linux", "owl"))),
                ),
                readOnly = true,
            ) { listApps(it.optString("query"), it.optString("kind").takeIf(String::isNotEmpty)) },
            McpTool(
                "open_app",
                "Opens an app on the phone, by its key from list_apps (an Android package, linux:<desktop id> or owl:<file>).",
                schema("app" to prop("string", "The app's key."), required = listOf("app")),
            ) { openApp(it.getString("app")) },
            McpTool(
                "show_toast",
                "Shows a short message at the bottom of the phone's screen, to tell the user something is done.",
                schema(
                    "text" to prop("string", "The message."),
                    "long" to prop("boolean", "Show it longer (about 3.5 s instead of 2)."),
                    required = listOf("text"),
                ),
            ) { args ->
                val text = args.getString("text").take(500)
                main { Toast.makeText(context, text, if (args.optBoolean("long")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
                "shown"
            },
        )

    private fun listWidgets(): JSONObject {
        val specs = main { engine.specs.toList() }
        return JSONObject()
            .put("dir", "${Proot.GUEST_HOME}/${WidgetEngine.GUEST_DIR}")
            .put(
                "widgets",
                JSONArray(
                    specs.map { spec ->
                        JSONObject()
                            .put("file", spec.file.name)
                            .put("kind", spec.kind.word)
                            .put("name", spec.name)
                            .put("compiles", spec.program != null)
                            .apply {
                                if (engine.onHome(spec) && !spec.isFace) put("page", spec.page)
                                spec.error?.let { put("error", it.lineSequence().first()) }
                            }
                    },
                ),
            )
    }

    private fun readWidget(name: String): JSONObject {
        val file = File(widgetsDir, name)
        if (!file.isFile) throw ToolError("$name: no such file in the widgets folder; list_widgets lists them")
        return JSONObject()
            .put("file", name)
            .put("path", guest(WidgetEngine.GUEST_DIR, name))
            .put("source", file.readText())
            .put("errors", errorsOf(name))
    }

    private fun errorsOf(name: String) = runCatching { File(widgetsDir, ".errors/$name.txt").readText().trimEnd() }.getOrDefault("")

    private fun writeWidget(
        name: String,
        source: String,
    ): JSONObject {
        val compiled = LauncherChecks.compile(source)
        if (!compiled.getBoolean("compiles")) throw ToolError("$name doesn't compile, so it wasn't written:\n${compiled.getString("error")}")
        val file = File(widgetsDir, name)
        writeAtomically(file, source.toByteArray())
        val loaded = awaitLoaded(name, sha256Hex(source.toByteArray()))
        return compiled
            .put("written", guest(WidgetEngine.GUEST_DIR, name))
            .put("loaded", loaded != null)
            .put("errors", if (loaded != null) errorsOf(name) else "the launcher hasn't loaded it yet; check_owl reports on it once it has")
    }

    private fun checkDir() = File(widgetsDir, ".check")

    /** The file's check record (see WidgetEngine.writeCheck) once the launcher has loaded the version with [hash]; null if it hasn't in time. */
    private fun awaitLoaded(
        name: String,
        hash: String,
    ): List<String>? =
        poll(LOAD_WAIT_MS) {
            runCatching { File(checkDir(), "$name.txt").readLines() }.getOrNull()?.takeIf { "sha256 $hash" in it }
        }

    /** owl-check, for one file. */
    private fun checkFile(
        name: String,
        preview: Boolean,
    ): JSONObject {
        val file = File(widgetsDir, name)
        if (!file.isFile) throw ToolError("$name: no such file in the widgets folder")
        val hash = sha256Hex(file.readBytes())
        val check = awaitLoaded(name, hash) ?: throw ToolError("$name: the launcher hasn't loaded this version yet; try again in a moment")
        val out = JSONObject().put("file", name)
        if ("result ok" !in check) return out.put("compiles", false).put("errors", errorsOf(name))
        out.put("compiles", true)
        if (preview) {
            fun line(kind: String) = runCatching { File(checkDir(), "$name.txt").readLines() }.getOrNull()?.firstOrNull { it.startsWith("$kind $hash") }
            if (line("preview") == null && line("preview-skipped") == null) runCatching { File(checkDir(), "$name.want").writeText(hash) }
            val shown = poll(PREVIEW_WAIT_MS) { line("preview") ?: line("preview-skipped") }
            when {
                shown == null -> out.put("preview", "none yet (the launcher may be busy); call check_owl again")
                shown.startsWith("preview-skipped") -> out.put("preview", "skipped: ${shown.split(' ', limit = 3).getOrElse(2) { "" }}")
                else -> {
                    val (_, _, cells, px) = shown.split(' ', limit = 4).let { it + List(4 - it.size) { "" } }
                    out.put("preview", JSONObject().put("path", guest(WidgetEngine.GUEST_DIR, ".preview/$name.png")).put("cells", cells).put("pixels", px))
                    line("screen")?.let { s ->
                        out.put("screen", JSONObject().put("path", guest(WidgetEngine.GUEST_DIR, ".preview/$name.screen.png")).put("pixels", s.split(' ').getOrElse(2) { "" }))
                    }
                }
            }
        }
        return out.put("errors", errorsOf(name))
    }

    private fun homeLayout(): JSONObject {
        val text = runCatching { layoutFile.readText() }.getOrDefault("")
        val columns = main { engine.layout.columns }
        val out = JSONObject().put("path", guest(HomeStore.GUEST_DIR, HomeStore.FILE)).put("toml", text)
        runCatching { HomeFile.decode(Toml.parse(text), columns) }.getOrNull()?.let { decoded ->
            out.put("columns", decoded.layout.columns).put("pages", LauncherChecks.layoutJson(decoded.layout))
        }
        return out.put("errors", JSONArray(main { engine.home.errors }))
    }

    private fun setHomeLayout(toml: String): JSONObject {
        val columns = main { engine.layout.columns }
        val problems = LauncherChecks.layoutProblems(toml, columns)
        if (problems.isNotEmpty()) throw ToolError("${HomeStore.FILE} wasn't changed:\n" + problems.joinToString("\n"))
        writeAtomically(layoutFile, (if (toml.endsWith("\n")) toml else "$toml\n").toByteArray())
        // The launcher reads it within moments of the write, and errors.txt says what it couldn't apply.
        Thread.sleep(APPLY_WAIT_MS)
        return JSONObject().put("written", guest(HomeStore.GUEST_DIR, HomeStore.FILE)).put("problems", JSONArray(main { engine.home.errors }))
    }

    private fun listPages(): JSONObject =
        main {
            val pages =
                engine.pages.map { page ->
                    val items = engine.itemsOn(page)
                    val out = JSONObject().put("page", page)
                    items.firstOrNull { it.isFace }?.let { out.put("face", it.key) }
                    out.put(
                        "tiles",
                        JSONArray(
                            items.filterNot { it.isFace }.map { item ->
                                val tile =
                                    when (item) {
                                        is WidgetItem -> JSONObject().put("widget", item.key).put("name", item.spec.name)
                                        is Shortcut -> JSONObject().put("app", item.key).put("name", runtime.shortcutLabel(item))
                                        is Folder -> JSONObject().put("folder", item.name.orEmpty()).put("apps", JSONArray(item.apps.map { runtime.shortcutLabel(it) }))
                                        is AndroidWidget -> JSONObject().put("android", item.provider.flattenToShortString()).put("id", item.id).put("name", item.label)
                                    }
                                engine.layout.cell(item.key)?.let { c -> tile.put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h) }
                                tile
                            },
                        ),
                    )
                }
            JSONObject().put("shown", engine.activePage).put("columns", engine.layout.columns).put("pages", JSONArray(pages))
        }

    private fun listThemes(): JSONObject =
        main {
            val themes = runtime.themes
            val installed = themes.themes.map { it.name }.toSet()
            JSONObject()
                .put("current", themes.picked)
                .put(
                    "themes",
                    JSONArray(
                        themes.choices.map { t ->
                            JSONObject().put("name", t.name).put("title", t.title).put("light", t.colors["mode"] == "light").put("downloaded", t.name in installed)
                        },
                    ),
                )
        }

    private fun getTheme(): JSONObject =
        main {
            val themes = runtime.themes
            val theme = themes.current
            val out = JSONObject().put("name", themes.picked)
            if (theme != null && theme.name == themes.picked) {
                out
                    .put("title", theme.title)
                    .put("colors", JSONObject(theme.colors.toMap()))
                    .put("background", themes.background?.name ?: JSONObject.NULL)
                    .put("backgrounds", JSONArray(withContext(Dispatchers.IO) { theme.backgrounds().map { it.name } }))
            } else {
                out.put("applied", false).put("note", "not downloaded yet; it shows once it is")
            }
            out.put("errors", JSONArray(runtime.style.errors.filter { it.startsWith("theme") || it.startsWith("background") }))
        }

    private fun setTheme(
        name: String,
        background: String?,
    ): JSONObject {
        val themes = runtime.themes
        val known = main { themes.choices.map { it.name } + themes.themes.map { it.name } }
        if (name !in known) throw ToolError("$name: no such theme; list_themes lists them")
        if (background != null) {
            val theme = main { themes.themes.firstOrNull { it.name == name } } ?: throw ToolError("$name isn't downloaded yet; set it without a background first")
            val backgrounds = theme.backgrounds().map { it.name }
            if (background !in backgrounds) throw ToolError("$background isn't one of $name's backgrounds: ${backgrounds.joinToString()}")
        }
        main {
            val store = runtime.style
            val old = store.style
            store.update { it.with(Style.THEME, name).with(Style.BACKGROUND, background) }
            // Shown the way an edit of style.toml is: downloaded first if need be.
            if (store.style != old) store.onExternalChange(old, store.style)
        }
        return JSONObject().put("theme", name).put("background", background ?: "the theme's first")
    }

    private fun getStyle(): JSONObject =
        main {
            val style = runtime.style.style
            JSONObject()
                .put("path", guest(StyleStore.GUEST_DIR, StyleStore.FILE))
                .put(
                    "settings",
                    JSONArray(
                        StyleFile.ALL.map { s ->
                            JSONObject().put("key", s.key).put("value", LauncherChecks.json(style[s])).put("default", LauncherChecks.json(s.default)).put("doc", s.doc)
                        },
                    ),
                ).put("errors", JSONArray(runtime.style.errors))
        }

    private fun setStyleValue(
        key: String,
        value: Any?,
    ): JSONObject =
        main {
            val store = runtime.style
            val old = store.style
            val next = LauncherChecks.withValue(old, key, value)
            store.update { next }
            // What follows a change of the file (the theme, its background) follows this one too.
            if (next != old) store.onExternalChange(old, next)
            val setting = LauncherChecks.setting(key)
            JSONObject().put("key", key).put("value", LauncherChecks.json(next[setting])).put("changed", next != old)
        }

    private fun listApps(
        query: String,
        kind: String?,
    ): JSONObject =
        main {
            val apps = JSONArray()
            val tweaks = AppTweakStore.current
            // Apps hidden in apps.toml stay out, as in the drawer, and the others go by the names given them there.
            fun add(
                k: String,
                key: String,
                drawerKey: String,
                own: String,
                extra: JSONObject.() -> Unit = {},
            ) {
                if (kind != null && kind != k || tweaks.hidden(drawerKey)) return
                val name = tweaks.label(drawerKey, own)
                if (query.isNotEmpty() && !name.contains(query, ignoreCase = true) && !key.contains(query, ignoreCase = true)) return
                apps.put(JSONObject().put("kind", k).put("key", key).put("name", name).apply(extra))
            }
            val android = runtime.androidApps.apps.filter { !it.privateSpace }
            for (app in android) {
                val pkg = app.component.packageName
                // As layout.toml writes it: the package alone when it's the package's only app in the launcher's profile.
                val key = if (app.profile == null && android.count { it.profile == null && it.component.packageName == pkg } == 1) pkg else app.key
                add("android", key, "a:" + app.key, app.label) { if (app.profile != null) put("profile", app.profile) }
            }
            for (app in runtime.linuxApps.apps) add("linux", "linux:${app.id}", "l:" + app.id, app.name) { put("command", app.exec).put("terminal", app.terminal) }
            for (spec in engine.specs) if (spec.kind == OwlKind.App) add("owl", "owl:${spec.file.name}", "o:" + spec.file.name, spec.name) { put("runs", spec.program != null) }
            JSONObject().put("apps", apps)
        }

    private fun openApp(key: String): String =
        main {
            val k = key.trim()
            when {
                k.startsWith("linux:") -> {
                    val app = runtime.linuxApps.apps.firstOrNull { it.id == k.removePrefix("linux:") } ?: throw ToolError("$k: no such Linux app; list_apps lists them")
                    runtime.launch(app)
                    "opened ${app.name}"
                }
                k.startsWith("owl:") -> {
                    val name = k.removePrefix("owl:")
                    if (!engine.isApp(name)) throw ToolError("$name: not a running owl app; list_apps lists them, and check_owl says why one doesn't run")
                    runtime.openOwlApp(name)
                    "opened $name"
                }
                else -> {
                    val apps = runtime.androidApps.apps.filter { !it.privateSpace }
                    val app =
                        apps.firstOrNull { it.key == k }
                            ?: apps.firstOrNull { it.profile == null && it.component.flattenToShortString() == k }
                            ?: apps.firstOrNull { it.profile == null && it.component.packageName == k }
                            ?: throw ToolError("$k: no such app on the phone; list_apps lists them")
                    runtime.androidApps.open(app)
                    "opened ${app.label}"
                }
            }
        }

    /** Runs [get] every tenth of a second until it gives something, for at most [ms]. */
    private fun <T : Any> poll(
        ms: Long,
        get: () -> T?,
    ): T? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            get()?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            Thread.sleep(100)
        }
    }

    private companion object {
        /** A saved file takes the launcher about a second to pick up (see owl-check). */
        const val LOAD_WAIT_MS = 10_000L
        const val PREVIEW_WAIT_MS = 25_000L
        const val APPLY_WAIT_MS = 1_000L

        fun prop(
            type: String,
            description: String,
        ): JSONObject = JSONObject().put("type", type).put("description", description)

        fun schema(
            vararg properties: Pair<String, JSONObject>,
            required: List<String> = emptyList(),
        ): JSONObject =
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().apply { for ((k, v) in properties) put(k, v) })
                .put("required", JSONArray(required))
                .put("additionalProperties", false)
    }
}
