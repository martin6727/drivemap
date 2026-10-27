package com.drivemap.app.net

import com.drivemap.app.geo.LatLon
import com.drivemap.app.route.Place
import org.json.JSONArray
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder

/** A search service refused us for a while (HTTP 429) or is in its cool-down. */
class SearchBusyException(service: String) : java.io.IOException("$service is busy")

/**
 * Place search: Photon (komoot) for search-as-you-type, Nominatim (OpenStreetMap's live
 * database) for explicit searches and categories.
 *
 * Kind to the free public services: answers are cached for 10 minutes, Nominatim gets at most one
 * request a second (its usage policy), requests time out quickly instead of hanging, and a
 * "too many requests" answer pauses that service for a while instead of retrying.
 */
object Search {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- cache: same query around the same spot -> same answer, no request
    private const val CACHE_TTL_MS = 10 * 60_000L
    private val cache = object : LinkedHashMap<String, Pair<Long, List<Place>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Place>>>) = size > 80
    }

    private fun cacheKey(service: String, q: String, near: LatLon?, extra: String = ""): String =
        service + "|" + SearchRank.normalize(q) + "|" + (near?.let { "%.2f,%.2f".format(it.lat, it.lon) } ?: "-") + "|" + extra

    private suspend fun cached(key: String, load: suspend () -> List<Place>): List<Place> {
        synchronized(cache) {
            cache[key]?.let { (t, v) -> if (System.currentTimeMillis() - t < CACHE_TTL_MS) return v }
        }
        val v = load()
        synchronized(cache) { cache[key] = System.currentTimeMillis() to v }
        return v
    }

    // ---- politeness: Nominatim allows one request per second; 429 = back off for a while
    private val nominatimGate = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastNominatimMs = 0L
    @Volatile private var photonPauseUntil = 0L
    @Volatile private var nominatimPauseUntil = 0L

    private suspend fun nominatimGet(url: String): String {
        if (System.currentTimeMillis() < nominatimPauseUntil) throw SearchBusyException("Nominatim")
        return nominatimGate.withLock {
            val wait = lastNominatimMs + 1100 - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastNominatimMs = System.currentTimeMillis()
            try {
                Http.get(url, timeoutSec = 8, totalTimeoutSec = 10)
            } catch (e: HttpException) {
                if (e.code == 429 || e.code == 503) nominatimPauseUntil = System.currentTimeMillis() + 60_000
                throw e
            }
        }
    }

    private suspend fun photonGet(url: String): String {
        if (System.currentTimeMillis() < photonPauseUntil) throw SearchBusyException("Photon")
        return try {
            Http.get(url, timeoutSec = 6, totalTimeoutSec = 8)
        } catch (e: HttpException) {
            if (e.code == 429 || e.code == 503) photonPauseUntil = System.currentTimeMillis() + 30_000
            throw e
        }
    }

    /** Search-as-you-type (Photon). Cached; a busy / failing service throws. */
    suspend fun autocomplete(query: String, near: LatLon?, limit: Int = 15): List<Place> =
        cached(cacheKey("photon", query, near, "$limit")) {
            val sb = StringBuilder("https://photon.komoot.io/api/?q=").append(enc(query)).append("&limit=").append(limit)
            if (near != null) sb.append("&lat=").append(near.lat).append("&lon=").append(near.lon).append("&location_bias_scale=0.3")
            val json = JSONObject(photonGet(sb.toString()))
            // Photon returns e.g. the city, its county and its state with the same name: keep the first
            parsePhoton(json).distinctBy { it.name.lowercase() + "|" + it.address.lowercase() }
        }

    /**
     * Full search in OpenStreetMap's live database (Nominatim): finds places added in the last
     * days/weeks that Photon's index doesn't have yet. Only on explicit search (Nominatim's usage
     * policy forbids search-as-you-type).
     */
    suspend fun fullSearch(query: String, near: LatLon?, limit: Int = 10): List<Place> =
        cached(cacheKey("nominatim", query, near, "$limit")) { fullSearchLoad(query, near, limit) }

    private suspend fun fullSearchLoad(query: String, near: LatLon?, limit: Int): List<Place> {
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?q=").append(enc(query))
            .append("&format=jsonv2&addressdetails=1&limit=").append(limit)
        if (near != null) {
            val d = 0.6
            sb.append("&viewbox=").append(near.lon - d).append(',').append(near.lat + d * 0.66).append(',')
                .append(near.lon + d).append(',').append(near.lat - d * 0.66)
        }
        val arr = JSONArray(nominatimGet(sb.toString()))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pos = LatLon(o.optString("lat").toDoubleOrNull() ?: continue, o.optString("lon").toDoubleOrNull() ?: continue)
            val a = o.optJSONObject("address")
            val name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') }
            val road = listOfNotNull(a?.optString("road")?.ifBlank { null }, a?.optString("house_number")?.ifBlank { null }).joinToString(" ")
            val city = a?.optString("city")?.ifBlank { null } ?: a?.optString("town")?.ifBlank { null } ?: a?.optString("village") ?: ""
            out.add(Place(name, pos, listOf(road, city, a?.optString("country") ?: "").filter { it.isNotBlank() }.joinToString(", "),
                o.optString("category") + ":" + o.optString("type")))
        }
        return out
    }

    suspend fun reverse(pos: LatLon): Place? {
        val url = "https://photon.komoot.io/reverse?lat=${pos.lat}&lon=${pos.lon}&limit=1"
        val json = JSONObject(Http.get(url, timeoutSec = 15))
        return parsePhoton(json).firstOrNull()?.copy(pos = pos)
    }

    /** Country code (ISO alpha-2, upper case) at a position, via reverse geocoding. */
    suspend fun countryAt(pos: LatLon): String? {
        val url = "https://photon.komoot.io/reverse?lat=${pos.lat}&lon=${pos.lon}&limit=1"
        val json = JSONObject(Http.get(url, timeoutSec = 15))
        val f = json.optJSONArray("features") ?: return null
        if (f.length() == 0) return null
        return f.getJSONObject(0).optJSONObject("properties")?.optString("countrycode")?.uppercase()?.ifBlank { null }
    }

    private fun parsePhoton(json: JSONObject): List<Place> {
        val out = ArrayList<Place>()
        val features = json.optJSONArray("features") ?: return out
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val p = f.optJSONObject("properties") ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val pos = LatLon(c.getDouble(1), c.getDouble(0))
            val street = p.optString("street")
            val house = p.optString("housenumber")
            val city = p.optString("city").ifBlank { p.optString("town").ifBlank { p.optString("village").ifBlank { p.optString("locality") } } }
            var name = p.optString("name")
            val streetLine = listOf(street, house).filter { it.isNotBlank() }.joinToString(" ")
            if (name.isBlank()) name = streetLine.ifBlank { city.ifBlank { p.optString("county") } }
            val addrParts = ArrayList<String>()
            if (streetLine.isNotBlank() && streetLine != name) addrParts.add(streetLine)
            if (city.isNotBlank() && city != name) addrParts.add(city)
            val country = p.optString("country")
            if (country.isNotBlank()) addrParts.add(country)
            val cat = listOf(p.optString("osm_key"), p.optString("osm_value")).filter { it.isNotBlank() }.joinToString(":")
            out.add(Place(name.ifBlank { "Dropped pin" }, pos, addrParts.joinToString(", "), cat))
        }
        return out
    }

    /** Nearby places of a category ("fuel", "parking", ...) inside a box around [center]. */
    suspend fun category(term: String, center: LatLon, radiusDeg: Double = 0.08): List<Place> =
        categoryIn(term, center.lat - radiusDeg * 0.66, center.lon - radiusDeg, center.lat + radiusDeg * 0.66, center.lon + radiusDeg)

    /** Places of a category inside a box (e.g. around a stretch of the route). */
    suspend fun categoryIn(term: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Place> =
        cached(cacheKey("category", term, null, "%.3f,%.3f,%.3f,%.3f".format(minLat, minLon, maxLat, maxLon))) {
            categoryLoad(term, minLat, minLon, maxLat, maxLon)
        }

    private suspend fun categoryLoad(term: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Place> {
        val vb = "$minLon,$maxLat,$maxLon,$minLat"
        val url = "https://nominatim.openstreetmap.org/search?q=${enc(term)}&viewbox=$vb&bounded=1&format=jsonv2&limit=30&addressdetails=1"
        val arr = JSONArray(nominatimGet(url))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pos = LatLon(o.optString("lat").toDoubleOrNull() ?: continue, o.optString("lon").toDoubleOrNull() ?: continue)
            val a = o.optJSONObject("address")
            val name = o.optString("name").ifBlank { a?.optString("brand") ?: "" }.ifBlank { term.replaceFirstChar { it.uppercase() } }
            val road = a?.optString("road") ?: ""
            val city = a?.optString("city")?.ifBlank { null } ?: a?.optString("town")?.ifBlank { null } ?: a?.optString("village") ?: ""
            val addr = listOf(road, city).filter { it.isNotBlank() }.joinToString(", ")
            out.add(Place(name, pos, addr, o.optString("category") + ":" + o.optString("type")))
        }
        return out
    }
}
