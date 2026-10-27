package com.drivemap.app

import com.drivemap.app.geo.LatLon
import com.drivemap.app.route.Costs
import com.drivemap.app.route.Route
import com.drivemap.app.route.RouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sport routes are picked by twistiness: hairpin roads must count, straight roads must not. */
class CurvatureTest {
    private val lat0 = 48.0
    private val lon0 = 18.0
    private val mLat = 1.0 / 111_320.0
    private val mLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))

    private fun route(pts: List<LatLon>): Route {
        val g = RouteGeometry()
        g.shape.addAll(pts)
        var len = 0.0
        for (i in 1 until pts.size) len += com.drivemap.app.geo.Geo.dist(pts[i - 1], pts[i])
        g.lengthKm = len / 1000.0
        g.timeS = len / 8.0
        return Route(g, "test", emptyList())
    }

    /** A mountain road: straights joined by 180° hairpins (like Masca). */
    private fun switchbacks(legs: Int, legM: Double, radiusM: Double): List<LatLon> {
        val out = ArrayList<LatLon>()
        var x = 0.0
        var y = 0.0
        var dir = 1.0
        for (l in 0 until legs) {
            var s = 0.0
            while (s < legM) { out += LatLon(lat0 + y * mLat, lon0 + x * mLon); y += dir * 10.0; s += 10.0 }
            // half circle to the east, ending heading the other way
            val cx = x + radiusM
            for (k in 0..12) {
                val a = Math.PI - Math.PI * k / 12.0
                out += LatLon(lat0 + (y + dir * Math.sin(a) * radiusM) * mLat, lon0 + (cx + Math.cos(a) * radiusM) * mLon)
            }
            x += 2 * radiusM
            dir = -dir
        }
        return out
    }

    @Test
    fun hairpinsAreCountedAsSharpCorners() {
        val (perKm, corners) = Costs.curvature(route(switchbacks(legs = 8, legM = 250.0, radiusM = 15.0)))
        assertTrue("hairpins: $corners corners", corners >= 6)
        assertTrue("twistiness $perKm °/km", perKm > 200)
    }

    @Test
    fun straightRoadHasNoCorners() {
        val pts = (0..300).map { LatLon(lat0 + it * 10 * mLat, lon0) }
        val (perKm, corners) = Costs.curvature(route(pts))
        assertEquals(0, corners)
        assertTrue(perKm < 5)
    }
}
