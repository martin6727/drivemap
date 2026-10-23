package com.drivemap.app.speed

import com.drivemap.app.data.Countries
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.location.Fix
import com.drivemap.app.net.LocatedEdge
import com.drivemap.app.net.Search
import com.drivemap.app.net.Valhalla
import com.drivemap.app.route.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RoadInfo(
    /** km/h; null = unknown, -1 = no limit. */
    val limit: Int?,
    val implied: Boolean,
    val country: String?,
    val urban: Boolean,
    val motorway: Boolean,
    val roadClass: String,
    val name: String?,
)

/**
 * Current speed limit + road context. While navigating it comes from the route's road attributes;
 * otherwise the nearest road is looked up with Valhalla's /locate and cached by its geometry so a
 * request is only made when we leave the known road.
 */
class SpeedLimitProvider(private val valhalla: Valhalla, private val scope: CoroutineScope) {
    private val _road = MutableStateFlow<RoadInfo?>(null)
    val road: StateFlow<RoadInfo?> = _road
    private val _country = MutableStateFlow<String?>(null)
    val country: StateFlow<String?> = _country

    private val cache = ArrayDeque<LocatedEdge>()
    private var lastQueryMs = 0L
    private var query: Job? = null
    private var countryJob: Job? = null
    private var countryPos: LatLon? = null
    private var countryMs = 0L

    fun onFix(fix: Fix, route: Route?, alongM: Double?) {
        if (route != null && alongM != null && route.edges.isNotEmpty()) {
            val e = route.edgeAt(alongM)
            if (e != null) {
                if (e.country != null) setCountry(e.country, fix.pos)
                val urban = e.density >= 7 && e.roadClass != "motorway" && e.roadClass != "trunk"
                val limit = e.speedLimit ?: Countries.defaultLimit(e.country, e.roadClass, urban, e.toll)
                _road.value = RoadInfo(
                    limit = limit,
                    implied = e.speedLimit == null,
                    country = e.country,
                    urban = urban || (limit in 1..50),
                    motorway = e.roadClass == "motorway" || (e.roadClass == "trunk" && e.toll),
                    roadClass = e.roadClass,
                    name = null,
                )
                return
            }
        }
        freeDrive(fix)
    }

    private fun freeDrive(fix: Fix) {
        refreshCountry(fix.pos)
        val match = matchCached(fix)
        if (match != null) {
            _road.value = toInfo(match)
            return
        }
        val now = System.currentTimeMillis()
        if (query?.isActive == true || now - lastQueryMs < 3500) return
        if (fix.speedMps < 1.0 && _road.value != null) return
        lastQueryMs = now
        query = scope.launch {
            try {
                val edges = valhalla.locate(fix.pos, if (fix.hasBearing) fix.bearing else null)
                    .filter { it.use != "driveway" && it.use != "parking_aisle" && it.use != "footway" && it.use != "cycleway" }
                for (e in edges) {
                    cache.addFirst(e)
                    while (cache.size > 60) cache.removeLast()
                }
                val m = matchCached(fix) ?: edges.minByOrNull { it.distanceM }
                _road.value = m?.let { toInfo(it) }
            } catch (_: Exception) {
            }
        }
    }

    private fun matchCached(fix: Fix): LocatedEdge? {
        var best: LocatedEdge? = null
        var bestScore = Double.MAX_VALUE
        val maxD = 18.0 + fix.accuracy.coerceAtMost(30.0) * 0.5
        for (e in cache) {
            val pts = e.shape
            for (i in 0 until pts.size - 1) {
                val (_, d) = Geo.projectOnSegment(fix.pos, pts[i], pts[i + 1])
                if (d > maxD) continue
                var score = d
                if (fix.hasBearing && fix.speedMps > 2) {
                    val segB = Geo.bearing(pts[i], pts[i + 1])
                    val diff = Geo.absAngle(fix.bearing, segB)
                    val aligned = minOf(diff, 180 - diff)
                    if (aligned > 40) continue
                    score += aligned * 0.3
                }
                if (score < bestScore) {
                    bestScore = score
                    best = e
                }
            }
        }
        return best
    }

    private fun toInfo(e: LocatedEdge): RoadInfo {
        val c = _country.value
        val cls = e.classification
        val urban = cls == "residential" || e.use == "living_street" ||
            (cls != "motorway" && cls != "trunk" && e.defaultSpeed in 1..45)
        val limit = e.speedLimit ?: Countries.defaultLimit(c, cls, urban, e.toll)
        return RoadInfo(
            limit = limit,
            implied = e.speedLimit == null,
            country = c,
            urban = urban || (limit in 1..50),
            motorway = cls == "motorway" || (cls == "trunk" && e.toll),
            roadClass = cls,
            name = e.names.firstOrNull(),
        )
    }

    private fun setCountry(c: String, pos: LatLon) {
        if (_country.value != c) _country.value = c
        countryPos = pos
        countryMs = System.currentTimeMillis()
    }

    private fun refreshCountry(pos: LatLon) {
        val last = countryPos
        val now = System.currentTimeMillis()
        val stale = last == null || Geo.dist(last, pos) > 8000 || now - countryMs > 15 * 60_000
        if (!stale || countryJob?.isActive == true) return
        if (last != null && now - countryMs < 60_000) return
        countryJob = scope.launch {
            try {
                val c = Search.countryAt(pos)
                if (c != null) setCountry(c, pos) else { countryMs = System.currentTimeMillis(); countryPos = pos }
            } catch (_: Exception) {
                countryMs = System.currentTimeMillis() - 14 * 60_000
            }
        }
    }

    fun clear() {
        _road.value = null
    }
}
