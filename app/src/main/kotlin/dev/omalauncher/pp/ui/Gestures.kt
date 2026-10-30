package dev.omalauncher.pp.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import android.content.Context
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.pp.LauncherData
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.theme.GestureAction
import dev.omalauncher.pp.theme.GestureSetting
import dev.omalauncher.pp.theme.GestureStyle
import dev.omalauncher.pp.theme.Style
import dev.omalauncher.pp.theme.StyleStore
import dev.omalauncher.pp.theme.panel
import dev.omalauncher.pp.widgets.WidgetEngine
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * What each of home's gestures does ([HomeGesture]), kept in style.toml as a [GestureAction] so agents can set them too:
 * swiping up opens drawer.owl, the app drawer, by default; the rest do nothing until picked.
 */
class Gestures(
    /** The app's, for home's error banner and settings, which say what a gesture does. */
    internal val context: Context,
    private val style: StyleStore,
) {
    /** What [gesture] does; one style.toml has no action for reads as nothing. Saved as it's set. */
    operator fun get(gesture: HomeGesture): GestureAction = GestureAction.parseOrNull(LauncherStyle.style[gesture.setting]) ?: GestureAction.None

    operator fun set(
        gesture: HomeGesture,
        action: GestureAction,
    ) = style.update { it.with(gesture.setting, action.encoded) }

    /** drawer.owl by default: the app drawer, like stock launchers, written in owl. As style.toml has it. */
    var swipeUp: String
        get() = LauncherStyle.style[Style.SWIPE_UP]
        set(choice) = style.update { it.with(Style.SWIPE_UP, GestureAction.parse(choice).encoded) }

    /** Nothing by default. As style.toml has it. */
    var swipeDown: String
        get() = LauncherStyle.style[Style.SWIPE_DOWN]
        set(choice) = style.update { it.with(Style.SWIPE_DOWN, GestureAction.parse(choice).encoded) }

    init {
        // Launchers before style.toml kept swipe up in preferences; moved over once, and left there for a downgrade.
        val prefs = LauncherData.prefs(context)
        if (!prefs.getBoolean(MOVED, false)) {
            if (prefs.getString(OLD_SWIPE_UP, null) == NONE) swipeUp = NONE
            prefs.edit().putBoolean(MOVED, true).apply()
        }
    }

    companion object {
        val NONE = GestureAction.None.encoded
        private const val OLD_SWIPE_UP = "gesture_swipe_up"
        private const val MOVED = "gestures_in_style"
    }
}

/** Home's gestures, in the order settings › gestures lists them: [setting] keeps what each does, [label] names it there. */
enum class HomeGesture(
    val setting: GestureSetting,
    @StringRes val label: Int,
) {
    SwipeUp(Style.SWIPE_UP, R.string.gestures_swipe_up),
    SwipeDown(Style.SWIPE_DOWN, R.string.gestures_swipe_down),
    DoubleTap(GestureStyle.DOUBLE_TAP, R.string.gesture_double_tap),
    TwoFingerUp(GestureStyle.TWO_FINGER_UP, R.string.gesture_two_finger_up),
    TwoFingerDown(GestureStyle.TWO_FINGER_DOWN, R.string.gesture_two_finger_down),
    PinchIn(GestureStyle.PINCH_IN, R.string.gesture_pinch_in),
    PinchOut(GestureStyle.PINCH_OUT, R.string.gesture_pinch_out),
    ;

    companion object {
        /** The ones made with two fingers, which home only watches for while one of them does something. */
        val TWO_FINGERS = listOf(TwoFingerUp, TwoFingerDown, PinchIn, PinchOut)
    }
}

/** The panel [choice] names, if it's one that runs; null for another action, and a panel that's gone or broken. */
internal fun Runtime.panelOf(choice: String) = GestureAction.parseOrNull(choice)?.panel?.takeIf { widgets.panelInstance(it) != null }

/** What [choice] is called in settings: nothing, the notification shade, the panel's name (its file's, while it has none), … */
internal fun Runtime.gestureLabel(choice: String) = actionLabel(GestureAction.parseOrNull(choice) ?: GestureAction.None)

/**
 * Picked panels that don't compile, drawer.owl and keybar.owl, for home's error banner: without a tile, nothing else would say so,
 * and the drawer quietly shows another until they're fixed.
 */
internal fun Runtime.gestureProblems(): List<Pair<String, List<String>>> {
    val context = gestures.context
    val drawer =
        widgets.drawerPanel()?.let { if (it == WidgetEngine.STOCK_DRAWER) context.getString(R.string.gestures_stock_drawer) else gestureLabel(it) }
            ?: context.getString(R.string.gestures_nothing)
    val drawerShows = context.getString(R.string.gestures_drawer_shows, drawer)
    // Swiping down and the others open nothing meanwhile.
    val meanwhile =
        linkedMapOf(gestures[HomeGesture.SwipeUp].panel to drawerShows, WidgetEngine.DRAWER to drawerShows) +
            (gestures[HomeGesture.SwipeDown].panel to context.getString(R.string.gestures_swipe_down_nothing)) +
            (HomeGesture.entries - HomeGesture.SwipeUp - HomeGesture.SwipeDown).map { g -> gestures[g].panel to context.getString(R.string.gesture_opens_nothing, context.getString(g.label)) }
    val panels =
        meanwhile.mapNotNull { (choice, what) ->
            choice ?: return@mapNotNull null
            val spec = widgets.panels().firstOrNull { it.file.name == choice && it.error != null } ?: return@mapNotNull null
            spec.file.name to listOf(context.getString(R.string.gestures_until_fixed, what, spec.error!!.lineSequence().first()))
        }
    // keybar.owl too: the stock keys stand in for it as quietly.
    val keys = widgets.specs.firstOrNull { it.file.name == WidgetEngine.KEYBAR && it.error != null }?.let { it.file.name to listOf(context.getString(R.string.gestures_keybar_until_fixed, it.error!!.lineSequence().first())) }
    return panels + listOfNotNull(keys)
}

