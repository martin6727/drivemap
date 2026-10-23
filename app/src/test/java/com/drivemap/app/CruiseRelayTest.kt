package com.drivemap.app

import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.geo.LatLon
import com.drivemap.app.route.CruiseInvite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live test of cruise challenges: joins convoy CONVOY_CODE as "Peter" (parked in Nitra), sends a
 * cruise challenge to everyone, then for CRUISE_WAIT_S seconds prints answers and auto-accepts
 * challenges sent to Peter. Skipped unless CONVOY_CODE is set.
 */
class CruiseRelayTest {
    @Test
    fun peterChallenges() {
        val code = System.getenv("CONVOY_CODE")
        assumeTrue("set CONVOY_CODE to run", !code.isNullOrBlank())
        val hub = MqttHub(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { "ssl://broker.hivemq.com:8883" }
        hub.acquire("test")
        val key = ConvoyCrypto.deriveKey(code!!)
        val p = "dm1/c/" + ConvoyCrypto.topicId(code)
        val myId = "peter0test01"
        val start = LatLon(48.3080, 18.0900)
        hub.subscribe("$p/#") { topic, payload ->
            if (payload.isEmpty()) return@subscribe
            val plain = ConvoyCrypto.decrypt(key, payload) ?: return@subscribe
            val rest = topic.removePrefix("$p/")
            if (rest.startsWith("cra/")) println("ANSWER $rest: ${String(plain)}")
            if (rest.startsWith("cr/")) {
                val inv = CruiseInvite.fromJson(JSONObject(String(plain))) ?: return@subscribe
                if (inv.fromId != myId && (inv.toId == null || inv.toId == myId) && System.currentTimeMillis() - inv.ts < 120_000) {
                    println("PETER GOT CHALLENGE from ${inv.fromName}: ${inv.minutes} min, scenic=${inv.scenic}, ${inv.loop.size} loop points")
                    val o = JSONObject().put("a", true).put("n", "Peter").put("ts", System.currentTimeMillis())
                    hub.publish("$p/cra/${inv.id}/$myId", ConvoyCrypto.encrypt(key, o.toString().toByteArray()), retained = true, expirySec = 600)
                }
            }
        }
        var waited = 0
        while (!hub.connected.value && waited < 300) { Thread.sleep(100); waited++ }
        assertTrue("broker connection", hub.connected.value)
        Thread.sleep(1500)
        val waitS = (System.getenv("CRUISE_WAIT_S") ?: "60").toInt()
        val end = System.currentTimeMillis() + waitS * 1000L
        var sent = false
        while (System.currentTimeMillis() < end) {
            val now = System.currentTimeMillis()
            val me = JSONObject().put("id", myId).put("name", "Peter").put("la", start.lat).put("lo", start.lon)
                .put("b", 0.0).put("v", 0.0).put("ts", now)
            hub.publish("$p/m/$myId", ConvoyCrypto.encrypt(key, me.toString().toByteArray()), retained = true, expirySec = 300)
            if (!sent && System.getenv("CRUISE_SEND") == "join") {
                sent = true
                val inv = CruiseInvite("j" + now.toString().takeLast(8), myId, "Peter", null, 20, false, LatLon(48.2440, 18.3100), emptyList(), now, destName = "Lidl Vráble")
                hub.publish("$p/cr/${inv.id}", ConvoyCrypto.encrypt(key, inv.toJson().toString().toByteArray()), retained = true, expirySec = 900)
                println("SENT join ${inv.id}")
            }
            if (!sent && System.getenv("CRUISE_SEND") == "1") {
                sent = true
                val loop = listOf(LatLon(48.36, 18.07), LatLon(48.33, 18.17), LatLon(48.27, 18.12))
                val inv = CruiseInvite("t" + now.toString().takeLast(8), myId, "Peter", null, 60, true, start, loop, now)
                hub.publish("$p/cr/${inv.id}", ConvoyCrypto.encrypt(key, inv.toJson().toString().toByteArray()), retained = true, expirySec = 900)
                println("SENT challenge ${inv.id}")
            }
            Thread.sleep(3000)
        }
        hub.publish("$p/m/$myId", ByteArray(0), retained = true)
        Thread.sleep(1000)
    }
}
