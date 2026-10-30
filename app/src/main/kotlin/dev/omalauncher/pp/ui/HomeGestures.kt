package dev.omalauncher.pp.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import dev.omalauncher.pp.GestureAccessibility
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.linux.LinuxState
import dev.omalauncher.pp.linux.agentInvocation
import dev.omalauncher.pp.theme.GestureAction

/**
 * Which press home's grid last found on empty space, not on a tile: the grid knows where its tiles are, so it says
 * ([pressed]) and double-tapping only counts there. Not state; nothing draws from it.
 */
internal class EmptyPresses {
    private var last: PointerId? = null

    fun pressed(id: PointerId) {
        last = id
    }

    fun wasEmpty(id: PointerId) = last == id
}

/** How soon a second finger has to follow the first for the two to be one gesture, rather than a swipe or a press already under way. */
private const val TOGETHER_MS = 250L

/**
 * Double-tapping empty space and two-finger swipes and pinches on home, each doing what gesture settings say. Only
 * watched for while one of them does something, so home's touches are as they were until then.
 *
 * Two fingers that land together are home's: from the second one on, every touch of the gesture is taken before the
 * pages, the tiles and their widgets see it (they get it as cancelled), so a pinch neither turns a page nor scrolls a
 * widget's list, and a long press doesn't lift a tile. A second finger that comes later, once the first is dragging a
 * tile, turning a page or scrolling, leaves that be. A double tap is two taps nothing else took, both on empty space
 * ([empty], which the grid tells).
 */
@Composable
internal fun Modifier.homeGestures(
    runtime: Runtime,
    state: LauncherState,
    empty: EmptyPresses,
    enabled: Boolean,
): Modifier {
    val gestures = runtime.gestures
    val twoFingers = enabled && HomeGesture.TWO_FINGERS.any { gestures[it] != GestureAction.None }
    val doubleTap = enabled && gestures[HomeGesture.DoubleTap] != GestureAction.None
    val distance = with(LocalDensity.current) { GestureDistance.toPx() }
    val fingers =
        if (!twoFingers) {
            Modifier
        } else {
            Modifier.pointerInput(runtime, state, distance) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // The second finger, while the first holds still.
                    var second = first
                    while (second === first) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val a = event.changes.firstOrNull { it.id == first.id }
                        if (a == null || !a.pressed || (a.position - first.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                        val b = event.changes.firstOrNull { it.id != first.id && it.pressed && !it.previousPressed } ?: continue
                        if (b.uptimeMillis - first.uptimeMillis > TOGETHER_MS) return@awaitEachGesture
                        event.changes.forEach { it.consume() }
                        second = b
                    }
                    val a0 = first.position
                    val b0 = second.position
                    var decided = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                        if (decided) continue
                        val a = event.changes.firstOrNull { it.id == first.id }?.takeIf { it.pressed }?.position
                        val b = event.changes.firstOrNull { it.id == second.id }?.takeIf { it.pressed }?.position
                        // One lifted before the two said what they were doing: nothing, and the rest is still eaten.
                        if (a == null || b == null) {
                            decided = true
                            continue
                        }
                        val move = twoFingerMove(a0.x, a0.y, b0.x, b0.y, a.x, a.y, b.x, b.y, distance) ?: continue
                        decided = true
                        val gesture =
                            when (move) {
                                TwoFingerMove.Up -> HomeGesture.TwoFingerUp
                                TwoFingerMove.Down -> HomeGesture.TwoFingerDown
                                TwoFingerMove.PinchIn -> HomeGesture.PinchIn
                                TwoFingerMove.PinchOut -> HomeGesture.PinchOut
                                TwoFingerMove.Sideways -> null
                            }
                        gesture?.let { runtime.performOnHome(it, state) }
                    }
                }
            }
        }
    val taps =
        if (!doubleTap) {
            Modifier
        } else {
            Modifier.pointerInput(runtime, state, empty) {
                val taps = DoubleTaps(viewConfiguration.doubleTapTimeoutMillis, viewConfiguration.touchSlop * 4)
                awaitEachGesture {
                    // After everything under it: a tap a tile or a widget took (consumed) isn't one on home.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    if (down.isConsumed) return@awaitEachGesture taps.reset()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val c = event.changes.firstOrNull { it.id == down.id }
                        val another = event.changes.any { it.id != down.id && it.pressed }
                        if (c == null || another || c.isConsumed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture taps.reset()
                        if (c.pressed) continue
                        // Held long enough to open home's menu, or on a tile: not a tap on empty space.
                        if (c.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis || !empty.wasEmpty(down.id)) return@awaitEachGesture taps.reset()
                        if (taps.tap(c.position.x, c.position.y, down.uptimeMillis, c.uptimeMillis)) runtime.performOnHome(HomeGesture.DoubleTap, state)
                        return@awaitEachGesture
                    }
                }
            }
        }
    return then(fingers).then(taps)
}

/** Does what [gesture] is set to, made on home: not while the drawer, a panel or home's menu covers it, whose touches those are. */
private fun Runtime.performOnHome(
    gesture: HomeGesture,
    state: LauncherState,
) {
    if (state.drawer.value > 0f || state.pulled != null || state.homeMenuOpen) return
    perform(gestures[gesture], state)
}

