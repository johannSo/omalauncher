package dev.omalauncher.pp.search

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.os.SystemClock
import android.util.Log
import dev.omalauncher.pp.AndroidApp
import dev.omalauncher.pp.AndroidAppCatalog
import dev.omalauncher.pp.ShortcutRef
import dev.omalauncher.pp.shortcutLabel
import dev.omalauncher.pp.widgets.lang.matchScore

/** A shortcut an app offers ("New incognito tab"), and the app offering it, for its name and icon. */
class OfferedEntry(
    val label: String,
    /** The longer label, which matches too. */
    val long: String?,
    val ref: ShortcutRef,
    val app: AndroidApp,
)

/**
 * Every shortcut apps offer in their long-press menus, to find by name from the bar: what LauncherApps.getShortcuts lists
 * from their manifests and as they run (recent chats), as [AndroidAppCatalog.offered] lists one app's. Listing them
 * takes a call per profile, so it's kept, and listed again once it's [STALE_MS] old or the apps changed.
 *
 * Shortcuts already pinned are left out, as the bar finds those among the apps. So are the private space's, which a lock
 * would leave listed here, and a paused work profile's, which can't open.
 */
class ShortcutIndex(
    context: Context,
    private val apps: AndroidAppCatalog,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    @Volatile private var entries: List<OfferedEntry> = emptyList()

    @Volatile private var listedAt = 0L

    @Volatile private var listedFor: List<AndroidApp>? = null

    /** Lists the shortcuts again if they're old or the apps changed. Blocks: call off the main thread. */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val current = apps.apps.toList()
        if (current == listedFor && now - listedAt < STALE_MS) return
        listedFor = current
        listedAt = now
        if (!apps.listsShortcuts) {
            entries = emptyList()
            return
        }
        val byApp = current.filter { !it.privateSpace && !apps.paused(it.profile) }.groupBy { it.user }
        entries =
            byApp.flatMap { (user, userApps) ->
                val query = ShortcutQuery().setQueryFlags(ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_DYNAMIC)
                val infos =
                    runCatching { launcherApps.getShortcuts(query, user) }
                        .onFailure { Log.w(TAG, "cannot list the shortcuts of $user", it) }
                        .getOrNull()
                        .orEmpty()
                infos.filter { it.isEnabled && !it.isPinned }.mapNotNull { info ->
                    // The app whose menu offers it: its activity, else the package's first.
                    val app = userApps.firstOrNull { it.component == info.activity } ?: userApps.firstOrNull { it.component.packageName == info.`package` } ?: return@mapNotNull null
                    OfferedEntry(shortcutLabel(info), info.longLabel?.toString(), ShortcutRef(info.`package`, info.id), app)
                }
            }
    }

    /** The shortcuts [query] names, scored as apps' names are: by their label, or the longer one. */
    fun search(query: String): List<Pair<OfferedEntry, Int>> {
        if (query.trim().length < 2) return emptyList()
        return entries.mapNotNull { e ->
            val score = maxOf(matchScore(e.label, query), e.long?.let { matchScore(it, query) } ?: 0)
            if (score >= MIN_SCORE) e to score else null
        }
    }

    /** Opens [entry], as its app's long-press menu does. */
    fun open(entry: OfferedEntry) = apps.open(entry.ref, entry.app.profile)

    private companion object {
        const val TAG = "search"
        const val STALE_MS = 120_000L

        /** From a word's start, as for settings pages: scattered letters would find a shortcut in most apps. */
        const val MIN_SCORE = 300
    }
}
