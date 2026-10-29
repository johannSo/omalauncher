package dev.omalauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import dev.omalauncher.widgets.AndroidWidget
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Settings › backup: the launcher's own data as one zip, to bring it back on a new phone or this one. Home (its
 * pages, apps, folders and tiles), the style, the owl widgets, faces and apps with what they keep, the store's
 * records of them, and the user's themes and backgrounds. Not Linux, being gigabytes, nor Omarchy's release, which
 * downloads again. Widgets' secrets are sealed with this phone's key, so they stay on it. Android widgets are bound
 * to this phone: on another one they come back as tiles that add them again on a tap.
 */
object Backup {
    const val MIME = "application/zip"

    /** Says what the zip is; a zip without it isn't taken in. */
    private const val MANIFEST = "omalauncher-backup.json"
    private const val PREFS = "prefs.json"
    private const val FILES = "files/"
    private const val FORMAT = 1

    /** Directories in filesDir, taken whole and put back whole: one missing from a backup comes back empty. */
    internal val DIRS = listOf("style", "widgets", "widget-state", "themes", "backgrounds")

    /** Single files in filesDir: the record of which defaults are still as the launcher wrote them. */
    internal val SINGLE_FILES = listOf(LauncherData.LAUNCHER_MANIFEST)

    /** What the widgets dir holds only while the launcher runs, for agents and owl-check. */
    private val RUNTIME_DIRS = listOf("widgets/.errors/", "widgets/.check/", "widgets/.inbox/", "widgets/.preview/")

    /** Sealed with this phone's keystore key: unreadable anywhere else, so kept on the phone when a backup comes in. */
    private const val SECRET = ".secret"

    /** SharedPreferences that are home and the launcher's settings: replaced whole, but for [LOCAL_KEYS]. */
    internal val PREF_FILES = listOf("launcher", "themes", "shortcuts", "app_widgets", "widget_grid", "modes", "editor", "private_space", "app_usage")

    /**
     * Keys about this phone rather than choices: its GPU and driver, setup's progress, asking for the home role, the
     * hash of the layout.toml written here. They aren't exported, and stay as they are when a backup comes in.
     */
    internal fun local(key: String) = key.startsWith("gpu_") || key in setOf("setup_done", "home_role_asked", "home_layout_written")

    /** Which launcher rendered Linux's theme configs: not exported, so a restore drops it and the restored theme is rendered. */
    private const val RENDERED_BY = "rendered_by"

