package dev.omalauncher.pp.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for the microphone for a widget's speech("start"), see [dev.omalauncher.pp.widgets.runtime.Dictation], and for
 * what a widget's permission(...) names, see [dev.omalauncher.pp.widgets.runtime.WidgetPermissions]. Each activity that
 * shows widgets has one, made with it, as a launcher must be registered before the activity starts.
 */
class MicPermission(
    private val activity: ComponentActivity,
) {
    private var then: ((Boolean) -> Unit)? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val then = then
            this.then = null
            then?.invoke(granted)
        }

    fun ask(then: (Boolean) -> Unit) {
        this.then = then
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Asks for [permission]; `blocked` says Android won't show its dialog again, after two refusals, so only settings can grant it. */
    fun ask(
        permission: String,
        then: (granted: Boolean, blocked: Boolean) -> Unit,
    ) {
        this.then = { granted -> then(granted, !granted && !activity.shouldShowRequestPermissionRationale(permission)) }
        launcher.launch(permission)
    }
}
