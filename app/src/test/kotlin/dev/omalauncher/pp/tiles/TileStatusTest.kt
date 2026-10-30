package dev.omalauncher.pp.tiles

import dev.omalauncher.pp.linux.LinuxState
import dev.omalauncher.pp.linux.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileStatusTest {
    private fun status(
        state: LinuxState,
        wanted: Boolean = true,
        off: Boolean = false,
        setUp: Boolean = true,
        started: Boolean = true,
        starting: Boolean = false,
        shuttingDown: Boolean = false,
        ready: Boolean = false,
    ) = linuxTileStatus(state, wanted, off, setUp, started, starting, shuttingDown, ready)

    private val installing = LinuxState.Installing(Stage.Download, "", 0.5f, emptyList())

    @Test fun beforeTheFirstCheck() {
        assertEquals(LinuxTileStatus.NotInstalled, status(LinuxState.Checking, wanted = false))
        assertEquals(LinuxTileStatus.Stopped, status(LinuxState.Checking, off = true))
        // A tile bound the process, the launcher never started in it: Linux isn't coming up.
        assertEquals(LinuxTileStatus.Stopped, status(LinuxState.Checking, started = false))
        assertEquals(LinuxTileStatus.Starting, status(LinuxState.Checking))
    }

    @Test fun installingIsStartingOnceSetUp() {
        assertEquals(LinuxTileStatus.Installing, status(installing, setUp = false))
        assertEquals(LinuxTileStatus.Starting, status(installing))
        assertEquals(LinuxTileStatus.Starting, status(installing, setUp = false, starting = true))
    }

    @Test fun installedRunsOnceItsServicesDo() {
        assertEquals(LinuxTileStatus.Starting, status(LinuxState.Installed))
        assertEquals(LinuxTileStatus.Running, status(LinuxState.Installed, ready = true))
        assertEquals(LinuxTileStatus.Stopping, status(LinuxState.Installed, ready = false, shuttingDown = true))
    }

    @Test fun offUntilStarted() {
        assertEquals(LinuxTileStatus.Stopped, status(LinuxState.Off, off = true))
        assertEquals(LinuxTileStatus.Starting, status(LinuxState.Off, off = true, starting = true))
    }

    @Test fun theRest() {
        assertEquals(LinuxTileStatus.NotInstalled, status(LinuxState.NotInstalled, wanted = false))
        assertEquals(LinuxTileStatus.Failed, status(LinuxState.Failed(Stage.Packages, "no network", emptyList())))
        assertEquals(LinuxTileStatus.Removing, status(LinuxState.Removing))
    }

    @Test fun taps() {
        assertEquals(LinuxTap.Start, linuxTap(LinuxTileStatus.Stopped, windowsOpen = false))
        assertEquals(LinuxTap.ShutDown, linuxTap(LinuxTileStatus.Running, windowsOpen = false))
        assertEquals(LinuxTap.AskShutDown, linuxTap(LinuxTileStatus.Running, windowsOpen = true))
        assertEquals(LinuxTap.Wait, linuxTap(LinuxTileStatus.Starting, windowsOpen = true))
        assertEquals(LinuxTap.Wait, linuxTap(LinuxTileStatus.Stopping, windowsOpen = false))
        for (s in listOf(LinuxTileStatus.NotInstalled, LinuxTileStatus.Installing, LinuxTileStatus.Failed, LinuxTileStatus.Removing)) {
            assertEquals(s.name, LinuxTap.OpenSetup, linuxTap(s, windowsOpen = false))
        }
    }

    @Test fun whatATerminalCanStart() {
        assertTrue(LinuxTileStatus.Running.usable)
        assertTrue(LinuxTileStatus.Stopped.usable)
        assertTrue(LinuxTileStatus.Starting.usable)
        assertTrue(LinuxTileStatus.Stopping.usable)
        assertFalse(LinuxTileStatus.NotInstalled.usable)
        assertFalse(LinuxTileStatus.Installing.usable)
        assertFalse(LinuxTileStatus.Failed.usable)
        assertFalse(LinuxTileStatus.Removing.usable)
    }
}
