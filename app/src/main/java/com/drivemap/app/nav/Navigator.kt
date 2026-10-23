package com.drivemap.app.nav

import com.drivemap.app.data.SettingsStore
import com.drivemap.app.data.VoiceLevel
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.location.Fix
import com.drivemap.app.route.Maneuver
import com.drivemap.app.route.Route
import com.drivemap.app.route.RoutePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class NavState(
    val route: Route,
    val request: RoutePlanner.Request,
    val alongM: Double,
    val snapped: LatLon,
    val crossTrackM: Double,
    val maneuverIdx: Int,
    val distToManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val etaMs: Long,
    val rerouting: Boolean,
    val arrived: Boolean,
    val nextWaypoint: Int,
    val routeVersion: Int,
    /** True once we have clearly left the route (shown with the real GPS position until rerouted). */
    val offRoute: Boolean = false,
) {
    val next: Maneuver? get() = route.geometry.maneuvers.getOrNull(maneuverIdx)
    val then: Maneuver? get() = route.geometry.maneuvers.getOrNull(maneuverIdx + 1)
    /** Fraction of the route already driven (for the map's line gradient). */
    val progress: Double get() = if (route.path.length > 0) (alongM / route.path.length).coerceIn(0.0, 1.0) else 0.0
}

/**
 * Turn-by-turn engine: snaps fixes to the route, tracks the next maneuver, speaks instructions,
 * detects leaving the route and re-plans.
 */
