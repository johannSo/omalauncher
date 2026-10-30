package dev.omalauncher.pp.theme

/**
 * Home's gestures beyond swiping up and down, kept out of [Style] like [ForkStyle]'s so merges stay small. [StyleFile.ALL]
 * lists them after `gestures.swipe-down`, so defaults.toml writes one `[gestures]` table, and every gesture takes what
 * [Style.SWIPE_UP]'s doc lists.
 */
object GestureStyle {
    val DOUBLE_TAP = GestureSetting("gestures.double-tap", GestureAction.None, "What double-tapping empty space on home does: an action as gestures.swipe-up takes; \"none\" by default.")
    val TWO_FINGER_UP = GestureSetting("gestures.two-finger-up", GestureAction.None, "What swiping up with two fingers on home does: an action as gestures.swipe-up takes; \"none\" by default.")
    val TWO_FINGER_DOWN = GestureSetting("gestures.two-finger-down", GestureAction.None, "What swiping down with two fingers on home does, \"quick-settings\" say; \"none\" by default.")
    val PINCH_IN = GestureSetting("gestures.pinch-in", GestureAction.None, "What pinching two fingers together on home does, \"pages\" say; \"none\" by default.")
    val PINCH_OUT = GestureSetting("gestures.pinch-out", GestureAction.None, "What spreading two fingers apart on home does; \"none\" by default.")

    val MORE: List<GestureSetting> = listOf(DOUBLE_TAP, TWO_FINGER_UP, TWO_FINGER_DOWN, PINCH_IN, PINCH_OUT)

    /** Every gesture's setting, swiping up and down first. */
    val ALL: List<GestureSetting> get() = listOf(Style.SWIPE_UP, Style.SWIPE_DOWN) + MORE

    /** The panels [style]'s gestures open, so they're ready the moment one is made. */
    fun panels(style: Style): Set<String> = ALL.mapNotNullTo(LinkedHashSet()) { GestureAction.parseOrNull(style[it])?.panel }
}

/** A gesture's [GestureAction], kept as its [GestureAction.encoded] text: what's read is written back the same way. */
class GestureSetting(
    key: String,
    default: GestureAction,
    doc: String,
) : Setting<String>(key, default.encoded, doc) {
    override fun decode(value: Any): String = GestureAction.parse(value as? String ?: throw IllegalArgumentException(GestureAction.TAKES)).encoded
}
