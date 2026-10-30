package dev.omalauncher.pp.search

import dev.omalauncher.pp.widgets.lang.foldAccents
import dev.omalauncher.pp.widgets.lang.isStrongMatch

/**
 * The bar's kinds of results besides apps, each with its [id] in `android.drawer`'s `search` and the most it lists.
 * Their order breaks ties between groups that match as well.
 */
enum class SearchGroup(
    val id: String,
    val most: Int,
) {
    Calc("calc", 1),
    Settings("settings", 4),
    Shortcuts("shortcuts", 4),
    Contacts("contacts", 4),
    Files("files", 5),
    Packages("packages", 5),
    Web("web", 1),
}

/** Something a result can do besides its own: [word] names it in the drawer ("call"), [key] runs it with open(...). */
data class SearchAction(
    val word: String,
    val key: String,
)

/**
 * One result. [key] is what Enter or a tap runs, through open(...); [verb] names that ("copy", "open", "call",
 * "install", "search"), and [actions] are the others offered beside it. [score] is how well it matches, in the app
 * search's tiers (matchScore: 600 exact, 500 prefix, 400 word prefix, ...), so results and apps compare.
 */
data class SearchResult(
    val group: SearchGroup,
    val key: String,
    val title: String,
    val score: Int,
    val verb: String,
    val subtitle: String = "",
    val actions: List<SearchAction> = emptyList(),
    /** An `icon:` source for Image, or null for [glyph] on a badge. */
    val icon: String? = null,
    val glyph: String = "",
)

/** What a query asks for beyond finding things by name. */
sealed interface QueryIntent {
    /** Find apps and everything else by name. */
    data object Plain : QueryIntent

    /** "install btop": the packages called that. */
    data class Install(
        val name: String,
    ) : QueryIntent

    /** "search the web for owl": a web search for [terms]. */
    data class Web(
        val terms: String,
    ) : QueryIntent
}

/**
 * The bar's results in order: [results] best first, the first [lead] of them before the apps. Those take Enter over
 * the apps and the agent; the rest go after the apps.
 */
data class Ranked(
    val results: List<SearchResult>,
    val lead: Int,
)

/**
 * Orders the bar's results so what the query most likely means comes first. Pure, for the unit tests.
 *
 * - A calculation leads: arithmetic and conversions are never an app's name.
 * - "install x" leads with packages, "search the web for x" with the web, unless an app matches well (one called
 *   "Install Helper" stays the app it names).
 * - Otherwise the apps keep Enter when one matches well, as the bar always did. With none, a setting, shortcut or contact
 *   whose name matches as well as an app's would ("bluetooth", "new incognito tab") takes it; files and packages never
 *   do, as a sentence's first word often names one.
 * - The other groups follow the apps by how well their best result matches, the web search last of all.
 */
object SearchRanker {
    /** "install btop" and its kin in the launcher's languages, and the package managers' own spelling. */
    private val INSTALL = listOf("install ", "instalar ", "instal ", "pacman -s ", "yay -s ", "yay ")

    /** "search the web for owl" and its kin; the longest first, so "search the web for" doesn't leave "the web for". */
    private val WEB =
        listOf(
            "search the web for ", "search the web ", "search web for ", "search for ", "web search ", "web ", "google ", "ddg ", "? ",
            "buscar en la web ", "buscar en internet ", "buscar en google ", "busca en la web ",
            "pesquisar na web ", "pesquisar na internet ", "pesquisar no google ", "procurar na web ",
            "cari di web ", "cari di internet ", "cari di google ",
        ).sortedByDescending { it.length }

    /** The score from which a result names what's typed as well as an app's name does: a prefix of a word in it. */
    private const val NAMES_IT = 400

    /** The groups whose result may take Enter from the agent for a query that's only a name. */
    private val NAMING = setOf(SearchGroup.Settings, SearchGroup.Shortcuts, SearchGroup.Contacts)

    fun intent(query: String): QueryIntent {
        val q = query.trim()
        val folded = foldAccents(q)
        for (p in INSTALL) if (folded.startsWith(p) && q.length > p.length) return QueryIntent.Install(q.substring(p.length).trim())
        for (p in WEB) if (folded.startsWith(p) && q.length > p.length) return QueryIntent.Web(q.substring(p.length).trim())
        return QueryIntent.Plain
    }

    /**
     * [results] ordered for [query]; [appScore] is the best score of an app the drawer lists for it (0 for none), as
     * matchScore gives it, which decides whether the apps keep Enter.
     */
    fun rank(
        query: String,
        results: List<SearchResult>,
        appScore: Int,
    ): Ranked {
        val appNames = isStrongMatch(appScore, query)
        val groups =
            results
                .groupBy { it.group }
                .mapValues { (g, rs) -> rs.sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.title.length }.thenBy { it.title }).take(g.most) }
        val leading =
            when (val intent = intent(query)) {
                is QueryIntent.Install -> SearchGroup.Packages.takeIf { !appNames }
                is QueryIntent.Web -> SearchGroup.Web.takeIf { !appNames }
                QueryIntent.Plain -> null
            }.let { if (SearchGroup.Calc in groups) SearchGroup.Calc else it }
        val lead = leading?.let { groups[it] }.orEmpty().toMutableList()
        // The rest, group by group, the best matching group first; the web search is always last.
        val rest =
            groups
                .filterKeys { it != leading && it != SearchGroup.Web }
                .values
                .sortedWith(compareByDescending<List<SearchResult>> { it.first().score }.thenBy { it.first().group.ordinal })
                .map { it.toMutableList() }
        if (lead.isEmpty() && !appNames) {
            // A name that isn't an app's, but a setting's or a contact's: that one goes first, and takes Enter.
            val best = rest.firstOrNull()?.firstOrNull()
            if (best != null && best.group in NAMING && best.score >= NAMES_IT && isStrongMatch(best.score, query)) {
                lead += best
                rest.first().removeAt(0)
            }
        }
        val web = if (leading == SearchGroup.Web) emptyList() else groups[SearchGroup.Web].orEmpty()
        return Ranked(lead + rest.flatten() + web, lead.size)
    }
}
