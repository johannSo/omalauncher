package dev.omalauncher.pp.mcp

import dev.omalauncher.pp.theme.Style
import dev.omalauncher.pp.widgets.HomeFile
import dev.omalauncher.pp.theme.Toml
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LauncherChecksTest {
    private fun refused(block: () -> Unit): String {
        try {
            block()
        } catch (e: ToolError) {
            return e.message.orEmpty()
        }
        fail("expected a ToolError")
        error("unreachable")
    }

    @Test fun widgetFilesAreOwlFilesInTheFolderItself() {
        assertEquals("clock.owl", LauncherChecks.widgetFile("clock.owl"))
        assertEquals("10-to_do.v2.owl", LauncherChecks.widgetFile("10-to_do.v2.owl"))
        for (bad in listOf("clock", "../clock.owl", "a/clock.owl", ".stock-drawer.owl", "", ".owl", "clock.owl.txt")) {
            refused { LauncherChecks.widgetFile(bad) }
        }
        assertEquals(".stock-drawer.owl", LauncherChecks.widgetFile(".stock-drawer.owl", hidden = true))
        refused { LauncherChecks.widgetFile("../x.owl", hidden = true) }
    }

    @Test fun compilesOwlOrSaysWhere() {
        val ok = LauncherChecks.compile("widget \"Hi\" {\n  Text { text: \"hi\" }\n}\n")
        assertTrue(ok.getBoolean("compiles"))
        assertEquals("widget", ok.getString("kind"))
        assertEquals("Hi", ok.getString("name"))

        val bad = LauncherChecks.compile("widget \"Hi\" {\n  Text { text: }\n}\n")
        assertFalse(bad.getBoolean("compiles"))
        assertTrue(bad.getString("error"), bad.getString("error").startsWith("2:"))
    }

    @Test fun layoutsAreCheckedBeforeTheyreWritten() {
        val good = "version = 1\ncolumns = 6\n\n[[page]]\ntiles = [ { widget = \"clock.owl\", x = 0, y = 0, w = 3, h = 2 }, { app = \"org.mozilla.firefox\" } ]\n"
        assertEquals(emptyList<String>(), LauncherChecks.layoutProblems(good, 6))
        assertTrue(LauncherChecks.layoutProblems("[[page]\n", 6).single().startsWith("layout.toml line 1"))
        assertTrue(LauncherChecks.layoutProblems("version = 1\n", 6).single().startsWith("no [[page]]"))
        val problems = LauncherChecks.layoutProblems("[[page]]\ntiles = [ { widget = \"a.owl\", app = \"b\" } ]\n", 6)
        assertTrue(problems.toString(), problems.single().contains("one of widget, app, folder or android"))
    }

    @Test fun layoutAsJson() {
        val text = "[[page]]\nface = \"face.owl\"\n\n[[page]]\ntiles = [ { widget = \"a.owl\", x = 1, y = 2, w = 3, h = 4 }, { folder = \"Games\", apps = [\"x\", \"y\"], x = 0, y = 0 } ]\n"
        val pages = LauncherChecks.layoutJson(HomeFile.decode(Toml.parse(text), 6).layout)
        assertEquals("face.owl", pages.getJSONObject(0).getString("face"))
        val tiles = pages.getJSONObject(1).getJSONArray("tiles")
        assertEquals("a.owl", tiles.getJSONObject(0).getString("widget"))
        assertEquals(4, tiles.getJSONObject(0).getInt("h"))
        assertEquals("Games", tiles.getJSONObject(1).getString("folder"))
        assertEquals(2, tiles.getJSONObject(1).getJSONArray("apps").length())
        // A folder is one cell; the file has no size for it.
        assertFalse(tiles.getJSONObject(1).has("w"))
    }

    @Test fun styleValuesAreCheckedLikeTheFilesAre() {
        val base = Style()
        assertEquals(5, LauncherChecks.withValue(base, "home.columns", 5)[Style.COLUMNS])
        assertEquals(5, LauncherChecks.withValue(base, "home.columns", 5.0)[Style.COLUMNS])
        assertEquals("nord", LauncherChecks.withValue(base, "theme", "nord")[Style.THEME])
        assertEquals(true, LauncherChecks.withValue(base, "icons.monochrome", true)[Style.MONOCHROME_ICONS])
        assertTrue(refused { LauncherChecks.withValue(base, "home.columns", 42) }.startsWith("home.columns takes a whole number from 4 to 9"))
        assertTrue(refused { LauncherChecks.withValue(base, "home.columns", "6") }.startsWith("home.columns takes"))
        assertTrue(refused { LauncherChecks.withValue(base, "home.colums", 6) }.startsWith("home.colums: not a setting"))
        val changed = LauncherChecks.withValue(base, "home.columns", 5)
        assertEquals(6, LauncherChecks.withValue(changed, "home.columns", JSONObject.NULL)[Style.COLUMNS])
        assertEquals(null, LauncherChecks.withValue(LauncherChecks.withValue(base, "theme", "nord"), "theme", null)[Style.THEME])
    }

    @Test fun styleValuesAsJson() {
        assertEquals(6L, LauncherChecks.json(6.0))
        assertEquals(0.5, LauncherChecks.json(0.5))
        assertEquals(true, LauncherChecks.json(true))
        assertEquals("nord", LauncherChecks.json("nord"))
        assertEquals(JSONObject.NULL, LauncherChecks.json(null))
    }
}
