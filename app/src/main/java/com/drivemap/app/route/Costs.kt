package com.drivemap.app.route

import com.drivemap.app.data.AppSettings
import com.drivemap.app.data.Countries
import com.drivemap.app.data.FuelType
import com.drivemap.app.geo.Geo
import com.drivemap.app.vignette.VignetteCatalog
import com.drivemap.app.vignette.VignetteStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object Costs {

    /** Relative consumption curve vs speed. Petrol/diesel: idle losses at low speed + drag at high speed. */
    private fun curve(v: Double, electric: Boolean): Double {
        val s = v.coerceIn(10.0, 170.0)
        return if (electric) 11.44 + 0.000625 * s * s else 212.94 / s + 0.623 + 0.00031 * s * s
    }

    /** Consumption per 100 km at a steady [speedKmh], scaled so mixed driving (~70 km/h) = user's average. */
    fun per100(speedKmh: Double, s: AppSettings): Double {
        val el = s.fuelType == FuelType.ELECTRIC
        return s.consumption * curve(speedKmh, el) / curve(70.0, el)
    }

    fun metrics(route: Route, s: AppSettings, store: VignetteStore, departureMs: Long): RouteMetrics {
        val edges = route.edges
        var fuel = 0.0
        var fuelCost = 0.0 // each country's own pump price when automatic prices are on
        var motorwayKm = 0.0
        var tollEur = 0.0
        val countries = LinkedHashSet<String>()
        // per vignette country: km on vignette roads, first/last time seen
        val vigKm = HashMap<String, Double>()
        val vigFirst = HashMap<String, Double>()
        val vigLast = HashMap<String, Double>()
        var tSec = 0.0
        if (edges.isNotEmpty()) {
            for (e in edges) {
                val speed = e.speedKmh.coerceAtLeast(5.0)
                val litres = e.lengthKm * per100(speed, s) / 100.0
                fuel += litres
                fuelCost += litres * com.drivemap.app.fuel.FuelPrices.price(e.country, s)
                val dt = e.lengthKm / speed * 3600.0
                val c = e.country
                if (c != null) countries.add(c)
                if (e.roadClass == "motorway") motorwayKm += e.lengthKm
                if (c != null && c in Countries.vignetteCountries) {
                    val needs = e.toll || VignetteCatalog.coversAllRoads(c)
                    if (needs) {
                        vigKm[c] = (vigKm[c] ?: 0.0) + e.lengthKm
                        if (!vigFirst.containsKey(c)) vigFirst[c] = tSec
                        vigLast[c] = tSec + dt
                    }
                } else if (e.toll) {
                    tollEur += e.lengthKm * Countries.tollRate(c)
                }
                tSec += dt
            }
            // Valhalla's time includes turn costs etc.; scale edge-time to route time.
        } else {
            for (m in route.geometry.maneuvers) {
                val speed = if (m.timeS > 0) (m.lengthKm / (m.timeS / 3600.0)).coerceIn(5.0, 140.0) else 50.0
                fuel += m.lengthKm * per100(speed, s) / 100.0
            }
            fuelCost = fuel * com.drivemap.app.fuel.FuelPrices.price(null, s)
        }
        val scale = if (tSec > 0) route.timeS / tSec else 1.0
        val zone = ZoneId.systemDefault()
        val vignettes = vigKm.filter { it.value >= 0.3 }.map { (c, km) ->
            val from = Instant.ofEpochMilli(departureMs + ((vigFirst[c] ?: 0.0) * scale * 1000).toLong()).atZone(zone).toLocalDate()
            val to = Instant.ofEpochMilli(departureMs + ((vigLast[c] ?: 0.0) * scale * 1000).toLong()).atZone(zone).toLocalDate()
            val covered = store.covers(c, from, to)
            val product = VignetteCatalog.cheapestCovering(c, from, to)
            VignetteNeed(c, km, product?.label ?: "vignette", product?.priceEur ?: 0.0, covered)
        }.sortedBy { it.country }
        val (curv, corners) = curvature(route)
        return RouteMetrics(
            fuelLiters = fuel,
            fuelCostEur = fuelCost,
            tollCostEur = tollEur,
            vignettes = vignettes,
            curvatureDegPerKm = curv,
            sharpCorners = corners,
            countries = countries.toList(),
            motorwayKm = motorwayKm,
        )
    }

    /**
     * Twistiness of the route outside towns: total heading change per km (resampled every 20 m)
     * and the number of sharp corners (≥ 60° within ~120 m), ignoring junction turns.
     */
    /** A bend along the route: from/to (metres along) and whether it is sharp (else medium). */
    data class Corner(val fromM: Double, val toM: Double, val sharp: Boolean)

    /**
     * Bends and turns along the route (junctions and roundabouts included): heading change over ~80 m,
     * ≥ 70° = sharp, 35–70° = medium.
     */
    fun corners(route: Route): List<Corner> {
        val path = route.path
        val step = 10.0
        val n = (path.length / step).toInt()
        if (n < 10) return emptyList()
        val h = DoubleArray(n) { i -> Geo.bearing(path.pointAt(i * step), path.pointAt(i * step + step)) }
        val level = IntArray(n) // 0 none, 1 medium, 2 sharp
        for (i in 4 until n - 4) {
            var turn = 0.0
            for (j in i - 4 until i + 4) turn += Geo.angleDiff(h[j], h[j + 1])
            val d = Math.abs(turn)
            level[i] = when {
                d >= 70 -> 2
                d >= 35 -> 1
                else -> 0
            }
        }
        val out = ArrayList<Corner>()
        var i = 0
        while (i < n) {
            if (level[i] == 0) { i++; continue }
            var j = i
            var max = 0
            while (j < n && level[j] > 0) { max = maxOf(max, level[j]); j++ }
            out += Corner((i * step - 15).coerceAtLeast(0.0), (j * step + 15).coerceAtMost(path.length), max == 2)
            i = j
        }
        return out
    }

    fun curvature(route: Route): Pair<Double, Int> {
        val path = route.path
        if (path.length < 200) return 0.0 to 0
        val step = 20.0
        val n = (path.length / step).toInt()
        if (n < 3) return 0.0 to 0
        val headings = DoubleArray(n)
        val rural = BooleanArray(n)
        val junctionAlong = route.geometry.maneuvers.filter { it.type !in setOf(0, 1, 8, 22) }.map { it.alongM }
        var jIdx = 0
        for (i in 0 until n) {
            val a = i * step
            val p1 = path.pointAt(a)
            val p2 = path.pointAt(a + step)
            headings[i] = Geo.bearing(p1, p2)
            val e = route.edgeAt(a)
            val fast = (e?.speedKmh ?: 60.0) >= 45.0 && (e?.density ?: 0) < 9
            while (jIdx < junctionAlong.size && junctionAlong[jIdx] < a - 60) jIdx++
            val nearJunction = jIdx < junctionAlong.size && Math.abs(junctionAlong[jIdx] - a) < 60
            rural[i] = fast && !nearJunction
        }
        var total = 0.0
        var ruralKm = 0.0
        var corners = 0
        var skipUntil = -1
        for (i in 1 until n) {
            if (!rural[i] || !rural[i - 1]) continue
            ruralKm += step / 1000.0
            val d = Math.abs(Geo.angleDiff(headings[i - 1], headings[i]))
            if (d >= 2.0 && d < 120) total += d
            if (i > skipUntil && i >= 6) {
                var ok = true
                for (k in i - 6..i) if (!rural[k]) { ok = false; break }
                if (ok) {
                    val turn = Math.abs(Geo.angleDiff(headings[i - 6], headings[i]))
                    if (turn >= 60 && turn < 170) {
                        corners++
                        skipUntil = i + 8
                    }
                }
            }
        }
        val perKm = if (ruralKm > 1) total / ruralKm else 0.0
        return perKm to corners
    }

    fun departureDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
}
