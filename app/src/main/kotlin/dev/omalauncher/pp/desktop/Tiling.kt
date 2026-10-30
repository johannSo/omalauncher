package dev.omalauncher.pp.desktop

import kotlin.math.roundToInt

/** A rectangle in pixels. */
data class Box(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right get() = x + width
    val bottom get() = y + height
    val centerX get() = x + width / 2f
    val centerY get() = y + height / 2f

    /** Smaller by [n] on every side, never below nothing. */
    fun inset(n: Int) = Box(x + n, y + n, (width - 2 * n).coerceAtLeast(0), (height - 2 * n).coerceAtLeast(0))
}

enum class Direction { Left, Right, Up, Down }

/**
 * A floating window's place as fractions of the workspace's area, so it keeps its place when the area changes size
 * (the bar shows, the monitor's resolution changes, the workspace moves to another display).
 */
data class FloatBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun on(area: Box): Box {
        val w = (width * area.width).roundToInt().coerceIn(1, area.width.coerceAtLeast(1))
        val h = (height * area.height).roundToInt().coerceIn(1, area.height.coerceAtLeast(1))
        // Kept on the area, so a window can't be lost off its edge.
        val left = (area.x + x * area.width).roundToInt().coerceIn(area.x, (area.right - w).coerceAtLeast(area.x))
        val top = (area.y + y * area.height).roundToInt().coerceIn(area.y, (area.bottom - h).coerceAtLeast(area.y))
        return Box(left, top, w, h)
    }

    companion object {
        /** [box] on [area] as fractions of it. */
        fun of(
            box: Box,
            area: Box,
        ) = FloatBox(
            (box.x - area.x).toFloat() / area.width.coerceAtLeast(1),
            (box.y - area.y).toFloat() / area.height.coerceAtLeast(1),
            box.width.toFloat() / area.width.coerceAtLeast(1),
            box.height.toFloat() / area.height.coerceAtLeast(1),
        )

        /** Centered, [w] by [h] of the area. */
        fun centered(
            w: Float,
            h: Float,
        ) = FloatBox((1 - w) / 2, (1 - h) / 2, w, h)
    }
}

/** Where a window goes: [box] in pixels, and how it's shown there. */
data class Placement<W>(
    val window: W,
    val box: Box,
    val floating: Boolean = false,
    val fullscreen: Boolean = false,
)

/**
 * Hyprland's dwindle layout over numbered workspaces, without Android in it: windows are any [W] compared by equality.
 *
 * Each workspace's tiled windows are the leaves of a binary tree. A new window splits the focused tiled window's leaf
 * in two, the new one second (right of it or below it); which way a split cuts is decided when it's laid out, by its
 * box's shape: wider than tall side by side, else one above the other, as Hyprland does without preserve_split. A
 * window going away gives its half to its sibling. Floating windows sit above the tiled ones in the order they were
 * last focused, each at a [FloatBox]. One window per workspace can be fullscreen: it then covers the area alone.
 */
