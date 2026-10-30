package dev.omalauncher.pp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(
        base: File,
        path: String,
        text: String,
    ) = File(base, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun export(
        files: File,
        prefs: Map<String, Map<String, *>> = emptyMap(),
    ) = ByteArrayOutputStream().also { Backup.write(files, prefs, "0.16.0", it) }.toByteArray()

    private fun names(zip: ByteArray) =
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }

    @Test fun exportsTheLauncherDataButNotLinuxOrWhatIsMadeWhileRunning() {
        val files = tmp.newFolder("files")
        file(files, "style/style.toml", "theme = \"nord\"")
        file(files, "widgets/clock.owl", "widget clock")
        file(files, "widgets/.market.json", "{}")
        file(files, "widgets/.errors/clock.owl.txt", "oops")
        file(files, "widgets/.inbox/1.req", "req")
        file(files, "widget-state/clock.json", "{}")
        file(files, "widget-state/clock.secret", "sealed")
        file(files, "themes/mine/colors.toml", "x")
        file(files, "launcher-managed.json", "{}")
        file(files, "arch/etc/passwd", "root")
        file(files, "omarchy/themes/nord/colors.toml", "x")
        val names = names(export(files)).toSet()
        assertTrue("omalauncher-backup.json" in names)
        assertTrue("prefs.json" in names)
        for (path in listOf("style/style.toml", "widgets/clock.owl", "widgets/.market.json", "widget-state/clock.json", "themes/mine/colors.toml", "launcher-managed.json")) {
            assertTrue(path, "files/$path" in names)
        }
        for (path in listOf("widgets/.errors/clock.owl.txt", "widgets/.inbox/1.req", "widget-state/clock.secret", "arch/etc/passwd", "omarchy/themes/nord/colors.toml")) {
            assertFalse(path, "files/$path" in names)
        }
    }

    @Test fun restoresWholeDirectoriesAndKeepsThisPhonesSecrets() {
        val old = tmp.newFolder("old")
        file(old, "style/style.toml", "theme = \"nord\"")
        file(old, "widgets/clock.owl", "widget clock")
        val zip = export(old)

        val files = tmp.newFolder("files")
        file(files, "style/style.toml", "theme = \"tokyo-night\"")
        file(files, "widgets/weather.owl", "widget weather")
        file(files, "widget-state/weather.secret", "sealed here")
        file(files, "themes/other/colors.toml", "x")
        file(files, "home/layout.toml", "[[page]]")
        file(files, "launcher-managed.json", "{\"mine\": 1}")
        file(files, "arch/etc/passwd", "root")
        val staging = File(files, ".restore")
        Backup.read(ByteArrayInputStream(zip), staging)
        Backup.restore(files, staging)

        assertEquals("theme = \"nord\"", File(files, "style/style.toml").readText())
        assertTrue(File(files, "widgets/clock.owl").exists())
        assertFalse(File(files, "widgets/weather.owl").exists())
        assertEquals("sealed here", File(files, "widget-state/weather.secret").readText())
        assertFalse(File(files, "themes/other").exists())
        assertFalse("home writes it again from the restored layout", File(files, "home/layout.toml").exists())
        assertFalse("not in the backup", File(files, "launcher-managed.json").exists())
        assertTrue("Linux isn't touched", File(files, "arch/etc/passwd").exists())
    }

    @Test fun refusesAZipThatIsNotABackup() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("files/style/style.toml"))
            z.write("x".toByteArray())
        }
        val result = runCatching { Backup.read(ByteArrayInputStream(zip.toByteArray()), tmp.newFolder("staging")) }
        assertTrue(result.exceptionOrNull() is Backup.NotABackup)
    }

    @Test fun refusesABackupFromANewerFormat() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("omalauncher-backup.json"))
            z.write("{\"format\": 99}".toByteArray())
        }
        val error = runCatching { Backup.read(ByteArrayInputStream(zip.toByteArray()), tmp.newFolder("staging")) }.exceptionOrNull()
        assertTrue((error as Backup.NotABackup).newer)
    }

    @Test fun leavesOutPathsThatWouldLeaveTheDataDir() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("omalauncher-backup.json"))
            z.write("{\"format\": 1}".toByteArray())
            for (name in listOf("files/style/../../evil", "files//abs", "files/arch/etc/passwd", "files/widgets/ok.owl")) {
                z.putNextEntry(ZipEntry(name))
                z.write("x".toByteArray())
            }
        }
        val root = tmp.newFolder("root")
        val staging = File(root, "staging")
        Backup.read(ByteArrayInputStream(zip.toByteArray()), staging)
        assertFalse(File(root, "evil").exists())
        assertFalse(File(staging, "arch").exists())
        assertTrue(File(staging, "widgets/ok.owl").exists())
    }

    @Test fun prefsKeepTheirTypesAndLeaveOutThisPhonesKeys() {
        val prefs =
            mapOf(
                "launcher" to mapOf("language" to "es", "setup_done" to true, "gpu_device" to "mali", "count" to 3, "big" to 5_000_000_000L, "scale" to 1.5f, "tags" to setOf("a", "b")),
                "themes" to mapOf("rendered_by" to "27", "mono_icons" to false),
            )
        val back = Backup.decodePrefs(JSONObject(Backup.encodePrefs(prefs).toString()))
        assertEquals(mapOf("language" to "es", "count" to 3, "big" to 5_000_000_000L, "scale" to 1.5f, "tags" to setOf("a", "b")), back["launcher"])
        assertEquals(mapOf("mono_icons" to false), back["themes"])
    }

    @Test fun androidWidgetsFromAnotherPhoneGetIdsAndroidNeverGives() {
        val items =
            JSONArray()
                .put(JSONObject().put("id", 7).put("provider", "com.a/.Clock").put("page", 1))
                .put(JSONObject().put("id", 9).put("provider", "com.b/.Weather").put("page", 2))
        val grid =
            JSONObject()
                .put("columns", 4)
                .put("cells", JSONObject().put("appwidget:7", JSONObject().put("x", 0)).put("appwidget:9", JSONObject().put("x", 1)).put("clock.owl", JSONObject().put("x", 2)))
        val prefs =
            mapOf(
                "app_widgets" to mapOf("items" to JSONObject().put("v", 1).put("data", items).toString()),
                "widget_grid" to mapOf("layout" to JSONObject().put("v", 1).put("data", grid).toString()),
            )
        // 7 is still this launcher's Clock: a backup of this phone.
        val out = Backup.rebindAndroidWidgets(prefs) { id, provider -> id == 7 && provider == "com.a/.Clock" }

        val restored = JSONObject(out.getValue("app_widgets").getValue("items") as String)
        assertEquals(1, restored.getInt("v"))
        val ids = restored.getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("id") } }
        assertEquals(listOf(7, -1), ids)
        val cells = JSONObject(out.getValue("widget_grid").getValue("layout") as String).getJSONObject("data").getJSONObject("cells")
        assertEquals(setOf("appwidget:7", "appwidget:-1", "clock.owl"), cells.keys().asSequence().toSet())
        assertEquals(1, cells.getJSONObject("appwidget:-1").getInt("x"))
    }
}
