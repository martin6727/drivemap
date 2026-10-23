package com.drivemap.app

import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.geo.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Maintenance: deletes fuel price reports made by FUEL_CLEAN_BY around FUEL_CLEAN_AT ("lat,lon")
 * from the shared relay (e.g. test reports). Skipped unless both are set.
 */
class FuelReportCleanupTest {
    @Test
    fun deleteReports() {
        val by = System.getenv("FUEL_CLEAN_BY")
        val at = System.getenv("FUEL_CLEAN_AT")
        assumeTrue("set FUEL_CLEAN_BY and FUEL_CLEAN_AT", !by.isNullOrBlank() && !at.isNullOrBlank())
        val (lat, lon) = at!!.split(",").map { it.trim().toDouble() }
        val hub = MqttHub(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { "ssl://broker.hivemq.com:8883" }
        hub.acquire("cleanup")
        val center = Geo.geohash(lat, lon, 5)
        val cells = listOf(center) + Geo.geohashWithNeighbours(center).filter { it != center }
        val found = ConcurrentHashMap<String, String>()
        for (c in cells) hub.subscribe("dm1/f/$c/+") { topic, payload ->
            if (payload.isEmpty()) return@subscribe
            val plain = ConvoyCrypto.decrypt(ConvoyCrypto.publicKey, payload) ?: return@subscribe
            val o = JSONObject(String(plain))
            if (o.optString("by") == by) found[topic] = o.optString("n") + " " + o.optDouble("p") + "/" + o.optDouble("d")
        }
        var waited = 0
        while (!hub.connected.value && waited < 300) { Thread.sleep(100); waited++ }
        assertTrue("broker connection", hub.connected.value)
        Thread.sleep(4000)
        println("reports by $by: $found")
        for (t in found.keys) hub.publish(t, ByteArray(0), retained = true)
        Thread.sleep(2000)
        println("deleted ${found.size}")
    }
}
