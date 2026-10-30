package dev.omalauncher.pp

/**
 * What apps.toml says about one app: whether it's [hidden] from the drawer, home's app lists and the bar's search,
 * and the [name] it goes by instead of its own. Null leaves either to a broader entry (an Android app's package),
 * or else as the app has it.
 */
data class AppTweak(
    val hidden: Boolean? = null,
    val name: String? = null,
) {
    val empty get() = hidden == null && name == null
}

/**
 * Apps hidden and renamed, as apps.toml ([AppTweaksFile]) keeps them: by the keys layout.toml names apps by
 * ([fileKeys]), asked by the drawer's keys ([dev.omalauncher.pp.ui.AppHit.key]: `a:`, `l:`, `o:` and `s:`).
 * The private space's apps are never asked about: they keep the space's own rules.
 */
data class AppTweaks(
    /** By file key, in the file's order. */
    val apps: Map<String, AppTweak> = emptyMap(),
    /** Typing a hidden app's full name in the bar still finds it, as on stock launchers. */
    val findHidden: Boolean = true,
) {
    /** What holds for the app with drawer key [key]: each of hidden and name from its own entry, else its package's. */
    fun of(key: String): AppTweak = merged(fileKeys(key))

    /** Whether the app with drawer key [key] is kept out of the drawer, home's app lists and search. */
    fun hidden(key: String) = of(key).hidden == true

    /** The name the app with drawer key [key] goes by: the one given it, else [own]. */
    fun label(
        key: String,
        own: String,
    ) = of(key).name?.takeIf { it.isNotBlank() } ?: own

    /** Whether [typed] finds a hidden app called any of [names]: with [findHidden], its full name, in any case. */
    fun finds(
        typed: String,
        vararg names: String,
    ): Boolean {
        val t = typed.trim()
        return findHidden && t.isNotEmpty() && names.any { it.trim().equals(t, ignoreCase = true) }
    }

    /** Hides or shows the app with drawer key [key]; [preferred] is the file key a new entry gets ([preferredFileKey]). */
    fun hide(
        key: String,
        hidden: Boolean,
        preferred: String = fileKeys(key).first(),
    ) = change(key, preferred) { inherited -> copy(hidden = hidden.takeIf { it != (inherited.hidden ?: false) }) }

    /** Names the app with drawer key [key] [name]; null, blank or its [own] name gives it back the one it had. */
    fun rename(
        key: String,
        name: String?,
        own: String,
        preferred: String = fileKeys(key).first(),
    ) = change(key, preferred) { inherited -> copy(name = name?.trim()?.takeIf { it.isNotEmpty() && it != (inherited.name ?: own) }) }

    fun withFindHidden(find: Boolean) = copy(findHidden = find)

    /**
     * [edit]s the most specific entry the app already has, down to [preferred], or else a new one at [preferred], given
     * what the broader entries after it say; an entry left saying nothing they don't goes. Never one broader than
     * [preferred]: showing one icon of a package hidden whole leaves its others hidden.
     */
    private fun change(
        key: String,
        preferred: String,
        edit: AppTweak.(inherited: AppTweak) -> AppTweak,
    ): AppTweaks {
        val keys = fileKeys(key)
        require(preferred in keys) { "$preferred isn't a key of $key" }
        val target = keys.take(keys.indexOf(preferred) + 1).firstOrNull { it in apps } ?: preferred
        val inherited = merged(keys.drop(keys.indexOf(target) + 1))
        val next = (apps[target] ?: AppTweak()).edit(inherited)
        val out = LinkedHashMap(apps)
        if (next.empty) out.remove(target) else out[target] = next
        return copy(apps = out)
    }

    private fun merged(keys: List<String>): AppTweak {
        val entries = keys.mapNotNull { apps[it] }
        return AppTweak(entries.firstNotNullOfOrNull { it.hidden }, entries.firstNotNullOfOrNull { it.name })
    }

    companion object {
        /**
         * The file keys that may hold what's said about the app with drawer key [key], most specific first: an Android
         * app's activity ("pkg/.Main", "pkg/.Main@10" in another profile), then for the launcher's own profile its
         * package; "linux:id", "owl:file" and "shortcut:key" for the rest, as layout.toml writes them.
         */
        fun fileKeys(key: String): List<String> {
            val id = key.substringAfter(':')
            return when (key.substringBefore(':', "")) {
                "a" -> if ('@' in id || '/' !in id) listOf(id) else listOf(id, id.substringBefore('/'))
                "l" -> listOf("linux:$id")
                "o" -> listOf("owl:$id")
                "s" -> listOf("shortcut:$id")
                else -> listOf(key)
            }
        }

        /**
         * The file key a new entry for [key] gets: an Android app in the launcher's own profile by its package [alone]
         * when that's its package's only app in the drawer, as layout.toml writes it, else by its activity.
         */
        fun preferredFileKey(
            key: String,
            alone: Boolean,
        ): String {
            val keys = fileKeys(key)
            return if (alone) keys.last() else keys.first()
        }

        /**
         * A key as the file may write it, the way [fileKeys] gives them: a drawer key (`a:`, `l:`, `o:`, `s:`) or a
         * layout.toml one (`app:`) works too, and an activity written in full ("pkg/pkg.Main") is shortened as
         * Android shortens it ("pkg/.Main"). Null for one that can't name an app.
         */
        fun normalize(key: String): String? {
            val k = key.trim()
            if (k.isEmpty()) return null
            val prefix = k.substringBefore(':', "")
            val id = k.substringAfter(':')
            return when (prefix) {
                "a", "app" -> normalize(id)?.takeIf { ':' !in it }
                "l", "linux" -> id.takeIf { it.isNotEmpty() }?.let { "linux:$it" }
                "o", "owl" -> id.takeIf { it.isNotEmpty() }?.let { "owl:$it" }
                "s", "shortcut" -> id.takeIf { it.isNotEmpty() }?.let { "shortcut:$it" }
                "" -> shortComponent(k)
                else -> null
            }
        }

        /** "pkg/pkg.Main@10" as "pkg/.Main@10", as ComponentName.flattenToShortString writes it; a package alone as it is. */
        private fun shortComponent(key: String): String? {
            val pkg = key.substringBefore('/')
            if (pkg.isEmpty() || ' ' in key) return null
            if ('/' !in key) return key.takeIf { '@' !in it }
            val rest = key.substringAfter('/')
            val cls = rest.substringBefore('@')
            val profile = rest.substringAfter('@', "").let { if (it.isEmpty()) "" else "@$it" }
            if (cls.isEmpty()) return null
            val short = if (cls.startsWith("$pkg.") && cls.length > pkg.length + 1) cls.substring(pkg.length) else cls
            return "$pkg/$short$profile"
        }

        /** The drawer key of a home tile's key ("app:pkg/.Main" is "a:pkg/.Main"); null for a tile that isn't an app. */
        fun drawerKeyOfTile(tileKey: String): String? {
            val id = tileKey.substringAfter(':')
            return when (tileKey.substringBefore(':', "")) {
                "app" -> "a:$id"
                "linux" -> "l:$id"
                "owl" -> "o:$id"
                "shortcut" -> "s:$id"
                else -> null
            }
        }
    }
}

