package dev.omalauncher.pp.desktop

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.theme.ColorSpec
import dev.omalauncher.pp.theme.DesktopStyle
import dev.omalauncher.pp.ui.BracketAction
import dev.omalauncher.pp.ui.KeyboardSink
import dev.omalauncher.pp.ui.LauncherStyle
import dev.omalauncher.pp.ui.Mono
import dev.omalauncher.pp.ui.Palette
import dev.omalauncher.pp.ui.TextSize
import dev.omalauncher.pp.ui.hexColor
import dev.omalauncher.pp.ui.linuxWindowInput
import dev.omalauncher.pp.wayland.XdgToplevel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** How tall the desktop's bar is, like Omarchy's waybar. */
private val BarHeight = 26.dp

/** The smallest a floating window resizes to with Super and the right mouse button, in pixels. */
private const val MIN_FLOAT = 120

/** Workspaces the bar always shows, as Omarchy's waybar does; the others only while they have windows. */
private const val SHOWN_WORKSPACES = 5

/**
 * The desktop: the theme's wallpaper, the current workspace's windows where [Tiling] puts them with Omarchy's gaps
 * and borders, and the bar at the top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopScreen(
    runtime: Runtime,
    desktop: Desktop,
    activity: DesktopActivity,
) {
    // The tiling isn't snapshot state; this is, and changes with it.
    desktop.changes
    val tiling = desktop.tiling
    val style = LauncherStyle.style
    val density = LocalDensity.current
    var sink by remember { mutableStateOf<KeyboardSink?>(null) }
    // On a tablet the soft keyboard takes room from the windows, once, where it ends up; on a monitor it's on the phone.
    val keyboard = (WindowInsets.imeAnimationTarget.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        runtime.themes.wallpaper?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { v -> sink = v } },
            modifier = Modifier.size(1.dp),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = with(density) { keyboard.toDp() }),
        ) {
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val bar = if (style[DesktopStyle.BAR]) with(density) { BarHeight.roundToPx() } else 0
            val area = Box(0, bar, width, (height - bar).coerceAtLeast(1))
            val gapsIn = with(density) { style[DesktopStyle.GAPS_IN].toFloat().dp.roundToPx() }
            val gapsOut = with(density) { style[DesktopStyle.GAPS_OUT].toFloat().dp.roundToPx() }
            val laidOut = tiling.layout(tiling.current, area, gapsIn, gapsOut)
            // Fullscreen covers the bar too.
            val fullscreen = laidOut.singleOrNull()?.takeIf { it.fullscreen }
            val placements = fullscreen?.let { listOf(it.copy(box = Box(0, 0, width, height))) } ?: laidOut
            activity.area = area
            val focused = tiling.focused()
            for (p in placements) {
                key(p.window) { WindowTile(runtime, desktop, activity, p, focused = p.window == focused, onCursorTap = { sink?.takeUnless { it.showing }?.show() }) }
            }
            if (placements.isEmpty()) EmptyWorkspace(runtime, Modifier.align(Alignment.Center))
            if (activity.superHeld) FloatDrag(desktop, area)
            if (bar > 0 && fullscreen == null) {
                DesktopBar(
                    runtime,
                    desktop,
                    onSwitch = { ws ->
                        tiling.switchTo(ws)
                        desktop.changed()
                        activity.syncFocus()
                    },
                    onKeyboard = { sink?.toggle() },
                    modifier = Modifier.fillMaxWidth().height(BarHeight),
                )
            }
        }
    }
}

/** One window at its [Placement], in its border: the focused one's in the active color. */
@Composable
private fun WindowTile(
    runtime: Runtime,
    desktop: Desktop,
    activity: DesktopActivity,
    p: Placement<XdgToplevel>,
    focused: Boolean,
    onCursorTap: () -> Unit,
) {
    val style = LauncherStyle.style
    val window = p.window
    val density = LocalDensity.current
    val borderWidth = if (p.fullscreen) 0f else style[DesktopStyle.BORDER_WIDTH].toFloat()
    val border = with(density) { borderWidth.dp.roundToPx() }
    val color = if (focused) color(style[DesktopStyle.BORDER_ACTIVE], Palette.accent) else color(style[DesktopStyle.BORDER], Palette.border)
    val scale = activity.windowScale
    val box = p.box.let { if (p.floating) fixedSize(window, it, scale.takeIf { s -> s > 0 } ?: runtime.wm.scale, border) else it }
    val background = Palette.background.toArgb()
    Box(
        Modifier
            .offset { IntOffset(box.x, box.y) }
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(box.width, box.height))
                layout(box.width, box.height) { placeable.place(0, 0) }
            }.then(if (border > 0) Modifier.border(borderWidth.dp, color) else Modifier)
            .padding(with(density) { border.toDp() })
            // Before the window's own input sees the press: it's the desktop's focused window from then on.
            .pointerInput(window) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && desktop.tiling.focused() != window) {
                            desktop.tiling.focus(window)
                            desktop.changed()
                        }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { DesktopWindowView(it, runtime, window) },
            update = {
                it.background = background
                it.scale = scale
                it.fullscreen = p.fullscreen
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Over the TextureView, which Compose's pointer input doesn't reach.
        Box(Modifier.fillMaxSize().linuxWindowInput(runtime.wm, window, onCursorTap))
    }
}