/**
 * Dragging up opens the drawer under the finger and dragging down closes it, as on stock launchers: the panel
 * follows the drag and a fling or how far it got decides where it settles. Scrollables inside (a widget's
 * list, the recent apps strip) take their own direction first.
 */
@Composable
internal fun Modifier.drawerSwipe(
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled,
        state = rememberDraggableState { dy -> state.dragBy(-dy / screenHeight) },
        onDragStopped = { velocity -> state.settle(velocity) },
    )
}

/** What a drag on home turned out to be, decided by where the drawer is and which way it first moves. */
private enum class HomeDrag { Undecided, Drawer, Pull, Shade, Action, Ignored }

/** The drag under way: not state, nothing draws from it. */
private class DragTrack {
    var kind = HomeDrag.Undecided

    /** For [HomeDrag.Action]: the swipe's, done once it's gone far enough. */
    var action: GestureAction = GestureAction.None

    /** How far it went, in px, positive = down. */
    var travelled = 0f
}

/**
 * Swiping on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings: up for the
 * drawer or a panel in its place, down for a panel from the top, either way any other [GestureAction]. With the drawer
 * open, the swipe drags it whatever the setting, so it can still be pulled closed.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val gestures = runtime.gestures
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val drags = gestures[HomeGesture.SwipeUp] != GestureAction.None || gestures[HomeGesture.SwipeDown] != GestureAction.None || drawerShown
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val far = with(LocalDensity.current) { GestureDistance.toPx() }
    val drag = remember { DragTrack() }
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled && drags,
        state =
            rememberDraggableState { dy ->
                if (drag.kind == HomeDrag.Undecided && dy != 0f) {
                    val action = gestures[if (dy < 0f) HomeGesture.SwipeUp else HomeGesture.SwipeDown]
                    drag.kind =
                        when {
                            state.drawer.value > 0f -> HomeDrag.Drawer
                            action == GestureAction.None -> HomeDrag.Ignored
                            // Up, a panel is what the drawer shows: it comes up with the finger.
                            dy < 0f && (action == GestureAction.Drawer || action is GestureAction.OpenPanel) -> HomeDrag.Drawer
                            // Android's own shade comes down by itself, as on stock launchers.
                            action == GestureAction.Shade -> HomeDrag.Shade.also { expandNotificationShade(gestures.context) }
                            action == GestureAction.QuickSettings -> HomeDrag.Shade.also { expandQuickSettings(gestures.context) }
                            // Picked as the drag starts: it comes down from out of sight above with the finger.
                            action is GestureAction.OpenPanel -> runtime.panelOf(action.encoded)?.let { name -> state.pulled = name; HomeDrag.Pull } ?: HomeDrag.Ignored
                            // The rest wait for the swipe to go far enough, so a slight one locks nothing.
                            else -> HomeDrag.Action.also { drag.action = action }
                        }
                }
                when (drag.kind) {
                    HomeDrag.Drawer -> state.dragBy(-dy / screenHeight)
                    HomeDrag.Pull -> state.pullBy(dy / screenHeight)
                    HomeDrag.Action -> drag.travelled += dy
                    else -> {}
                }
            },
        onDragStarted = {
            drag.kind = HomeDrag.Undecided
            drag.travelled = 0f
            state.startPull()
        },
        onDragStopped = { velocity ->
            when (drag.kind) {
                HomeDrag.Drawer -> state.settle(velocity)
                HomeDrag.Pull -> state.settlePull(velocity)
                // Far enough, or flung, the way it started; not when turned back.
                HomeDrag.Action ->
                    if (kotlin.math.abs(drag.travelled) >= far || (velocity * drag.travelled > 0f && kotlin.math.abs(velocity) > FLING)) {
                        runtime.perform(drag.action, state)
                    }
                else -> {}
            }
            drag.kind = HomeDrag.Undecided
        },
    )
}

/** How far a swipe or a pinch goes before it counts, where nothing follows the finger. */
internal val GestureDistance = 56.dp

/** px/s that make a short swipe count, as [LauncherState.settle]'s. */
private const val FLING = 800f

/**
 * Dragging the open swipe-down panel moves it under the finger; it settles open or closed like the drawer. A list in
 * it scrolls first: pushing up past its end moves the panel on, as pulling down past the drawer's top closes it.
 */
@Composable
internal fun Modifier.pullSwipe(state: LauncherState): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val pushToClose =
        remember(state, screenHeight) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Partly pushed up and dragged back down: it comes back before the list scrolls.
                    if (source != NestedScrollSource.UserInput || available.y <= 0f || state.pull.value >= 1f) return Offset.Zero
                    state.pullBy(available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                    if (state.pull.value >= 1f) state.startPull()
                    state.pullBy(available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.pull.value >= 1f) return Velocity.Zero
                    state.settlePull(available.y)
                    return available
                }
            }
        }
    return nestedScroll(pushToClose).draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { dy -> state.pullBy(dy / screenHeight) },
        onDragStarted = { state.startPull() },
        onDragStopped = { velocity -> state.settlePull(velocity) },
    )
}

/** Just under the anchor, lined up with its end, and kept on screen. */
internal object BelowAnchorEnd : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}
