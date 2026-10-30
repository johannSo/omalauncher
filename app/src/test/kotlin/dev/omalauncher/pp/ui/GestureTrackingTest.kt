package dev.omalauncher.pp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTrackingTest {
    private val d = 100f

    /** Fingers at (100, 500) and (300, 500), moved by (dax, day) and (dbx, dby). */
    private fun move(
        dax: Float,
        day: Float,
        dbx: Float,
        dby: Float,
    ) = twoFingerMove(100f, 500f, 300f, 500f, 100f + dax, 500f + day, 300f + dbx, 500f + dby, d)

    @Test fun waitsUntilFarEnough() {
        assertNull(move(0f, 0f, 0f, 0f))
        assertNull(move(0f, -60f, 0f, -60f))
        assertNull(move(30f, 0f, -30f, 0f))
    }

    @Test fun swipesTogether() {
        assertEquals(TwoFingerMove.Up, move(0f, -120f, 0f, -110f))
        assertEquals(TwoFingerMove.Down, move(10f, 150f, -10f, 140f))
        assertEquals(TwoFingerMove.Sideways, move(150f, 20f, 150f, 20f))
    }

    @Test fun pinches() {
        assertEquals(TwoFingerMove.PinchIn, move(60f, 0f, -60f, 0f))
        assertEquals(TwoFingerMove.PinchOut, move(-60f, 0f, 60f, 0f))
        // One finger still, the other moving: its midpoint moves half as far as the gap changes.
        assertEquals(TwoFingerMove.PinchIn, move(0f, 0f, -150f, 0f))
        // Diagonal fingers, drawn apart while drifting up a little.
        assertEquals(TwoFingerMove.PinchOut, twoFingerMove(200f, 400f, 300f, 600f, 150f, 280f, 350f, 700f, d))
    }

    @Test fun doubleTapsOnce() {
        val taps = DoubleTaps(timeout = 300, slop = 50f)
        assertFalse(taps.tap(10f, 10f, 0, 80))
        assertTrue(taps.tap(20f, 15f, 200, 260))
        // A third starts over.
        assertFalse(taps.tap(20f, 15f, 400, 450))
        assertTrue(taps.tap(20f, 15f, 600, 650))
    }

    @Test fun doubleTapsCloseAndSoon() {
        val taps = DoubleTaps(timeout = 300, slop = 50f)
        taps.tap(10f, 10f, 0, 80)
        // Too late: it's a first tap again, which the next one finishes.
        assertFalse(taps.tap(10f, 10f, 500, 560))
        assertTrue(taps.tap(10f, 10f, 700, 760))
        taps.tap(10f, 10f, 1_000, 1_050)
        // Too far.
        assertFalse(taps.tap(200f, 10f, 1_100, 1_150))
        taps.reset()
        assertFalse(taps.tap(200f, 10f, 1_200, 1_250))
    }
}