class Navigator(
    private val scope: CoroutineScope,
    private val planner: RoutePlanner,
    private val voice: Voice,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state

    /** True while Android Auto is connected: there "Full" narration is shortened to "Normal". */
    var carConnected: () -> Boolean = { false }

    private fun voiceLevel(): VoiceLevel = settings.value.voiceLevel.let {
        if (it == VoiceLevel.FULL && carConnected()) VoiceLevel.NORMAL else it
    }

    private var route: Route? = null
    private var request: RoutePlanner.Request? = null
    private var seg = 0
    private var mi = 0
    private var nextWp = 0
    private var offCount = 0
    private var offRoute = false
    private var lastReroute = 0L
    private var rerouteJob: Job? = null
    private var rerouting = false
    private var arrivedSpoken = false
    private var version = 0

    // trip statistics for the arrival summary (kept across reroutes)
    private var tripStartMs = 0L
    private var tripDistM = 0.0
    private var tripMaxMps = 0.0
    private var tripLastPos: LatLon? = null
    private var tripTimeS = 0.0
    private var tripLastMs = 0L
    /** How fast trip time runs (the simulator's speed-up while simulating). */
    var timeScale: () -> Double = { 1.0 }

    /** Stats of the trip so far (distance actually driven, times, speeds, estimated fuel). */
    fun tripSummary(radars: Int): TripSummary {
        val durS = tripTimeS.toLong().coerceAtLeast(1)
        val km = tripDistM / 1000
        val avg = km / (durS / 3600.0)
        val s = settings.value
        val fuel = com.drivemap.app.route.Costs.per100(avg.coerceIn(15.0, 130.0), s) * km / 100
        return TripSummary(
            destination = request?.stops?.lastOrNull()?.name ?: "",
            distanceKm = km, durationS = durS, avgKmh = avg, maxKmh = tripMaxMps * 3.6,
            fuel = fuel, fuelUnit = s.fuelType.unit, fuelCostEur = fuel * com.drivemap.app.fuel.FuelPrices.price(null, s), radars = radars,
        )
    }
    private val spoken = HashMap<Int, Int>()

    val active get() = route != null

    fun start(r: Route, req: RoutePlanner.Request) {
        rerouteJob?.cancel()
        route = r
        request = req
        tripStartMs = System.currentTimeMillis()
        tripDistM = 0.0
        tripMaxMps = 0.0
        tripLastPos = null
        tripTimeS = 0.0
        tripLastMs = 0L
        seg = 0
        mi = 1
        nextWp = 0
        offCount = 0
        offRoute = false
        rerouting = false
        arrivedSpoken = false
        spoken.clear()
        version++
        val first = r.geometry.maneuvers.firstOrNull()
        if (settings.value.voice && voiceLevel() == VoiceLevel.FULL && first != null) voice.speak(first.verbalPre ?: first.instruction, flush = true)
        spoken[0] = 2
        publish(r.geometry.shape.firstOrNull() ?: req.origin, 0.0, 0.0)
        loadLanes(r, req)
    }

    /** Lane guidance arrives a moment after the route (one extra request). */
    private fun loadLanes(r: Route, req: RoutePlanner.Request) {
        if (r.lanes.isNotEmpty()) return
        scope.launch {
            try {
                r.lanes = planner.laneGuidance(r, req)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        rerouteJob?.cancel()
        route = null
        request = null
        _state.value = null
    }

    /** Swap in a new route (reroute, new convoy stop) keeping navigation running. */
    fun replace(r: Route, req: RoutePlanner.Request) {
        route = r
        request = req
        seg = 0
        mi = 1
        nextWp = 0
        offCount = 0
        offRoute = false
        spoken.clear()
        spoken[0] = 2
        version++
        val f = lastFix
        if (f != null) onFix(f) else publish(r.geometry.shape.firstOrNull() ?: req.origin, 0.0, 0.0)
        loadLanes(r, req)
    }

    private var lastFix: Fix? = null

    fun onFix(fix: Fix) {
        lastFix = fix
        val r = route ?: return
        tripLastPos?.let { p -> Geo.dist(p, fix.pos).takeIf { it < 500 }?.let { tripDistM += it } }
        tripLastPos = fix.pos
        val nowMs = System.currentTimeMillis()
        if (tripLastMs > 0) tripTimeS += ((nowMs - tripLastMs) / 1000.0).coerceAtMost(60.0) * timeScale()
        tripLastMs = nowMs
        if (fix.accuracy <= 30) tripMaxMps = maxOf(tripMaxMps, fix.speedMps)
        val path = r.path
        val n = path.points.size
        if (n < 2) return
        var hit = bestHit(r, fix, (seg - 5).coerceAtLeast(0), (seg + 120).coerceAtMost(n - 1))
        if (hit == null || hit.second > 60) {
            val global = bestHit(r, fix, 0, n - 1)
            if (global != null && (hit == null || global.second < hit.second)) hit = global
        }
        if (hit == null) return
        val (segIdx, cross, along) = hit
        seg = segIdx

        // Leaving the route: far from it, driving against it, or heading clearly away from it
        // (turned into another road). The heading test catches a wrong turn within ~2 s.
        val limit = maxOf(35.0, fix.accuracy * 1.5)
        val segBearing = Geo.bearing(path.points[segIdx], path.points[segIdx + 1])
        val wrongWay = fix.hasBearing && fix.speedMps > 5 && Geo.absAngle(fix.bearing, segBearing) > 135
        val headingAway = fix.hasBearing && fix.speedMps > 3 && Geo.absAngle(fix.bearing, segBearing) > 40 &&
            cross > maxOf(12.0, fix.accuracy)
        offCount = if (cross > limit || wrongWay || headingAway) offCount + 1 else 0
        if (offCount >= 2 || cross > limit) offRoute = true
        else if (cross < 10 && !headingAway) offRoute = false
        val now = System.currentTimeMillis()
        if (offCount >= 2 && !rerouting && now - lastReroute > 5000 && fix.speedMps > 1.0) {
            reroute(fix)
        }
        publish(path.pointAt(along), along, cross, fix)
    }

    /** (segment, crossTrack, along) with a penalty for segments pointing against our heading. */
    private fun bestHit(r: Route, fix: Fix, from: Int, to: Int): Triple<Int, Double, Double>? {
        val pts = r.path.points
        var best: Triple<Int, Double, Double>? = null
        var bestScore = Double.MAX_VALUE
        for (i in from until to) {
            val (t, d) = Geo.projectOnSegment(fix.pos, pts[i], pts[i + 1])
            var score = d
            if (fix.hasBearing && fix.speedMps > 3 && r.path.cum[i + 1] - r.path.cum[i] > 3) {
                val diff = Geo.absAngle(fix.bearing, Geo.bearing(pts[i], pts[i + 1]))
                if (diff > 100) score += 40
            }
            if (score < bestScore) {
                bestScore = score
                val along = r.path.cum[i] + (r.path.cum[i + 1] - r.path.cum[i]) * t
                best = Triple(i, d, along)
            }
        }
        return best
    }

    private fun publish(snapped: LatLon, along: Double, cross: Double, fix: Fix? = null) {
        val r = route ?: return
        val req = request ?: return
        val mans = r.geometry.maneuvers
        while (mi < mans.size && mans[mi].alongM <= along + 3) mi++
        if (mi >= mans.size) mi = mans.size - 1
        val next = mans.getOrNull(mi)
        val dist = ((next?.alongM ?: r.path.length) - along).coerceAtLeast(0.0)
        val remainingM = (r.path.length - along).coerceAtLeast(0.0)
        var remainingS = 0.0
        for (k in mi until mans.size) remainingS += mans[k].timeS
        val cur = mans.getOrNull(mi - 1)
        if (cur != null && next != null) {
            val span = (next.alongM - cur.alongM).coerceAtLeast(1.0)
            remainingS += cur.timeS * (dist / span).coerceIn(0.0, 1.0)
        }
        while (nextWp < r.waypointAlong.size && along > r.waypointAlong[nextWp] - 40) nextWp++
        val arrived = remainingM < 35 || (next != null && next.isArrival && mi == mans.size - 1 && dist < 25)
        if (fix != null && settings.value.voice) speakFor(mans, dist, fix.speedMps, along)
        if (arrived && !arrivedSpoken) {
            arrivedSpoken = true
        }
        _state.value = NavState(
            route = r,
            request = req,
            alongM = along,
            snapped = snapped,
            crossTrackM = cross,
            offRoute = offRoute,
            maneuverIdx = mi,
            distToManeuverM = dist,
            remainingM = remainingM,
            remainingS = remainingS,
            etaMs = System.currentTimeMillis() + (remainingS * 1000).toLong(),
            rerouting = rerouting,
            arrived = arrived,
            nextWaypoint = nextWp,
            routeVersion = version,
        )
    }

    private fun speakFor(mans: List<Maneuver>, dist: Double, speedMps: Double, along: Double) {
        val level = voiceLevel()
        if (level == VoiceLevel.ALERTS) return // radar warnings are spoken by the radar alerter
        val full = level == VoiceLevel.FULL
        val lang = settings.value.voiceLang
        val next = mans.getOrNull(mi) ?: return
        val v = maxOf(speedMps, 8.0)
        val near = (v * 7).coerceIn(60.0, 350.0)
        val far = (v * 28).coerceIn(400.0, 2000.0)
        val stage = spoken[mi] ?: 0
        val legLen = next.alongM - (mans.getOrNull(mi - 1)?.alongM ?: 0.0)
        // "Continue for 12 km" right after a maneuver when the next one is far away
        val prev = mans.getOrNull(mi - 1)
        if (full && prev != null && (spoken[mi - 1] ?: 0) < 3 && along - prev.alongM in 30.0..400.0 && dist > 3000 && prev.verbalPost != null) {
            spoken[mi - 1] = 3
            voice.speak(prev.verbalPost)
            return
        }
        // Full: Valhalla's narration with street names; Normal: short prompt ("Turn right")
        val short = if (full) null else Phrases.turn(lang, next.type, next.roundaboutExit)
        if (stage < 1 && dist <= far && dist > near * 1.8 && legLen > far * 0.7) {
            spoken[mi] = 1
            val text = if (full) next.verbalAlert ?: next.verbalPre ?: next.instruction else short ?: return
            voice.speak(Phrases.inDistance(lang, dist, text))
        } else if (stage < 2 && dist <= near) {
            spoken[mi] = 2
            val text = if (full) next.verbalPre ?: next.instruction else short ?: return
            voice.speak(text, flush = true)
        }
    }

    private fun reroute(fix: Fix) {
        val req = request ?: return
        val r = route ?: return
        lastReroute = System.currentTimeMillis()
        rerouting = true
        if (settings.value.voice && voiceLevel() != VoiceLevel.ALERTS) voice.speak(Phrases.get(settings.value.voiceLang, "reroute"), flush = true)
        val remaining = req.stops.drop(nextWp.coerceAtMost(req.stops.size - 1))
        val req2 = req.copy(
            origin = fix.pos,
            originHeading = if (fix.hasBearing) fix.bearing else null,
            stops = remaining,
            departureMs = System.currentTimeMillis(),
        )
        rerouteJob = scope.launch {
            try {
                var shown: Route? = null
                val nr = planner.reroute(req2, r) { quick ->
                    shown = quick
                    rerouting = false
                    replace(quick, req2)
                }
                rerouting = false
                if (nr !== shown) replace(nr, req2)
            } catch (_: Exception) {
                rerouting = false
            }
        }
    }

    /** Re-plan with a new list of stops (e.g. a convoy member added one). */
    fun updateStops(stops: List<com.drivemap.app.route.Waypoint>) {
        val req = request ?: return
        val r = route ?: return
        val f = lastFix ?: return
        rerouting = true
        val req2 = req.copy(origin = f.pos, originHeading = if (f.hasBearing) f.bearing else null, stops = stops, departureMs = System.currentTimeMillis())
        rerouteJob?.cancel()
        rerouteJob = scope.launch {
            try {
                val nr = planner.reroute(req2, r)
                rerouting = false
                replace(nr, req2)
            } catch (_: Exception) {
                rerouting = false
            }
        }
    }

    fun currentRequest() = request
    fun remainingStops(): List<com.drivemap.app.route.Waypoint> {
        val req = request ?: return emptyList()
        return req.stops.drop(nextWp.coerceAtMost(req.stops.size - 1))
    }
}

/** Shown for a few seconds after arriving. */
data class TripSummary(
    val destination: String,
    val distanceKm: Double,
    val durationS: Long,
    val avgKmh: Double,
    val maxKmh: Double,
    val fuel: Double,
    val fuelUnit: String,
    val fuelCostEur: Double,
    val radars: Int,
)
