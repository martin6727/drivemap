package com.drivemap.app

import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Live relay test: joins the convoy given in env CONVOY_CODE as a fake member "Peter", drives a
 * short loop near Bratislava, adds a convoy stop, and checks it can decrypt the other members.
 * Skipped unless CONVOY_CODE is set.
 */
class ConvoyRelayTest {

    @Test
    fun fakeMemberDrivesInConvoy() {
        val code = System.getenv("CONVOY_CODE")
        assumeTrue("set CONVOY_CODE to run", !code.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hub = MqttHub(scope) { System.getenv("MQTT_URL") ?: "ssl://broker.hivemq.com:8883" }
        hub.acquire("test")
        val key = ConvoyCrypto.deriveKey(code!!)
        val prefix = "dm1/c/" + ConvoyCrypto.topicId(code)
        val seen = ConcurrentHashMap<String, String>()
        hub.subscribe("$prefix/#") { topic, payload ->
            if (topic.startsWith("$prefix/m/") && payload.isNotEmpty()) {
                val plain = ConvoyCrypto.decrypt(key, payload) ?: return@subscribe
                val o = JSONObject(String(plain))
                seen[o.getString("id")] = o.optString("name") + " @ " + o.optDouble("la") + "," + o.optDouble("lo")
            }
        }
        var waited = 0
        while (!hub.connected.value && waited < 300) { Thread.sleep(100); waited++ }
        assertTrue("broker connection", hub.connected.value)
        Thread.sleep(1500) // let subscription settle

        val myId = "peter0test01"
        // drive east along Bratislava's riverside, ~8 m/s
        val start = doubleArrayOf(48.1405, 17.1040)
        val end = doubleArrayOf(48.1418, 17.1300)
        val steps = 22
        for (i in 0..steps) {
            val t = i / steps.toDouble()
            val lat = start[0] + (end[0] - start[0]) * t
            val lon = start[1] + (end[1] - start[1]) * t
            val o = JSONObject().put("id", myId).put("name", "Peter").put("la", lat).put("lo", lon)
                .put("b", 85.0).put("v", 48.0).put("ts", System.currentTimeMillis())
                .put("dest", "Vienna").put("eta", System.currentTimeMillis() + 3_600_000).put("rem", 61.0)
            hub.publish("$prefix/m/$myId", ConvoyCrypto.encrypt(key, o.toString().toByteArray()), retained = true, expirySec = 900)
            if (i == 6) {
                val stops = JSONArray().put(
                    JSONObject().put("id", "teststop01").put("n", "Shell Einsteinova").put("la", 48.1336).put("lo", 17.1112)
                        .put("by", myId).put("bn", "Peter").put("ts", System.currentTimeMillis()).put("rm", false)
                )
                hub.publish("$prefix/s", ConvoyCrypto.encrypt(key, stops.toString().toByteArray()), retained = true, expirySec = 3600)
            }
            Thread.sleep(2000)
        }
        println("members seen from the app: $seen")
        assertTrue("received another member's encrypted position", seen.keys.any { it != myId })
        hub.release("test")
        scope.cancel()
    }
}
