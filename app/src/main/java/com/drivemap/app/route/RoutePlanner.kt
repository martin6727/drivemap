package com.drivemap.app.route

import com.drivemap.app.i18n.t
import android.util.Log
import com.drivemap.app.data.Countries
import com.drivemap.app.data.SettingsStore
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.RouteLocation
import com.drivemap.app.net.Valhalla
import com.drivemap.app.radar.CameraRepository
import com.drivemap.app.vignette.VignetteCatalog
import com.drivemap.app.vignette.VignetteStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.drivemap.app.route.LaneInfo

/**
 * Builds route candidates for a trip and picks the best one for the chosen [RouteMode].
 *
 * - FASTEST: Valhalla's fastest route + alternatives.
 * - ECONOMICAL: adds shortest / motorway-light variants, picks the lowest fuel use.
 * - SPORT: adds motorcycle "adventure" and no-motorway variants, picks the twistiest.
 * - CHEAPEST: fuel + per-km tolls + vignettes you'd have to buy, picks the lowest total.
 * - FEWEST_RADARS: re-routes around fixed cameras on the fastest route.
 *
 * With "avoid unpaid vignettes" on, routes that use motorways of countries where the user has no
 * valid vignette are re-planned per country: tolls are avoided only inside those countries.
 */
class RoutePlanner(
    private val valhalla: Valhalla,
    private val settings: SettingsStore,
    private val vignettes: VignetteStore,
    private val cameras: CameraRepository,
) {
    data class Request(
        val origin: LatLon,
        val originHeading: Double?,
        /** Intermediate stops followed by the destination (last). */
        val stops: List<Waypoint>,
        val mode: RouteMode,
        val avoid: AvoidOptions,
        val departureMs: Long = System.currentTimeMillis(),
    )

    data class Result(val routes: List<Route>, val notes: List<String>)

    private val gate = Semaphore(2)
    private suspend fun <T> limited(block: suspend () -> T): T = gate.withPermit { block() }

    private fun JSONObject.with(key: String, value: Any): JSONObject = JSONObject(this.toString()).put(key, value)

    private fun autoOptions(a: AvoidOptions): JSONObject {
        val o = JSONObject()
        if (a.tolls) o.put("use_tolls", 0.0)
        if (a.motorways) o.put("use_highways", 0.0)
        if (a.ferries) o.put("use_ferry", 0.0)
        return o
    }

    private fun motoOptions(a: AvoidOptions): JSONObject {
        val o = JSONObject().put("use_highways", 0.0).put("use_trails", 0.4)
        if (a.tolls) o.put("use_tolls", 0.0)
        if (a.ferries) o.put("use_ferry", 0.0)
        return o
    }

    suspend fun plan(req: Request, progress: (String) -> Unit = {}): Result = coroutineScope {
        val s = settings.value
        val lang = s.voiceLang
        val locs = listOf(RouteLocation(req.origin, req.originHeading)) + req.stops.map { RouteLocation(it.pos, heading = it.heading, through = it.through) }
        val base = autoOptions(req.avoid)
        val notes = ArrayList<String>()

        progress(t("Finding routes…"))
        val main = limited { valhalla.route(locs, "auto", base, lang, alternates = if (locs.size == 2) 2 else 0) }
        val candidates = ArrayList<Route>()
        main.forEachIndexed { i, g -> candidates.add(Route(g, if (i == 0) "fastest" else "alt$i", req.stops)) }

        val variants = ArrayList<Triple<String, String, JSONObject>>()
        when (req.mode) {
            RouteMode.ECONOMICAL -> {
                variants += Triple("eco-short", "auto", base.with("shortest", true))
                if (!req.avoid.motorways) variants += Triple("eco-nohw", "auto", base.with("use_highways", 0.15))
            }
            RouteMode.SPORT -> {
                variants += Triple("sport-moto", "motorcycle", motoOptions(req.avoid))
                variants += Triple("sport-nohw", "auto", base.with("use_highways", 0.0))
            }
            RouteMode.CHEAPEST -> {
                if (!req.avoid.tolls) variants += Triple("notoll", "auto", base.with("use_tolls", 0.0))
                variants += Triple("eco-short", "auto", base.with("shortest", true))
            }
            else -> {}
        }
        if (variants.isNotEmpty()) {
            candidates += variants.map { (name, costing, opts) ->
                async {
                    runCatching { limited { valhalla.route(locs, costing, opts, lang) } }
                        .onFailure { Log.w("Planner", "$name: ${it.message}") }
                        .getOrNull()?.firstOrNull()?.let { Route(it, name, req.stops) }
                }
            }.awaitAll().filterNotNull()
        }

        progress(t("Checking tolls, vignettes and speed limits…"))
        analyze(candidates, req)

        // Vignette-aware alternatives
        if (req.avoid.unpaidVignettes && !req.avoid.tolls) {
            val missing = candidates.flatMap { r -> r.metrics?.missingVignettes?.map { it.country } ?: emptyList() }.toSet()
            if (missing.isNotEmpty()) {
                progress(t("Avoiding motorways without a vignette…"))
                var nt = candidates.firstOrNull { it.variant == "notoll" }
                if (nt == null) {
                    nt = runCatching { limited { valhalla.route(locs, "auto", base.with("use_tolls", 0.0), lang) } }
                        .getOrNull()?.firstOrNull()?.let { Route(it, "notoll", req.stops) }
                    if (nt != null) {
                        analyze(listOf(nt), req)
                        candidates += nt
                    }
                }
                if (nt != null) {
                    val hy = runCatching { hybrid(req, base, nt, lang) }
                        .onFailure { Log.w("Planner", "hybrid: ${it.message}") }
                        .getOrNull()
                    if (hy != null) {
                        analyze(listOf(hy), req)
                        candidates += hy
                    }
                }
            }
        }

        // Speed cameras along each route
        progress(t("Looking for speed cameras…"))
        val all = candidates.flatMap { it.geometry.shape }
        if (all.isNotEmpty()) {
            val bb = Geo.bounds(all)
            withTimeoutOrNull(if (req.mode == RouteMode.FEWEST_RADARS) 25_000L else 7_000L) {
                cameras.ensureNow(bb[0], bb[1], bb[2], bb[3])
            }
        }
        for (r in candidates) {
            r.cameras = cameras.alongRoute(r.path)
            r.cameraCount = r.cameras.size
        }
        if (req.mode == RouteMode.FEWEST_RADARS) {
            val fastest = candidates.first()
            if (fastest.cameras.isNotEmpty()) {
                val exclude = fastest.cameras.take(50).map { fastest.path.pointAt(it.alongM) }
                val g = runCatching { limited { valhalla.route(locs, "auto", base, lang, excludeLocations = exclude) } }
                    .getOrNull()?.firstOrNull()
                if (g != null) {
                    val rr = Route(g, "radar", req.stops)
                    analyze(listOf(rr), req)
                    rr.cameras = cameras.alongRoute(rr.path)
                    rr.cameraCount = rr.cameras.size
                    candidates += rr
                }
            }
        }

        val unique = dedupe(candidates)
        val best = recommend(unique, req)
        tag(unique, req)
        best.tags.add("Best for ${req.mode.en.lowercase()}")
        notes.addAll(vignetteNotes(unique, best))
        val ordered = listOf(best) + unique.filter { it !== best }.sortedBy { it.timeS }
        Result(ordered, notes)
    }

    /**
     * Quick re-plan while driving (left the route / stops changed): one request with the same kind
     * of costing as [previous], keeping its vignette compliance.
     */
    /** Costing used for a route variant (so reroutes and lane lookups behave like the original). */
    private fun costingFor(variant: String, avoid: AvoidOptions, base: JSONObject): Pair<String, JSONObject> = when (variant) {
        "eco-short" -> "auto" to base.with("shortest", true)
        "eco-nohw" -> "auto" to base.with("use_highways", 0.15)
        "sport-moto" -> "motorcycle" to motoOptions(avoid)
        "sport-nohw" -> "auto" to base.with("use_highways", 0.0)
        "notoll" -> "auto" to base.with("use_tolls", 0.0)
        else -> "auto" to base
    }

    /**
     * Lane arrows for the maneuvers of [route] (from OSM turn:lanes), matched by position.
     * Only junctions where some lanes are wrong for us are kept – that's when guidance helps.
     */
    suspend fun laneGuidance(route: Route, req: Request): Map<Int, List<LaneInfo>> {
        val lang = settings.value.voiceLang
        val locs = listOf(RouteLocation(req.origin, req.originHeading)) + req.stops.map { RouteLocation(it.pos, heading = it.heading, through = it.through) }
        val (costing, opts) = costingFor(route.variant, req.avoid, autoOptions(req.avoid))
        val junctions = limited { valhalla.laneIntersections(locs, costing, opts, lang) }
        val mans = route.geometry.maneuvers
        val path = route.path
        val out = HashMap<Int, List<LaneInfo>>()
        var seg = 0
        for ((pos, lanes) in junctions) {
            if (lanes.all { it.valid } || lanes.none { it.valid }) continue
            var hit = path.project(pos, seg, (seg + 600).coerceAtMost(path.points.size - 1))
            if (hit == null || hit.crossTrack > 25) hit = path.project(pos)
            if (hit == null || hit.crossTrack > 25) continue
            seg = hit.segment
            val idx = mans.indices.minByOrNull { Math.abs(mans[it].alongM - hit.along) } ?: continue
            if (Math.abs(mans[idx].alongM - hit.along) < 35 && !out.containsKey(idx)) out[idx] = lanes
        }
        return out
    }

    suspend fun reroute(req: Request, previous: Route, onQuick: (Route) -> Unit = {}): Route {
        val lang = settings.value.voiceLang
        val locs = listOf(RouteLocation(req.origin, req.originHeading)) + req.stops.map { RouteLocation(it.pos, heading = it.heading, through = it.through) }
        val base = autoOptions(req.avoid)
        val (costing, opts) = costingFor(previous.variant, req.avoid, base)
        var r = Route(limited { valhalla.route(locs, costing, opts, lang) }.first(), previous.variant, req.stops)
        // show the new line right away; limits, tolls and cameras are filled in below
        onQuick(r)
        analyze(listOf(r), req)
        val prevCompliant = previous.metrics?.missingVignettes?.isEmpty() ?: true
        val nowMissing = r.metrics?.missingVignettes?.isNotEmpty() == true
        if (req.avoid.unpaidVignettes && !req.avoid.tolls && prevCompliant && nowMissing) {
            val nt = Route(limited { valhalla.route(locs, "auto", base.with("use_tolls", 0.0), lang) }.first(), "notoll", req.stops)
            analyze(listOf(nt), req)
            val hy = runCatching { hybrid(req, base, nt, lang) }.getOrNull()
            r = if (hy != null) {
                analyze(listOf(hy), req)
                hy
            } else nt
        }
        val bb = Geo.bounds(r.geometry.shape)
        cameras.ensure(bb[0], bb[1], bb[2], bb[3])
        r.cameras = cameras.alongRoute(r.path)
        r.cameraCount = r.cameras.size
        if (previous.variant == "radar" && r.cameras.isNotEmpty()) {
            val exclude = r.cameras.take(50).map { r.path.pointAt(it.alongM) }
            val g = runCatching { limited { valhalla.route(locs, "auto", base, lang, excludeLocations = exclude) } }.getOrNull()?.firstOrNull()
            if (g != null) {
                val rr = Route(g, "radar", req.stops)
                analyze(listOf(rr), req)
                rr.cameras = cameras.alongRoute(rr.path)
                rr.cameraCount = rr.cameras.size
                if (rr.cameraCount < r.cameraCount) r = rr
            }
        }
        r.tags.addAll(previous.tags.filter { it.startsWith("Best for") })
        return r
    }

    /** Road attributes (countries, tolls, limits) + cost metrics for each route. */
    private suspend fun analyze(routes: List<Route>, req: Request) = coroutineScope {
        routes.map { r ->
            async {
                if (r.edges.isEmpty()) {
                    r.edges = runCatching { limited { valhalla.traceAttributes(r.path) } }
                        .onFailure { Log.w("Planner", "attributes ${r.variant}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
                r.metrics = Costs.metrics(r, settings.value, vignettes, req.departureMs)
            }
        }.awaitAll()
    }

    /**
     * Rebuilds the route country by country: inside countries where the user lacks a vignette tolls
     * are avoided, elsewhere the normal fastest costing is used. Border crossings come from the
     * all-toll-free route [nt] so they are always on free roads.
     */
    private suspend fun hybrid(req: Request, base: JSONObject, nt: Route, lang: String): Route? {
        if (nt.edges.isEmpty()) return null
        val dep = Costs.departureDate(req.departureMs)
        val days = (nt.timeS / 86400.0).toLong() + 1
        fun allowed(c: String?): Boolean =
            c == null || c !in Countries.vignetteCountries || VignetteCatalog.coversAllRoads(c) ||
                vignettes.covers(c, dep, dep.plusDays(days))

        data class Anchor(val along: Double, val loc: RouteLocation, val kind: Int) // kind: 0 origin, 1 waypoint, 2 crossing, 3 destination

        val anchors = ArrayList<Anchor>()
        anchors += Anchor(0.0, RouteLocation(req.origin, req.originHeading), 0)
        nt.waypointAlong.forEachIndexed { i, a ->
            if (i < req.stops.size - 1) anchors += Anchor(a, RouteLocation(req.stops[i].pos), 1)
        }
        var prev: String? = null
        for (e in nt.edges) {
            val c = e.country ?: continue
            if (prev != null && c != prev) {
                val at = (e.startM + minOf(40.0, (e.endM - e.startM) / 2)).coerceIn(0.0, nt.path.length)
                anchors += Anchor(at, RouteLocation(nt.path.pointAt(at), nt.path.bearingAt(at), 35), 2)
            }
            prev = c
        }
        anchors += Anchor(nt.path.length, RouteLocation(req.stops.last().pos), 3)
        anchors.sortBy { it.along }
        if (anchors.none { it.kind == 2 }) return null

        val segAllowed = (0 until anchors.size - 1).map { k ->
            val mid = (anchors[k].along + anchors[k + 1].along) / 2
            allowed(nt.edgeAt(mid)?.country)
        }
        if (segAllowed.none { it }) return null

        val geo = RouteGeometry()
        var k = 0
        var groups = 0
        while (k < anchors.size - 1) {
            var j = k
            while (j + 1 < anchors.size - 1 && segAllowed[j + 1] == segAllowed[k]) j++
            val locs = ArrayList<RouteLocation>()
            locs += anchors[k].loc
            for (m in k + 1..j) if (anchors[m].kind == 1) locs += anchors[m].loc
            locs += anchors[j + 1].loc
            val opts = if (segAllowed[k]) base else base.with("use_tolls", 0.0)
            val g = limited { valhalla.route(locs, "auto", opts, lang) }.first()
            val startIsWaypoint = anchors[k].kind == 1
            geo.append(g, joinAsThrough = k > 0 && !startIsWaypoint, markWaypoint = startIsWaypoint)
            if (++groups > 8) return null
            k = j + 1
        }
        return Route(geo, "vignette", req.stops)
    }

    private fun dedupe(list: List<Route>): List<Route> {
        val out = ArrayList<Route>()
        for (r in list) {
            val dup = out.firstOrNull { o ->
                o.signature() == r.signature() ||
                    (Math.abs(o.timeS - r.timeS) < o.timeS * 0.01 && Math.abs(o.lengthKm - r.lengthKm) < o.lengthKm * 0.005)
            }
            if (dup == null) out.add(r) else if (r.cameraCount >= 0 && dup.cameraCount < 0) dup.cameraCount = r.cameraCount
        }
        return out
    }

    private fun compliant(r: Route, req: Request) =
        !req.avoid.unpaidVignettes || (r.metrics?.missingVignettes?.isEmpty() ?: true)

    private fun sportScore(r: Route) = (r.metrics?.sharpCorners ?: 0) * 10.0 + (r.metrics?.curvatureDegPerKm ?: 0.0)

    private fun costScore(r: Route) = (r.metrics?.totalCostEur ?: 0.0) + r.timeS / 3600.0 * 2.0

    private fun recommend(list: List<Route>, req: Request): Route {
        val pool = list.filter { compliant(it, req) }.ifEmpty { list }
        val fastest = pool.minOf { it.timeS }
        return when (req.mode) {
            RouteMode.FASTEST -> pool.minBy { it.timeS }
            RouteMode.ECONOMICAL -> pool.filter { it.timeS <= fastest * 1.5 }
                .minBy { (it.metrics?.fuelLiters ?: 999.0) + it.timeS / 3.6e5 }
            RouteMode.SPORT -> pool.filter { it.timeS <= fastest * 1.8 }.maxBy { sportScore(it) }
            RouteMode.CHEAPEST -> {
                val f = list.minOf { it.timeS }
                list.filter { it.timeS <= f * 2.0 }.minBy { costScore(it) }
            }
            RouteMode.FEWEST_RADARS -> pool.filter { it.timeS <= fastest * 1.4 }
                .minBy { it.cameraCount.coerceAtLeast(0) * 1e6 + it.timeS }
        }
    }

    private fun tag(list: List<Route>, req: Request) {
        if (list.isEmpty()) return
        list.minBy { it.timeS }.tags.add("Fastest")
        if (list.size > 1) {
            list.minBy { costScore(it) }.tags.add("Cheapest")
            list.minBy { it.metrics?.fuelLiters ?: 999.0 }.tags.add("Least fuel")
            val twisty = list.maxBy { sportScore(it) }
            if ((twisty.metrics?.sharpCorners ?: 0) >= 5) twisty.tags.add("Twistiest")
            val counts = list.map { it.cameraCount }.filter { it >= 0 }
            if (counts.isNotEmpty() && counts.max() > counts.min()) list.minBy { if (it.cameraCount < 0) 999 else it.cameraCount }.tags.add("Fewest radars")
        }
        for (r in list) {
            val miss = r.metrics?.missingVignettes ?: emptyList()
            if (miss.isNotEmpty()) r.tags.add("Needs vignette: " + miss.joinToString { it.country })
            if (r.variant == "vignette") r.tags.add("Only roads you've paid for")
        }
    }

    private fun vignetteNotes(list: List<Route>, best: Route): List<String> {
        val out = ArrayList<String>()
        val bestMissing = best.metrics?.missingVignettes ?: emptyList()
        if (bestMissing.isNotEmpty()) {
            for (v in bestMissing) {
                out += "${Countries.flag(v.country)} " + t("This route needs a {0} vignette: {1} ≈ {2}", Countries.name(v.country), v.productLabel, "€" + String.format("%.2f", v.priceEur))
            }
            return out
        }
        val faster = list.filter { it.timeS < best.timeS - 120 && (it.metrics?.missingVignettes?.isNotEmpty() == true) }
            .minByOrNull { it.timeS } ?: return out
        val miss = faster.metrics!!.missingVignettes
        val price = miss.sumOf { it.priceEur }
        out += t(
            "Buying {0} (≈ {1}) would save {2}",
            miss.joinToString(" + ") { "${Countries.flag(it.country)} ${it.productLabel}" },
            String.format("€%.2f", price), Geo.formatDuration(best.timeS - faster.timeS),
        )

        return out
    }
}
