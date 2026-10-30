package dev.omalauncher.pp.theme

import dev.omalauncher.pp.search.WebSearch

/**
 * style.toml's [search]: which kinds of results the bar lists besides apps, and the web search engine. Kept out of
 * [Style] like [ForkStyle], so merges stay small.
 */
object SearchStyle {
    val CALCULATOR = Setting.Flag("search.calculator", true, "Arithmetic and unit conversions in the bar (\"2^10\", \"5 km in mi\"); Enter copies the result.")
    val SETTINGS = Setting.Flag("search.settings", true, "Android's settings pages in the bar, by name (Wi-Fi, Bluetooth, battery, developer options, …).")
    val SHORTCUTS = Setting.Flag("search.shortcuts", true, "Apps' shortcuts in the bar, like a browser's \"New incognito tab\".")

    /** Off by default: the launcher asks for the contacts permission only when this is turned on in settings › search. */
    val CONTACTS =
        Setting.Flag(
            "search.contacts",
            false,
            "Contacts in the bar, to call, message or open; needs the contacts permission, which settings › search asks for when it's turned on.",
        )
    val FILES = Setting.Flag("search.files", true, "Files in Linux's home folder in the bar, by name; they open in Files or the editor.")
    val PACKAGES = Setting.Flag("search.packages", true, "pacman and AUR packages in the bar, for \"install <name>\" or a package's exact name.")
    val WEB = Setting.Flag("search.web", true, "A web search for what's typed, last in the bar's results.")
    val ENGINE =
        EngineSetting(
            "search.engine",
            "The bar's web search: ${WebSearch.ENGINES.keys.joinToString(", ") { "\"$it\"" }}, or a URL with %s where the words go (\"https://search.example/?q=%s\").",
        )

    val ALL: List<Setting<*>> = listOf(CALCULATOR, SETTINGS, SHORTCUTS, CONTACTS, FILES, PACKAGES, WEB, ENGINE)
}

/** An engine's name or a URL with %s, [WebSearch.DEFAULT] by default. */
class EngineSetting(
    key: String,
    doc: String,
) : Setting<String>(key, WebSearch.DEFAULT, doc) {
    override fun decode(value: Any): String {
        val v = (value as? String)?.trim()
        require(v != null && WebSearch.isValid(v)) { "takes ${WebSearch.ENGINES.keys.joinToString(", ") { "\"$it\"" }} or an http(s) URL with %s" }
        return if (v.lowercase() in WebSearch.ENGINES) v.lowercase() else v
    }
}
