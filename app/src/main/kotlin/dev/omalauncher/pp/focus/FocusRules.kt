package dev.omalauncher.pp.focus

import java.time.LocalDateTime

/** The profile on at a moment, and why: turned on by hand, or by its schedule. */
data class ActiveFocus(
    val profile: FocusProfile,
    /** When it goes off by itself: the hand-set end, or its schedule window's; null for when it's turned off. */
    val until: LocalDateTime?,
    val scheduled: Boolean,
)

/** Why an app may not open now, or that it may. */
sealed interface Verdict {
    data object Allowed : Verdict

    /** Focus [profile] is on and leaves the app out. */
    data class Hidden(
        val profile: String,
    ) : Verdict

    /** The app's daily limit of [minutes] is used up: [usedMs] today. */
    data class TimeUp(
        val minutes: Int,
        val usedMs: Long,
    ) : Verdict
}

/** What typing in the bar asked of focus mode: a profile on (for [minutes], or until turned off), or off. */
sealed interface FocusCommand {
    data class On(
        val profile: String,
        val minutes: Int?,
    ) : FocusCommand

    data object Off : FocusCommand
}

/**
 * Focus mode's and app timers' rules, without Android: which profile is on, which apps it lets through, whether an
 * app's time is up, and what "focus …" in the bar means. [Focus] applies them to the phone.
 */
object FocusRules {
    /** "focus off" in the bar, and the word no profile may be named. */
    const val OFF = "off"

    /** The word the bar's commands start with. */
    const val WORD = "focus"

    /** The profile on at [now]: one turned on by hand until its end, else the first one whose schedule covers [now]. */
    fun active(
        config: FocusConfig,
        now: LocalDateTime,
    ): ActiveFocus? {
        config.profile(config.active)?.let { p ->
            val until = config.until
            if (until == null || now.isBefore(until)) return ActiveFocus(p, until, scheduled = false)
        }
        if (config.pausedUntil?.let(now::isBefore) == true) return null
        for (p in config.profiles) {
            val window = p.schedule?.window(now) ?: continue
            return ActiveFocus(p, window.second, scheduled = true)
        }
        return null
    }

    /**
     * Whether [profile] lets the app with [ids] through: one of its ids is among the apps it lets through, or, for one
     * with a block list, none is blocked. No profile lets everything through.
     */
    fun allows(
        profile: FocusProfile?,
        ids: Collection<String>,
    ): Boolean {
        if (profile == null) return true
        val block = profile.block ?: return ids.any { it in profile.apps }
        return ids.none { it in block }
    }

    /** Milliseconds of [timer]'s limit left after [usedMs] today, with [extraMinutes] granted on top; 0 or less is up. */
    fun left(
        timer: AppTimer,
        usedMs: Long,
        extraMinutes: Int = 0,
    ): Long = (timer.minutes + extraMinutes) * 60_000L - usedMs

    /**
     * Whether the app may open now. Focus first: a profile that leaves it out hides it, unless [once] lets it through
     * this time. Then its timer, unless the user said to ignore it today ([ignored]).
     */
    fun verdict(
        config: FocusConfig,
        now: LocalDateTime,
        ids: Collection<String>,
        pkg: String?,
        usedMs: () -> Long,
        extraMinutes: Int = 0,
        ignored: Boolean = false,
        once: Boolean = false,
    ): Verdict {
        if (once) return Verdict.Allowed
        val focus = active(config, now)
        if (focus != null && !allows(focus.profile, ids)) return Verdict.Hidden(focus.profile.name)
        val timer = pkg?.let(config::timer) ?: return Verdict.Allowed
        if (ignored) return Verdict.Allowed
        val used = usedMs()
        return if (left(timer, used, extraMinutes) <= 0) Verdict.TimeUp(timer.minutes, used) else Verdict.Allowed
    }

    /**
     * [config] with [name] on by hand, for [minutes] or until turned off; scheduled ones no longer wait for a pause to
     * end, as one was asked for.
     */
    fun turnOn(
        config: FocusConfig,
        name: String,
        minutes: Int?,
        now: LocalDateTime,
    ): FocusConfig {
        val p = config.profile(name) ?: return config
        return config.copy(active = p.name, until = minutes?.let { now.plusMinutes(it.toLong()) }, pausedUntil = null)
    }

    /**
     * [config] with focus off: the profile on by hand goes off, and one on by its schedule stays off until its window
     * ends, then turns on again at its next one.
     */
    fun turnOff(
        config: FocusConfig,
        now: LocalDateTime,
    ): FocusConfig {
        val off = config.copy(active = null, until = null)
        val scheduled = active(off.copy(pausedUntil = null), now)?.takeIf { it.scheduled }
        return off.copy(pausedUntil = scheduled?.until ?: off.pausedUntil?.takeIf(now::isBefore))
    }

    /**
     * The next moment after [now] when what's on may change by itself: a hand-set end, a pause's end, a schedule window
     * starting or ending. Null when nothing is due within a week.
     */
    fun nextChange(
        config: FocusConfig,
        now: LocalDateTime,
    ): LocalDateTime? {
        val times = ArrayList<LocalDateTime>()
        config.until?.let(times::add)
        config.pausedUntil?.let(times::add)
        for (p in config.profiles) {
            val s = p.schedule ?: continue
            s.window(now)?.let { times += it.second }
            s.nextStart(now)?.let(times::add)
        }
        return times.filter { it.isAfter(now) }.minOrNull()
    }

    /**
     * What's typed in the bar as a focus command: "focus off", "focus <profile>", "focus <profile> 25m" (or 2h, 1h30,
     * 90). A profile is found by its name, or the start of just one name; anything else isn't a command (null), and
     * goes on to search and the agent as usual.
     */
    fun command(
        text: String,
        profiles: List<FocusProfile>,
    ): FocusCommand? {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2 || !words[0].equals(WORD, ignoreCase = true)) return null
        if (words.size == 2 && words[1].equals(OFF, ignoreCase = true)) return FocusCommand.Off
        // A profile's name may have spaces; a last word that reads as a duration isn't part of it.
        val duration = if (words.size >= 3) minutes(words.last()) else null
        val name = words.subList(1, if (duration != null) words.size - 1 else words.size).joinToString(" ")
        val exact = profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val profile = exact ?: profiles.filter { it.name.startsWith(name, ignoreCase = true) }.singleOrNull() ?: return null
        return FocusCommand.On(profile.name, duration)
    }

    /** "25m", "2h", "1h30", "1h30m" or "90" (minutes) as minutes; null for anything else, or nothing. */
    fun minutes(text: String): Int? {
        val m = Regex("(?i)^(?:(\\d{1,3})h)?(?:(\\d{1,4})m?)?$").matchEntire(text.trim()) ?: return null
        val (h, min) = m.destructured
        if (h.isEmpty() && min.isEmpty()) return null
        val total = (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)
        return total.takeIf { it in 1..(24 * 60) }
    }
}
