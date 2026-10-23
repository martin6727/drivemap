package com.drivemap.app.radar

import android.content.Context
import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.data.SettingsStore
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A place where police often measure speed. Learned from mobile radar / police reports (yours,
 * your convoy's and other DriveMap drivers'): a spot reported on 2+ different days (or 3+ times)
 * counts as "frequent", and stays known after the individual reports have expired.
 */
data class Hotspot(
    val id: String,
    val pos: LatLon,
    /** Direction of the checked traffic, when the reports had one. */
    val bearing: Double?,
    /** Days (epoch day numbers) with at least one report. */
    val days: Set<Int>,
    val count: Int,
    val lastTs: Long,
) {
    val frequent get() = days.size >= 2 || count >= 3

    fun toJson(): JSONObject = JSONObject().put("id", id).put("la", pos.lat).put("lo", pos.lon)
        .put("b", bearing ?: -1.0).put("d", JSONArray(days.sorted())).put("c", count).put("ts", lastTs)

    companion object {
        fun fromJson(o: JSONObject): Hotspot? = try {
            val d = o.getJSONArray("d")
            Hotspot(
                id = o.getString("id"),
                pos = LatLon(o.getDouble("la"), o.getDouble("lo")),
                bearing = o.optDouble("b", -1.0).takeIf { it >= 0 },
                days = (0 until d.length()).map { d.getInt(it) }.toSet(),
                count = o.optInt("c", 1),
                lastTs = o.optLong("ts"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

class HotspotStore(
    context: Context,
    private val hub: MqttHub,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "hotspots.json")
    private val spots = ConcurrentHashMap<String, Hotspot>()
    private val seenReports = LinkedHashSet<String>()
    private val _frequentCount = MutableStateFlow(0)
    /** Number of known frequent spots (shown in Settings). */
    val frequentCount: StateFlow<Int> = _frequentCount
    private var cells: List<String> = emptyList()
    private var active = false
    private var saveJob: Job? = null

    init {
        load()
    }

    /** Frequent spots within [radiusM] of [pos]. */
    fun near(pos: LatLon, radiusM: Double): List<Hotspot> =
        spots.values.filter { it.frequent && Geo.dist(it.pos, pos) <= radiusM }

    /** A mobile radar / police report was made or received: count it for its spot. */
    fun record(r: Report) {
        if (r.type != ReportType.MOBILE_RADAR && r.type != ReportType.POLICE) return
        synchronized(seenReports) {
            if (!seenReports.add(r.id)) return
            if (seenReports.size > 3000) seenReports.remove(seenReports.first())
        }
        val day = (r.ts / 86_400_000L).toInt()
        val existing = closest(r.pos, r.bearing)
        val updated = if (existing == null) {
            Hotspot(Geo.geohash(r.pos.lat, r.pos.lon, 8) + r.id.take(4), r.pos, r.bearing, setOf(day), 1, r.ts)
        } else {
            existing.copy(days = trimDays(existing.days + day), count = existing.count + 1, lastTs = maxOf(existing.lastTs, r.ts))
        }
        spots[updated.id] = updated
        changed(setOf(cellOf(updated)))
    }

    private fun closest(pos: LatLon, bearing: Double?): Hotspot? = spots.values
        .filter { Geo.dist(it.pos, pos) < 250 && sameWay(it.bearing, bearing) }
        .minByOrNull { Geo.dist(it.pos, pos) }

    private fun sameWay(a: Double?, b: Double?) = a == null || b == null || Geo.absAngle(a, b) <= 60

    private fun trimDays(d: Set<Int>) = if (d.size <= 60) d else d.sorted().takeLast(60).toSet()

    private fun cellOf(h: Hotspot) = Geo.geohash(h.pos.lat, h.pos.lon, 4)

    // ---------------------------------------------------------------- sharing (per area cell)

    private fun topic(cell: String) = "dm1/h/$cell"

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) hub.acquire("hotspots") else {
            cells.forEach { hub.unsubscribe(topic(it)) }
            cells = emptyList()
            hub.release("hotspots")
        }
    }

    fun onPosition(pos: LatLon) {
        if (!active || !settings.value.mobileReports) return
        val cell = Geo.geohash(pos.lat, pos.lon, 4)
        if (cells.firstOrNull() == cell) return
        val wanted = listOf(cell) + Geo.geohashWithNeighbours(cell).filter { it != cell }
        for (c in cells) if (c !in wanted) hub.unsubscribe(topic(c))
        for (c in wanted) if (c !in cells) hub.subscribe(topic(c)) { t, payload -> onRemote(t.substringAfterLast('/'), payload) }
        cells = wanted
    }

    private fun onRemote(cell: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        val plain = ConvoyCrypto.decrypt(ConvoyCrypto.publicKey, payload) ?: return
        val arr = try { JSONArray(String(plain)) } catch (_: Exception) { return }
        var learnedNew = false   // remote had something we didn't
        val remoteIds = HashSet<String>()
        for (i in 0 until arr.length()) {
            val h = Hotspot.fromJson(arr.optJSONObject(i) ?: continue) ?: continue
            remoteIds += h.id
            val local = spots[h.id] ?: closest(h.pos, h.bearing)
            if (local == null) {
                spots[h.id] = h
                learnedNew = true
            } else {
                val merged = local.copy(days = trimDays(local.days + h.days), count = maxOf(local.count, h.count), lastTs = maxOf(local.lastTs, h.lastTs))
                if (merged != local) {
                    spots[local.id] = merged
                    learnedNew = true
                }
            }
        }
        if (learnedNew) save()
        // if we know spots in this cell that the shared copy lacks, publish the merged list
        val mine = spots.values.filter { cellOf(it) == cell }
        if (mine.any { it.id !in remoteIds && closestIn(arr, it) == null }) publishCell(cell)
        _frequentCount.value = spots.values.count { it.frequent }
    }

    private fun closestIn(arr: JSONArray, h: Hotspot): Hotspot? {
        for (i in 0 until arr.length()) {
            val r = Hotspot.fromJson(arr.optJSONObject(i) ?: continue) ?: continue
            if (Geo.dist(r.pos, h.pos) < 250 && sameWay(r.bearing, h.bearing)) return r
        }
        return null
    }

    private fun changed(changedCells: Set<String>) {
        _frequentCount.value = spots.values.count { it.frequent }
        save()
        changedCells.forEach { publishCell(it) }
    }

    private fun publishCell(cell: String) {
        if (!settings.value.mobileReports) return
        val list = spots.values.filter { cellOf(it) == cell }.sortedByDescending { it.lastTs }.take(200)
        if (list.isEmpty()) return
        val json = JSONArray(list.map { it.toJson() }).toString().toByteArray()
        // kept on the broker for ~6 months
        hub.publish(topic(cell), ConvoyCrypto.encrypt(ConvoyCrypto.publicKey, json), retained = true, expirySec = 180L * 86_400)
    }

    // ---------------------------------------------------------------- storage

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) Hotspot.fromJson(arr.getJSONObject(i))?.let { spots[it.id] = it }
        } catch (_: Exception) {
        }
        _frequentCount.value = spots.values.count { it.frequent }
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(2000)
            try {
                file.writeText(JSONArray(spots.values.map { it.toJson() }).toString())
            } catch (_: Exception) {
            }
        }
    }
}