class Tiling<W : Any>(
    /** How many workspaces; Super+1..9. */
    val count: Int = 9,
) {
    private sealed class Node {
        class Leaf<W>(
            var window: W,
        ) : Node()

        class Split(
            var first: Node,
            var second: Node,
        ) : Node()
    }

    private inner class Workspace {
        var root: Node? = null

        /** Bottom to top. */
        val floating = ArrayList<W>()

        /** Least to most recently focused: where focus goes when a window leaves. */
        val history = ArrayList<W>()
        var fullscreen: W? = null

        val focused get() = history.lastOrNull()
    }

    private val spaces = List(count) { Workspace() }

    /** Floating windows' places, kept when one is tiled again so floating it once more puts it back. */
    private val floats = HashMap<W, FloatBox>()

    /** The workspace on screen, from 0. */
    var current = 0
        private set

    val windows: List<W> get() = spaces.flatMap { tiledOf(it) + it.floating }

    operator fun contains(window: W) = workspaceOf(window) != null

    fun workspaceOf(window: W): Int? = spaces.indexOfFirst { window in it.history }.takeIf { it >= 0 }

    /** The windows on workspace [ws]: tiled ones in the tree's order, then floating ones bottom to top. */
    fun windowsOn(ws: Int): List<W> = spaces[ws].let { tiledOf(it) + it.floating }

    fun isOccupied(ws: Int) = spaces[ws].history.isNotEmpty()

    fun focused(ws: Int = current): W? = spaces[ws].focused

    fun isFloating(window: W) = spaces.any { window in it.floating }

    fun isFullscreen(window: W) = spaces.any { it.fullscreen == window }

    /**
     * Puts [window] on workspace [ws] and focuses it there: tiled beside that workspace's focused tiled window (else its
     * last one), or [floating] at [at], else where it last floated, else centered.
     */
    fun add(
        window: W,
        ws: Int = current,
        floating: Boolean = false,
        at: FloatBox? = null,
    ) {
        if (window in this) return
        val space = spaces[ws]
        if (floating) {
            floats[window] = at ?: floats[window] ?: DEFAULT_FLOAT
            space.floating += window
        } else {
            insertTiled(space, window)
        }
        space.history += window
    }

    /** Takes [window] off its workspace; focus there goes to the window focused before it. */
    fun remove(window: W) {
        val space = spaces.firstOrNull { window in it.history } ?: return
        if (!space.floating.remove(window)) space.root = space.root?.let { without(it, window) }
        space.history -= window
        if (space.fullscreen == window) space.fullscreen = null
    }

    /** Forgets [window] entirely, its floating place too: it's gone. */
    fun forget(window: W) {
        remove(window)
        floats -= window
    }

    /** Focuses [window] and shows its workspace; a floating window comes to the top. */
    fun focus(window: W) {
        val ws = workspaceOf(window) ?: return
        val space = spaces[ws]
        space.history -= window
        space.history += window
        if (space.floating.remove(window)) space.floating += window
        current = ws
    }

    fun switchTo(ws: Int) {
        if (ws in 0 until count) current = ws
    }

    /** Moves [window] to workspace [ws], tiled or floating as it was; the current workspace stays. */
    fun moveTo(
        window: W,
        ws: Int,
    ) {
        val from = workspaceOf(window) ?: return
        if (from == ws || ws !in 0 until count) return
        val floating = isFloating(window)
        remove(window)
        add(window, ws, floating)
    }

    /** Fullscreen on its workspace, or back. */
    fun toggleFullscreen(window: W) {
        val space = spaces[workspaceOf(window) ?: return]
        space.fullscreen = if (space.fullscreen == window) null else window
    }

    /**
     * Floats a tiled [window], where it last floated or else centered at its tiled size on [area] (but at least
     * [DEFAULT_FLOAT]'s); tiles a floating one beside the focused tiled window.
     */
    fun toggleFloating(
        window: W,
        area: Box,
    ) {
        val ws = workspaceOf(window) ?: return
        val space = spaces[ws]
        if (space.floating.remove(window)) {
            insertTiled(space, window, near = space.history.lastOrNull { it != window && it !in space.floating })
            return
        }
        val tiled = layout(ws, area).firstOrNull { it.window == window }?.box
        space.root = space.root?.let { without(it, window) }
        if (space.fullscreen == window) space.fullscreen = null
        floats.getOrPut(window) {
            val w = maxOf(DEFAULT_FLOAT.width, (tiled?.width ?: 0).toFloat() / area.width.coerceAtLeast(1))
            val h = maxOf(DEFAULT_FLOAT.height, (tiled?.height ?: 0).toFloat() / area.height.coerceAtLeast(1))
            FloatBox.centered(minOf(w, 0.9f), minOf(h, 0.9f))
        }
        space.floating += window
    }

    /** Where floating [window] goes on [area] from now on; a window moved or resized with the mouse. */
    fun place(
        window: W,
        box: Box,
        area: Box,
    ) {
        if (isFloating(window)) floats[window] = FloatBox.of(box, area)
    }

    /**
     * Workspace [ws] on [area], bottom to top: the tiled windows each [gapsIn] in from their box, so two are twice that
     * apart and [gapsOut] from the area's edges, then the floating ones. A fullscreen window covers the area alone.
     */
    fun layout(
        ws: Int,
        area: Box,
        gapsIn: Int = 0,
        gapsOut: Int = 0,
    ): List<Placement<W>> {
        val space = spaces[ws]
        space.fullscreen?.let { return listOf(Placement(it, area, floating = it in space.floating, fullscreen = true)) }
        val out = ArrayList<Placement<W>>()
        val outer = (gapsOut - gapsIn).coerceAtLeast(0)
        val inner = if (gapsOut < gapsIn) gapsOut else gapsIn
        space.root?.let { place(it, area.inset(outer), inner, out) }
        for (w in space.floating) out += Placement(w, (floats[w] ?: DEFAULT_FLOAT).on(area), floating = true)
        return out
    }

    /**
     * The window on the current workspace that's next to the focused one toward [dir] on [area]: of those whose center
     * is past the focused window's edge that way, the ones beside it (overlapping it across [dir]) first, then the
     * nearest, then the most recently focused.
     */
    fun neighbor(
        dir: Direction,
        area: Box,
    ): W? {
        val space = spaces[current]
        val focused = space.focused ?: return null
        val boxes = layout(current, area)
        val from = boxes.firstOrNull { it.window == focused }?.box ?: return null
        return boxes
            .filter { it.window != focused && beyond(from, it.box, dir) }
            .minWithOrNull(
                compareBy<Placement<W>> { if (overlaps(from, it.box, dir)) 0 else 1 }
                    .thenBy { distance(from, it.box, dir) }
                    .thenByDescending { space.history.indexOf(it.window) },
            )?.window
    }

    /** Focuses [neighbor] toward [dir]; false if there's none. */
    fun focusToward(
        dir: Direction,
        area: Box,
    ): Boolean {
        val next = neighbor(dir, area) ?: return false
        focus(next)
        return true
    }

    /** Swaps the focused tiled window with its tiled [neighbor] toward [dir]; it keeps the focus. False if it can't. */
    fun swapToward(
        dir: Direction,
        area: Box,
    ): Boolean {
        val space = spaces[current]
        val focused = space.focused ?: return false
        if (focused in space.floating) return false
        val other = neighbor(dir, area)?.takeIf { it !in space.floating } ?: return false
        val root = space.root ?: return false
        val a = leafOf(root, focused) ?: return false
        val b = leafOf(root, other) ?: return false
        a.window = other
        b.window = focused
        return true
    }

    private fun insertTiled(
        space: Workspace,
        window: W,
        near: W? = space.history.lastOrNull { it !in space.floating },
    ) {
        val root = space.root
        if (root == null) {
            space.root = Node.Leaf(window)
            return
        }
        val target = near?.let { leafOf(root, it) } ?: lastLeaf(root)
        space.root = replace(root, target, Node.Split(Node.Leaf(target.window), Node.Leaf(window)))
    }

    private fun tiledOf(space: Workspace): List<W> = space.root?.let { leaves(it) }.orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun leaves(node: Node): List<W> =
        when (node) {
            is Node.Leaf<*> -> listOf(node.window as W)
            is Node.Split -> leaves(node.first) + leaves(node.second)
        }

    @Suppress("UNCHECKED_CAST")
    private fun leafOf(
        node: Node,
        window: W,
    ): Node.Leaf<W>? =
        when (node) {
            is Node.Leaf<*> -> (node as Node.Leaf<W>).takeIf { it.window == window }
            is Node.Split -> leafOf(node.first, window) ?: leafOf(node.second, window)
        }

    @Suppress("UNCHECKED_CAST")
    private fun lastLeaf(node: Node): Node.Leaf<W> =
        when (node) {
            is Node.Leaf<*> -> node as Node.Leaf<W>
            is Node.Split -> lastLeaf(node.second)
        }

    /** [node] with [target] (a node in it) replaced by [with]. */
    private fun replace(
        node: Node,
        target: Node,
        with: Node,
    ): Node =
        when {
            node === target -> with
            node is Node.Split -> node.apply {
                first = replace(first, target, with)
                second = replace(second, target, with)
            }
            else -> node
        }

    /** [node] without [window]'s leaf, its sibling taking its parent's place; null if nothing's left. */
    @Suppress("UNCHECKED_CAST")
    private fun without(
        node: Node,
        window: W,
    ): Node? =
        when (node) {
            is Node.Leaf<*> -> if ((node as Node.Leaf<W>).window == window) null else node
            is Node.Split -> {
                val first = without(node.first, window)
                val second = without(node.second, window)
                when {
                    first == null -> second
                    second == null -> first
                    else -> node.apply {
                        this.first = first
                        this.second = second
                    }
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun place(
        node: Node,
        box: Box,
        gap: Int,
        out: MutableList<Placement<W>>,
    ) {
        when (node) {
            is Node.Leaf<*> -> out += Placement(node.window as W, box.inset(gap))
            is Node.Split ->
                if (box.width >= box.height) {
                    val half = box.width / 2
                    place(node.first, Box(box.x, box.y, half, box.height), gap, out)
                    place(node.second, Box(box.x + half, box.y, box.width - half, box.height), gap, out)
                } else {
                    val half = box.height / 2
                    place(node.first, Box(box.x, box.y, box.width, half), gap, out)
                    place(node.second, Box(box.x, box.y + half, box.width, box.height - half), gap, out)
                }
        }
    }

    private fun beyond(
        from: Box,
        to: Box,
        dir: Direction,
    ) = when (dir) {
        Direction.Left -> to.centerX < from.x
        Direction.Right -> to.centerX > from.right
        Direction.Up -> to.centerY < from.y
        Direction.Down -> to.centerY > from.bottom
    }

    private fun overlaps(
        from: Box,
        to: Box,
        dir: Direction,
    ) = when (dir) {
        Direction.Left, Direction.Right -> to.y < from.bottom && to.bottom > from.y
        Direction.Up, Direction.Down -> to.x < from.right && to.right > from.x
    }

    private fun distance(
        from: Box,
        to: Box,
        dir: Direction,
    ): Int =
        when (dir) {
            Direction.Left -> from.x - to.right
            Direction.Right -> to.x - from.right
            Direction.Up -> from.y - to.bottom
            Direction.Down -> to.y - from.bottom
        }.coerceAtLeast(0)

    companion object {
        /** Where a window floats that never has: centered, half the area. */
        val DEFAULT_FLOAT = FloatBox.centered(0.5f, 0.5f)
    }
}