/**
 * apps.toml, which Linux sees as ~/.config/omalauncher/apps/apps.toml: an `[[app]]` table per app hidden or
 * renamed, written as settings › hidden apps and the apps' long-press menus change them, and read as agents and the
 * user edit it. Keys it doesn't know at the top are kept, as in style.toml.
 */
object AppTweaksFile {
    private const val FIND_HIDDEN = "find-hidden"
    private const val APP = "app"
    private const val KEY = "key"
    private const val HIDDEN = "hidden"
    private const val NAME = "name"

    val HEADER =
        """
        |# Apps hidden from the drawer, the recent apps and the bar's search, and the names apps go by. Settings ›
        |# hidden apps and an app's long-press menu rewrite this file, and the launcher follows edits to it.
        |# Each [[app]] has a key, as ../home/layout.toml names apps: an Android app's package ("com.whatsapp",
        |# all its icons), "package/activity" for one of them (with "@profile" for a work profile's), "linux:<desktop id>",
        |# "owl:<file>" or "shortcut:<key>"; the key android.drawer gives an app works too.
        |# hidden = true hides it (it stays on home if it's pinned there); name = "…" is what it's called everywhere,
        |# home's tiles, the drawer and its letters included. A package's entry counts for each of its icons that
        |# has none of its own. find-hidden = false stops typing a hidden app's full name in the bar from finding it.
        |# The private space's apps aren't changed here: the space hides them.
        |# What couldn't be read is in errors.txt next to this file.
        |
        """.trimMargin()

    data class Decoded(
        val tweaks: AppTweaks,
        val problems: List<String>,
    )

    fun decode(table: Map<String, Any>): Decoded {
        val problems = ArrayList<String>()
        val find =
            when (val v = table[FIND_HIDDEN]) {
                null -> true
                is Boolean -> v
                else -> true.also { problems += "$FIND_HIDDEN: should be true or false" }
            }
        val apps = LinkedHashMap<String, AppTweak>()
        val list = table[APP]
        if (list != null && list !is List<*>) problems += "$APP: should be [[app]] tables"
        (list as? List<*>).orEmpty().forEachIndexed { i, entry ->
            val where = "[[app]] ${i + 1}"
            val t = entry as? Map<*, *>
            val raw = t?.get(KEY) as? String
            val key = raw?.let(AppTweaks::normalize)
            if (t == null || raw == null || key == null) {
                problems += if (raw == null) "$where: needs a key = \"…\"" else "$where: \"$raw\" isn't an app's key"
                return@forEachIndexed
            }
            val hidden =
                when (val v = t[HIDDEN]) {
                    null, is Boolean -> v as Boolean?
                    else -> null.also { problems += "$raw: hidden should be true or false" }
                }
            val name =
                when (val v = t[NAME]) {
                    null -> null
                    is String -> v.trim().takeIf { it.isNotEmpty() }
                    else -> null.also { problems += "$raw: name should be a string" }
                }
            for (k in t.keys) if (k !in listOf(KEY, HIDDEN, NAME)) problems += "$raw: unknown key $k"
            if (key in apps) problems += "$raw is listed twice; the last one counts"
            apps[key] = AppTweak(hidden, name)
        }
        return Decoded(AppTweaks(apps.filterValues { !it.empty }, find), problems)
    }

    /** [tweaks] over [table], whose other top-level keys stay as they were. */
    fun encode(
        table: Map<String, Any>,
        tweaks: AppTweaks,
    ): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out[FIND_HIDDEN] = tweaks.findHidden
        for ((k, v) in table) if (k != FIND_HIDDEN && k != APP) out[k] = v
        // An empty list would be written `app = []`, which reads as no [[app]] anyway.
        if (tweaks.apps.isNotEmpty()) {
            out[APP] =
                tweaks.apps.map { (key, t) ->
                    linkedMapOf<String, Any>(KEY to key).apply {
                        t.hidden?.let { put(HIDDEN, it) }
                        t.name?.let { put(NAME, it) }
                    }
                }
        }
        return out
    }
}
