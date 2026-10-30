package dev.omalauncher.pp.widgets.runtime

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import dev.omalauncher.pp.widgets.lang.Extra
import dev.omalauncher.pp.widgets.lang.ExtraType
import dev.omalauncher.pp.widgets.lang.IntentSpec
import dev.omalauncher.pp.widgets.lang.Intents
import dev.omalauncher.pp.widgets.lang.Value
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * How widgets talk to other Android apps (Tasker, Home Assistant, KDE Connect): `intent(...)` and `broadcast(...)`
 * made into Intents, and the broadcasts a `source x = broadcast("…")` hears. The launcher's own activities and
 * receivers are out of reach either way: the compiler refuses them when they're written out, [send] when values make them.
 */
internal class AppIntents(
    private val context: Context,
) {
    /** Starts the activity or sends the broadcast [spec] says; why it couldn't, or null. Main thread. */
    fun send(spec: IntentSpec): String? {
        Intents.check(spec)?.let { return it }
        val intent =
            try {
                intentOf(spec)
            } catch (e: IllegalArgumentException) {
                return e.message
            }
        if (reachesLauncher(intent, spec.broadcast)) return "it would reach the launcher itself, which widgets can't do this way"
        return try {
            if (spec.broadcast) context.sendBroadcast(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: ActivityNotFoundException) {
            "no app takes it" + (spec.pkg?.let { " (is $it installed?)" } ?: "")
        } catch (e: SecurityException) {
            // A protected action (CALL, a system broadcast) or an activity that isn't exported.
            "Android refused it: ${e.message}"
        } catch (e: RuntimeException) {
            // A file:// link (FileUriExposedException), extras too big for a transaction.
            e.message ?: e.toString()
        }
    }

    private fun intentOf(spec: IntentSpec): Intent {
        // A package alone opens that app, as androidApp(...) does, with the extras.
        val opensApp = !spec.broadcast && spec.action == null && spec.component == null && spec.data == null && spec.pkg != null
        val intent =
            if (opensApp) {
                context.packageManager.getLaunchIntentForPackage(spec.pkg!!) ?: throw IllegalArgumentException("${spec.pkg} isn't installed, or has nothing to open")
            } else {
                Intent().also { i -> spec.pkg?.let(i::setPackage) }
            }
        // Data alone is a link: whatever app opens it, as url(...) does.
        (spec.action ?: Intent.ACTION_VIEW.takeIf { !spec.broadcast && spec.data != null })?.let(intent::setAction)
        val uri = spec.data?.let(Uri::parse)
        when {
            uri != null && spec.type != null -> intent.setDataAndType(uri, spec.type)
            uri != null -> intent.data = uri
            spec.type != null -> intent.type = spec.type
        }
        spec.component?.let { intent.component = ComponentName.unflattenFromString(it) ?: throw IllegalArgumentException("component '$it' isn't a package and a class") }
        spec.categories.forEach(intent::addCategory)
        if (spec.extras.isNotEmpty()) intent.putExtras(bundleOf(spec.extras))
        // An app Tasker or the like hasn't been opened since it was installed still hears one meant for it.
        if (spec.broadcast && (spec.pkg != null || spec.component != null)) intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        return intent
    }

    /**
     * Whether [intent] would go to the launcher's own activities: an explicit one, or one only the launcher takes
     * (Android's chooser counts as another app). Broadcasts to it are refused by package and action already.
     */
    private fun reachesLauncher(
        intent: Intent,
        broadcast: Boolean,
    ): Boolean {
        val own = context.packageName
        if (intent.`package` == own || intent.component?.packageName == own) return true
        if (broadcast) return false
        return intent.resolveActivity(context.packageManager)?.packageName == own
    }

    private fun bundleOf(extras: List<Extra>): Bundle =
        Bundle().apply {
            for (e in extras) {
                @Suppress("UNCHECKED_CAST")
                when (e.type) {
                    ExtraType.STRING -> putString(e.name, e.value as String)
                    ExtraType.INT -> putInt(e.name, e.value as Int)
                    ExtraType.LONG -> putLong(e.name, e.value as Long)
                    ExtraType.FLOAT -> putFloat(e.name, e.value as Float)
                    ExtraType.DOUBLE -> putDouble(e.name, e.value as Double)
                    ExtraType.BOOL -> putBoolean(e.name, e.value as Boolean)
                    ExtraType.STRINGS -> putStringArray(e.name, (e.value as List<String>).toTypedArray())
                    ExtraType.STRING_LIST -> putStringArrayList(e.name, ArrayList(e.value as List<String>))
                    ExtraType.BUNDLE -> putBundle(e.name, bundleOf(e.value as List<Extra>))
                }
            }
        }

    /**
     * Every broadcast any app sends with one of [actions], as `{action, data, extras}`, while collected. Exported: that's
     * the point, and what comes is only data. A widget that can't keep up loses the oldest.
     */
    fun broadcasts(actions: List<String>): Flow<Value> =
        callbackFlow {
            val filter = IntentFilter().apply { actions.forEach(::addAction) }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        trySend(Intents.received(intent.action, intent.dataString, extrasOf(intent.extras)))
                    }
                }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
            awaitClose { context.unregisterReceiver(receiver) }
        }.buffer(MAX_QUEUE, BufferOverflow.DROP_OLDEST)

    companion object {
        private const val MAX_QUEUE = 64

        /**
         * [bundle] as plain Kotlin values for [Intents.value]. An extra of a class this app doesn't have (another app's
         * Parcelable) can't be read, and is left out rather than losing the rest.
         */
        fun extrasOf(
            bundle: Bundle?,
            depth: Int = 0,
        ): Map<String, Any?> {
            if (bundle == null || depth > 4) return emptyMap()
            val keys = runCatching { bundle.keySet().toList() }.getOrDefault(emptyList())
            return keys.associateWith { k ->
                @Suppress("DEPRECATION")
                when (val v = runCatching { bundle.get(k) }.getOrNull()) {
                    is Bundle -> extrasOf(v, depth + 1)
                    is Uri -> v.toString()
                    is ComponentName -> v.flattenToString()
                    else -> v
                }
            }
        }
    }
}
