package dev.omalauncher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.theme.ColorSpec
import dev.omalauncher.theme.ForkStyle
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.theme.TintSetting

/** How big app icons draw (style.toml's icons.size), as a multiple of each place's own size. */
val LauncherStyle.iconScale get() = style[ForkStyle.ICON_SIZE].toFloat()

/** App names under their icons on home, where a cell is tall enough. */
val LauncherStyle.homeLabels get() = style[ForkStyle.HOME_LABELS]

/** App names under their icons in the drawer; android.drawer passes it on as `labels`. */
val LauncherStyle.drawerLabels get() = style[ForkStyle.DRAWER_LABELS]

/** The colors the tint rows offer besides accent, by theme name: they follow the theme. */
private val TintColors = listOf("red", "yellow", "green", "cyan", "blue", "magenta", "foreground")

/**
 * The Style menu's app icons: an icon pack, their size and shape, their backdrop, grayscale, a tint or a two-color
 * gradient, and the names under them on home and in the drawer. Each changes style.toml's [icons], which redraws them.
 */
@Composable
internal fun IconOptions(
    runtime: Runtime,
    style: StyleStore,
) {
    Heading(R.string.fork_style_icons)
    IconPackOption(runtime.iconPacks, style)
    NumberOption(
        R.string.fork_style_icon_size,
        listOf(R.string.fork_style_icon_small to 0.8, R.string.fork_style_icon_normal to 1.0, R.string.fork_style_icon_large to 1.25),
        ForkStyle.ICON_SIZE,
        style,
        unit = "×",
    )
    val shapes = ForkStyle.ICON_SHAPE.options
    val shapeLabels = listOf(R.string.fork_style_shape_system, R.string.fork_style_shape_circle, R.string.fork_style_shape_squircle).map { stringResource(it) }
    StyleOption(R.string.fork_style_icon_shape, shapeLabels, shapes.indexOf(style.style[ForkStyle.ICON_SHAPE])) { i -> style.update { it.with(ForkStyle.ICON_SHAPE, shapes[i]) } }
    Flag(R.string.fork_style_icon_backdrop, R.string.style_shown, R.string.style_hidden, style, ForkStyle.ICON_BACKDROP)
    Flag(R.string.fork_style_grayscale, R.string.style_on, R.string.style_off, style, ForkStyle.ICON_GRAYSCALE)
    TintOption(R.string.fork_style_tint, ForkStyle.ICON_TINT, style)
    // A gradient needs its top first.
    if (style.style[ForkStyle.ICON_TINT] != ColorSpec.NONE) TintOption(R.string.fork_style_tint_bottom, ForkStyle.ICON_TINT_BOTTOM, style)
    Flag(R.string.fork_style_home_labels, R.string.style_shown, R.string.style_hidden, style, ForkStyle.HOME_LABELS)
    Flag(R.string.fork_style_drawer_labels, R.string.style_shown, R.string.style_hidden, style, ForkStyle.DRAWER_LABELS)
    if (style.style[Style.MONOCHROME_ICONS]) OptionHint(stringResource(R.string.fork_style_icons_monochrome))
}

/** The icon packs on the phone, listed as it opens; the picked one shows, and tapping it lists the others under it. */
@Composable
private fun IconPackOption(
    packs: IconPackStore,
    style: StyleStore,
) {
    LaunchedEffect(packs) { packs.list() }
    var open by remember { mutableStateOf(false) }
    val picked = style.style[ForkStyle.ICON_PACK]
    val own = stringResource(R.string.fork_style_icon_pack_none)
    // One picked in style.toml that isn't installed (anymore) shows by its package name: the apps draw their own meanwhile.
    val choices = listOf<String?>(null) + packs.installed.map { it.packageName } + listOfNotNull(picked?.takeIf { p -> packs.installed.none { it.packageName == p } })
    fun label(pkg: String?) = if (pkg == null) own else packs.installed.firstOrNull { it.packageName == pkg }?.label ?: pkg
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(R.string.fork_style_icon_pack))
        Box(Modifier.weight(1f, fill = false)) {
            Row(
                Modifier
                    .frame(1.dp, Palette.border, radius = 12.dp)
                    .clickable { open = !open }
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(label(picked), Modifier.weight(1f, fill = false), style = Mono.copy(color = Palette.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 6.dp))
            }
            if (open) {
                ChoicePopup(choices, picked, BelowAnchorStart, onDismiss = { open = false }, onPick = { pkg -> style.update { it.with(ForkStyle.ICON_PACK, pkg) } }, label = ::label)
            }
        }
    }
    if (packs.installed.isEmpty()) OptionHint(stringResource(R.string.fork_style_icon_pack_hint), Modifier.padding(bottom = 4.dp))
}

/** A tint: none, the accent, another theme color, or one typed as #rrggbb. Theme colors follow the theme. */
@Composable
private fun TintOption(
    @StringRes label: Int,
    setting: TintSetting,
    style: StyleStore,
) {
    val value = style.style[setting]
    var typing by remember { mutableStateOf(false) }
    fun set(v: String) = style.update { it.with(setting, v) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(label))
        // Like the other options, scrolled rather than wrapped when there's no room.
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp)) {
                for ((v, text) in listOf(ColorSpec.NONE to stringResource(R.string.fork_style_tint_none), "accent" to stringResource(R.string.fork_style_tint_accent))) {
                    val active = value == v || (v == "accent" && value == ColorSpec.THEME)
                    BasicText(
                        text,
                        Modifier
                            .background(if (active) Palette.accent else Color.Transparent)
                            .clickable { set(v) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                        softWrap = false,
                    )
                }
            }
            for (name in TintColors) {
                Swatch(Palette.named(name) ?: Palette.foreground, selected = value == name) { set(name) }
            }
            // A color of its own: the swatch shows it once typed.
            val typed = hexColor(value)
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .frame(if (typed != null) 2.dp else 1.dp, if (typed != null) Palette.accent else Palette.border, typed ?: Color.Transparent, radius = 12.dp)
                    .clickable { typing = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicText("#", style = Mono.copy(color = if (typed != null) Palette.background else Palette.foreground))
            }
        }
    }
    if (typing) {
        TextDialog(
            stringResource(label),
            value.takeIf { it.startsWith("#") }.orEmpty(),
            stringResource(R.string.fork_style_tint_set),
            hint = "#rrggbb",
            onDismiss = { typing = false },
        ) { text ->
            typing = false
            val hex = "#" + text.trim().removePrefix("#").lowercase()
            if (ColorSpec.isValid(hex, noneAllowed = false)) set(hex)
        }
    }
}

/** A theme color to tint with, ringed in the accent when it's the one picked. */
@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(32.dp)
            .frame(if (selected) 2.dp else 1.dp, if (selected) Palette.accent else Palette.border, radius = 12.dp)
            .clickable(onClick = onClick)
            .padding(5.dp)
            .background(color, LauncherStyle.shape(8.dp)),
    )
}
