package com.drivemap.app.nav

import com.drivemap.app.data.Countries
import com.drivemap.app.location.Fix
import com.drivemap.app.route.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives along a route at the road's speed limit (plus an optional extra) so navigation, radar
 * alerts and the fine meter can be tried at home.
 */
class Simulator(private val scope: CoroutineScope) {
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    /** Extra km/h above the limit (to test the fine meter). */
    val extraKmh = MutableStateFlow(0)
    /** Time multiplier. */
    val speedUp = MutableStateFlow(1)

    fun start(route: () -> Route?, startAlong: Double = 0.0, emit: (Fix) -> Unit) {
        stop()
        _running.value = true
        job = scope.launch {
            var along = startAlong
            var lastRoute: Route? = null
            var speedKmh = 30.0
            while (isActive) {
                val r = route() ?: break
                if (r !== lastRoute) {
                    // new route after a reroute: continue from its start (we are on it)
                    if (lastRoute != null) along = 0.0
                    lastRoute = r
                }
                val path = r.path
                if (along >= path.length) {
                    emit(fixAt(r, path.length, 0.0))
                    break
                }
                val e = r.edgeAt(along)
                val limit = e?.speedLimit?.takeIf { it > 0 }
                    ?: e?.let { Countries.defaultLimit(it.country, it.roadClass, it.density >= 7, it.toll) }?.takeIf { it > 0 }
                    ?: 90
                val target = (minOf(limit, (e?.speedKmh ?: limit.toDouble()).toInt().coerceAtLeast(limit - 10)) + extraKmh.value).toDouble()
                // smooth acceleration / braking
                speedKmh += (target - speedKmh).coerceIn(-12.0, 6.0)
                // slow down for upcoming sharp maneuvers
                val next = r.geometry.maneuvers.firstOrNull { it.alongM > along + 1 }
                if (next != null && next.type !in setOf(8, 22, 4, 5, 6) && next.alongM - along < 60) speedKmh = minOf(speedKmh, 30.0)
                val tick = 0.5
                along += speedKmh / 3.6 * tick * speedUp.value
                emit(fixAt(r, along.coerceAtMost(path.length), speedKmh / 3.6))
                delay((tick * 1000).toLong())
            }
            _running.value = false
        }
    }

    private fun fixAt(r: Route, along: Double, speedMps: Double): Fix = Fix(
        pos = r.path.pointAt(along),
        speedMps = speedMps,
        bearing = r.path.bearingAt(along),
        accuracy = 5.0,
        timeMs = System.currentTimeMillis(),
        hasBearing = true,
        simulated = true,
    )

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }
}
