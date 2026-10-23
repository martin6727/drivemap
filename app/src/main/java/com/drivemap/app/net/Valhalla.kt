package com.drivemap.app.net

import com.drivemap.app.i18n.t
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.geo.PolylinePath
import com.drivemap.app.route.EdgeSpan
import com.drivemap.app.route.Maneuver
import com.drivemap.app.route.ManeuverSign
import com.drivemap.app.route.RouteGeometry
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class RoutingException(message: String) : IOException(message)

data class RouteLocation(
    val pos: LatLon,
    val heading: Double? = null,
    val headingTolerance: Int = 45,
    val through: Boolean = false,
)

data class LocatedEdge(
    val wayId: Long,
    val shape: List<LatLon>,
    val speedLimit: Int?,
    val classification: String,
    val use: String,
    val defaultSpeed: Int,
    val forward: Boolean,
    val distanceM: Double,
    val heading: Double,
    val toll: Boolean,
    val names: List<String>,
)

/**
 * Client for a Valhalla routing server (default: the free FOSSGIS instance).
 */
class Valhalla(private val baseUrl: () -> String) {

    private fun url(action: String) = baseUrl().trimEnd('/') + "/" + action

    suspend fun route(
        locations: List<RouteLocation>,
        costing: String,
        costingOptions: JSONObject,
        language: String,
        alternates: Int = 0,
        excludeLocations: List<LatLon> = emptyList(),
    ): List<RouteGeometry> {
        val body = JSONObject()
        val locs = JSONArray()
        locations.forEachIndexed { i, l ->
            val o = JSONObject().put("lat", l.pos.lat).put("lon", l.pos.lon)
            o.put("type", if (l.through && i != 0 && i != locations.size - 1) "through" else "break")
            if (l.heading != null) {
                o.put("heading", l.heading.toInt())
                o.put("heading_tolerance", l.headingTolerance)
            }
            if (i == 0) o.put("radius", 0)
            locs.put(o)
        }
        body.put("locations", locs)
        body.put("costing", costing)
        body.put("costing_options", JSONObject().put(costing, costingOptions))
        body.put("directions_options", JSONObject().put("units", "kilometers").put("language", language))
        if (alternates > 0 && locations.size == 2) body.put("alternates", alternates)
        if (excludeLocations.isNotEmpty()) {
            val ex = JSONArray()
            excludeLocations.take(50).forEach { ex.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
            body.put("exclude_locations", ex)
        }
        val text = try {
            Http.postJson(url("route"), body.toString(), timeoutSec = 60)
        } catch (e: HttpException) {
            throw RoutingException(errorMessage(e.body) ?: t("Routing failed ({0})", e.code))
        }
        val json = JSONObject(text)
        val out = ArrayList<RouteGeometry>()
        out.add(parseTrip(json.getJSONObject("trip")))
        val alts = json.optJSONArray("alternates")
        if (alts != null) for (i in 0 until alts.length()) {
            out.add(parseTrip(alts.getJSONObject(i).getJSONObject("trip")))
        }
        return out
    }

    private fun errorMessage(body: String): String? = try {
        val o = JSONObject(body)
        val code = o.optInt("error_code")
        val msg = o.optString("error")
        when (code) {
            442, 443 -> t("No road route found between these places")
            171 -> t("Couldn't find a road near one of the points")
            else -> if (msg.isNotBlank()) msg else null
        }
    } catch (_: Exception) {
        null
    }

    fun parseTrip(trip: JSONObject): RouteGeometry {
        val g = RouteGeometry()
        val legs = trip.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val shape = Geo.decodePolyline(leg.getString("shape"), 6)
            val mans = parseManeuvers(leg.getJSONArray("maneuvers"))
            val sum = leg.optJSONObject("summary")
            g.appendLeg(
                shape, mans,
                sum?.optDouble("time", 0.0) ?: 0.0,
                sum?.optDouble("length", 0.0) ?: 0.0,
                joinAsThrough = false,
                markWaypoint = li > 0,
            )
            if (sum != null) {
                g.hasToll = g.hasToll || sum.optBoolean("has_toll")
                g.hasHighway = g.hasHighway || sum.optBoolean("has_highway")
                g.hasFerry = g.hasFerry || sum.optBoolean("has_ferry")
            }
        }
        return g
    }

