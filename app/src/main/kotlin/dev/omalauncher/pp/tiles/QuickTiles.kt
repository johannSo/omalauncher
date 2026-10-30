package dev.omalauncher.pp.tiles

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.pp.R
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.linux.LinuxState
import dev.omalauncher.pp.ui.LauncherState
import dev.omalauncher.pp.ui.PromptRequest
import dev.omalauncher.pp.ui.SettingsPage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The launcher's quick settings tiles ([LinuxTile], [TerminalTile], [AgentTile]) and what they share: what they show,
 * kept up to date, and what home does for the ones that open it.
 */
object QuickTiles {
    private const val TAG = "tiles"

    /** In settings' order. */
    val services: List<Class<out TileService>> = listOf(LinuxTile::class.java, TerminalTile::class.java, AgentTile::class.java)

    /** What the tiles show; one changing redraws them. */
    data class Facts(
        val linux: LinuxTileStatus,
        /** The default coding agent, which the ask tile names; null with none installed. */
        val agent: String?,
    )

    private val scope = MainScope()

    fun facts(runtime: Runtime): Facts {
        val bootstrap = runtime.bootstrap
        val status =
            linuxTileStatus(
                bootstrap.state.value,
                wanted = bootstrap.wanted,
                off = bootstrap.isOff,
                // Only an install that hasn't completed can be cancelled.
                setUp = !bootstrap.cancellable,
                started = runtime.started,
                starting = runtime.starting,
                shuttingDown = runtime.shuttingDown,
                ready = runtime.linux,
            )
        return Facts(status, runtime.agents.default?.name)
    }

    /** [facts], now and on every change. */
    fun updates(runtime: Runtime): Flow<Facts> =
        combine(
            runtime.bootstrap.state,
            snapshotFlow { listOf(runtime.started, runtime.starting, runtime.shuttingDown, runtime.linux, runtime.agents.default?.name) },
        ) { _, _ -> facts(runtime) }.distinctUntilChanged()

    /**
     * The tiles are active ones (META_DATA_ACTIVE_TILE): Android keeps what they last showed and binds them again only
     * when asked to, so every change asks. So does a new process, as the last one may have gone with Linux running.
     */
    fun watch(
        context: Context,
        runtime: Runtime,
    ) {
        val app = context.applicationContext
        scope.launch {
            updates(runtime).collect {
                for (service in services) {
                    // Not added to quick settings: Android ignores it.
                    runCatching { TileService.requestListeningState(app, ComponentName(app, service)) }.onFailure { Log.w(TAG, "can't update ${service.simpleName}", it) }
                }
            }
        }
    }

    /** What a tile opened home for. */
    enum class HomeRequest { Terminal, Type, Listen }

    /** Set by a tile as it opens home, which takes it as it resumes; a tile's own, so other apps can't open home with one. */
    private var pending: HomeRequest? = null
    private var pendingAt = 0L

    /** A request home didn't take this soon (the start was refused) is forgotten, rather than done the next time home shows. */
    private const val PENDING_MS = 10_000L

    fun requestHome(request: HomeRequest) {
        pending = request
        pendingAt = SystemClock.elapsedRealtime()
    }

    /** Home resumed: does what a tile opened it for. A terminal opens from home, the one screen allowed to open windows then. */
    fun onHomeResumed(
        activity: ComponentActivity,
        runtime: Runtime,
        state: LauncherState,
    ) {
        val request = pending ?: return
        pending = null
        if (SystemClock.elapsedRealtime() - pendingAt > PENDING_MS) return
        val down = facts(runtime).linux != LinuxTileStatus.Running
        when (request) {
            HomeRequest.Terminal -> {
                if (down) Toast.makeText(activity, R.string.qs_tiles_starting_linux, Toast.LENGTH_SHORT).show()
                scope.launch { if (whenLinuxUp(runtime)) runtime.terminal(null, "Terminal") else runtime.openSettings(SettingsPage.Linux) }
            }
            // The bar finds apps too, so it comes up at once; Linux starts meanwhile, for the agent.
            HomeRequest.Type -> {
                if (down) scope.launch { whenLinuxUp(runtime) }
                activity.lifecycleScope.launch {
                    activity.awaitWindowFocus()
                    state.promptRequest = PromptRequest.Type
                }
            }
            // The bar listens only for the agent, so once Linux is up.
            HomeRequest.Listen -> {
                if (down) Toast.makeText(activity, R.string.qs_tiles_starting_linux, Toast.LENGTH_SHORT).show()
                activity.lifecycleScope.launch {
                    if (!whenLinuxUp(runtime)) return@launch runtime.openSettings(SettingsPage.Linux)
                    activity.awaitWindowFocus()
                    state.promptRequest = PromptRequest.Listen
                }
            }
        }
    }

    /** Starts Linux from a tile, which doesn't wait for it. */
    fun startLinux(runtime: Runtime) {
        scope.launch { whenLinuxUp(runtime) }
    }

    /**
     * Brings Linux up, as the launcher does when it starts or settings › linux's start: true once its services run,
     * false if it can't come up by itself (not installed, or failed: settings › linux has the way on).
     */
    suspend fun whenLinuxUp(runtime: Runtime): Boolean {
        // The launcher, and with it Linux unless it was shut down; nothing if it's up already.
        runtime.start()
        // A shutdown under way ends first; Linux then starts again.
        snapshotFlow { runtime.shuttingDown }.first { !it }
        if (runtime.bootstrap.state.first { it != LinuxState.Checking } == LinuxState.Off) runtime.startLinux()
        return combine(runtime.bootstrap.state, snapshotFlow { runtime.linux to runtime.starting }) { state, (up, starting) ->
            when {
                up -> true
                state == LinuxState.Installed || state is LinuxState.Installing -> null
                state == LinuxState.Off && starting -> null
                else -> false
            }
        }.filterNotNull().first()
    }

    /**
     * Until the window has focus, which comes once the shade has gone up: the keyboard asked for before that doesn't
     * show. Not longer than a second or two, in case it never comes (a dialog over home).
     */
    private suspend fun Activity.awaitWindowFocus() {
        if (hasWindowFocus()) return
        val observer = window.decorView.viewTreeObserver
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                val listener =
                    object : ViewTreeObserver.OnWindowFocusChangeListener {
                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                            if (!hasFocus) return
                            observer.removeOnWindowFocusChangeListener(this)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                observer.addOnWindowFocusChangeListener(listener)
                cont.invokeOnCancellation { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
            }
        }
    }
}
