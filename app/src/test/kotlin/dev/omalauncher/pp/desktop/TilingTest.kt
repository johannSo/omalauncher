package dev.omalauncher.pp.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TilingTest {
    private val area = Box(0, 0, 1600, 900)

    private fun Tiling<String>.boxes(ws: Int = current) = layout(ws, area).associate { it.window to it.box }

    private fun tiling(vararg windows: String) = Tiling<String>().apply { windows.forEach { add(it) } }

    @Test fun oneWindowFillsTheArea() {
        assertEquals(mapOf("a" to area), tiling("a").boxes())
    }

    @Test fun dwindleSplitsTheFocusedWindowAlongItsLongerSide() {
        val t = tiling("a", "b", "c")
        // b split a side by side (wide), then c split b, which is taller than wide, above and below.
        assertEquals(
            mapOf(
                "a" to Box(0, 0, 800, 900),
                "b" to Box(800, 0, 800, 450),
                "c" to Box(800, 450, 800, 450),
            ),
            t.boxes(),
        )
        assertEquals("c", t.focused())
    }

    @Test fun aNewWindowSplitsTheFocusedOneNotTheLast() {
        val t = tiling("a", "b")
        t.focus("a")
        t.add("c")
        assertEquals(Box(0, 0, 800, 450), t.boxes()["a"])
        assertEquals(Box(0, 450, 800, 450), t.boxes()["c"])
        assertEquals(Box(800, 0, 800, 900), t.boxes()["b"])
    }

    @Test fun gapsSeparateWindowsAndTheEdges() {
        val t = tiling("a", "b")
        val boxes = t.layout(0, area, gapsIn = 5, gapsOut = 10).associate { it.window to it.box }
        assertEquals(Box(10, 10, 785, 880), boxes["a"])
        assertEquals(Box(805, 10, 785, 880), boxes["b"])
        // Twice gaps_in between them, gaps_out to the edges.
        assertEquals(10, boxes.getValue("b").x - boxes.getValue("a").right)
    }

    @Test fun removingGivesTheSiblingItsHalfAndFocusGoesBack() {
        val t = tiling("a", "b", "c")
        t.focus("a")
        t.focus("c")
        t.remove("c")
        assertEquals(mapOf("a" to Box(0, 0, 800, 900), "b" to Box(800, 0, 800, 900)), t.boxes())
        assertEquals("a", t.focused())
        t.remove("a")
        t.remove("b")
        assertTrue(t.layout(0, area).isEmpty())
        assertNull(t.focused())
    }

    @Test fun workspacesKeepTheirOwnWindows() {
        val t = tiling("a")
        t.switchTo(2)
        t.add("b")
        assertEquals(mapOf("b" to area), t.boxes())
        assertEquals(listOf("a"), t.windowsOn(0))
        assertTrue(t.isOccupied(0))
        assertFalse(t.isOccupied(1))
        t.focus("a")
        assertEquals(0, t.current)
    }

    @Test fun movingToAWorkspaceLeavesTheCurrentOne() {
        val t = tiling("a", "b")
        t.moveTo("b", 4)
        assertEquals(0, t.current)
        assertEquals(listOf("a"), t.windowsOn(0))
        assertEquals(listOf("b"), t.windowsOn(4))
        assertEquals("a", t.focused())
        assertEquals("b", t.focused(4))
        assertEquals(4, t.workspaceOf("b"))
    }

    @Test fun focusMovesToTheNeighborThatWay() {
        val t = tiling("a", "b", "c")
        // a | b over c; c is focused.
        assertEquals("a", t.neighbor(Direction.Left, area))
        assertEquals("b", t.neighbor(Direction.Up, area))
        assertNull(t.neighbor(Direction.Down, area))
        assertNull(t.neighbor(Direction.Right, area))
        assertTrue(t.focusToward(Direction.Up, area))
        assertEquals("b", t.focused())
        assertTrue(t.focusToward(Direction.Left, area))
        assertEquals("a", t.focused())
        // From a, both b and c are to the right: the one focused last wins.
        assertEquals("b", t.neighbor(Direction.Right, area))
    }

    @Test fun swappingTradesPlacesAndKeepsFocus() {
        val t = tiling("a", "b")
        assertTrue(t.swapToward(Direction.Left, area))
        assertEquals(Box(0, 0, 800, 900), t.boxes()["b"])
        assertEquals(Box(800, 0, 800, 900), t.boxes()["a"])
        assertEquals("b", t.focused())
        assertFalse(t.swapToward(Direction.Left, area))
    }

    @Test fun fullscreenCoversTheAreaAlone() {
        val t = tiling("a", "b")
        t.toggleFullscreen("a")
        assertEquals(listOf(Placement("a", area, fullscreen = true)), t.layout(0, area, 5, 10))
        t.toggleFullscreen("a")
        assertEquals(2, t.layout(0, area).size)
        // A fullscreen window that leaves takes fullscreen with it.
        t.toggleFullscreen("b")
        t.remove("b")
        assertEquals(mapOf("a" to area), t.boxes())
    }

    @Test fun floatingLeavesTheTreeAndSitsOnTop() {
        val t = tiling("a", "b")
        t.toggleFloating("b", area)
        assertTrue(t.isFloating("b"))
        val placed = t.layout(0, area)
        assertEquals(listOf("a", "b"), placed.map { it.window })
        assertEquals(area, placed[0].box)
        // At its tiled size, centered.
        assertEquals(Box(400, 0, 800, 900).width, placed[1].box.width)
        assertEquals(400, placed[1].box.x)
        assertTrue(placed[1].floating)
        // Tiled again, beside a.
        t.toggleFloating("b", area)
        assertFalse(t.isFloating("b"))
        assertEquals(Box(800, 0, 800, 900), t.boxes()["b"])
    }

    @Test fun aFloatingWindowKeepsWhereItWasPlaced() {
        val t = tiling("a")
        t.add("d", floating = true)
        assertEquals(Box(400, 225, 800, 450), t.boxes()["d"])
        t.place("d", Box(100, 100, 400, 300), area)
        assertEquals(Box(100, 100, 400, 300), t.boxes()["d"])
        // On a smaller area it keeps its place in proportion.
        assertEquals(Box(50, 50, 200, 150), t.layout(0, Box(0, 0, 800, 450)).first { it.window == "d" }.box)
        t.toggleFloating("d", area)
        t.toggleFloating("d", area)
        assertEquals(Box(100, 100, 400, 300), t.boxes()["d"])
    }

    @Test fun focusRaisesAFloatingWindow() {
        val t = tiling()
        t.add("x", floating = true)
        t.add("y", floating = true)
        assertEquals(listOf("x", "y"), t.layout(0, area).map { it.window })
        t.focus("x")
        assertEquals(listOf("y", "x"), t.layout(0, area).map { it.window })
    }

    @Test fun floatBoxesStayOnTheArea() {
        assertEquals(Box(600, 0, 400, 900), FloatBox(0.9f, 0.9f, 0.4f, 1f).on(Box(0, 0, 1000, 900)))
        val off = FloatBox(1.2f, -0.5f, 0.5f, 0.5f).on(area)
        assertEquals(area.right, off.right)
        assertEquals(0, off.y)
    }

    @Test fun aWindowIsOnlyAddedOnce() {
        val t = tiling("a")
        t.add("a")
        assertEquals(listOf("a"), t.windows)
    }
}
