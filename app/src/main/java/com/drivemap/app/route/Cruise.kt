package com.drivemap.app.route

import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.RouteLocation
import com.drivemap.app.net.Valhalla
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs

/** A cruise someone in the convoy invited you to (or that you're about to send). */
data class CruiseInvite(
    val id: String,
    val fromId: String,
    val fromName: String,
    /** Member it is for; null = the whole convoy. */
    val toId: String?,
    val minutes: Int,
    val scenic: Boolean,
    /** Meeting point = start and end of the loop. */
    val start: LatLon,
    /** Points the loop passes through, in order. */
    val loop: List<LatLon>,
    val ts: Long,
    /** Set for "drive with me": just this destination, no loop. */
    val destName: String? = null,
    /** Direction of travel at each [loop] point, so everyone drives the loop the same way. */
    val headings: List<Double> = emptyList(),
    /** A→B: where it ends (null = back at [start], a loop). */
    val end: LatLon? = null,
) {
    /** A→B to a named destination ("drive with me" / challenge to your destination). */
    val isJoin get() = destName != null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("f", fromId).put("fn", fromName).put("to", toId ?: "")
        .put("min", minutes).put("sc", scenic).put("la", start.lat).put("lo", start.lon).put("ts", ts).put("dn", destName ?: "")
        .apply { if (end != null) put("ea", end.lat).put("eo", end.lon) }
        .put("loop", JSONArray(loop.mapIndexed { i, p -> JSONArray(listOfNotNull(p.lat, p.lon, headings.getOrNull(i))) }))

    /** Stops for the route planner: meeting point (unless we're there), the loop, back to the start. */
    fun stops(meetName: String, endName: String, includeMeeting: Boolean): List<Waypoint> =
        (if (includeMeeting) listOf(Waypoint(meetName, start)) else emptyList()) +
            loop.mapIndexed { i, p -> Waypoint("", p, through = true, heading = headings.getOrNull(i)) } + Waypoint(endName, end ?: start)

    companion object {
        fun fromJson(o: JSONObject): CruiseInvite? = try {
            val arr = o.getJSONArray("loop")
            CruiseInvite(
                id = o.getString("id"), fromId = o.getString("f"), fromName = o.optString("fn"),
                toId = o.optString("to").ifBlank { null }, minutes = o.optInt("min", 60), scenic = o.optBoolean("sc", true),
                start = LatLon(o.getDouble("la"), o.getDouble("lo")),
                loop = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } },
                ts = o.optLong("ts"), destName = o.optString("dn").ifBlank { null },
                headings = (0 until arr.length()).mapNotNull { i -> arr.getJSONArray(i).optDouble(2).takeIf { !it.isNaN() } }
                    .takeIf { it.size == arr.length() } ?: emptyList(),
                end = if (o.has("ea")) LatLon(o.getDouble("ea"), o.getDouble("eo")) else null,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Pins [inv]'s loop to the exact roads of [route] (the one the sender sees): points spread along
 * it with the direction of travel, so every phone in the challenge gets the same route. The
 * routing server takes at most 10 locations: you, a meeting point, these and the finish.
 */
fun pinToRoute(inv: CruiseInvite, route: Route, fromAlong: Double = 0.0, count: Int = 7): CruiseInvite {
    val path = route.path
    val len = path.length - fromAlong
    if (len < 2000) return inv
    // skip the first / last stretch, which depends on where each driver starts
    val from = fromAlong + len * 0.06
    val to = fromAlong + len * 0.94
    val along = (0 until count).map { from + (to - from) * it / (count - 1) }
    return inv.copy(
        loop = along.map { path.pointAt(it) },
        headings = along.map { path.bearingAt(it) },
    )
}

/**
 * Plans a round trip ("cruise") of about [minutes] from [start]: three shaping points on a circle,
 * a few rotations tried, and the one closest to the wanted time with the most bends wins
 * (scenic) or simply the one closest to the time (relaxed).
 */
object CruisePlanner {
    suspend fun plan(valhalla: Valhalla, start: LatLon, minutes: Int, scenic: Boolean, lang: String): List<LatLon> = coroutineScope {
        // country-road average ~55 km/h; roads wind ~1.35x the circle's length
        val km = minutes / 60.0 * (if (scenic) 52.0 else 60.0)
        val radiusM = km * 1000 / (2 * PI * 1.35)
        val opts = JSONObject().put("use_highways", if (scenic) 0.0 else 0.3).put("use_tolls", 0.0)
        val base = (Math.random() * 360)
        val candidates = (0 until 6).map { k ->
            val rot = base + k * 20.0
            listOf(0.0, 120.0, 240.0).map { a -> Geo.destination(start, rot + a, radiusM) }
        }
        val scored = candidates.map { pts ->
            async {
                try {
                    val locs = listOf(RouteLocation(start)) + pts.map { RouteLocation(it, through = true) } + RouteLocation(start)
                    val g = valhalla.route(locs, "auto", opts, lang).first()
                    val r = Route(g, "cruise", emptyList())
                    val timeErr = abs(r.timeS / 60.0 - minutes) / minutes
                    val (curv, _) = Costs.curvature(r)
                    // closer to the wanted time is better; scenic also wants bends; driving the
                    // same road twice (out-and-back spurs to a shaping point) is heavily penalised
                    val score = timeErr * 100 - (if (scenic) curv / 10 else 0.0) + overlap(r) * 300
                    score to pts
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
        scored.minByOrNull { it.first }?.second ?: candidates.first()
    }

    /** Share of the route (sampled every 50 m) that runs within 25 m of an earlier, non-adjacent part. */
    private fun overlap(r: Route): Double {
        val path = r.path
        val n = (path.length / 50).toInt()
        if (n < 10) return 0.0
        val pts = (0 until n).map { path.pointAt(it * 50.0) }
        var hits = 0
        for (i in 6 until n) {
            // skip the start/end area, where the loop rightly meets itself
            if (Geo.dist(pts[i], pts[0]) < 600) continue
            if ((0 until i - 5).any { j -> Geo.dist(pts[i], pts[j]) < 25 }) hits++
        }
        return hits.toDouble() / n
    }
}
