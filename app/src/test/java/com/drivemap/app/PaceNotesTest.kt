package com.drivemap.app

import com.drivemap.app.geo.LatLon
import com.drivemap.app.nav.PaceNotes
import com.drivemap.app.route.Route
import com.drivemap.app.route.RouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pace notes from road shapes drawn like a turtle: straights and arcs of known radius. */
class PaceNotesTest {
    private val lat0 = 48.3
    private val lon0 = 18.0
    private val mLat = 1.0 / 111_320.0
    private val mLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))

    /** Each piece: (length m, total turn deg; + = right). Points every 2 m. */
    private fun road(vararg pieces: Pair<Double, Double>): Route {
        val pts = ArrayList<LatLon>()
        var x = 0.0
        var y = 0.0
        var h = 0.0
        pts += LatLon(lat0, lon0)
        for ((len, turn) in pieces) {
            val steps = (len / 2.0).toInt().coerceAtLeast(1)
            val dh = turn / steps
            repeat(steps) {
                h += dh
                x += Math.sin(Math.toRadians(h)) * 2.0
                y += Math.cos(Math.toRadians(h)) * 2.0
                pts += LatLon(lat0 + y * mLat, lon0 + x * mLon)
            }
        }
        val g = RouteGeometry()
        g.shape.addAll(pts)
        return Route(g, "test", emptyList())
    }

    private fun arc(radius: Double, deg: Double) = radius * Math.toRadians(Math.abs(deg)) to deg
    private fun straight(m: Double) = m to 0.0

    @Test
    fun straightRoadHasNoNotes() {
        assertEquals(0, PaceNotes.bends(road(straight(1500.0))).size)
    }

    @Test
    fun gentleBendIsGradeFiveRight() {
        val b = PaceNotes.bends(road(straight(200.0), arc(150.0, 60.0), straight(200.0)))
        assertEquals(1, b.size)
        assertTrue(!b[0].left)
        assertEquals(5, b[0].grade)
    }

    @Test
    fun tightNinetyIsGradeOneLeft() {
        val b = PaceNotes.bends(road(straight(200.0), arc(20.0, -90.0), straight(200.0)))
        assertEquals(1, b.size)
        assertTrue(b[0].left)
        assertEquals(1, b[0].grade)
    }

    @Test
    fun hairpinsAreHairpinsAndAlternate() {
        val pieces = ArrayList<Pair<Double, Double>>()
        pieces += straight(150.0)
        for (k in 0 until 6) { pieces += arc(12.0, if (k % 2 == 0) 180.0 else -180.0); pieces += straight(150.0) }
        val b = PaceNotes.bends(road(*pieces.toTypedArray()))
        assertEquals(6, b.size)
        assertTrue(b.all { it.grade == 0 })
        assertTrue((0 until 5).all { b[it].left != b[it + 1].left })
    }

    @Test
    fun sCurveGivesTwoLinkedBends() {
        val b = PaceNotes.bends(road(straight(200.0), arc(60.0, -70.0), arc(60.0, 70.0), straight(200.0)))
        assertEquals(2, b.size)
        assertTrue(b[0].left && !b[1].left)
        assertTrue(b.all { it.grade == 3 })
        assertTrue("linked: gap ${b[1].startM - b[0].endM}", b[1].startM - b[0].endM < 40)
    }

    @Test
    fun tighteningBendIsCalled() {
        // opens wide (radius 200) and closes to 40: "tightens"
        val b = PaceNotes.bends(road(straight(200.0), arc(200.0, 25.0), arc(100.0, 30.0), arc(40.0, 45.0), straight(200.0)))
        assertEquals(1, b.size)
        assertTrue(b[0].tightens)
        assertEquals(2, b[0].grade)
    }

    @Test
    fun smallKinksAreIgnored() {
        assertEquals(0, PaceNotes.bends(road(straight(300.0), arc(100.0, 12.0), straight(300.0))).size)
    }
}
