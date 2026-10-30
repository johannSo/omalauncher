package dev.omalauncher.pp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.pp.theme.Toml
import dev.omalauncher.pp.upgrade.SyncedFile
import java.io.File

/**
 * Apps hidden and renamed ([AppTweaks]), kept in apps.toml ([AppTweaksFile]) in the launcher's data, which Linux sees
 * as ~/.config/omalauncher/apps/apps.toml and a backup takes along. Settings › hidden apps and the apps' long-press
 * menus change it through [hide] and [rename]; an agent or the user editing the file changes it too. What couldn't
 * be read goes into errors.txt next to it. Main thread.
 *
 * Whatever lists apps asks [current] whether one is hidden and what it's called, by its drawer key: the drawer and
 * the bar's search, home's tiles and recent apps, and owl's `android.apps`, `android.recent` and `android.drawer`.
 * The private space's apps aren't asked about; the space keeps them out of sight itself.
 */
class AppTweakStore(
    context: Context,
    private val apps: AndroidAppCatalog,
) {
    private val dir = LauncherData.appsDir(context)

    /** The file's keys, the ones this launcher doesn't know among them. */
    private var table: Map<String, Any> = emptyMap()

    private val synced =
        SyncedFile(File(dir, FILE), File(dir, ERRORS), "the hidden and renamed apps") {
            table = AppTweaksFile.encode(table, tweaks)
            Toml.write(table, AppTweaksFile.HEADER)
        }

    /** What the file says now, kept in [current]. */
    var tweaks: AppTweaks
        get() = current
        private set(value) {
            current = value
        }

    /** What errors.txt says. */
    val errors get() = synced.errors

    init {
        // Small, and read before the first frame, so hidden apps never flash by.
        val text = synced.open()
        if (text == null) synced.create() else read(text)
    }

    fun start() {
        synced.start { text ->
            read(text)
            // Deleted: nothing hidden or renamed, and the file back, so agents find it.
            if (text.isEmpty() && !synced.exists) synced.save()
        }
    }

    /** Hides the app with drawer key [key] from the drawer, home's app lists and search, or shows it again. */
    fun hide(
        key: String,
        hidden: Boolean,
    ) = update { it.hide(key, hidden, preferred(key)) }

    /** Calls the app with drawer key [key] [name] everywhere; null or blank gives it back its [own] name. */
    fun rename(
        key: String,
        name: String?,
        own: String,
    ) = update { it.rename(key, name, own, preferred(key)) }

    /** Whether typing a hidden app's full name in the bar still finds it. */
    fun findHidden(find: Boolean) = update { it.withFindHidden(find) }

    private fun update(change: (AppTweaks) -> AppTweaks) {
        val next = change(tweaks)
        if (next == tweaks) return
        tweaks = next
        synced.save()
    }

    /** An Android app in the launcher's own profile goes in by its package when it's the package's only one, as in layout.toml. */
    private fun preferred(key: String): String {
        val id = key.removePrefix("a:")
        val pkg = id.substringBefore('/')
        val alone = key.startsWith("a:") && '@' !in id && apps.apps.count { it.profile == null && it.component.packageName == pkg } == 1
        return AppTweaks.preferredFileKey(key, alone)
    }

    /** Takes in the file's [text]; a file that doesn't parse changes nothing but errors.txt, and isn't saved over. */
    private fun read(text: String) {
        val parsed =
            try {
                Toml.parse(text)
            } catch (e: Toml.Error) {
                synced.readable = false
                synced.setProblems(listOf("$FILE ${e.message}; nothing in it was applied, and hiding or renaming apps won't save it until it's fixed"))
                return
            }
        synced.readable = true
        val decoded = AppTweaksFile.decode(parsed)
        table = parsed
        tweaks = decoded.tweaks
        synced.setProblems(decoded.problems)
    }

    companion object {
        const val FILE = "apps.toml"
        const val ERRORS = "errors.txt"

        /** Where Linux sees [FILE], relative to the guest home. */
        const val GUEST_DIR = ".config/omalauncher/apps"

        /**
         * What apps.toml says, for code without the runtime at hand (an app's label in the drawer), as
         * [dev.omalauncher.pp.ui.LauncherStyle] holds the style; reading it in a composable or a snapshotFlow follows it.
         */
        var current by mutableStateOf(AppTweaks())
            private set
    }
}
