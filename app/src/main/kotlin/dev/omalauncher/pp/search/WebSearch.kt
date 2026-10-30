package dev.omalauncher.pp.search

import java.net.URLEncoder

/** The web search the bar offers last: by an engine's name from [ENGINES], or a URL with `%s` where the terms go. */
object WebSearch {
    const val DEFAULT = "duckduckgo"

    /** Engines by the name style.toml's search.engine takes. */
    val ENGINES =
        linkedMapOf(
            "duckduckgo" to "https://duckduckgo.com/?q=%s",
            "google" to "https://www.google.com/search?q=%s",
            "bing" to "https://www.bing.com/search?q=%s",
            "brave" to "https://search.brave.com/search?q=%s",
            "startpage" to "https://www.startpage.com/do/search?q=%s",
            "ecosia" to "https://www.ecosia.org/search?q=%s",
            "kagi" to "https://kagi.com/search?q=%s",
            "qwant" to "https://www.qwant.com/?q=%s",
        )

    /** Whether [engine] is one search.engine takes: a name from [ENGINES], or an http(s) URL with `%s`. */
    fun isValid(engine: String) = engine.lowercase() in ENGINES || (engine.startsWith("https://") || engine.startsWith("http://")) && "%s" in engine

    /** The page that searches [engine] for [terms]; an engine that isn't valid is [DEFAULT]. */
    fun url(
        engine: String,
        terms: String,
    ): String {
        val template = ENGINES[engine.lowercase()] ?: engine.takeIf(::isValid) ?: ENGINES.getValue(DEFAULT)
        return template.replace("%s", URLEncoder.encode(terms.trim(), "UTF-8").replace("+", "%20"))
    }
}
