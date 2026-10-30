package dev.omalauncher.pp.theme

/** style.toml's settings for the quick settings tiles ([dev.omalauncher.pp.tiles.QuickTiles]). */
object TileStyle {
    /** [ASK_AGENT]'s values. */
    const val TYPE = "type"
    const val VOICE = "voice"

    val ASK_AGENT =
        ChoiceSetting(
            "quick-settings.ask-agent",
            TYPE,
            listOf(TYPE, VOICE),
            "What the \"ask agent\" quick settings tile opens: \"type\" the bar with the keyboard up, \"voice\" the bar listening (once the microphone is allowed; until then it types).",
        )

    val ALL: List<Setting<*>> = listOf(ASK_AGENT)
}
