package dev.omalauncher.pp.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.omalauncher.pp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The Android permissions widgets' sources need, asked for by `permission("location")` from a tap on a widget that says why.
 * Nothing asks on its own: a source without its permission reads as unavailable, and the widget offers the tap. Main thread.
 */
object WidgetPermissions {
    /** Android's permission behind each of permission(...)'s words (PERMISSIONS in the owl module). */
    private val names = mapOf("location" to Manifest.permission.ACCESS_COARSE_LOCATION, "steps" to Manifest.permission.ACTIVITY_RECOGNITION)

    /** What to tell the user when Android won't show its dialog again, and the app's settings open instead. */
    private val inSettings = mapOf("location" to R.string.owl_data_allow_location, "steps" to R.string.owl_data_allow_steps)

    /**
     * Asks with Android's dialog over the activity in front, then says whether it was granted and, if not, whether Android
     * would show the dialog again; set as an activity that shows widgets resumes.
     */
    var ask: ((permission: String, answer: (granted: Boolean, blocked: Boolean) -> Unit) -> Unit)? = null
        set(value) {
            field = value
            // Resumed, maybe back from the app's settings with a permission switched there.
            if (value != null) bump()
        }

    private val changed = MutableStateFlow(0)

    /** Bumped whenever a permission may have changed: the sources that need one check again. */
    val changes: StateFlow<Int> get() = changed

    private fun bump() = changed.update { it + 1 }

    fun granted(
        context: Context,
        word: String,
    ): Boolean = names[word]?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } == true

    /** permission("location"), "steps": Android's dialog, or after it was refused twice, the app's settings with a word on where. */
    fun request(
        context: Context,
        word: String,
    ) {
        val permission = names[word] ?: return
        if (granted(context, word)) return
        val ask = ask ?: return openSettings(context, word)
        ask(permission) { granted, blocked ->
            bump()
            if (!granted && blocked) openSettings(context, word)
        }
    }

    private fun openSettings(
        context: Context,
        word: String,
    ) {
        inSettings[word]?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