/**
 * A floating window whose client has one size only (a fixed dialog) at that size, centered where it floats; it
 * wouldn't fill a bigger box, and would be shrunk to fit a smaller one.
 */
private fun fixedSize(
    window: XdgToplevel,
    box: Box,
    scale: Int,
    border: Int,
): Box {
    val (w, h) = window.minSize
    if (w <= 0 || h <= 0 || window.minSize != window.maxSize) return box
    val width = w * scale + 2 * border
    val height = h * scale + 2 * border
    return Box((box.centerX - width / 2f).roundToInt(), (box.centerY - height / 2f).roundToInt(), width, height)
}

/**
 * While Super is down, over every window: the mouse moves the floating window it presses on, or resizes it with the
 * right button, and the press doesn't reach the window. A press elsewhere does nothing.
 */
@Composable
private fun FloatDrag(
    desktop: Desktop,
    area: Box,
) {
    val current by rememberUpdatedState(area)
    Box(
        Modifier.fillMaxSize().pointerInput(desktop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val resize = currentEvent.buttons.isSecondaryPressed
                val tiling = desktop.tiling
                val target =
                    tiling.layout(tiling.current, current).lastOrNull {
                        it.floating && down.position.x >= it.box.x && down.position.x < it.box.right && down.position.y >= it.box.y && down.position.y < it.box.bottom
                    } ?: return@awaitEachGesture
                tiling.focus(target.window)
                desktop.changed()
                val start = target.box
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    val dx = (change.position.x - down.position.x).roundToInt()
                    val dy = (change.position.y - down.position.y).roundToInt()
                    val next =
                        if (resize) {
                            start.copy(width = (start.width + dx).coerceAtLeast(MIN_FLOAT), height = (start.height + dy).coerceAtLeast(MIN_FLOAT))
                        } else {
                            start.copy(x = start.x + dx, y = start.y + dy)
                        }
                    tiling.place(target.window, next, current)
                    desktop.changed()
                }
            }
        },
    )
}

/** The workspaces, the focused window's title and the clock, as Omarchy's waybar has them; ⌨ for a soft keyboard. */
@Composable
private fun DesktopBar(
    runtime: Runtime,
    desktop: Desktop,
    onSwitch: (Int) -> Unit,
    onKeyboard: () -> Unit,
    modifier: Modifier,
) {
    val tiling = desktop.tiling
    val focused = tiling.focused()
    val small = Mono.copy(fontSize = TextSize.Small)
    Row(modifier.background(Palette.glass).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            for (ws in 0 until tiling.count) {
                val current = ws == tiling.current
                val occupied = tiling.isOccupied(ws)
                if (ws >= SHOWN_WORKSPACES && !occupied && !current) continue
                BasicText(
                    "${ws + 1}",
                    Modifier.clickable { onSwitch(ws) }.padding(horizontal = 6.dp, vertical = 4.dp),
                    style = small.copy(color = if (current) Palette.accent else if (occupied) Palette.foreground else Palette.muted),
                )
            }
        }
        BasicText(
            focused?.let { it.title.ifEmpty { runtime.displayName(it.appId, it.title) } }.orEmpty(),
            Modifier.weight(2f),
            style = small.copy(textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Clock(small)
            BasicText("⌨", Modifier.clickable(onClick = onKeyboard).padding(horizontal = 8.dp, vertical = 4.dp), style = small)
        }
    }
}

/** The day and time, to the minute, as Omarchy's waybar shows them. */
@Composable
private fun Clock(style: androidx.compose.ui.text.TextStyle) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val pattern = remember { DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "EEEEHHmm" else "EEEEhmma") }
    BasicText(DateFormat.format(pattern, now).toString(), style = style)
}

/** An empty workspace: how to open a terminal, and the keys. */
@Composable
private fun EmptyWorkspace(
    runtime: Runtime,
    modifier: Modifier,
) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BracketAction(stringResource(R.string.desktop_terminal), Palette.accent) { runtime.terminal(null, "Terminal") }
        BasicText(
            stringResource(R.string.desktop_keys),
            Modifier.padding(top = 8.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small, textAlign = TextAlign.Center),
        )
    }
}

/** A color setting's value: [theme] for "theme" or a color this theme lacks. */
private fun color(
    spec: String,
    theme: Color,
): Color =
    when {
        spec == ColorSpec.THEME -> theme
        spec.startsWith("#") -> hexColor(spec) ?: theme
        else -> Palette.named(spec) ?: theme
    }
