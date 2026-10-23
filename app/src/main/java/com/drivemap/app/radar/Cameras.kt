package com.drivemap.app.radar

import android.content.Context
import android.util.Log
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.geo.PolylinePath
import com.drivemap.app.net.Overpass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

enum class CamType { SPEED, RED_LIGHT, SECTION_START, SECTION_END }

data class Camera(
    val id: Long,
    /** Position used for alerts: on the road when known (enforcement "from" node), else the device. */
    val pos: LatLon,
    val type: CamType,
    val maxspeed: Int?,
    /** Direction of the checked traffic (from enforcement relation), degrees. */
    val travelDir: Double? = null,
    /** Direction the camera faces (direction=* on the node), degrees. */
    val facing: Double? = null,
    val section: Section? = null,
)

/** Average speed (section control) zone between [start] and [end], checked in direction start→end. */
data class Section(
    val id: Long,
    val start: LatLon,
    val end: LatLon,
    val maxspeed: Int?,
) {
    val straightM get() = Geo.dist(start, end)
    val dir get() = Geo.bearing(start, end)
}

/** Camera that lies on a route, with its distance along the route. */
data class RouteCamera(val camera: Camera, val alongM: Double)

/**
 * Fixed speed cameras and section controls from OpenStreetMap.
 * A snapshot for Central Europe ships in assets; 1°×1° tiles are refreshed from Overpass when used.
 */
class CameraRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tiles = ConcurrentHashMap<String, List<Camera>>()
    private val tileAge = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val fetchLock = Mutex()
    private val dir = File(context.filesDir, "cams").apply { mkdirs() }
    private val _version = MutableStateFlow(0)
    /** Bumped whenever camera data changes (map layer refresh). */
    val version: StateFlow<Int> = _version
    @Volatile var loaded = false
        private set

    fun start() {
        scope.launch {
            loadBundle()
            loadCache()
            loaded = true
            _version.value++
        }
    }

    private fun key(lat: Double, lon: Double) = "${floor(lat).toInt()}_${floor(lon).toInt()}"

    private fun loadBundle() {
        val am = context.assets
        val bundleTime = try {
            am.open("cameras/meta.txt").bufferedReader().readText().trim().toLong()
        } catch (_: Exception) {
            0L
        }
        val files = try { am.list("cameras")?.filter { it.endsWith(".json") } ?: emptyList() } catch (_: Exception) { emptyList() }
        val grouped = HashMap<String, MutableList<Camera>>()
        val seen = HashSet<Long>()
        for (f in files) {
            try {
                val text = am.open("cameras/$f").bufferedReader().readText()
                // country snapshots can overlap at borders: keep each camera once
                for (c in parse(text)) if (seen.add(c.id)) grouped.getOrPut(key(c.pos.lat, c.pos.lon)) { ArrayList() }.add(c)
            } catch (e: Exception) {
                Log.w("Cameras", "bundle $f: $e")
            }
        }
        for ((k, list) in grouped) {
            tiles[k] = list
            tileAge[k] = bundleTime
        }
    }

    private fun loadCache() {
        dir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".json")) return@forEach
            val k = f.name.removeSuffix(".json")
            try {
                tiles[k] = parse(f.readText())
                tileAge[k] = f.lastModified()
            } catch (_: Exception) {
            }
        }
    }

    /** Parses an Overpass JSON answer (nodes + enforcement relations). */
    fun parse(text: String): List<Camera> {
        val els = JSONObject(text).optJSONArray("elements") ?: return emptyList()
        val nodes = HashMap<Long, JSONObject>()
        val rels = ArrayList<JSONObject>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            when (e.optString("type")) {
                "node" -> nodes[e.getLong("id")] = e
                "relation" -> rels.add(e)
            }
        }
        fun pos(n: JSONObject) = LatLon(n.getDouble("lat"), n.getDouble("lon"))
        val out = ArrayList<Camera>()
        val usedDevices = HashSet<Long>()
        for (r in rels) {
            val tags = r.optJSONObject("tags") ?: continue
            val kind = tags.optString("enforcement")
            val members = r.optJSONArray("members") ?: continue
            var from: LatLon? = null
            var to: LatLon? = null
            var device: LatLon? = null
            val devices = ArrayList<Long>()
            for (j in 0 until members.length()) {
                val m = members.getJSONObject(j)
                if (m.optString("type") != "node") continue
                val n = nodes[m.getLong("ref")] ?: continue
                when (m.optString("role")) {
                    "from" -> if (from == null) from = pos(n)
                    "to" -> to = pos(n)
                    "device" -> {
                        if (device == null) device = pos(n)
                        devices.add(m.getLong("ref"))
                    }
                }
            }
            val relLimit = parseSpeed(tags.optString("maxspeed"))
            val devLimit = devices.firstNotNullOfOrNull { id -> nodes[id]?.optJSONObject("tags")?.optString("maxspeed")?.let { parseSpeed(it) } }
            val limit = relLimit ?: devLimit
            when (kind) {
                "average_speed" -> {
                    val s = from ?: device ?: continue
                    val e = to ?: continue
                    val sec = Section(r.getLong("id"), s, e, limit)
                    out.add(Camera(r.getLong("id") * 10 + 1, s, CamType.SECTION_START, limit, sec.dir, null, sec))
                    out.add(Camera(r.getLong("id") * 10 + 2, e, CamType.SECTION_END, limit, sec.dir, null, sec))
                    usedDevices.addAll(devices)
                }
                "maxspeed", "traffic_signals", "mindistance" -> {
                    val at = from ?: device ?: continue
                    val target = to ?: device
                    val dir = if (target != null && Geo.dist(at, target) > 5) Geo.bearing(at, target) else null
                    val type = if (kind == "traffic_signals") CamType.RED_LIGHT else CamType.SPEED
                    out.add(Camera(r.getLong("id") * 10, at, type, limit, dir, null))
                    usedDevices.addAll(devices)
                }
            }
        }
        for ((id, n) in nodes) {
            if (id in usedDevices) continue
            val tags = n.optJSONObject("tags") ?: continue
            if (tags.optString("highway") != "speed_camera") continue
            out.add(
                Camera(
                    id = id * 10 + 5,
                    pos = pos(n),
                    type = CamType.SPEED,
                    maxspeed = parseSpeed(tags.optString("maxspeed")),
                    travelDir = null,
                    facing = parseDirection(tags.optString("direction")),
                )
            )
        }
        return out
    }

    private fun parseSpeed(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val n = s.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return if (s.contains("mph")) (n * 1.609).toInt() else n.takeIf { it in 5..200 }
    }

    private val cardinals = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5, "E" to 90.0, "ESE" to 112.5, "SE" to 135.0,
        "SSE" to 157.5, "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5, "W" to 270.0,
        "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )

    private fun parseDirection(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        s.trim().toDoubleOrNull()?.let { return Geo.normalizeDeg(it) }
        return cardinals[s.trim().uppercase()]
    }

    // ---------------------------------------------------------------- queries

    fun near(p: LatLon, radiusM: Double): List<Camera> {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * Math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.2))
        val out = ArrayList<Camera>()
        for (la in floor(p.lat - dLat).toInt()..floor(p.lat + dLat).toInt()) {
            for (lo in floor(p.lon - dLon).toInt()..floor(p.lon + dLon).toInt()) {
                val list = tiles["${la}_$lo"] ?: continue
                for (c in list) if (Math.abs(c.pos.lat - p.lat) < dLat && Math.abs(c.pos.lon - p.lon) < dLon && Geo.dist(c.pos, p) <= radiusM) out.add(c)
            }
        }
        return out
    }

    fun inBounds(s: Double, w: Double, n: Double, e: Double, limit: Int = 3000): List<Camera> {
        val out = ArrayList<Camera>()
        for (la in floor(s).toInt()..floor(n).toInt()) for (lo in floor(w).toInt()..floor(e).toInt()) {
            val list = tiles["${la}_$lo"] ?: continue
            for (c in list) {
                if (c.pos.lat in s..n && c.pos.lon in w..e) {
                    out.add(c)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    /**
     * Cameras on [path] that check traffic going our way. Uses a coarse grid over the route
     * segments so long routes stay fast.
     */
    fun alongRoute(path: PolylinePath, maxOffsetM: Double = 35.0): List<RouteCamera> {
        val pts = path.points
        if (pts.size < 2) return emptyList()
        val cell = 0.01
        val grid = HashMap<Long, MutableList<Int>>()
        fun ck(la: Int, lo: Int) = (la.toLong() shl 32) or (lo.toLong() and 0xffffffffL)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val la0 = floor(minOf(a.lat, b.lat) / cell).toInt()
            val la1 = floor(maxOf(a.lat, b.lat) / cell).toInt()
            val lo0 = floor(minOf(a.lon, b.lon) / cell).toInt()
            val lo1 = floor(maxOf(a.lon, b.lon) / cell).toInt()
            for (la in la0..la1) for (lo in lo0..lo1) grid.getOrPut(ck(la, lo)) { ArrayList(2) }.add(i)
        }
        val bb = Geo.bounds(pts)
        val out = ArrayList<RouteCamera>()
        val seenSections = HashSet<Long>()
        for (c in inBounds(bb[0] - 0.01, bb[1] - 0.01, bb[2] + 0.01, bb[3] + 0.01, limit = 50_000)) {
            val la = floor(c.pos.lat / cell).toInt()
            val lo = floor(c.pos.lon / cell).toInt()
            var bestD = Double.MAX_VALUE
            var bestSeg = -1
            var bestT = 0.0
            for (dla in -1..1) for (dlo in -1..1) {
                val segs = grid[ck(la + dla, lo + dlo)] ?: continue
                for (i in segs) {
                    val (t, d) = Geo.projectOnSegment(c.pos, pts[i], pts[i + 1])
                    if (d < bestD) { bestD = d; bestSeg = i; bestT = t }
                }
            }
            if (bestSeg < 0 || bestD > maxOffsetM) continue
            val routeDir = Geo.bearing(pts[bestSeg], pts[bestSeg + 1])
            if (!appliesTo(c, routeDir)) continue
            val along = path.cum[bestSeg] + (path.cum[bestSeg + 1] - path.cum[bestSeg]) * bestT
            out.add(RouteCamera(c, along))
            c.section?.let { seenSections.add(it.id) }
        }
        out.sortBy { it.alongM }
        return out
    }

    /** True if the camera checks traffic travelling with bearing [travel]. */
    fun appliesTo(c: Camera, travel: Double): Boolean {
        c.travelDir?.let { return Geo.absAngle(travel, it) <= 60 }
        c.facing?.let {
            // A camera facing across our road is on a side street. Facing towards us or away is OK
            // (most cameras photograph oncoming traffic, some from behind).
            val d = Geo.absAngle(travel, it)
            return d <= 60 || d >= 120
        }
        return true
    }

    // ---------------------------------------------------------------- refresh

    /** Makes sure tiles covering [s,w,n,e] are present and fresh; fetches missing ones from Overpass. */
    fun ensure(s: Double, w: Double, n: Double, e: Double, maxTiles: Int = 30) {
        val keys = ArrayList<String>()
        for (la in floor(s).toInt()..floor(n).toInt()) for (lo in floor(w).toInt()..floor(e).toInt()) keys.add("${la}_$lo")
        val now = System.currentTimeMillis()
        val stale = keys.filter { k ->
            val age = tileAge[k]
            age == null || now - age > 14L * 24 * 3600 * 1000
        }.take(maxTiles)
        for (k in stale) {
            if (!inFlight.add(k)) continue
            scope.launch {
                try {
                    fetchLock.withLock { fetchTile(k) }
                } finally {
                    inFlight.remove(k)
                }
            }
        }
    }

    suspend fun ensureNow(s: Double, w: Double, n: Double, e: Double, maxTiles: Int = 30) {
        ensure(s, w, n, e, maxTiles)
        // wait for in-flight fetches of these tiles
        var waited = 0
        while (inFlight.isNotEmpty() && waited < 400) {
            kotlinx.coroutines.delay(50)
            waited++
        }
    }

    fun tilesMissing(s: Double, w: Double, n: Double, e: Double): Boolean {
        for (la in floor(s).toInt()..floor(n).toInt()) for (lo in floor(w).toInt()..floor(e).toInt()) {
            if (!tileAge.containsKey("${la}_$lo")) return true
        }
        return false
    }

    private suspend fun fetchTile(k: String) {
        val (la, lo) = k.split("_").map { it.toInt() }
        val bbox = "$la,$lo,${la + 1},${lo + 1}"
        val q = "[out:json][timeout:60];(node[\"highway\"=\"speed_camera\"]($bbox);" +
            "rel[\"type\"=\"enforcement\"][\"enforcement\"~\"^(maxspeed|average_speed|mindistance|traffic_signals)$\"]($bbox);)->.a;" +
            "(.a;node(r.a););out body qt;"
        try {
            val text = Overpass.query(q, attempts = 3)
            val list = parse(text)
            File(dir, "$k.json").writeText(text)
            tiles[k] = list
            tileAge[k] = System.currentTimeMillis()
            _version.value++
        } catch (e: Exception) {
            Log.w("Cameras", "tile $k failed: ${e.message}")
            // remember the failure for a while so we don't hammer Overpass
            if (!tileAge.containsKey(k)) tileAge[k] = System.currentTimeMillis() - 13L * 24 * 3600 * 1000
        }
    }
}