    /** Writes the backup to [uri]. IO thread. */
    fun export(
        context: Context,
        uri: Uri,
    ) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't write $uri")
        out.use { write(context.filesDir, PREF_FILES.associateWith { prefs(context, it).all }, appVersion(context), it) }
    }

    /**
     * Puts the backup at [uri] in place of the launcher's data, then starts the launcher again, so every store reads
     * it fresh. Nothing changes if it isn't a backup. [bound] tells whether an Android widget id is still this
     * launcher's, for that provider (flattened). IO thread; it doesn't return, it throws or the process ends.
     */
    fun import(
        context: Context,
        uri: Uri,
        bound: (id: Int, provider: String) -> Boolean,
    ): Nothing {
        val staging = File(context.filesDir, STAGING)
        LauncherData.deleteTree(staging)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't read $uri")
            val prefs = input.use { read(it, staging) }
            restore(context.filesDir, staging)
            for ((name, values) in rebindAndroidWidgets(prefs, bound)) replace(prefs(context, name), values)
        } finally {
            runCatching { LauncherData.deleteTree(staging) }
        }
        restart(context)
    }

    private fun prefs(
        context: Context,
        name: String,
    ) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun appVersion(context: Context) = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** The zip: [MANIFEST], [PREFS] and [FILES] from [filesDir], with no links followed. */
    internal fun write(
        filesDir: File,
        prefs: Map<String, Map<String, *>>,
        version: String,
        out: OutputStream,
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(
                name: String,
                bytes: ByteArray,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST, JSONObject().put("format", FORMAT).put("version", version).put("created", System.currentTimeMillis()).toString(2).toByteArray())
            put(PREFS, encodePrefs(prefs).toString(2).toByteArray())
            val roots = DIRS.map { File(filesDir, it) } + SINGLE_FILES.map { File(filesDir, it) }
            for (root in roots) {
                root.walkTopDown()
                    .onEnter { !Files.isSymbolicLink(it.toPath()) }
                    .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                    .forEach { file ->
                        val path = file.relativeTo(filesDir).invariantSeparatorsPath
                        if (exported(path)) {
                            zip.putNextEntry(ZipEntry(FILES + path))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
            }
        }
    }

    private fun exported(path: String) = RUNTIME_DIRS.none(path::startsWith) && !(path.startsWith("widget-state/") && path.endsWith(SECRET))

    /**
     * Reads a backup into [staging], laid out as filesDir, and returns its SharedPreferences. Throws [NotABackup]
     * without a manifest, or for a format newer than this launcher's. Entries outside [DIRS] and [SINGLE_FILES], or
     * whose path would leave [staging], are left out.
     */
    internal fun read(
        input: InputStream,
        staging: File,
    ): Map<String, Map<String, Any?>> {
        var manifest: JSONObject? = null
        var prefs: Map<String, Map<String, Any?>> = emptyMap()
        staging.mkdirs()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when (val name = entry.name) {
                    MANIFEST -> manifest = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                    PREFS -> prefs = decodePrefs(JSONObject(zip.readBytes().decodeToString()))
                    else -> {
                        val path = name.removePrefix(FILES).takeIf { name.startsWith(FILES) && safe(it) && restored(it) && exported(it) } ?: continue
                        val file = File(staging, path)
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val format = manifest?.optInt("format", 0) ?: 0
        if (format < 1) throw NotABackup()
        if (format > FORMAT) throw NotABackup(newer = true)
        return prefs
    }

    /** Relative, without `.` or `..` segments: a zip's names are anyone's to choose. */
    private fun safe(path: String): Boolean {
        val segments = path.split('/')
        return !path.startsWith("/") && '\\' !in path && segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun restored(path: String) = path in SINGLE_FILES || DIRS.any { path.startsWith("$it/") }

    /**
     * Swaps what [staging] holds for [filesDir]'s own: each of [DIRS] goes whole, keeping widgets' secrets from this
     * phone, and each of [SINGLE_FILES] is replaced or deleted. layout.toml is deleted too: home writes it again from
     * the restored layout when it starts.
     */
    internal fun restore(
        filesDir: File,
        staging: File,
    ) {
        for (dir in DIRS) {
            val current = File(filesDir, dir)
            val incoming = File(staging, dir)
            if (dir == "widget-state") {
                current.listFiles { f -> f.name.endsWith(SECRET) }?.forEach { secret ->
                    incoming.mkdirs()
                    secret.copyTo(File(incoming, secret.name), overwrite = true)
                }
            }
            LauncherData.deleteTree(current)
            if (incoming.exists() && !incoming.renameTo(current)) throw IOException("can't move $incoming to $current")
        }
        for (name in SINGLE_FILES) {
            val current = File(filesDir, name)
            val incoming = File(staging, name)
            current.delete()
            if (incoming.exists() && !incoming.renameTo(current)) throw IOException("can't move $incoming to $current")
        }
        File(filesDir, HOME_LAYOUT).delete()
    }

    /**
     * Android widget ids are the phone's: one from another phone could be a different widget here, or become one
     * once a widget is added. An id this launcher still has bound to the same provider (a backup of this phone) stays;
     * the rest get ids below zero, which Android never gives, so their tiles offer to add them again. Their cells
     * follow them in widget_grid.
     */
    internal fun rebindAndroidWidgets(
        prefs: Map<String, Map<String, Any?>>,
        bound: (id: Int, provider: String) -> Boolean,
    ): Map<String, Map<String, Any?>> {
        val widgets = prefs[ANDROID_WIDGETS]?.get(ITEMS) as? String ?: return prefs
        val renamed = HashMap<String, String>()
        var next = -1
        val items =
            versioned(widgets) { array ->
                array as JSONArray
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val id = o.getInt("id")
                    if (id > 0 && bound(id, o.optString("provider"))) continue
                    o.put("id", next)
                    renamed[AndroidWidget.keyOf(id)] = AndroidWidget.keyOf(next--)
                }
            }
        val grid =
            (prefs[GRID]?.get(LAYOUT) as? String)?.let { text ->
                versioned(text) { data ->
                    for (field in listOf("cells", "pages")) {
                        val map = (data as JSONObject).optJSONObject(field) ?: continue
                        // Old keys all out first: a new key may be one an old one had.
                        val moved = renamed.keys.filter(map::has).associateWith { map.remove(it) }
                        for ((old, value) in moved) map.put(renamed.getValue(old), value)
                    }
                }
            }
        return prefs + (ANDROID_WIDGETS to prefs.getValue(ANDROID_WIDGETS) + (ITEMS to items)) +
            (if (grid != null) mapOf(GRID to prefs.getValue(GRID) + (LAYOUT to grid)) else emptyMap())
    }

    /** Changes the data of a `{"v": N, "data": …}` document (see [dev.omalauncher.upgrade.Versioned]) in place, keeping its version. */
    private fun versioned(
        text: String,
        change: (Any) -> Unit,
    ): String {
        val root = JSONTokener(text).nextValue()
        val wrapped = root is JSONObject && root.has("v") && root.has("data")
        change(if (wrapped) (root as JSONObject).get("data") else root)
        return root.toString()
    }

    /** [prefs] holds [values] and keeps its own [local] keys; written before the launcher restarts, so not applied later. */
    private fun replace(
        prefs: SharedPreferences,
        values: Map<String, Any?>,
    ) {
        val edit = prefs.edit()
        for (key in prefs.all.keys) if (!local(key)) edit.remove(key)
        for ((key, value) in values) {
            if (local(key)) continue
            when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        // Not apply(): the process ends right after.
        edit.commit()
    }

    /** Each value with its type, as SharedPreferences tells an Int from a Long, and JSON doesn't. */
    internal fun encodePrefs(prefs: Map<String, Map<String, *>>): JSONObject {
        val json = JSONObject()
        for ((name, values) in prefs) {
            val file = JSONObject()
            for ((key, value) in values) {
                if (local(key) || key == RENDERED_BY) continue
                val type =
                    when (value) {
                        is String -> "string"
                        is Int -> "int"
                        is Long -> "long"
                        is Float -> "float"
                        is Boolean -> "boolean"
                        is Set<*> -> "set"
                        else -> continue
                    }
                file.put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
            }
            json.put(name, file)
        }
        return json
    }

    /** What [encodePrefs] wrote, for [PREF_FILES] only; values it can't read are left out. */
    internal fun decodePrefs(json: JSONObject): Map<String, Map<String, Any?>> =
        PREF_FILES.filter(json::has).associateWith { name ->
            val file = json.getJSONObject(name)
            file.keys().asSequence().mapNotNull { key ->
                val o = file.optJSONObject(key) ?: return@mapNotNull null
                val value: Any? =
                    runCatching {
                        when (o.optString("type")) {
                            "string" -> o.getString("value")
                            "int" -> o.getInt("value")
                            "long" -> o.getLong("value")
                            "float" -> o.getDouble("value").toFloat()
                            "boolean" -> o.getBoolean("value")
                            "set" -> o.getJSONArray("value").let { a -> (0 until a.length()).map(a::getString).toSet() }
                            else -> null
                        }
                    }.getOrNull()
                value?.let { key to it }
            }.toMap()
        }

    /** Home opens on what was restored, from a new process: every store read its data when it started. */
    private fun restart(context: Context): Nothing {
        val home = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
        context.startActivity(home)
        exitProcess(0)
    }

    /** Where a backup is unpacked before anything is replaced; in filesDir, so moving it in is a rename. */
    private const val STAGING = ".restore"
    private const val HOME_LAYOUT = "home/layout.toml"
    private const val ANDROID_WIDGETS = "app_widgets"
    private const val ITEMS = "items"
    private const val GRID = "widget_grid"
    private const val LAYOUT = "layout"

    /** Not an omalauncher backup, or [newer]: from a launcher whose backups this one can't read. */
    class NotABackup(
        val newer: Boolean = false,
    ) : IOException(if (newer) "backup from a newer launcher" else "not an omalauncher backup")
}
