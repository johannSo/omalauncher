package dev.omalauncher.theme

/**
 * The fork's own style.toml settings (deploy), kept out of [Style] so merges from upstream stay clean. [StyleFile.ALL]
 * lists [ICONS] right after `icons.monochrome`, so defaults.toml writes one `[icons]` table.
 */
object ForkStyle {
    /** [ICON_SHAPE]'s values. */
    const val SHAPE_SYSTEM = "system"
    const val SHAPE_CIRCLE = "circle"
    const val SHAPE_SQUIRCLE = "squircle"

    val ICON_PACK =
        Setting.Name(
            "icons.pack",
            "An icon pack's package name (one made for Nova, ADW, Apex, GO and the like): it draws the app icons it has. Unset: every app's own icon.",
        )
    val ICON_SIZE = Setting.Number("icons.size", 1.0, 0.5..1.5, "How big app icons draw in the drawer, on home and in menus: 0.8 small, 1 normal, 1.25 large.")
    val ICON_SHAPE =
        ChoiceSetting(
            "icons.shape",
            SHAPE_SYSTEM,
            listOf(SHAPE_SYSTEM, SHAPE_CIRCLE, SHAPE_SQUIRCLE),
            "Cuts every app icon to a shape: \"system\" leaves them as they come, \"circle\" or \"squircle\".",
        )
    val ICON_BACKDROP = Setting.Flag("icons.backdrop", true, "Adaptive icons on their backdrop, as they come; false draws only their logo.")
    val ICON_GRAYSCALE = Setting.Flag("icons.grayscale", false, "App icons drained of their colors.")
    val ICON_TINT =
        TintSetting(
            "icons.tint",
            "Recolors app icons, keeping their light and shade: \"none\", a theme color (accent, red, color4, …, following the theme) or \"#rrggbb\".",
        )
    val ICON_TINT_BOTTOM =
        TintSetting(
            "icons.tint-bottom",
            "With icons.tint, shades app icons in a gradient from icons.tint at their top to this at their bottom; \"none\" keeps one color.",
        )
    val HOME_LABELS = Setting.Flag("icons.home-labels", true, "App names under their icons on home, where a cell is tall enough; false draws icons alone, bigger.")
    val DRAWER_LABELS = Setting.Flag("icons.drawer-labels", true, "App names under their icons in the app drawer (android.drawer's labels).")

    val ICONS: List<Setting<*>> = listOf(ICON_PACK, ICON_SIZE, ICON_SHAPE, ICON_BACKDROP, ICON_GRAYSCALE, ICON_TINT, ICON_TINT_BOTTOM, HOME_LABELS, DRAWER_LABELS)
}

/** One of [options], as text. */
class ChoiceSetting(
    key: String,
    default: String,
    val options: List<String>,
    doc: String,
) : Setting<String>(key, default, doc) {
    override fun decode(value: Any): String {
        val v = (value as? String)?.trim()?.lowercase()
        require(v != null && v in options) { "takes ${options.joinToString(", ") { "\"$it\"" }}" }
        return v
    }
}

/** A color to tint with, [ColorSpec.NONE] by default: a theme color's name or #rrggbb ("theme" reads as the accent). */
class TintSetting(
    key: String,
    doc: String,
) : Setting<String>(key, ColorSpec.NONE, doc) {
    override fun decode(value: Any): String {
        val v = (value as? String)?.trim()?.lowercase()
        require(v != null && ColorSpec.isValid(v, noneAllowed = true)) { "takes \"none\", a theme color (accent, red, color4, …) or \"#rrggbb\"" }
        return v
    }
}
