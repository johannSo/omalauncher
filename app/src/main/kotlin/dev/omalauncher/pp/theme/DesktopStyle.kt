package dev.omalauncher.pp.theme

/**
 * style.toml's `[desktop]`: the tiling desktop Linux windows open in on a monitor, or on a large screen, instead of
 * as tasks of their own (see [dev.omalauncher.pp.desktop.Desktop]). Its look follows Omarchy's Hyprland defaults.
 */
object DesktopStyle {
    /** [EXTERNAL]'s values. */
    const val ASK = "ask"
    const val AUTO = "auto"
    const val OFF = "off"

    val EXTERNAL =
        ChoiceSetting(
            "desktop.external",
            ASK,
            listOf(ASK, AUTO, OFF),
            "A monitor plugged in: \"ask\" offers the desktop on it in a notification, \"auto\" opens it there at once, \"off\" leaves the monitor to Android. Linux windows open on the monitor while it's up, and go back to their own tasks when it's unplugged.",
        )
    val LARGE_SCREENS =
        Setting.Flag(
            "desktop.large-screens",
            false,
            "On a tablet or an unfolded foldable (600dp wide or more either way), Linux windows open side by side in the desktop instead of each in its own task.",
        )
    val SCALE =
        Setting.Count(
            "desktop.scale",
            0,
            0..4,
            "Monitor pixels per Linux pixel on the desktop on a monitor: 0 picks from the monitor's density; 1 gives the most room. Apps still draw at the phone's scale and are scaled down.",
        )
    val GAPS_IN = Setting.Number("desktop.gaps-in", 5.0, 0.0..24.0, "Space in dp around each window, like Hyprland's gaps_in: two windows are twice this apart.")
    val GAPS_OUT = Setting.Number("desktop.gaps-out", 10.0, 0.0..48.0, "Space in dp from the windows to the screen's edges and the bar, like Hyprland's gaps_out.")
    val BORDER_WIDTH = Setting.Number("desktop.border-width", 2.0, 0.0..8.0, "How thick windows' borders are, in dp; 0 draws none.")
    val BORDER = Setting.Color("desktop.border", "Windows' borders: \"theme\" is the theme's border color.")
    val BORDER_ACTIVE = Setting.Color("desktop.border-active", "The focused window's border: \"theme\" is the theme's accent, as Omarchy's active border.")
    val BAR = Setting.Flag("desktop.bar", true, "The bar at the desktop's top: workspaces, the focused window's title and the clock.")

    val ALL: List<Setting<*>> = listOf(EXTERNAL, LARGE_SCREENS, SCALE, GAPS_IN, GAPS_OUT, BORDER_WIDTH, BORDER, BORDER_ACTIVE, BAR)
}