/** Does [action], for a gesture made on home. One that can't (an app that's gone, the accessibility service off) says why. */
internal fun Runtime.perform(
    action: GestureAction,
    state: LauncherState,
) {
    val context = gestures.context
    when (action) {
        GestureAction.None -> {}
        GestureAction.Drawer -> state.open()
        GestureAction.Shade -> expandNotificationShade(context)
        GestureAction.QuickSettings -> expandQuickSettings(context)
        GestureAction.Pages -> state.show(SettingsPage.Pages)
        GestureAction.Lock -> if (!GestureAccessibility.lock()) askForAccessibility()
        GestureAction.Recents -> if (!GestureAccessibility.recents()) askForAccessibility()
        is GestureAction.OpenPanel -> panelOf(action.encoded)?.let(state::openPull) ?: toast(context, R.string.gesture_panel_missing, action.file)
        is GestureAction.OpenApp -> openApp(action)
        is GestureAction.OpenLinuxApp ->
            when {
                linuxApps.apps.any { it.id == action.id } -> launch(linuxApps.apps.first { it.id == action.id })
                // Its apps are listed once Linux runs; settings › linux starts it, or installs it.
                bootstrap.state.value != LinuxState.Installed -> openSettings(SettingsPage.Linux)
                else -> toast(context, R.string.gesture_app_missing, action.id)
            }
        is GestureAction.RunLinux -> launch(action.command)
        is GestureAction.RunInTerminal -> terminal(action.command, action.command.substringBefore(' '))
        // As the bar sends one: the agent's terminal stays once it's answered.
        is GestureAction.AskAgent -> state.withAgent(this, agents.default?.name.orEmpty()) { bin -> "${agentInvocation(bin, action.prompt)}; exec bash" }
    }
}

/** Lock and recent apps without the accessibility service: settings › gestures says what turning it on takes. */
private fun Runtime.askForAccessibility() {
    toast(gestures.context, R.string.gesture_needs_accessibility)
    openSettings(SettingsPage.Gestures)
}

/** The app in the launcher's own profile, as the drawer opens it; one not in the drawer (a non-launcher activity) by its intent. */
private fun Runtime.openApp(action: GestureAction.OpenApp) {
    val context = gestures.context
    val component = action.activity?.let { ComponentName.createRelative(action.packageName, it) }
    val app = androidApps.apps.firstOrNull { it.profile == null && !it.privateSpace && (it.component == component || (component == null && it.component.packageName == action.packageName)) }
    if (app != null) return androidApps.open(app)
    val intent =
        if (component == null) {
            context.packageManager.getLaunchIntentForPackage(action.packageName)
        } else {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
        }
    runCatching { context.startActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
        Log.w("gestures", "cannot open ${action.component}", it)
        toast(context, R.string.gesture_app_missing, action.component)
    }
}

/** Pulls Android's shade all the way down to quick settings; like [expandNotificationShade], a phone that blocks it does nothing. */
fun expandQuickSettings(context: Context) {
    try {
        val service = context.getSystemService("statusbar") ?: return
        Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(service)
    } catch (e: Exception) {
        Log.w("gestures", "cannot pull down quick settings", e)
    }
}

private fun toast(
    context: Context,
    @StringRes text: Int,
    vararg args: Any,
) = Toast.makeText(context, context.getString(text, *args), Toast.LENGTH_SHORT).show()

/** What [action] is called in settings: nothing, the notification shade, a panel's name (its file's, while it has none), an app's, the command, … */
internal fun Runtime.actionLabel(action: GestureAction): String {
    val context = gestures.context
    return when (action) {
        GestureAction.None -> context.getString(R.string.gestures_nothing)
        GestureAction.Drawer -> context.getString(R.string.gesture_action_drawer)
        GestureAction.Shade -> context.getString(R.string.fork_gestures_shade)
        GestureAction.QuickSettings -> context.getString(R.string.gesture_action_quick_settings)
        GestureAction.Pages -> context.getString(R.string.gesture_action_pages)
        GestureAction.Lock -> context.getString(R.string.gesture_action_lock)
        GestureAction.Recents -> context.getString(R.string.gesture_action_recents)
        is GestureAction.OpenPanel -> widgets.panels().firstOrNull { it.file.name == action.file }?.name ?: action.file.removeSuffix(".owl")
        is GestureAction.OpenApp -> {
            val component = action.activity?.let { ComponentName.createRelative(action.packageName, it) }
            androidApps.apps.firstOrNull { it.profile == null && (it.component == component || (component == null && it.component.packageName == action.packageName)) }?.label ?: action.component
        }
        is GestureAction.OpenLinuxApp -> linuxApps.apps.firstOrNull { it.id == action.id }?.name ?: action.id
        is GestureAction.RunLinux -> context.getString(R.string.gesture_action_linux_label, action.command)
        is GestureAction.RunInTerminal -> context.getString(R.string.gesture_action_terminal_label, action.command)
        is GestureAction.AskAgent -> context.getString(R.string.gesture_action_agent_label, action.prompt)
    }
}
