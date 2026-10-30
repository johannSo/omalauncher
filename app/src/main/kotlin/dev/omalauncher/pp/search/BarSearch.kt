package dev.omalauncher.pp.search

import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.copyText
import dev.omalauncher.pp.linux.Aur
import dev.omalauncher.pp.linux.LinuxState
import dev.omalauncher.pp.linux.Package
import dev.omalauncher.pp.linux.Proot
import dev.omalauncher.pp.openUrl
import dev.omalauncher.pp.theme.SearchStyle
import dev.omalauncher.pp.theme.Setting
import dev.omalauncher.pp.ui.LauncherStyle
import dev.omalauncher.pp.ui.isText
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Null
import dev.omalauncher.pp.widgets.lang.Num
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import dev.omalauncher.pp.widgets.lang.Value
import dev.omalauncher.pp.widgets.lang.matchScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** What the bar found for [query], unranked: ranking waits for the apps, which come from the drawer. */
class Found(
    val query: String,
    val results: List<SearchResult>,
)

/**
 * The bar's search beyond apps: the calculator, Android's settings pages, apps' shortcuts, contacts, Linux's files and
 * packages, and the web. The drawer reads it as `android.drawer`'s `search` ([into]) and runs a result with open(key),
 * which comes here ([open]).
 *
 * Nothing runs on the main thread, and nothing slow on every keystroke: what's in memory (the calculator, settings,
 * shortcuts already listed, the web) answers at once, and contacts, files and packages once typing pauses for
 * [PAUSE_MS]. The lists behind shortcuts, files and packages are made as the drawer opens, not as the query changes.
 * style.toml's [search] turns each kind off ([SearchStyle]).
 */
