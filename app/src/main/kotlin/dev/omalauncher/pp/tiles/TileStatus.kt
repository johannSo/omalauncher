package dev.omalauncher.pp.tiles

import dev.omalauncher.pp.linux.LinuxState

/** Where Linux stands as the quick settings tiles show it: [LinuxState] with what the runtime is doing about it. */
enum class LinuxTileStatus {
    NotInstalled,
    Installing,
    Failed,
    Removing,
    Stopped,
    Starting,
    Running,
    Stopping,
}

/**
 * [state] as a tile shows it. [wanted] and [off] are read from disk, which [LinuxState.Checking] hasn't yet; [setUp]
 * means the first install completed, so an [LinuxState.Installing] is the update steps a start runs first. [started]
 * is the launcher having started in this process: a tile can bind to a process the launcher never started in, where
 * Linux isn't coming up by itself. [ready] is the runtime's services being up, after [LinuxState.Installed].
 */
fun linuxTileStatus(
    state: LinuxState,
    wanted: Boolean,
    off: Boolean,
    setUp: Boolean,
    started: Boolean,
    starting: Boolean,
    shuttingDown: Boolean,
    ready: Boolean,
): LinuxTileStatus =
    when (state) {
        LinuxState.Checking ->
            when {
                !wanted -> LinuxTileStatus.NotInstalled
                off || !started -> LinuxTileStatus.Stopped
                else -> LinuxTileStatus.Starting
            }
        LinuxState.NotInstalled -> LinuxTileStatus.NotInstalled
        is LinuxState.Installing -> if (setUp || starting) LinuxTileStatus.Starting else LinuxTileStatus.Installing
        is LinuxState.Failed -> LinuxTileStatus.Failed
        LinuxState.Installed ->
            when {
                shuttingDown -> LinuxTileStatus.Stopping
                ready -> LinuxTileStatus.Running
                else -> LinuxTileStatus.Starting
            }
        LinuxState.Off -> if (starting) LinuxTileStatus.Starting else LinuxTileStatus.Stopped
        LinuxState.Removing -> LinuxTileStatus.Removing
    }

/** What a tap on the Linux tile does. */
enum class LinuxTap {
    Start,
    ShutDown,

    /** Linux windows are open, which shutting down closes: ask first, as settings › linux does. */
    AskShutDown,

    /** Settings › linux: the install offer, its progress, a failure to retry or the removal. */
    OpenSetup,

    /** Already on its way up or down. */
    Wait,
}

fun linuxTap(
    status: LinuxTileStatus,
    windowsOpen: Boolean,
): LinuxTap =
    when (status) {
        LinuxTileStatus.Stopped -> LinuxTap.Start
        LinuxTileStatus.Running -> if (windowsOpen) LinuxTap.AskShutDown else LinuxTap.ShutDown
        LinuxTileStatus.Starting, LinuxTileStatus.Stopping -> LinuxTap.Wait
        LinuxTileStatus.NotInstalled, LinuxTileStatus.Installing, LinuxTileStatus.Failed, LinuxTileStatus.Removing -> LinuxTap.OpenSetup
    }

/** Whether something that needs a running Linux (a terminal, an agent) can get it from here, by starting it if it must. */
val LinuxTileStatus.usable get() = this == LinuxTileStatus.Running || this == LinuxTileStatus.Starting || this == LinuxTileStatus.Stopped || this == LinuxTileStatus.Stopping
