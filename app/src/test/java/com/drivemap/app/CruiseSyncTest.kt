package com.drivemap.app

import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.RouteLocation
import com.drivemap.app.net.Valhalla
import com.drivemap.app.route.CruiseInvite
import com.drivemap.app.route.CruisePlanner
import com.drivemap.app.route.Route
import com.drivemap.app.route.pinToRoute
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live: plans a challenge loop like the sender, pins it to 7 points with headings like
 * sendCruise, routes those like the receiver (from somewhere else, via a meeting point) and checks
 * the loop part follows the sender's roads. Skipped unless LIVE=1.
 */
class CruiseSyncTest {
    @Test
    fun receiverGetsSenderRoute() = runBlocking {
        assumeTrue("set LIVE=1", System.getenv("LIVE") == "1")
        val v = Valhalla { "https://valhalla1.openstreetmap.de" }
        val opts = JSONObject().put("use_highways", 0.0).put("use_tolls", 0.0)
        val start = LatLon(48.3080, 18.0900)
        val loop = CruisePlanner.plan(v, start, 45, true, "en-US")
        suspend fun route(stops: List<RouteLocation>) = Route(v.route(stops, "auto", opts, "en-US").first(), "x", emptyList())
        val sender = route(listOf(RouteLocation(start)) + loop.map { RouteLocation(it, through = true) } + RouteLocation(start))
        val inv = pinToRoute(CruiseInvite("t", "a", "A", null, 45, true, start, loop, 0), sender)
        // the receiver starts 3 km away and drives to the meeting point first
        val elsewhere = LatLon(48.33, 18.11)
        val receiver = route(
            listOf(RouteLocation(elsewhere), RouteLocation(start)) +
                inv.loop.mapIndexed { i, p -> RouteLocation(p, heading = inv.headings[i], through = true) } + RouteLocation(start),
        )
        // share of the sender's loop (sampled every 100 m) that the receiver's route covers within 30 m
        val sp = sender.path
        val rp = receiver.path
        val rPts = (0 until (rp.length / 20).toInt()).map { rp.pointAt(it * 20.0) }
        val samples = (0 until (sp.length / 100).toInt()).map { sp.pointAt(it * 100.0) }
        val covered = samples.count { s -> rPts.any { Geo.dist(it, s) < 30 } }
        val share = covered.toDouble() / samples.size
        println("sender ${"%.1f".format(sp.length / 1000)} km, receiver ${"%.1f".format(rp.length / 1000)} km, same roads ${"%.0f".format(share * 100)} %")
        assertTrue("receiver follows the sender's loop", share > 0.95)
    }
}