class BarSearch(
    private val context: Context,
    private val runtime: Runtime,
    private val scope: CoroutineScope,
) {
    private val settings = SettingsIndex(context)
    private val shortcuts = ShortcutIndex(context, runtime.androidApps)
    private val contacts = ContactSearch(context)
    private val files = FileIndex(Proot.GUEST_HOME) { runtime.proot.home }
    private val packages = PackageIndex(runtime.proot.rootfs)

    /** The bar's text; set by the UI. */
    @Volatile var query: () -> String = { "" }

    /** Leaves the drawer as opening an app does: its text goes and it closes. Set by the UI. */
    @Volatile var leave: () -> Unit = {}

    /** Shows settings › packages, searching for the name it's given. Set by the UI. */
    @Volatile var showPackages: (String) -> Unit = {}

    /** The search settings › packages opens with once, from a package result; taken by [takePackageQuery]. */
    @Volatile private var packageQuery: String? = null

    /** What settings › packages searches for as it opens: a package picked in the bar, once, else nothing. */
    fun takePackageQuery(): String? = packageQuery.also { packageQuery = null }

    /** What each result's key runs: the latest [MAX_KEYS], so the results the drawer still shows keep theirs. */
    private val actions =
        object : LinkedHashMap<String, () -> Unit>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, () -> Unit>?) = size > MAX_KEYS
        }
    private val keys = AtomicLong()

    private fun key(run: () -> Unit): String {
        val k = "$PREFIX${keys.incrementAndGet()}"
        synchronized(actions) { actions[k] = run }
        return k
    }

    /** Runs the result [key] names, leaving the drawer; false for a key that isn't the search's (an app's). */
    fun open(key: String): Boolean {
        if (!key.startsWith(PREFIX)) return false
        val run = synchronized(actions) { actions[key] } ?: return true
        leave()
        run()
        return true
    }

    private val linux get() = runtime.bootstrap.state.value == LinuxState.Installed

    private fun on(flag: Setting.Flag) = LauncherStyle.style[flag]

    /**
     * What the bar finds as its text changes: the quick results at once, then all of them once typing pauses. Starting
     * (as the drawer opens) brings the lists of shortcuts, files and packages up to date, off the main thread.
     */
    fun found(): Flow<Found> =
        snapshotFlow { query().trim() }
            .distinctUntilChanged()
            .transformLatest { q ->
                if (q.isEmpty()) return@transformLatest emit(Found(q, emptyList()))
                val quick = quick(q)
                emit(Found(q, quick))
                if (!slowWanted(q)) return@transformLatest
                delay(PAUSE_MS)
                emit(Found(q, quick + withContext(Dispatchers.IO) { slowOrNone(q) }))
            }.onStart { scope.launch(Dispatchers.IO) { warm() } }
            .flowOn(Dispatchers.Default)

    /** The lists the quick results read, and the slow ones', made or brought up to date. */
    private fun warm() {
        if (on(SearchStyle.SHORTCUTS)) runCatching { shortcuts.refresh() }
        if (linux && on(SearchStyle.FILES)) runCatching { files.refresh() }
        if (linux && on(SearchStyle.PACKAGES)) runCatching { packages.refresh() }
    }

    /** What answers from memory: the calculator, settings pages, shortcuts and the web. */
    private fun quick(q: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        if (on(SearchStyle.CALCULATOR)) {
            Calculator.evaluate(q)?.let { calc ->
                out += SearchResult(SearchGroup.Calc, key { copy(calc.value) }, calc.text, Int.MAX_VALUE, "copy", subtitle = calc.expression, glyph = "=")
            }
        }
        if (on(SearchStyle.SETTINGS)) {
            for (hit in settings.search(q)) {
                out += SearchResult(SearchGroup.Settings, key { settings.open(hit.action) }, hit.title, hit.score, "open", glyph = "⚙")
            }
        }
        if (on(SearchStyle.SHORTCUTS)) {
            for ((e, score) in shortcuts.search(q)) {
                out += SearchResult(SearchGroup.Shortcuts, key { shortcuts.open(e) }, e.label, score, "open", subtitle = e.app.label, icon = "icon:a:" + e.app.key)
            }
        }
        if (on(SearchStyle.WEB)) {
            val terms = (SearchRanker.intent(q) as? QueryIntent.Web)?.terms ?: q
            val engine = LauncherStyle.style[SearchStyle.ENGINE]
            out += SearchResult(SearchGroup.Web, key { context.openUrl(WebSearch.url(engine, terms)) }, terms, 0, "search", glyph = "⌕")
        }
        return out
    }

    /** Whether [slow] could find anything for [q], so a query that can't isn't held back by the pause. */
    private fun slowWanted(q: String) =
        q.length >= 2 && ((on(SearchStyle.CONTACTS) && contacts.granted()) || (linux && (on(SearchStyle.FILES) || on(SearchStyle.PACKAGES))))

    /** [slow], or nothing where it failed: a file that went or a database half written mustn't end the drawer's source. */
    private suspend fun slowOrNone(q: String): List<SearchResult> =
        try {
            slow(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "search for $q failed", e)
            emptyList()
        }

    /** What reads the disk, the contacts or the network: contacts, files and packages. Blocks. */
    private suspend fun slow(q: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        if (on(SearchStyle.CONTACTS)) {
            for (c in contacts.search(q)) {
                val more =
                    c.phone?.let { phone -> listOf(SearchAction("call", key { contacts.dial(phone) }), SearchAction("message", key { contacts.message(phone) })) }.orEmpty()
                out += SearchResult(SearchGroup.Contacts, key { contacts.open(c) }, c.name, c.score, "open", subtitle = c.phone.orEmpty(), actions = more, glyph = "@")
            }
        }
        if (!linux) return out
        if (on(SearchStyle.FILES)) {
            files.refresh()
            for ((f, score) in files.search(q)) {
                out += SearchResult(SearchGroup.Files, key { openFile(f) }, f.name, score, "open", subtitle = f.path.substringBeforeLast('/'), glyph = if (f.dir) "▸" else "≡")
            }
        }
        if (on(SearchStyle.PACKAGES)) out += packageResults(q)
        return out
    }

    /**
     * For "install x", the packages x finds in the repos and the AUR, as settings › packages lists them; for a plain query,
     * only a package called exactly that (and those named like it), as one word is rarely a package being looked for.
     */
    private suspend fun packageResults(q: String): List<SearchResult> {
        packages.refresh()
        val install = SearchRanker.intent(q) as? QueryIntent.Install
        val name = install?.name ?: q
        val found: List<Pair<Package, Int>> =
            if (install == null) {
                packages.search(name, exactOnly = true)
            } else if (packages.ready) {
                val repos = packages.search(name, exactOnly = false).take(SearchGroup.Packages.most)
                val known = repos.mapTo(HashSet()) { it.first.name }
                val aur = Aur.search(name).filter { it.name !in known }.map { it to matchScore(it.name, name) / 2 }
                repos + aur
            } else {
                // The databases aren't readable (not synced yet): pacman finds them, slower.
                runtime.pacman.search(name).map { it to matchScore(it.name, name) }
            }
        return found.map { (pkg, score) ->
            val exact = pkg.name == name.lowercase()
            val install = SearchAction("install", key { install(pkg) })
            val subtitle = listOfNotNull(pkg.repo, pkg.version.takeIf { it.isNotEmpty() }, pkg.description.takeIf { it.isNotEmpty() }).joinToString(" · ")
            when {
                pkg.installed -> SearchResult(SearchGroup.Packages, key { showPackage(pkg.name) }, pkg.name, score, "open", subtitle = subtitle, glyph = "✓")
                // Enter installs only the package called what was typed; any other opens settings › packages on it first.
                exact -> SearchResult(SearchGroup.Packages, install.key, pkg.name, score, "install", subtitle = subtitle, glyph = "↓")
                else -> SearchResult(SearchGroup.Packages, key { showPackage(pkg.name) }, pkg.name, score, "open", subtitle = subtitle, actions = listOf(install), glyph = "↓")
            }
        }
    }

    private fun showPackage(name: String) {
        packageQuery = name
        showPackages(name)
    }

    /** Installs [pkg] as settings › packages does, which opens on it to show the install going. */
    private fun install(pkg: Package) {
        showPackage(pkg.name)
        scope.launch { if (runtime.pacman.install(pkg)) runtime.refreshApps() }
    }

    /** A folder opens in Files, a text file in the editor, anything else in Files at its folder. */
    private fun openFile(f: FileEntry) {
        if (f.dir) return runtime.openFiles(f.path)
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { isText(f.name, runtime.proot.guestFs.host(f.path)) }.getOrDefault(false) }
            if (text) runtime.edit(f.path) else runtime.openFiles(f.path.substringBeforeLast('/').ifEmpty { "/" })
        }
    }

    private fun copy(text: String) {
        context.copyText("calculator", text)
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.widgets_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * [drawer] (`android.drawer`'s value) with `search`: `{query, lead, results}`, [found] ranked against the drawer's own
     * apps, so whether an app matches well is decided by the apps it lists. Each result is `{key, group, title, subtitle,
     * verb, glyph, icon, actions}`, `actions` each `{word, key}`; the first `lead` go before the apps.
     */
    fun into(
        drawer: Value,
        found: Found,
    ): Value {
        val d = drawer as? Obj ?: return drawer
        val apps = (d.fields["apps"] as? Lst)?.items.orEmpty()
        val appScore =
            if (found.query.isEmpty()) {
                0
            } else {
                apps.maxOfOrNull { a ->
                    val o = a as? Obj ?: return@maxOfOrNull 0
                    maxOf((o.fields["name"] as? Str)?.v?.let { matchScore(it, found.query) } ?: 0, (o.fields["alias"] as? Str)?.v?.let { matchScore(it, found.query) } ?: 0)
                } ?: 0
            }
        val ranked = SearchRanker.rank(found.query, found.results, appScore)
        val search =
            Obj(
                mapOf(
                    "query" to Str(found.query),
                    "lead" to Num(ranked.lead.toDouble()),
                    "results" to Lst(ranked.results.map(::value)),
                ),
            )
        return Obj(d.fields + ("search" to search))
    }

    private fun value(r: SearchResult): Value =
        Obj(
            mapOf(
                "key" to Str(r.key),
                "group" to Str(r.group.id),
                "title" to Str(r.title),
                "subtitle" to Str(r.subtitle),
                "verb" to Str(r.verb),
                "glyph" to Str(r.glyph),
                "icon" to (r.icon?.let(::Str) ?: Null),
                "actions" to Lst(r.actions.map { Obj(mapOf("word" to Str(it.word), "key" to Str(it.key))) }),
            ),
        )

    private companion object {
        const val TAG = "search"

        /** How long typing pauses before contacts, files and packages are searched. */
        const val PAUSE_MS = 250L

        /** Keys of results open(...) runs; apps' keys never start with it. */
        const val PREFIX = "search:"
        const val MAX_KEYS = 512
    }
}
