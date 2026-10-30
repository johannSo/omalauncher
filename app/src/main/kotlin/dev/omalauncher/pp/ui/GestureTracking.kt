package dev.omalauncher.pp.ui

import kotlin.math.abs
import kotlin.math.hypot

/** What two fingers on home did, once they've gone far enough to say. */
enum class TwoFingerMove { Up, Down, PinchIn, PinchOut, Sideways }

/**
 * What two fingers that started at ([ax0], [ay0]) and ([bx0], [by0]) and are now at ([ax], [ay]) and ([bx], [by]) are
 * doing; null until either their midpoint has moved [distance] or the gap between them has changed by as much. Whichever
 * went further decides, so a pinch with one finger held still, whose midpoint moves half as far as the gap changes, is a
 * pinch; and fingers moving together, which keep their gap, swipe.
 */
fun twoFingerMove(
    ax0: Float,
    ay0: Float,
    bx0: Float,
    by0: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    distance: Float,
): TwoFingerMove? {
    val dx = ((ax - ax0) + (bx - bx0)) / 2
    val dy = ((ay - ay0) + (by - by0)) / 2
    val pinch = hypot(bx - ax, by - ay) - hypot(bx0 - ax0, by0 - ay0)
    val moved = hypot(dx, dy)
    if (abs(pinch) < distance && moved < distance) return null
    return when {
        abs(pinch) >= moved -> if (pinch < 0) TwoFingerMove.PinchIn else TwoFingerMove.PinchOut
        abs(dy) >= abs(dx) -> if (dy < 0) TwoFingerMove.Up else TwoFingerMove.Down
        // Sideways turns home's pages, which a second finger shouldn't change into something else.
        else -> TwoFingerMove.Sideways
    }
}

/**
 * Taps on empty space, told one at a time, and whether each finishes a double tap: the second down within [timeout] ms
 * of the first lifting and [slop] px of it. A third tap starts over, so tapping on doesn't fire on every other one.
 */
class DoubleTaps(
    private val timeout: Long,
    private val slop: Float,
) {
    private var pending = false
    private var upAt = 0L
    private var x = 0f
    private var y = 0f

    /** A tap at ([x], [y]) that went down at [downTime] and lifted at [upTime]; true if it's a double tap's second. */
    fun tap(
        x: Float,
        y: Float,
        downTime: Long,
        upTime: Long,
    ): Boolean {
        val second = pending && downTime - upAt in 0..timeout && hypot(x - this.x, y - this.y) <= slop
        pending = !second
        upAt = upTime
        this.x = x
        this.y = y
        return second
    }

    /** Something other than a tap on empty space came between: the next tap is a first one. */
    fun reset() {
        pending = false
    }
}
