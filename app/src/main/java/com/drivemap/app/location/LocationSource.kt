package com.drivemap.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Fix(
    val pos: LatLon,
    val speedMps: Double,
    val bearing: Double,
    val accuracy: Double,
    val timeMs: Long,
    val hasBearing: Boolean,
    val simulated: Boolean = false,
) {
    val speedKmh get() = speedMps * 3.6
    /** Speed as shown on screen: true GPS speed rounded to the nearest km/h, no upper cap. */
    val shownKmh: Int get() = Math.round(speedMps * 3.6).toInt()

    fun toLocation(): Location = Location("drivemap").also {
        it.latitude = pos.lat
        it.longitude = pos.lon
        it.speed = speedMps.toFloat()
        it.bearing = bearing.toFloat()
        it.accuracy = accuracy.toFloat()
        it.time = timeMs
        it.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
    }
}

/** GPS fixes (1 Hz) plus an injection path for the drive simulator. */
class LocationSource(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    private val _hasPermission = MutableStateFlow(checkPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission

    @Volatile var simulating = false
    private var listening = false
    private var lastGpsMs = 0L
    private var lastBearing = 0.0
    private var lastReal: Fix? = null
    private var lastPrecise: Fix? = null
    /** Listeners are called on the main thread for every accepted fix. */
    var onFix: ((Fix) -> Unit)? = null

    fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastGpsMs = System.currentTimeMillis()
            accept(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { restart() }
        override fun onProviderDisabled(provider: String) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastAnyMs = System.currentTimeMillis()
            // Only use these fixes while GPS is silent (tunnels, garages, weak signal).
            if (System.currentTimeMillis() - lastGpsMs > 10_000) accept(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { restart() }
        override fun onProviderDisabled(provider: String) {}
    }

    private var lastAnyMs = 0L
    private var lastRestartMs = 0L
    private val handler = android.os.Handler(Looper.getMainLooper())

    /**
     * Watchdog: when GPS has been silent for 20 s (signal lost, chip stuck, location switched
     * off and on), drop and re-request the updates so it reconnects without restarting the app.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!listening) return
            val now = System.currentTimeMillis()
            if (!simulating && now - lastGpsMs > 20_000 && now - lastRestartMs > 20_000) restart()
            handler.postDelayed(this, 5_000)
        }
    }

    /** Location switched on/off in quick settings. */
    private val providersReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) { restart() }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        _hasPermission.value = checkPermission()
        if (listening || !_hasPermission.value || lm == null) return
        listening = true
        lastGpsMs = System.currentTimeMillis()
        register()
        try {
            ContextCompat.registerReceiver(
                context, providersReceiver, android.content.IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 5_000)
        // Seed with the last known position so the map can centre immediately.
        if (_fix.value == null) {
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }
            if (last != null) accept(last, seed = true)
        }
    }

    /** Ask for GPS plus a backup provider. Registered even while location is off: it starts by itself once switched on. */
    @SuppressLint("MissingPermission")
    private fun register() {
        val m = lm ?: return
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
        } catch (_: Exception) {
        }
        // Backup while GPS is silent: the fused provider (GPS + Wi-Fi + cell) where there is one, else network.
        val backup = if (android.os.Build.VERSION.SDK_INT >= 31 && m.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else LocationManager.NETWORK_PROVIDER
        try {
            m.requestLocationUpdates(backup, 2000L, 0f, netListener, Looper.getMainLooper())
        } catch (_: Exception) {
        }
    }

    /** Drop and re-request location updates. */
    private fun restart() {
        if (!listening || lm == null) return
        _hasPermission.value = checkPermission()
        if (!_hasPermission.value) return
        lastRestartMs = System.currentTimeMillis()
        try { lm.removeUpdates(gpsListener) } catch (_: Exception) {}
        try { lm.removeUpdates(netListener) } catch (_: Exception) {}
        register()
    }

    fun stop() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(watchdog)
        try { context.unregisterReceiver(providersReceiver) } catch (_: Exception) {}
        try { lm?.removeUpdates(gpsListener) } catch (_: Exception) {}
        try { lm?.removeUpdates(netListener) } catch (_: Exception) {}
    }

    private fun accept(l: Location, seed: Boolean = false) {
        if (simulating) return
        val pos = LatLon(l.latitude, l.longitude)
        val prev = lastReal
        val now = System.currentTimeMillis()
        // The GPS chip's own (Doppler) speed is the accurate one: use it as-is, never capped.
        var speed = if (l.hasSpeed()) l.speed.toDouble() else 0.0
        val precise = l.provider == LocationManager.GPS_PROVIDER || (l.hasAccuracy() && l.accuracy <= 25f)
        val pp = lastPrecise
        if (precise && pp != null && !seed) {
            // Some providers (and the emulator) report no speed or 0 while moving: derive it from
            // GPS positions only. Rough network positions jump around and would show fake speeds.
            val dt = (now - pp.timeMs) / 1000.0
            if (dt in 0.5..10.0) {
                val moved = Geo.dist(pp.pos, pos) / dt
                if (!l.hasSpeed() || (speed < 0.5 && moved > 2.5)) speed = moved
            }
        }
        var bearing = lastBearing
        var hasBearing = false
        if (l.hasBearing() && speed > 1.5) {
            bearing = l.bearing.toDouble()
            hasBearing = true
        } else if (prev != null && Geo.dist(prev.pos, pos) > 8 && speed > 1.5) {
            bearing = Geo.bearing(prev.pos, pos)
            hasBearing = true
        }
        lastBearing = bearing
        val f = Fix(pos, speed, bearing, if (l.hasAccuracy()) l.accuracy.toDouble() else 30.0, if (seed) l.time else now, hasBearing)
        lastReal = f
        if (precise) lastPrecise = f
        _fix.value = f
        if (!seed) onFix?.invoke(f)
    }

    /** Used by the simulator. */
    fun inject(f: Fix) {
        _fix.value = f
        onFix?.invoke(f)
    }

    fun lastRealFix() = lastReal
}
