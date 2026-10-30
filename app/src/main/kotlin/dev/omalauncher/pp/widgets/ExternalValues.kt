package dev.omalauncher.pp.widgets

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.omalauncher.pp.LauncherData
import dev.omalauncher.pp.upgrade.writeAtomically
import dev.omalauncher.pp.widgets.lang.Compiler
import dev.omalauncher.pp.widgets.lang.Global
import dev.omalauncher.pp.widgets.lang.Intents
import dev.omalauncher.pp.widgets.lang.Json
import dev.omalauncher.pp.widgets.lang.MAX_TEXT
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Value
import dev.omalauncher.pp.widgets.runtime.AppIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Values other apps put into widgets. Tasker, Home Assistant or anything else on the phone sends the broadcast
 * [Intents.SET] to this app with the extras `widget` (its file, like `battery.owl`), `key` and `value`, and the
 * widget reads it as `ext.key` from a `source ext = external`; no value removes the key.
 *
 * Any app may send it, so two things guard it: [allowed], off until the user turns it on in settings, keeps
 * [SetValueReceiver] a disabled component, so until then no app can even start the launcher with one; and a widget
 * without an `external` source takes nothing. Values are kept per file beside its `persist` ones in widget-state/,
 * so one set while the launcher wasn't running is there as it starts, and goes with the file.
 */
class ExternalValues private constructor(
    private val context: Context,
) {
    private val prefs = LauncherData.prefs(context)
    private val stateDir = File(context.filesDir, "widget-state")
    private val widgetsDir = LauncherData.widgetsDir(context)

    /** Each file's values, once read. */
    private val values = HashMap<String, MutableStateFlow<Map<String, Value>>>()

    /** Whether each file has an `external` source, as of its last change: its stamp and the answer. */
    private val accepts = HashMap<String, Pair<Long, Boolean>>()

    /** Whether other apps may set values; settings › values from other apps. Snapshot state, for the settings row. */
    var allowed by mutableStateOf(prefs.getBoolean(KEY, false))
        private set

    init {
        // A backup's import brings the setting back and starts the launcher again: the receiver follows it here.
        sync()
    }

    fun allow(on: Boolean) {
        prefs.edit { putBoolean(KEY, on) }
        allowed = on
        sync()
    }

    private fun sync() {
        val pm = context.packageManager
        val component = ComponentName(context, SetValueReceiver::class.java)
        val want = if (allowed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component) != want) pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
    }

    /** [file]'s values as a map, now and after every change; read from disk off the main thread the first time. */
    fun updates(file: String): Flow<Value> = flow { emitAll(state(file)) }.map<Map<String, Value>, Value> { Obj(it) }.flowOn(Dispatchers.IO)

    private fun state(file: String) = synchronized(values) { values.getOrPut(file) { MutableStateFlow(load(file)) } }

    private fun stored(file: String) = File(stateDir, "$file$SUFFIX")

    private fun load(file: String): Map<String, Value> = runCatching { (Json.parse(stored(file).readText()) as? Obj)?.fields }.getOrNull().orEmpty()

    /** Sets [key] of [widget]'s values to [value] (null removes it), as [Intents.SET] asks; why it was turned down, or null. IO thread. */
    fun set(
        widget: String?,
        key: String?,
        value: Any?,
    ): String? {
        val file = fileName(widget) ?: return "widget is a widget's file name, like battery.owl, not ${widget?.let { "\"$it\"" } ?: "missing"}"
        if (!accepting(file)) return "$file has no source x = external, so it takes no values from other apps"
        val flow = state(file)
        synchronized(flow) {
            val next = Intents.setOutside(flow.value, key, value).getOrElse { return it.message }
            if (next == flow.value) return null
            flow.value = next
            Json.write(Obj(next), MAX_TEXT)?.let { runCatching { writeAtomically(stored(file), it.toByteArray()) } }
        }
        return null
    }

    /** A file in the widgets folder, by its name with or without `.owl`; null for anything that could point elsewhere. */
    private fun fileName(widget: String?): String? {
        val name = widget?.trim()?.let { if (it.endsWith(".owl")) it else "$it.owl" } ?: return null
        return name.takeIf { it.length <= 128 && '/' !in it && '\\' !in it && !it.startsWith(".") }
    }

    /** Whether [file] has a `source x = external`, compiled anew only when it changed. */
    private fun accepting(file: String): Boolean {
        val f = File(widgetsDir, file)
        val stamp = f.lastModified().takeIf { f.isFile } ?: return false
        synchronized(accepts) { accepts[file]?.takeIf { it.first == stamp }?.let { return it.second } }
        val yes = runCatching { Compiler.compile(f.readText()).globals.any { (it as? Global.Source)?.kind == "external" } }.getOrDefault(false)
        synchronized(accepts) { accepts[file] = stamp to yes }
        return yes
    }

    /** [file] was deleted: its values go with it. */
    fun forget(file: String) {
        synchronized(values) { values.remove(file) }
        stored(file).delete()
    }

    companion object {
        private const val TAG = "ExternalValues"
        private const val KEY = "widget_values_from_apps"
        private const val SUFFIX = ".external.json"

        @Volatile private var instance: ExternalValues? = null

        fun get(context: Context): ExternalValues = instance ?: synchronized(this) { instance ?: ExternalValues(context.applicationContext).also { instance = it } }

        /** Sets values off the main thread: [SetValueReceiver] may start a process that has nothing else running. */
        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal fun log(message: String) = Log.w(TAG, message)
    }
}

/** [Intents.SET] from other apps; enabled only while [ExternalValues.allowed]. */
class SetValueReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intents.SET) return
        val store = ExternalValues.get(context)
        // Turned off as this one was on its way.
        if (!store.allowed) return
        val extras = AppIntents.extrasOf(intent.extras)
        val pending = goAsync()
        ExternalValues.scope.launch {
            try {
                store.set(extras["widget"]?.toString(), extras["key"]?.toString(), extras["value"])?.let { ExternalValues.log("${Intents.SET}: $it") }
            } finally {
                pending.finish()
            }
        }
    }
}