    private fun parseManeuvers(arr: JSONArray): List<Maneuver> {
        val out = ArrayList<Maneuver>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val names = ArrayList<String>()
            m.optJSONArray("street_names")?.let { a -> for (j in 0 until a.length()) names.add(a.getString(j)) }
            if (names.isEmpty()) m.optJSONArray("begin_street_names")?.let { a -> for (j in 0 until a.length()) names.add(a.getString(j)) }
            val sign = m.optJSONObject("sign")?.let { s ->
                fun texts(key: String): List<String> {
                    val a = s.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("text")?.ifBlank { null } }
                }
                val number = texts("exit_number_elements").firstOrNull()
                val branches = texts("exit_branch_elements")
                val toward = texts("exit_toward_elements").ifEmpty { texts("exit_name_elements") }
                if (number == null && branches.isEmpty() && toward.isEmpty()) null else ManeuverSign(number, branches, toward)
            }
            out.add(
                Maneuver(
                    type = m.optInt("type"),
                    instruction = m.optString("instruction"),
                    verbalAlert = m.optString("verbal_transition_alert_instruction").ifBlank { null },
                    verbalPre = m.optString("verbal_pre_transition_instruction").ifBlank { null },
                    verbalPost = m.optString("verbal_post_transition_instruction").ifBlank { null },
                    streetNames = names,
                    beginIndex = m.optInt("begin_shape_index"),
                    lengthKm = m.optDouble("length", 0.0),
                    timeS = m.optDouble("time", 0.0),
                    roundaboutExit = m.optInt("roundabout_exit_count", 0),
                    toll = m.optBoolean("toll"),
                    highway = m.optBoolean("highway"),
                    sign = sign,
                )
            )
        }
        return out
    }

    /**
     * Lane layout at every junction of a route that has painted lane arrows (OSM turn:lanes),
     * using Valhalla's OSRM-compatible output. Returns (junction position, lanes) in route order.
     */
    suspend fun laneIntersections(
        locations: List<RouteLocation>,
        costing: String,
        costingOptions: JSONObject,
        language: String,
    ): List<Pair<LatLon, List<com.drivemap.app.route.LaneInfo>>> {
        val body = JSONObject()
        val locs = JSONArray()
        locations.forEach { l ->
            val o = JSONObject().put("lat", l.pos.lat).put("lon", l.pos.lon).put("type", "break")
            if (l.heading != null) o.put("heading", l.heading.toInt()).put("heading_tolerance", l.headingTolerance)
            locs.put(o)
        }
        body.put("locations", locs)
        body.put("costing", costing)
        body.put("costing_options", JSONObject().put(costing, costingOptions))
        body.put("directions_options", JSONObject().put("units", "kilometers").put("language", language))
        body.put("format", "osrm")
        val json = JSONObject(Http.postJson(url("route"), body.toString(), timeoutSec = 60))
        val out = ArrayList<Pair<LatLon, List<com.drivemap.app.route.LaneInfo>>>()
        val routes = json.optJSONArray("routes") ?: return out
        if (routes.length() == 0) return out
        val legs = routes.getJSONObject(0).optJSONArray("legs") ?: return out
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val inters = steps.getJSONObject(si).optJSONArray("intersections") ?: continue
                for (ii in 0 until inters.length()) {
                    val x = inters.getJSONObject(ii)
                    val lanesArr = x.optJSONArray("lanes") ?: continue
                    val loc = x.optJSONArray("location") ?: continue
                    val lanes = (0 until lanesArr.length()).map { k ->
                        val l = lanesArr.getJSONObject(k)
                        val ind = l.optJSONArray("indications")
                        com.drivemap.app.route.LaneInfo(
                            indications = if (ind == null) emptyList() else (0 until ind.length()).map { ind.optString(it) },
                            valid = l.optBoolean("valid"),
                            active = l.optBoolean("active"),
                            validIndication = l.optString("valid_indication").ifBlank { null },
                        )
                    }
                    if (lanes.size >= 2) out.add(LatLon(loc.getDouble(1), loc.getDouble(0)) to lanes)
                }
            }
        }
        return out
    }

    /**
     * Road attributes along a route shape (country, tolls, limits, speeds).
     * Distances are mapped onto [path] so they line up with navigation progress.
     */
    suspend fun traceAttributes(path: PolylinePath, costing: String = "auto"): List<EdgeSpan> {
        val pts = path.points
        if (pts.size < 2) return emptyList()
        // Very long routes are split into chunks to stay inside server limits.
        val chunks = ArrayList<IntRange>()
        var start = 0
        while (start < pts.size - 1) {
            var end = start
            while (end < pts.size - 1 && (end - start) < 12000 && path.cum[end] - path.cum[start] < 900_000) end++
            chunks.add(start..end)
            start = end
        }
        val out = ArrayList<EdgeSpan>()
        for (range in chunks) {
            val sub = pts.subList(range.first, range.last + 1)
            val baseM = path.cum[range.first]
            val spanM = path.cum[range.last] - baseM
            out.addAll(traceChunk(sub, baseM, spanM, costing))
        }
        return out
    }

    private suspend fun traceChunk(points: List<LatLon>, baseM: Double, spanM: Double, costing: String): List<EdgeSpan> {
        val attrs = JSONArray(
            listOf(
                "edge.length", "edge.speed", "edge.speed_limit", "edge.road_class", "edge.toll", "edge.use",
                "edge.density", "edge.begin_shape_index", "edge.end_shape_index", "node.admin_index",
                "admin.country_code", "shape",
            )
        )
        fun body(match: String) = JSONObject()
            .put("encoded_polyline", Geo.encodePolyline(points, 6))
            .put("shape_match", match)
            .put("costing", costing)
            .put("filters", JSONObject().put("attributes", attrs).put("action", "include"))
            .toString()

        val text = try {
            Http.postJson(url("trace_attributes"), body("edge_walk"), timeoutSec = 90)
        } catch (e: HttpException) {
            try {
                Http.postJson(url("trace_attributes"), body("map_snap"), timeoutSec = 90)
            } catch (e2: HttpException) {
                throw RoutingException(errorMessage(e2.body) ?: t("Couldn't read road details"))

            }
        }
        val json = JSONObject(text)
        val admins = json.optJSONArray("admins") ?: JSONArray()
        val countries = (0 until admins.length()).map { admins.getJSONObject(it).optString("country_code").ifBlank { null } }
        val shape = json.optString("shape").let { if (it.isBlank()) points else Geo.decodePolyline(it, 6) }
        val sp = PolylinePath(shape)
        val scale = if (sp.length > 1) spanM / sp.length else 1.0
        val edges = json.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<EdgeSpan>(edges.length())
        var lastCountry: String? = null
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            val b = e.optInt("begin_shape_index").coerceIn(0, sp.cum.size - 1)
            val en = e.optInt("end_shape_index").coerceIn(0, sp.cum.size - 1)
            val adminIdx = e.optJSONObject("end_node")?.optInt("admin_index", -1) ?: -1
            val country = if (adminIdx in countries.indices) countries[adminIdx] else lastCountry
            if (country != null) lastCountry = country
            val limit: Int? = when (val v = e.opt("speed_limit")) {
                is Number -> v.toInt().takeIf { it in 1..199 }
                is String -> if (v.equals("unlimited", true)) -1 else v.toIntOrNull()?.takeIf { it in 1..199 }
                else -> null
            }
            out.add(
                EdgeSpan(
                    startM = baseM + sp.cum[b] * scale,
                    endM = baseM + sp.cum[en] * scale,
                    lengthKm = e.optDouble("length", 0.0),
                    speedKmh = e.optDouble("speed", 50.0),
                    speedLimit = limit,
                    roadClass = e.optString("road_class"),
                    use = e.optString("use"),
                    toll = e.optBoolean("toll"),
                    density = e.optInt("density", 0),
                    country = country,
                )
            )
        }
        // Edges of the first nodes may lack a country; back-fill from the first known one.
        val first = out.firstOrNull { it.country != null }?.country
        return out.map { if (it.country == null && first != null) it.copy(country = first) else it }
    }

    /** Nearest drivable roads to a point (used for speed limits when not navigating). */
    suspend fun locate(pos: LatLon, heading: Double?): List<LocatedEdge> {
        val loc = JSONObject().put("lat", pos.lat).put("lon", pos.lon).put("radius", 25)
        if (heading != null) {
            loc.put("heading", heading.toInt()).put("heading_tolerance", 60)
        }
        val body = JSONObject()
            .put("locations", JSONArray().put(loc))
            .put("costing", "auto")
            .put("verbose", true)
        val text = Http.postJson(url("locate"), body.toString(), timeoutSec = 15)
        val arr = JSONArray(text)
        if (arr.length() == 0) return emptyList()
        val edges = arr.getJSONObject(0).optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<LocatedEdge>()
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            val info = e.optJSONObject("edge_info") ?: continue
            val edge = e.optJSONObject("edge") ?: continue
            val cls = edge.optJSONObject("classification")
            val shapeStr = info.optString("shape")
            if (shapeStr.isBlank()) continue
            val names = ArrayList<String>()
            info.optJSONArray("names")?.let { a -> for (j in 0 until a.length()) names.add(a.optString(j)) }
            val sl = info.optInt("speed_limit", 0)
            out.add(
                LocatedEdge(
                    wayId = info.optLong("way_id"),
                    shape = Geo.decodePolyline(shapeStr, 6),
                    speedLimit = when {
                        sl == 255 -> -1
                        sl in 1..199 -> sl
                        else -> null
                    },
                    classification = cls?.optString("classification") ?: "",
                    use = cls?.optString("use") ?: "",
                    defaultSpeed = edge.optJSONObject("speeds")?.optInt("default", 0) ?: 0,
                    forward = edge.optBoolean("forward", true),
                    distanceM = e.optDouble("distance", 0.0),
                    heading = e.optDouble("heading", 0.0),
                    toll = edge.optBoolean("toll"),
                    names = names,
                )
            )
        }
        return out
    }
}
