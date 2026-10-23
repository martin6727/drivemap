package com.drivemap.app.radar

import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.data.SettingsStore
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

enum class ReportType(private val en: String, val ttlMin: Int, val phrase: String) {
    MOBILE_RADAR("Mobile radar", 90, "mobile"),
    POLICE("Police check", 60, "police"),
    HAZARD("Hazard", 120, "hazard"),
    ACCIDENT("Accident", 120, "hazard");

    val label: String get() = com.drivemap.app.i18n.t(en)
}


data class Report(
    val id: String,
    val type: ReportType,
    val pos: LatLon,
    /** Direction of travel of the reporter (the side of the road it applies to). */
    val bearing: Double?,
    val ts: Long,
    val by: String,
    val confirms: Int,
    val denies: Int,
    val viaConvoy: Boolean,
) {
    val ageMin get() = ((System.currentTimeMillis() - ts) / 60_000).toInt()
    val expired get() = System.currentTimeMillis() - ts > type.ttlMin * 60_000L

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", type.name).put("la", pos.lat).put("lo", pos.lon)
        .put("b", bearing ?: -1.0).put("ts", ts).put("by", by).put("c", confirms).put("n", denies)

    companion object {
        fun fromJson(o: JSONObject, viaConvoy: Boolean): Report? = try {
            Report(
                id = o.getString("id"),
                type = ReportType.valueOf(o.getString("t")),
                pos = LatLon(o.getDouble("la"), o.getDouble("lo")),
                bearing = o.optDouble("b", -1.0).takeIf { it >= 0 },
                ts = o.getLong("ts"),
                by = o.optString("by"),
                confirms = o.optInt("c"),
                denies = o.optInt("n"),
                viaConvoy = viaConvoy,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Mobile radar / police reports made in the app. They are shared on a public channel (by area,
 * geohash cells) with everyone using DriveMap, and inside your convoy group.
 */
class ReportsRepository(
    private val hub: MqttHub,
    private val settings: SettingsStore,
) {
    private val map = ConcurrentHashMap<String, Report>()
    private val _reports = MutableStateFlow<List<Report>>(emptyList())
    val reports: StateFlow<List<Report>> = _reports
    private var cells: List<String> = emptyList()
    private var active = false

    /** Called for every mobile radar / police report made or received (frequent-spot learning). */
    var onReportSeen: ((Report) -> Unit)? = null

    /** Set by the convoy manager: topic prefix + key of the current group. */
    @Volatile var convoyChannel: Pair<String, SecretKey>? = null

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) hub.acquire("reports") else {
            cells.forEach { hub.unsubscribe(topicFilter(it)) }
            cells = emptyList()
            hub.release("reports")
        }
    }

    private fun topicFilter(cell: String) = "dm1/r/$cell/+"

    fun onPosition(pos: LatLon) {
        if (!active || !settings.value.mobileReports) return
        val cell = Geo.geohash(pos.lat, pos.lon, 4)
        if (cells.firstOrNull() == cell) return
        val wanted = listOf(cell) + Geo.geohashWithNeighbours(cell).filter { it != cell }
        for (c in cells) if (c !in wanted) hub.unsubscribe(topicFilter(c))
        for (c in wanted) if (c !in cells) hub.subscribe(topicFilter(c)) { topic, payload -> onPublic(topic, payload) }
        cells = wanted
        prune()
    }

    private fun onPublic(topic: String, payload: ByteArray) {
        val id = topic.substringAfterLast('/')
        if (payload.isEmpty()) {
            map.remove(id)
            publishState()
            return
        }
        val plain = ConvoyCrypto.decrypt(ConvoyCrypto.publicKey, payload) ?: return
        val r = Report.fromJson(JSONObject(String(plain)), viaConvoy = false) ?: return
        accept(r)
    }

    /** Called by the convoy manager for reports in the group channel. */
    fun onConvoy(id: String, plain: ByteArray?) {
        if (plain == null) {
            map.remove(id)
            publishState()
            return
        }
        val r = Report.fromJson(JSONObject(String(plain)), viaConvoy = true) ?: return
        accept(r)
    }

    private fun accept(r: Report) {
        if (r.expired) return
        val cur = map[r.id]
        if (cur == null || r.ts >= cur.ts) map[r.id] = if (cur?.viaConvoy == true) r.copy(viaConvoy = true) else r
        onReportSeen?.invoke(r)
        publishState()
    }

    fun report(type: ReportType, pos: LatLon, bearing: Double?) {
        val r = Report(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            type = type, pos = pos, bearing = bearing, ts = System.currentTimeMillis(),
            by = settings.value.userName.ifBlank { "DriveMap user" }, confirms = 0, denies = 0,
            viaConvoy = convoyChannel != null,
        )
        map[r.id] = r
        onReportSeen?.invoke(r)
        publishState()
        send(r)
    }

    /** "Still there?" answer when passing a report. */
    fun vote(id: String, stillThere: Boolean) {
        val r = map[id] ?: return
        val updated = if (stillThere) r.copy(ts = System.currentTimeMillis(), confirms = r.confirms + 1) else r.copy(denies = r.denies + 1)
        if (!stillThere && updated.denies >= 2) {
            map.remove(id)
            publishState()
            clear(r)
            return
        }
        map[id] = updated
        publishState()
        send(updated)
    }

    private fun send(r: Report) {
        val json = r.toJson().toString().toByteArray()
        val ttl = (r.type.ttlMin * 60L - (System.currentTimeMillis() - r.ts) / 1000).coerceAtLeast(60)
        if (settings.value.mobileReports) {
            val cell = Geo.geohash(r.pos.lat, r.pos.lon, 4)
            hub.publish("dm1/r/$cell/${r.id}", ConvoyCrypto.encrypt(ConvoyCrypto.publicKey, json), retained = true, expirySec = ttl)
        }
        convoyChannel?.let { (prefix, key) ->
            hub.publish("$prefix/r/${r.id}", ConvoyCrypto.encrypt(key, json), retained = true, expirySec = ttl)
        }
    }

    private fun clear(r: Report) {
        val cell = Geo.geohash(r.pos.lat, r.pos.lon, 4)
        hub.publish("dm1/r/$cell/${r.id}", ByteArray(0), retained = true)
        convoyChannel?.let { (prefix, _) -> hub.publish("$prefix/r/${r.id}", ByteArray(0), retained = true) }
    }

    private fun prune() {
        map.entries.removeIf { it.value.expired }
    }

    private fun publishState() {
        prune()
        _reports.value = map.values.sortedByDescending { it.ts }
    }
}
