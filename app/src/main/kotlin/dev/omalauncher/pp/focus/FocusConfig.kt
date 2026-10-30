package dev.omalauncher.pp.focus

import dev.omalauncher.pp.theme.Toml
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * A focus profile: the apps it lets through ([apps]), or, with a [block] list, every app but those. [dnd] turns on Do
 * Not Disturb while it's on, where the user lets the launcher; [schedule] turns it on by itself.
 */
data class FocusProfile(
    val name: String,
    /** App ids ([Focus.ids]): Android packages, `linux:<id>`, `owl:<file>`. */
    val apps: Set<String> = emptySet(),
    /** Null for a profile of the [apps] it lets through; a list, even an empty one, makes it one of the apps it hides. */
    val block: Set<String>? = null,
    val dnd: Boolean = false,
    val schedule: WeeklySchedule? = null,
)

/**
 * The weekly hours a profile turns on by itself: [from] to [to] on each of [days]. A [to] at or before [from] runs past
 * midnight into the next day (22:00 to 07:00), and [days] are the days it starts on.
 */
data class WeeklySchedule(
    val days: Set<DayOfWeek>,
    val from: LocalTime,
    val to: LocalTime,
) {
    private val overnight get() = !to.isAfter(from)

    /** The window [at] falls in, as its start and end; null outside every window. */
    fun window(at: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
        // A window that started today, or one that started yesterday and runs overnight into today.
        for (back in 0L..1L) {
            val day = at.toLocalDate().minusDays(back)
            if (day.dayOfWeek !in days) continue
            val start = day.atTime(from)
            val end = if (overnight) day.plusDays(1).atTime(to) else day.atTime(to)
            if (!at.isBefore(start) && at.isBefore(end)) return start to end
        }
        return null
    }

    /** When the next window after [at] starts, within a week; null with no days. */
    fun nextStart(at: LocalDateTime): LocalDateTime? =
        (0L..7L).asSequence().map { at.toLocalDate().plusDays(it) }.filter { it.dayOfWeek in days }.map { it.atTime(from) }.firstOrNull { it.isAfter(at) }
}

/** A daily limit for an Android app, by package. */
data class AppTimer(
    val app: String,
    val minutes: Int,
)

/**
 * Everything focus.toml holds: the profiles, the app timers, and which profile is on. [active] was turned on by hand
 * (settings, the bar, a widget or an agent) and goes off at [until], if set; scheduled ones turn on by themselves,
 * unless [pausedUntil] says the user turned them off until then.
 */
data class FocusConfig(
    val profiles: List<FocusProfile> = emptyList(),
    val timers: List<AppTimer> = emptyList(),
    val active: String? = null,
    val until: LocalDateTime? = null,
    val pausedUntil: LocalDateTime? = null,
) {
    fun profile(name: String?) = name?.let { n -> profiles.firstOrNull { it.name.equals(n, ignoreCase = true) } }

    fun timer(pkg: String) = timers.firstOrNull { it.app == pkg }

    /** With [pkg]'s limit set to [minutes], or removed with null. */
    fun withTimer(
        pkg: String,
        minutes: Int?,
    ): FocusConfig {
        val at = timers.indexOfFirst { it.app == pkg }
        return copy(
            timers =
                when {
                    minutes == null -> timers.filter { it.app != pkg }
                    at >= 0 -> timers.toMutableList().also { it[at] = AppTimer(pkg, minutes) }
                    else -> timers + AppTimer(pkg, minutes)
                },
        )
    }

    /** With [profile] in place of the one named [old] (a rename), or added at the end; the one on follows a rename. */
    fun withProfile(
        old: String?,
        profile: FocusProfile,
    ): FocusConfig {
        val at = profiles.indexOfFirst { it.name == old }
        val list = if (at >= 0) profiles.toMutableList().also { it[at] = profile } else profiles + profile
        return copy(profiles = list, active = if (old != null && active == old) profile.name else active)
    }

    fun withoutProfile(name: String) = copy(profiles = profiles.filter { it.name != name }, active = active.takeIf { it != name }, until = until.takeIf { active != name })
}

/** focus.toml to [FocusConfig] and back; what can't be read is skipped and said in [Decoded.problems]. */
object FocusFile {
    const val VERSION = 1

    class Decoded(
        val config: FocusConfig,
        val problems: List<String>,
    )

    val HEADER =
        """
        |# OmaLauncher++'s focus mode and app timers. The launcher writes this file as settings › focus changes, and reads
        |# it again when an agent or you edit it; what it can't read goes to errors.txt next to it.
        |#
        |# active = "work" turns a profile on by hand ("" for none), until = "2026-10-30 17:00" turns it off then.
        |# paused-until keeps scheduled profiles off until then, after they were turned off while scheduled.
        |#
        |# [[profile]]: name; apps, the apps it lets through (others are hidden from the drawer, home and search), or
        |# instead block, the apps it hides; dnd = true turns on Do Not Disturb while it's on; days ("mon".."sun"),
        |# from and to ("09:00") turn it on by itself every week. Apps are Android packages ("org.mozilla.firefox"),
        |# "linux:<desktop id>" ("linux:foot") and "owl:<file>" ("owl:notes.owl").
        |#
        |# [[timer]]: app, an Android package, and minutes, how long it may be used a day. Opening it from the launcher
        |# after that shows a "time's up" screen first.
        |
        |""".trimMargin()

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DAYS = DayOfWeek.entries.associateBy { it.name.take(3).lowercase() }

    fun dayWord(day: DayOfWeek) = day.name.take(3).lowercase()

    fun formatTime(t: LocalTime): String = t.format(TIME)

    fun formatDateTime(t: LocalDateTime): String = t.format(DATE_TIME)

    fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim().padStart(5, '0'), TIME) }.getOrNull()

    fun parseDateTime(text: String): LocalDateTime? =
        try {
            LocalDateTime.parse(text.trim().replace('T', ' '), DATE_TIME)
        } catch (_: DateTimeParseException) {
            null
        }

    fun decode(table: Map<String, Any>): Decoded {
        val problems = ArrayList<String>()

        fun text(
            key: String,
            v: Any?,
        ): String? =
            when (v) {
                null -> null
                is String -> v
                else -> null.also { problems += "$key: expected text, like \"…\"" }
            }

        fun moment(key: String): LocalDateTime? {
            val t = text(key, table[key])?.takeIf { it.isNotBlank() } ?: return null
            return parseDateTime(t) ?: null.also { problems += "$key: \"$t\" isn't a time like \"2026-10-30 17:00\"" }
        }

        fun strings(
            key: String,
            v: Any?,
        ): Set<String> =
            when (v) {
                null -> emptySet()
                is List<*> -> v.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.toCollection(LinkedHashSet())
                else -> emptySet<String>().also { problems += "$key: expected a list, like [\"org.mozilla.firefox\"]" }
            }

        val profiles = ArrayList<FocusProfile>()
        for ((i, p) in (table["profile"] as? List<*>).orEmpty().withIndex()) {
            val t = p as? Map<*, *> ?: continue
            val where = "profile ${i + 1}"
            val name = (t["name"] as? String)?.trim()
            if (name.isNullOrEmpty()) {
                problems += "$where: has no name; it was skipped"
                continue
            }
            if (name.equals(FocusRules.OFF, ignoreCase = true) || profiles.any { it.name.equals(name, ignoreCase = true) }) {
                problems += "$where: \"$name\" is ${if (profiles.any { it.name.equals(name, ignoreCase = true) }) "named twice" else "a word the bar keeps for itself"}; it was skipped"
                continue
            }
            val days = strings("$where.days", t["days"]).mapNotNull { d -> DAYS[d.lowercase().take(3)] ?: null.also { problems += "$where.days: \"$d\" isn't a day (mon, tue, … sun)" } }.toSet()
            val from = (t["from"] as? String)?.let { s -> parseTime(s) ?: null.also { problems += "$where.from: \"$s\" isn't a time like \"09:00\"" } }
            val to = (t["to"] as? String)?.let { s -> parseTime(s) ?: null.also { problems += "$where.to: \"$s\" isn't a time like \"17:00\"" } }
            val schedule =
                when {
                    days.isNotEmpty() && from != null && to != null -> WeeklySchedule(days, from, to)
                    days.isEmpty() && from == null && to == null -> null
                    else -> null.also { problems += "$where: a schedule needs days, from and to" }
                }
            val dnd = t["dnd"]
            if (dnd != null && dnd !is Boolean) problems += "$where.dnd: expected true or false"
            if ("apps" in t && "block" in t) problems += "$where: has both apps and block; only block is used"
            val block = if ("block" in t) strings("$where.block", t["block"]) else null
            profiles += FocusProfile(name, if (block == null) strings("$where.apps", t["apps"]) else emptySet(), block, dnd == true, schedule)
        }

        val timers = ArrayList<AppTimer>()
        for ((i, x) in (table["timer"] as? List<*>).orEmpty().withIndex()) {
            val t = x as? Map<*, *> ?: continue
            val app = (t["app"] as? String)?.trim()
            val minutes = (t["minutes"] as? Double)?.toInt()
            when {
                app.isNullOrEmpty() -> problems += "timer ${i + 1}: has no app; it was skipped"
                minutes == null || minutes < 1 -> problems += "timer ${i + 1}: minutes is a number from 1 on; it was skipped"
                timers.any { it.app == app } -> problems += "timer ${i + 1}: $app has a limit already; it was skipped"
                else -> timers += AppTimer(app, minutes.coerceAtMost(24 * 60))
            }
        }

        var active = text("active", table["active"])?.trim()?.takeIf { it.isNotEmpty() }
        if (active != null && profiles.none { it.name.equals(active, ignoreCase = true) }) {
            problems += "active: there's no profile \"$active\""
            active = null
        }
        val name = active?.let { a -> profiles.first { it.name.equals(a, ignoreCase = true) }.name }
        return Decoded(FocusConfig(profiles, timers, name, if (name != null) moment("until") else null, moment("paused-until")), problems)
    }

    fun encode(config: FocusConfig): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out["version"] = VERSION.toDouble()
        out["active"] = config.active.orEmpty()
        out["until"] = config.until?.let(::formatDateTime).orEmpty()
        out["paused-until"] = config.pausedUntil?.let(::formatDateTime).orEmpty()
        out["profile"] =
            config.profiles.map { p ->
                LinkedHashMap<String, Any>().apply {
                    put("name", p.name)
                    if (p.block == null) put("apps", p.apps.toList()) else put("block", p.block.toList())
                    put("dnd", p.dnd)
                    p.schedule?.let { s ->
                        put("days", DayOfWeek.entries.filter { it in s.days }.map(::dayWord))
                        put("from", formatTime(s.from))
                        put("to", formatTime(s.to))
                    }
                }
            }
        out["timer"] = config.timers.map { linkedMapOf<String, Any>("app" to it.app, "minutes" to it.minutes.toDouble()) }
        return out
    }

    /** The file's text; an empty list of tables is left out, as TOML has no way to write one. */
    fun write(config: FocusConfig): String = Toml.write(encode(config).filterValues { it !is List<*> || it.isNotEmpty() }, HEADER)

    /** [text] read; throws [Toml.Error] for a file that doesn't parse at all. */
    fun read(text: String): Decoded = decode(Toml.parse(text))
}
