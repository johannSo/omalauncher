package dev.omalauncher.pp.tiles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.res.stringResource
import dev.omalauncher.pp.App
import dev.omalauncher.pp.AppLanguage
import dev.omalauncher.pp.R
import dev.omalauncher.pp.ui.ConfirmDialog
import dev.omalauncher.pp.ui.KeyIndicated
import dev.omalauncher.pp.ui.SettingsPage

/**
 * The Linux tile's "shut down?", while Linux windows are open: shutting down closes them and ends the agents working
 * in them, which settings › linux asks about too. Only the dialog shows, over whatever was under the shade.
 */
class LinuxPowerActivity : ComponentActivity() {
    /** In the language picked in settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = (application as? App)?.runtime
        if (runtime == null) {
            finish()
            return
        }
        setContent {
            KeyIndicated {
                ConfirmDialog(
                    stringResource(R.string.qs_tiles_shut_down_title),
                    stringResource(R.string.qs_tiles_shut_down_body),
                    stringResource(R.string.fork_linux_shut_down),
                    onDismiss = ::finish,
                ) {
                    runtime.shutDownLinux()
                    finish()
                }
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, LinuxPowerActivity::class.java)
    }
}

/**
 * Where a long press on a tile goes (ACTION_QS_TILE_PREFERENCES): settings › linux for the Linux tile, settings ›
 * quick settings tiles for the others. Shows nothing itself; settings is its own task.
 */
class TileSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = (application as? App)?.runtime
        val tile =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
            }
        if (runtime != null && intent.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            runtime.openSettings(if (tile?.className == LinuxTile::class.java.name) SettingsPage.Linux else SettingsPage.QuickTiles)
        }
        finish()
    }
}
