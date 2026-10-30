package dev.omalauncher.pp.widgets.runtime

import android.content.Context
import android.content.IntentFilter
import android.location.Address
import android.location.Geocoder
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.omalauncher.pp.registerNotExported
import dev.omalauncher.pp.widgets.lang.Null
import dev.omalauncher.pp.widgets.lang.Num
import dev.omalauncher.pp.widgets.lang.Obj
import dev.omalauncher.pp.widgets.lang.Str
import dev.omalauncher.pp.widgets.lang.Value
import dev.omalauncher.pp.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import android.location.Location as Fix

/** A fix younger than this is good enough: weather and a place name don't change faster. Asked for again after it. */
private const val FRESH_MS = 30 * 60_000L

/** Other apps' fixes, which cost nothing, at most this often and this far apart. */
private const val PASSIVE_MS = 10 * 60_000L
private const val PASSIVE_M = 500f

private const val GEOCODE_TIMEOUT_MS = 10_000L

/**
 * `source l = android.location`: where the phone is, coarsely (Android blurs it to a few km), once the user allows it with
 * `permission("location")`, and the place's name where the phone has a geocoder. Battery first: it takes the fixes other
 * apps get, and asks for one of its own only when the last is over half an hour old, from the network rather than GPS where
 * it can. Only while a widget shows it; never in the background.
 */
class Location(
    private val context: Context,
) {
    private val manager = context.getSystemService(LocationManager::class.java)

    /** The latest fix, from any provider. */
    @Volatile
    private var fix: Fix? = null

    /** Addresses by a ~1 km grid, so a phone that sits still asks the geocoder once; the last few places. */
    private val places =
        object : LinkedHashMap<String, Address?>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Address?>) = size > 8
        }

    private val granted get() = WidgetPermissions.granted(context, "location")

    private val on get() = runCatching { if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else true }.getOrDefault(false)

    private fun cell(f: Fix) = "%.2f,%.2f".format(Locale.ROOT, f.latitude, f.longitude)

    fun value(): Value {
        if (!granted) return Obj(mapOf("available" to bool(false), "on" to bool(on)))
        val f = fix ?: lastKnown()?.also { fix = it }
        val a = f?.let { synchronized(places) { places[cell(it)] } }
        fun text(s: String?) = s?.takeIf { it.isNotBlank() }?.let(::Str) ?: Null
        fun round(v: Double) = Num(Math.round(v * 1000) / 1000.0)
        return Obj(
            mapOf(
                "available" to bool(true),
                "on" to bool(on),
                "lat" to (f?.let { round(it.latitude) } ?: Null),
                "lon" to (f?.let { round(it.longitude) } ?: Null),
                "accuracy" to (f?.takeIf { it.hasAccuracy() }?.let { Num(it.accuracy.roundToInt().toDouble()) } ?: Null),
                "time" to (f?.let { Num((it.time / 1000).toDouble()) } ?: Null),
                "place" to text(a?.locality ?: a?.subAdminArea ?: a?.adminArea),
                "region" to text(a?.adminArea),
                "country" to text(a?.countryName),
                "countryCode" to text(a?.countryCode),
            ),
        )
    }

    /** The newest fix Android kept from any provider this app may read; GPS's needs the fine permission before Android 12. */
    private fun lastKnown(): Fix? =
        runCatching { manager.allProviders }
            .getOrDefault(emptyList())
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }

    private fun age(f: Fix?) = f?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 } ?: Long.MAX_VALUE

    private fun better(f: Fix) {
        val current = fix
        if (current == null || f.elapsedRealtimeNanos > current.elapsedRealtimeNanos) fix = f
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<Value> =
        WidgetPermissions.changes
            .map { granted }
            .distinctUntilChanged()
            .flatMapLatest { if (it) fixes() else flowOf(Unit) }
            .conflate()
            .map {
                withContext(Dispatchers.IO) {
                    fix?.let { name(it) }
                    value()
                }
            }.distinctUntilChanged()

    /** Emits as fixes come and as location is switched on or off. */
    private fun fixes(): Flow<Unit> =
        callbackFlow {
            val listener =
                listener { f ->
                    better(f)
                    trySend(Unit)
                }
            val main = Looper.getMainLooper()
            runCatching { manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, PASSIVE_MS, PASSIVE_M, listener, main) }
                .onFailure { Log.w("widgets", "passive location: ${it.message}") }
            var asking: CancellationSignal? = null

            fun ask() {
                if (asking != null || !on) return
                val provider = provider() ?: return
                val signal = CancellationSignal()
                asking = signal
                val got = { f: Fix? ->
                    asking = null
                    f?.let(::better)
                    trySend(Unit)
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= 30) {
                        manager.getCurrentLocation(provider, signal, context.mainExecutor) { got(it) }
                    } else {
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(provider, listener { f -> got(f) }, main)
                    }
                }.onFailure {
                    asking = null
                    Log.w("widgets", "location from $provider: ${it.message}")
                }
            }
            // Switched on: a fix at once, rather than in up to half an hour.
            val switched =
                context.registerNotExported(IntentFilter(LocationManager.MODE_CHANGED_ACTION)) {
                    trySend(Unit)
                    if (age(fix) >= FRESH_MS) ask()
                }
            launch {
                lastKnown()?.let(::better)
                trySend(Unit)
                while (true) {
                    if (age(fix) >= FRESH_MS) ask()
                    delay(FRESH_MS)
                }
            }
            awaitClose {
                asking?.cancel()
                context.unregisterReceiver(switched)
                manager.removeUpdates(listener)
            }
        }
            // The listeners and the receiver call back on the main thread, which keeps [asking] to one thread.
            .flowOn(Dispatchers.Main)

    // Every method written out: before Android 11 the other three have no default, and a lambda would leave them missing.
    private fun listener(got: (Fix) -> Unit) =
        object : LocationListener {
            override fun onLocationChanged(location: Fix) = got(location)

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }

    /** The cheapest provider that's on: fused or network, which need no GPS; GPS where the coarse permission may use it. */
    private fun provider(): String? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.GPS_PROVIDER)
        }
        return candidates.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** Looks the place up once per grid cell; offline or without a geocoder it stays unnamed and is asked again next fix. */
    private suspend fun name(f: Fix) {
        val key = cell(f)
        if (synchronized(places) { places.containsKey(key) } || !Geocoder.isPresent()) return
        val geocoder = Geocoder(context, Locale.getDefault())
        val found: List<Address>? =
            withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= 33) {
                    suspendCancellableCoroutine { k ->
                        geocoder.getFromLocation(
                            f.latitude,
                            f.longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) = k.resume(addresses)

                                override fun onError(errorMessage: String?) = k.resume(null)
                            },
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(f.latitude, f.longitude, 1) }.getOrNull()
                }
            }
        // Null is a failure, asked again; an empty answer (the sea) is kept, as a place without a name.
        if (found != null) synchronized(places) { places[key] = found.firstOrNull() }
    }
}
