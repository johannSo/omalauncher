package dev.omalauncher.pp.focus

/** One of Android's usage events, as much of it as the tally needs. */
data class UsageEvent(
    val time: Long,
    val kind: Kind,
    val pkg: String,
    /** The activity's class; several of one app's can be resumed at once (split screen, a dialog activity). */
    val cls: String = "",
) {
    enum class Kind { Resumed, Paused, ScreenOff }
}

/** An app's day: how long it was in front, in milliseconds, and how often it came to the front. */
data class AppTally(
    val ms: Long,
    val opens: Int,
)

/**
 * Adds up a day of [UsageEvent]s into each app's time in front and its opens, as Digital Wellbeing counts them: from an
 * activity resuming until the app's last one pauses or the screen goes off. An app already in front when the day
 * started counts from [start]; one still in front counts up to [end]. An open is the app coming to the front after
 * another one was (the home screen too), not a second activity of the one already there, nor the screen coming back
 * on to it. [ignore] leaves out what isn't anyone's use, like the home screen itself.
 */
object UsageTally {
    fun tally(
        events: List<UsageEvent>,
        start: Long,
        end: Long,
        ignore: (UsageEvent) -> Boolean = { false },
    ): Map<String, AppTally> {
        val ms = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        // Per app: since when it's been in front, and which of its activities are resumed.
        val front = HashMap<String, Pair<Long, MutableSet<String>>>()
        val seen = HashSet<String>()
        var last: String? = null

        fun add(
            pkg: String,
            from: Long,
            to: Long,
        ) {
            val d = to.coerceAtMost(end) - from.coerceAtLeast(start)
            if (d > 0) ms[pkg] = (ms[pkg] ?: 0L) + d
        }

        for (e in events.sortedBy { it.time }) {
            when (e.kind) {
                UsageEvent.Kind.ScreenOff -> {
                    for ((pkg, s) in front) add(pkg, s.first, e.time)
                    front.clear()
                }
                UsageEvent.Kind.Resumed -> {
                    seen += e.pkg
                    if (ignore(e)) {
                        last = e.pkg
                        continue
                    }
                    val s = front[e.pkg]
                    if (s == null) {
                        front[e.pkg] = e.time to mutableSetOf(e.cls)
                        if (last != e.pkg && e.time >= start) opens[e.pkg] = (opens[e.pkg] ?: 0) + 1
                    } else {
                        s.second += e.cls
                    }
                    last = e.pkg
                }
                UsageEvent.Kind.Paused -> {
                    if (ignore(e)) {
                        seen += e.pkg
                        continue
                    }
                    val s = front[e.pkg]
                    when {
                        s != null -> {
                            s.second -= e.cls
                            if (s.second.isEmpty()) {
                                add(e.pkg, s.first, e.time)
                                front -= e.pkg
                            }
                        }
                        // Resumed before the day began: in front since its start.
                        e.pkg !in seen -> add(e.pkg, start, e.time)
                    }
                    seen += e.pkg
                }
            }
        }
        for ((pkg, s) in front) add(pkg, s.first, end)
        return (ms.keys + opens.keys).associateWith { AppTally(ms[it] ?: 0L, opens[it] ?: 0) }
    }
}
