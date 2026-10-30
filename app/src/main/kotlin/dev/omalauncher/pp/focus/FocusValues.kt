package dev.omalauncher.pp.focus

import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.pp.widgets.lang.Builtins
import dev.omalauncher.pp.widgets.lang.Lst
import dev.omalauncher.pp.widgets.lang.Null
import dev.omalauncher.pp.widgets.lang.Num
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import dev.omalauncher.pp.widgets.lang.Value
import dev.omalauncher.pp.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.DayOfWeek
import java.time.ZoneId

private fun num(v: Long) = Num(v.toDouble())

/** How often `android.usage` reads the phone's usage again while a widget shows it: Android has no event for it. */
private const val USAGE_EVERY_MS = 60_000L

/**
 * Today's screen time for `source u = android.usage`: `{available, total, opens, apps}`. `available` is false until
 * the user grants usage access (`settings("usage")` opens it); `total` is the seconds every app was in front today and
 * `opens` how often one came to the front; `apps`, longest first, each `{package, name, icon, time, opens, limit,
 * left}`: `time` in seconds, `limit` the app timer's in seconds and `left` what's left of it (null without one).
 * Off the main thread: it reads a day of Android's usage events.
 */
internal fun Focus.usageValue(): Value {
    val today = screenTime.today() ?: return Obj(mapOf("available" to bool(false), "total" to Num(0.0), "opens" to Num(0.0), "apps" to Lst(emptyList())))
    // Apps someone opens, and the launcher's own Linux windows and screens; not the system's background pieces.
    val listed = apps.apps.mapTo(HashSet()) { it.component.packageName } + ownPackage
    val shown = today.filter { (pkg, t) -> pkg in listed && (t.ms >= 1000 || t.opens > 0) }.entries.sortedByDescending { it.value.ms }
    val items =
        shown.take(Builtins.MAX_LIST).map { (pkg, t) ->
            val timer = config.timer(pkg)
            Obj(
                mapOf(
                    "package" to Str(pkg),
                    "name" to Str(label(pkg)),
                    "icon" to Str("app:$pkg"),
                    "time" to num(t.ms / 1000),
                    "opens" to num(t.opens.toLong()),
                    "limit" to (timer?.let { num(it.minutes * 60L) } ?: Null),
                    "left" to (timer?.let { left(pkg)?.let { l -> num((l / 1000).coerceAtLeast(0)) } } ?: Null),
                ),
            )
        }
    return Obj(
        mapOf(
            "available" to bool(true),
            "total" to num(shown.sumOf { it.value.ms } / 1000),
            "opens" to num(shown.sumOf { it.value.opens }.toLong()),
            "apps" to Lst(items),
        ),
    )
}

/** `android.usage` again every minute while a widget shows it, and as the timers change. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Focus.usageUpdates(): Flow<Value> =
    snapshotFlow { config.timers }
        .flatMapLatest {
            flow {
                while (true) {
                    emit(usageValue())
                    delay(USAGE_EVERY_MS)
                }
            }.flowOn(Dispatchers.IO)
        }.distinctUntilChanged()

/**
 * Focus mode for `source f = android.focus`: `{active, until, scheduled, profiles}`. `active` is the profile on now,
 * null for none; `until` when it goes off by itself, in Unix seconds, null for when it's turned off; `scheduled` whether
 * its schedule turned it on. `profiles` are every profile, each `{name, active, apps, dnd, schedule}`: `apps` how many
 * apps it lets through (or, as a negative, how many it blocks), `dnd` whether it turns on Do Not Disturb, `schedule`
 * its weekly hours as `{days, from, to}` (`days` as `["mon", "tue"]`, the times as "09:00"), or null. `focus("work")`,
 * `focus("work", 25)` and `focus("off")` switch it.
 */
internal fun Focus.focusValue(): Value {
    val on = active
    val zone = ZoneId.systemDefault()
    return Obj(
        mapOf(
            "active" to (on?.profile?.name?.let(::Str) ?: Null),
            "until" to (on?.until?.let { num(it.atZone(zone).toEpochSecond()) } ?: Null),
            "scheduled" to bool(on?.scheduled == true),
            "profiles" to
                Lst(
                    config.profiles.map { p ->
                        Obj(
                            mapOf(
                                "name" to Str(p.name),
                                "active" to bool(on?.profile?.name == p.name),
                                "apps" to num(p.block?.let { -it.size.toLong() } ?: p.apps.size.toLong()),
                                "dnd" to bool(p.dnd),
                                "schedule" to (
                                    p.schedule?.let { s ->
                                        Obj(
                                            mapOf(
                                                "days" to Lst(DayOfWeek.entries.filter { it in s.days }.map { Str(FocusFile.dayWord(it)) }),
                                                "from" to Str(FocusFile.formatTime(s.from)),
                                                "to" to Str(FocusFile.formatTime(s.to)),
                                            ),
                                        )
                                    } ?: Null
                                ),
                            ),
                        )
                    },
                ),
        ),
    )
}

/** focus("work"), focus("work", 25) for 25 minutes, or focus("off"), from a widget; an unknown profile does nothing. */
internal fun Focus.act(
    word: String,
    minutes: Value?,
) {
    if (word.equals(FocusRules.OFF, ignoreCase = true)) return turnOff()
    val m = (minutes as? Num)?.v?.toInt() ?: (minutes as? Str)?.v?.let(FocusRules::minutes)
    if (config.profile(word) != null) turnOn(word, m?.takeIf { it > 0 })
}
