package dev.omalauncher.pp.tiles

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.StringRes
import dev.omalauncher.pp.App
import dev.omalauncher.pp.MainActivity
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.SettingsActivity
import dev.omalauncher.pp.theme.TileStyle
import dev.omalauncher.pp.ui.SettingsPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A quick settings tile of the launcher's. While the shade shows it, it follows [QuickTiles.updates]; in between,
 * [QuickTiles.watch] has Android bind it again for each change.
 */
abstract class QuickTile : TileService() {
    private var listening: CoroutineScope? = null

    /** Null while a backup runs, when the process has a plain Application (see MainActivity). */
    protected val runtime: Runtime? get() = (application as? App)?.runtime

    /** The app's, so the tiles are in the language picked in settings. */
    override fun getResources(): Resources = application?.resources ?: super.getResources()

    override fun onStartListening() {
        super.onStartListening()
        val runtime = runtime ?: return
        listening?.cancel()
        listening = MainScope().also { scope -> scope.launch { QuickTiles.updates(runtime).collect(::show) } }
    }

    /** Just added: it shows the manifest's label and icon until it's drawn. */
    override fun onTileAdded() {
        super.onTileAdded()
        requestListeningState(this, ComponentName(this, javaClass))
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    private fun show(facts: QuickTiles.Facts) {
        val tile = qsTile ?: return
        draw(tile, facts)
        tile.updateTile()
    }

    protected abstract fun draw(
        tile: Tile,
        facts: QuickTiles.Facts,
    )

    protected fun Tile.show(
        @StringRes label: Int,
        subtitle: String?,
        state: Int,
    ) {
        this.label = getString(label)
        this.subtitle = subtitle
        this.state = state
        if (Build.VERSION.SDK_INT >= 30) stateDescription = subtitle
    }

    /** [action] once the phone is unlocked: what a tile opens, or a shutdown, isn't for whoever holds a locked phone. */
    protected fun unlocked(action: () -> Unit) = if (isLocked) unlockAndRun(action) else action()

    /** Opens [intent]'s activity, closing the shade; Android 14+ takes only a PendingIntent for that. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun open(intent: Intent) =
        unlocked {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }

    /** Settings › linux: the install offer, its progress, or a failure to retry. */
    protected fun openSetup() = open(SettingsActivity.intent(this, SettingsPage.Linux, null))

    /** Home, for it to do [request] (see [QuickTiles.onHomeResumed]). */
    protected fun openHome(request: QuickTiles.HomeRequest) =
        unlocked {
            QuickTiles.requestHome(request)
            open(Intent(this, MainActivity::class.java))
        }

    override fun onDestroy() {
        listening?.cancel()
        super.onDestroy()
    }
}

/**
 * Linux: running, starting, shut down; a tap starts it, or shuts it down as settings › linux does, asking first while
 * Linux windows are open. Without Linux, or while it installs, it opens settings › linux; so does a long press.
 */
class LinuxTile : QuickTile() {
    override fun draw(
        tile: Tile,
        facts: QuickTiles.Facts,
    ) {
        val status = facts.linux
        val subtitle =
            when (status) {
                LinuxTileStatus.NotInstalled -> R.string.qs_tiles_not_installed
                LinuxTileStatus.Installing -> R.string.qs_tiles_installing
                LinuxTileStatus.Failed -> R.string.qs_tiles_failed
                LinuxTileStatus.Removing -> R.string.qs_tiles_removing
                LinuxTileStatus.Stopped -> R.string.qs_tiles_off
                LinuxTileStatus.Starting -> R.string.qs_tiles_starting
                LinuxTileStatus.Running -> R.string.qs_tiles_running
                LinuxTileStatus.Stopping -> R.string.qs_tiles_stopping
            }
        val state =
            when (status) {
                LinuxTileStatus.Running, LinuxTileStatus.Starting -> Tile.STATE_ACTIVE
                LinuxTileStatus.Removing -> Tile.STATE_UNAVAILABLE
                else -> Tile.STATE_INACTIVE
            }
        tile.show(R.string.qs_tiles_linux, getString(subtitle), state)
    }

    override fun onClick() {
        val runtime = runtime ?: return
        when (linuxTap(QuickTiles.facts(runtime).linux, windowsOpen = runtime.wm.appWindows.isNotEmpty())) {
            LinuxTap.Start -> QuickTiles.startLinux(runtime)
            LinuxTap.ShutDown -> unlocked(runtime::shutDownLinux)
            LinuxTap.AskShutDown -> open(LinuxPowerActivity.intent(this))
            LinuxTap.OpenSetup -> openSetup()
            LinuxTap.Wait -> {}
        }
    }
}

/** A terminal, as Super+Enter opens one; Linux starts first if it must. Without Linux, settings › linux. */
class TerminalTile : QuickTile() {
    override fun draw(
        tile: Tile,
        facts: QuickTiles.Facts,
    ) {
        val subtitle =
            when (facts.linux) {
                LinuxTileStatus.Running -> null
                LinuxTileStatus.Starting -> getString(R.string.qs_tiles_starting)
                LinuxTileStatus.Stopped, LinuxTileStatus.Stopping -> getString(R.string.qs_tiles_starts_linux)
                else -> getString(R.string.qs_tiles_needs_linux)
            }
        tile.show(R.string.qs_tiles_terminal, subtitle, Tile.STATE_INACTIVE)
    }

    override fun onClick() {
        val runtime = runtime ?: return
        if (QuickTiles.facts(runtime).linux.usable) openHome(QuickTiles.HomeRequest.Terminal) else openSetup()
    }
}

/**
 * Home with the bar focused for a prompt to the default agent, or listening for one ([TileStyle.ASK_AGENT], once the
 * microphone is allowed). Linux starts meanwhile if it must; without it, settings › linux.
 */
class AgentTile : QuickTile() {
    override fun draw(
        tile: Tile,
        facts: QuickTiles.Facts,
    ) {
        val subtitle = if (facts.linux.usable) facts.agent else getString(R.string.qs_tiles_needs_linux)
        tile.show(R.string.qs_tiles_ask, subtitle, Tile.STATE_INACTIVE)
    }

    override fun onClick() {
        val runtime = runtime ?: return
        if (!QuickTiles.facts(runtime).linux.usable) return openSetup()
        val voice =
            runtime.style.style[TileStyle.ASK_AGENT] == TileStyle.VOICE &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        openHome(if (voice) QuickTiles.HomeRequest.Listen else QuickTiles.HomeRequest.Type)
    }
}
