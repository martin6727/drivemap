package com.drivemap.app

import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.geo.LatLon
import com.drivemap.app.geo.PolylinePath
import com.drivemap.app.net.RouteLocation
import com.drivemap.app.net.Valhalla
import com.drivemap.app.route.CruiseInvite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live demo of a fake convoy member (DRIVE_NAME, default "Trabalik") posting positions like a phone:
 *  - DRIVE_TO set: drives the real route from DRIVE_FROM to DRIVE_TO right away;
 *  - otherwise: parks at DRIVE_FROM, waits for a challenge / "drive with me", accepts it and drives
 *    the route it carries (same pinned points as the real app), at the road's normal speeds.
 * Skipped unless CONVOY_CODE is set. DRIVE_DELAY_S, DRIVE_MAX_S, DRIVE_SKIN tune it.
 */
class ConvoyDriveTest {
    @Test
    fun memberDrives() {
        val code = System.getenv("CONVOY_CODE")
        assumeTrue("set CONVOY_CODE", !code.isNullOrBlank())
        fun ll(s: String) = s.split(",").let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) }
        val name = System.getenv("DRIVE_NAME") ?: "Trabalik"
        val myId = name.lowercase().filter { it.isLetterOrDigit() }.padEnd(8, '0').take(8) + "test"
        val home = ll(System.getenv("DRIVE_FROM") ?: "48.3075,18.0905")
        val valhalla = Valhalla({ "https://valhalla1.openstreetmap.de" })

        val hub = MqttHub(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { "ssl://broker.hivemq.com:8883" }
        hub.acquire("test")
        val key = ConvoyCrypto.deriveKey(code!!)
        val p = "dm1/c/" + ConvoyCrypto.topicId(code)

        val routeRef = java.util.concurrent.atomic.AtomicReference<Pair<PolylinePath, Double>?>(null) // path + metres per second
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val here = java.util.concurrent.atomic.AtomicReference(home) // where he is right now
        val restart = java.util.concurrent.atomic.AtomicBoolean(false)
        fun plan(stops: List<RouteLocation>): Pair<PolylinePath, Double> = runBlocking {
            val g = valhalla.route(stops, "auto", JSONObject(), "en-US").first()
            val path = PolylinePath(g.shape)
            path to path.length / g.timeS
        }

        hub.subscribe("$p/#") { topic, payload ->
            if (payload.isEmpty() || accepted.get()) return@subscribe
            val rest = topic.removePrefix("$p/")
            if (!rest.startsWith("cr/")) return@subscribe
            val plain = ConvoyCrypto.decrypt(key, payload) ?: return@subscribe
            val inv = CruiseInvite.fromJson(JSONObject(String(plain))) ?: return@subscribe
            if (inv.fromId == myId || (inv.toId != null && inv.toId != myId) || System.currentTimeMillis() - inv.ts > 120_000) return@subscribe
            println("$name GOT INVITE from ${inv.fromName} to ${inv.destName ?: "loop"} with ${inv.loop.size} route points")
            val o = JSONObject().put("a", true).put("n", name).put("ts", System.currentTimeMillis())
            accepted.set(true)
            hub.publish("$p/cra/${inv.id}/$myId", ConvoyCrypto.encrypt(key, o.toString().toByteArray()), retained = true, expirySec = 600)
            // accept and go: the same stops the app would use
            val stops = listOf(RouteLocation(here.get())) +
                inv.loop.mapIndexed { i, pt -> RouteLocation(pt, heading = inv.headings.getOrNull(i), through = true) } +
                RouteLocation(inv.end ?: inv.start)
            val r = plan(stops)
            routeRef.set(r)
            restart.set(true)
            println("$name DRIVING ${"%.1f".format(r.first.length / 1000)} km at ${"%.0f".format(r.second * 3.6)} km/h average")
        }
        var waited = 0
        while (!hub.connected.value && waited < 300) { Thread.sleep(100); waited++ }
        assertTrue("broker connection", hub.connected.value)

        System.getenv("DRIVE_TO")?.let { routeRef.set(plan(listOf(RouteLocation(home), RouteLocation(ll(it))))) }

        fun post(pos: LatLon, bearing: Double, kmh: Double) {
            val me = JSONObject().put("id", myId).put("name", name).put("la", pos.lat).put("lo", pos.lon)
                .put("b", bearing).put("v", kmh).put("sk", System.getenv("DRIVE_SKIN") ?: "sport").put("ts", System.currentTimeMillis())
            hub.publish("$p/m/$myId", ConvoyCrypto.encrypt(key, me.toString().toByteArray()), retained = true, expirySec = 300)
        }

        val delayS = (System.getenv("DRIVE_DELAY_S") ?: "3").toLong()
        val end = System.currentTimeMillis() + (System.getenv("DRIVE_MAX_S") ?: "240").toLong() * 1000
        var t0 = 0L
        while (System.currentTimeMillis() < end) {
            val r = routeRef.get()
            if (r == null) {
                post(home, 0.0, 0.0) // parked, waiting
            } else {
                if (t0 == 0L) t0 = System.currentTimeMillis() + delayS * 1000
                if (restart.getAndSet(false)) t0 = System.currentTimeMillis() // switched to the host route: go on from here
                val (path, speed) = r
                val along = ((System.currentTimeMillis() - t0) / 1000.0 * speed).coerceIn(0.0, path.length)
                val moving = System.currentTimeMillis() > t0 && along < path.length
                here.set(path.pointAt(along))
                post(path.pointAt(along), path.bearingAt(along), if (moving) speed * 3.6 else 0.0)
                if (along >= path.length) break
            }
            Thread.sleep(1000)
        }
        hub.publish("$p/m/$myId", ByteArray(0), retained = true)
        Thread.sleep(1000)
    }
}
