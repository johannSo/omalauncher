package dev.omalauncher.pp.focus

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.pp.AndroidAppCatalog
import dev.omalauncher.pp.InstantActivity
import dev.omalauncher.pp.LauncherData
import dev.omalauncher.pp.R
import dev.omalauncher.pp.theme.Toml
import dev.omalauncher.pp.ui.AppHit
import dev.omalauncher.pp.upgrade.SyncedFile
import dev.omalauncher.pp.widgets.Shortcut
import dev.omalauncher.pp.widgets.runtime.MediaListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Focus mode and app timers on the phone. focus.toml ([FocusFile]) in the launcher's data, which Linux sees as
 * ~/.config/omalauncher/focus/focus.toml, holds the profiles, the timers and which profile is on, so agents can edit it
 * and a backup takes it along. While a profile is on, the apps it leaves out are hidden from the drawer, search, the
 * recent strip and home, and Do Not Disturb is on if it asks for it. [check] is the one place that says whether an app
 * may open now; every Android app or shortcut the launcher opens asks it first ([AndroidAppCatalog.gate]), and so does
 * [FocusGuard], for apps opened elsewhere, when the user turns it on. Main thread.
 */
class Focus(
    private val context: Context,
    internal val apps: AndroidAppCatalog,
) {
    /** The launcher's own package: its Linux windows and screens count as its screen time. */
    internal val ownPackage: String = context.packageName

    private val dir = LauncherData.focusDir(context)
    private val synced = SyncedFile(File(dir, FILE), File(dir, ERRORS), "focus mode") { FocusFile.write(config) }

    /** Grants that last a day, and whether focus turned Do Not Disturb on: the phone's, not settings, so not in backups. */
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val screenTime = ScreenTime(context)

    var config by mutableStateOf(FocusConfig())
        private set

    /** Counts the minutes, and each change at a schedule's boundary: what reads [active] in Compose follows the clock too. */
    private var tick by mutableIntStateOf(0)

    /** The profile on now, or null. */
    val active: ActiveFocus?
        get() {
            tick
            return FocusRules.active(config, LocalDateTime.now())
        }

    /** What errors.txt says about focus.toml. */
    val errors get() = synced.errors

    /** The app let through once, from the time's-up screen's [ open anyway ], until another app comes to the front. */
    @Volatile private var once: String? = null

    /** The app whose timer settings › focus asks about as it opens, from the app's long-press menu; taken once it does. */
    var pickingTimer by mutableStateOf<String?>(null)

    /** Stopped launches waiting on the time's-up screen, by the token it's started with. */
    private val stops = HashMap<Int, Stop>()
    private var tokens = 0

    init {
        val text = synced.open()
        if (text == null) synced.create() else read(text)
        synced.start { changed ->
            read(changed)
            if (changed.isEmpty() && !synced.exists) synced.save()
            applyActive()
        }
        apps.gate = ::gate
        scope.launch {
            while (true) {
                applyActive()
                // Again at the next minute, or sooner at a boundary: a schedule's start lands on the minute.
                val now = LocalDateTime.now()
                val next = listOfNotNull(FocusRules.nextChange(config, now), now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)).min()
                delay(ChronoUnit.MILLIS.between(now, next).coerceIn(1_000, 60_000))
                tick++
            }
        }
    }

    private fun read(text: String) {
        val decoded =
            try {
                FocusFile.read(text)
            } catch (e: Toml.Error) {
                synced.readable = false
                synced.setProblems(listOf("focus.toml ${e.message}; nothing in it was applied, and settings won't save focus mode until it's fixed"))
                return
            }
        synced.readable = true
        config = decoded.config
        synced.setProblems(decoded.problems)
    }

    /** Changes focus.toml through [change] and saves it. */
    fun update(change: (FocusConfig) -> FocusConfig) {
        val next = change(config)
        if (next == config) return
        config = next
        // What was skipped goes from the file as it's written anew; a file that didn't parse isn't written, and says so still.
        if (synced.readable) synced.drop { true }
        synced.save()
        tick++
        applyActive()
    }

    /** [name] on, for [minutes] or until turned off. */
    fun turnOn(
        name: String,
        minutes: Int? = null,
    ) = update { FocusRules.turnOn(it, name, minutes, LocalDateTime.now()) }

    /** Focus off; a scheduled profile stays off until its window ends. */
    fun turnOff() = update { FocusRules.turnOff(it, LocalDateTime.now()) }

    /**
     * Runs "focus work", "focus work 25m" or "focus off" typed in the bar; false for anything else, which the bar
     * handles as usual.
     */
    fun command(text: String): Boolean {
        when (val c = FocusRules.command(text, config.profiles) ?: return false) {
            FocusCommand.Off -> turnOff()
            is FocusCommand.On -> turnOn(c.profile, c.minutes)
        }
        // The drawer changing under the bar says it too, but not what the profile is, or until when.
        val on = active
        val said =
            when {
                on == null -> context.getString(R.string.focus_toast_off)
                on.until != null -> context.getString(R.string.focus_toast_until, on.profile.name, FocusFile.formatTime(on.until.toLocalTime()))
                else -> context.getString(R.string.focus_toast_on, on.profile.name)
            }
        Toast.makeText(context, said, Toast.LENGTH_SHORT).show()
        return true
    }

    /** What Enter does with [text] in the bar, when it's a focus command: "▸ focus work"; else null. */
    fun barHint(text: String): String? =
        when (val c = FocusRules.command(text, config.profiles)) {
            null -> null
            FocusCommand.Off -> "▸ ${FocusRules.WORD} ${FocusRules.OFF}"
            is FocusCommand.On -> "▸ ${FocusRules.WORD} ${c.profile}" + (c.minutes?.let { " ${it}m" } ?: "")
        }

    /** Whether [hit] shows in the drawer, search and the recent strip with the profile on now ([profile]). */
    internal fun allows(
        hit: AppHit,
        profile: FocusProfile? = active?.profile,
    ) = FocusRules.allows(profile, ids(hit))

    /** Whether a pinned app on home shows; folders and widgets always do. */
    fun allows(shortcut: Shortcut): Boolean {
        val profile = active?.profile ?: return true
        return FocusRules.allows(profile, ids(shortcut))
    }

    /**
     * Whether the app may open now, and if not why: the one place that says it. Focus first, then the app's timer; the
     * time's-up screen's grants count. Reads today's usage only for an app with a timer.
     */
    fun check(
        pkg: String,
        ids: Collection<String> = listOf(pkg),
    ): Verdict {
        val today = LocalDate.now().toString()
        val extra = prefs.getString(EXTRA + pkg, null)?.split(',')?.takeIf { it.firstOrNull() == today }?.getOrNull(1)?.toIntOrNull() ?: 0
        val ignored = prefs.getString(IGNORED + pkg, null) == today
        return FocusRules.verdict(config, LocalDateTime.now(), ids, pkg, { screenTime.usedToday(pkg) }, extra, ignored, once = once == pkg)
    }

    /** Milliseconds of [pkg]'s limit left today, the grants counted; null for an app without a timer, or one ignored today. */
    fun left(pkg: String): Long? {
        val timer = config.timer(pkg) ?: return null
        val today = LocalDate.now().toString()
        if (prefs.getString(IGNORED + pkg, null) == today) return null
        val extra = prefs.getString(EXTRA + pkg, null)?.split(',')?.takeIf { it.firstOrNull() == today }?.getOrNull(1)?.toIntOrNull() ?: 0
        return FocusRules.left(timer, screenTime.usedToday(pkg), extra)
    }

    /** Another app came to the front, outside the launcher: the one let through once is through. */
    fun foreground(pkg: String) {
        if (once != null && once != pkg) once = null
    }

    /**
     * Asked by [AndroidAppCatalog] before it opens [pkg]: true when it may not, and the time's-up screen shows instead,
     * which can still [open] it.
     */
    private fun gate(
        pkg: String,
        open: () -> Unit,
    ): Boolean {
        if (pkg == context.packageName) return false
        // Opening it from the launcher again is a new ask: an [ open anyway ] was for the time before.
        once = null
        val verdict = check(pkg)
        if (verdict == Verdict.Allowed) return false
        stop(pkg, verdict, open)
        return true
    }

    /** Shows the time's-up screen for [pkg]; [open] opens it after a grant, or null when it's in front already, behind the screen. */
    fun stop(
        pkg: String,
        verdict: Verdict,
        open: (() -> Unit)?,
    ) {
        val token = ++tokens
        stops[token] = Stop(pkg, label(pkg), verdict, open)
        // Only the latest one is shown.
        stops.keys.removeAll { it < token - 8 }
        InstantActivity.startInstantly(context, Intent(context, TimesUpActivity::class.java).putExtra(TimesUpActivity.EXTRA_TOKEN, token).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    internal fun stopOf(token: Int): Stop? = stops[token]

    /** [ 1 more minute ]: the limit grows by a minute, today. */
    fun grantMinute(pkg: String) {
        val today = LocalDate.now().toString()
        val extra = prefs.getString(EXTRA + pkg, null)?.split(',')?.takeIf { it.firstOrNull() == today }?.getOrNull(1)?.toIntOrNull() ?: 0
        prefs.edit().putString(EXTRA + pkg, "$today,${extra + 1}").apply()
        screenTime.invalidate()
    }

    /** [ open anyway ]: a time's up isn't said again today; an app focus hides opens this once. */
    fun openAnyway(
        pkg: String,
        verdict: Verdict,
    ) {
        if (verdict is Verdict.TimeUp) prefs.edit().putString(IGNORED + pkg, LocalDate.now().toString()).apply() else once = pkg
    }

    /** The app's name as the drawer shows it, else Android's, else its package. */
    fun label(pkg: String): String =
        apps.apps.firstOrNull { it.component.packageName == pkg && it.profile == null }?.label
            ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            ?: pkg

    /** Whether the user lets the launcher switch Do Not Disturb: what a profile's dnd needs. */
    fun dndGranted() = notifications.isNotificationPolicyAccessGranted

    fun openDndSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "no notification policy settings", it) }
    }

    /** The profile on now, applied: Do Not Disturb on for one that asks for it, and back off once none does, if focus turned it on. */
    private fun applyActive() {
        val wants = active?.profile?.dnd == true
        val ours = prefs.getBoolean(DND_OURS, false)
        if (wants == ours) return
        val filter = if (wants) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        // Not one the user turned on themselves: focus only turns off what it turned on.
        if (wants && notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val done =
            when {
                dndGranted() -> runCatching { notifications.setInterruptionFilter(filter) }.isSuccess
                // Notification access, which widgets' dnd(...) uses, lets the launcher switch it too.
                else ->
                    MediaListener.connected?.let { l ->
                        runCatching { l.requestInterruptionFilter(if (wants) NotificationListenerService.INTERRUPTION_FILTER_PRIORITY else NotificationListenerService.INTERRUPTION_FILTER_ALL) }.isSuccess
                    } ?: false
            }
        if (done) prefs.edit().putBoolean(DND_OURS, wants).apply()
    }

    /** A launch the time's-up screen stands in for. */
    internal class Stop(
        val pkg: String,
        val label: String,
        val verdict: Verdict,
        val open: (() -> Unit)?,
    )

    companion object {
        const val FILE = "focus.toml"
        const val ERRORS = "errors.txt"
        private const val TAG = "Focus"
        private const val EXTRA = "extra:"
        private const val IGNORED = "ignored:"
        private const val DND_OURS = "dnd_ours"

        /** An Android app's or a shortcut's id in a profile is its package; Linux and owl apps have a prefix. */
        internal fun ids(hit: AppHit): List<String> =
            when (hit) {
                is AppHit.Android -> listOf(hit.app.component.packageName)
                is AppHit.Shortcut -> listOf(hit.shortcut.ref.packageName)
                is AppHit.Linux -> listOf("linux:" + hit.app.id)
                is AppHit.Owl -> listOf("owl:" + hit.spec.file.name)
            }

        fun ids(shortcut: Shortcut): List<String> =
            listOfNotNull(
                shortcut.component?.packageName,
                shortcut.androidShortcut?.packageName,
                shortcut.linux?.let { "linux:" + it.id },
                shortcut.owl?.let { "owl:$it" },
            )
    }
}
