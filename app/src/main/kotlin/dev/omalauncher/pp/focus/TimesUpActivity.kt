package dev.omalauncher.pp.focus

import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.addCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.omalauncher.pp.InstantActivity
import dev.omalauncher.pp.R
import dev.omalauncher.pp.ui.BracketAction
import dev.omalauncher.pp.ui.Mono
import dev.omalauncher.pp.ui.Palette
import dev.omalauncher.pp.ui.TextSize
import dev.omalauncher.pp.ui.holdsKeys

/**
 * What shows in place of an app focus mode leaves out, or one whose daily limit is used up: [ 1 more minute ] (a
 * timer's), [ open anyway ] and [ close ]. Opened by the launcher instead of the app, or by [FocusGuard] over an app
 * opened elsewhere; its own task, out of Recents, gone once left.
 */
class TimesUpActivity : InstantActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val focus = runtime.focus
        // Started again after the process went: the launch it stood for is gone too.
        val stop = focus.stopOf(intent.getIntExtra(EXTRA_TOKEN, -1)) ?: return finish()
        val app = runtime.androidApps.apps.firstOrNull { it.component.packageName == stop.pkg && it.profile == null }
        val icon = app?.let { runtime.androidApps.icons[it.key] }
        onBackPressedDispatcher.addCallback(this) { close(stop) }
        setInstantContent {
            TimesUpScreen(
                stop,
                icon,
                onMinute = {
                    focus.grantMinute(stop.pkg)
                    leave(stop)
                },
                onAnyway = {
                    focus.openAnyway(stop.pkg, stop.verdict)
                    leave(stop)
                },
                onClose = { close(stop) },
            )
        }
    }

    /** Away, and on to the app: opened now if the launcher stood in for it, else it's right behind. */
    private fun leave(stop: Focus.Stop) {
        closeInstantly()
        stop.open?.invoke()
    }

    /** Away, and home rather than to the app, if it was in front. */
    private fun close(stop: Focus.Stop) {
        if (stop.open == null) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        closeInstantly()
    }

    companion object {
        const val EXTRA_TOKEN = "token"
    }
}

@Composable
private fun TimesUpScreen(
    stop: Focus.Stop,
    icon: ImageBitmap?,
    onMinute: () -> Unit,
    onAnyway: () -> Unit,
    onClose: () -> Unit,
) {
    // Keys start on close: Enter right after the screen comes up mustn't open the app anyway.
    val start = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(32.dp).holdsKeys(start),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { Image(it, stop.label, Modifier.size(64.dp)) }
        val verdict = stop.verdict
        val resources = androidx.compose.ui.platform.LocalResources.current
        BasicText(
            stringResource(if (verdict is Verdict.TimeUp) R.string.focus_times_up_title else R.string.focus_hidden_title),
            style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title),
        )
        BasicText(
            when (verdict) {
                is Verdict.TimeUp -> stringResource(R.string.focus_times_up_message, stop.label, duration(resources, verdict.usedMs), duration(resources, verdict.minutes * 60_000L))
                is Verdict.Hidden -> stringResource(R.string.focus_hidden_message, stop.label, verdict.profile)
                Verdict.Allowed -> stop.label
            },
            Modifier.widthIn(max = 420.dp),
            style = Mono.copy(color = Palette.foreground, textAlign = TextAlign.Center),
        )
        if (verdict is Verdict.TimeUp) {
            BasicText(stringResource(R.string.focus_times_up_hint), Modifier.widthIn(max = 420.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small, textAlign = TextAlign.Center))
        }
        Row(horizontalArrangement = Arrangement.Center) {
            if (verdict is Verdict.TimeUp) BracketAction(stringResource(R.string.focus_one_more_minute), Palette.accent, onClick = onMinute)
            BracketAction(stringResource(R.string.focus_open_anyway), Palette.muted, onClick = onAnyway)
            BracketAction(stringResource(R.string.focus_close), Palette.accent, Modifier.focusRequester(start), onClick = onClose)
        }
    }
}

/** [ms] as "1h 5m" or "32m", as the time's-up screen and settings say it. */
internal fun duration(
    resources: Resources,
    ms: Long,
): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0)
    return if (minutes >= 60) resources.getString(R.string.focus_duration_hours, minutes / 60, minutes % 60) else resources.getString(R.string.focus_duration_minutes, minutes)
}
