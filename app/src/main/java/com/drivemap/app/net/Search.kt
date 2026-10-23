package com.drivemap.app.net

import com.drivemap.app.geo.LatLon
import com.drivemap.app.route.Place
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Place search: Photon (komoot) for autocomplete, Nominatim for category search. */
object Search {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun autocomplete(query: String, near: LatLon?, limit: Int = 10): List<Place> {
        val sb = StringBuilder("https://photon.komoot.io/api/?q=").append(enc(query)).append("&limit=").append(limit)
        if (near != null) sb.append("&lat=").append(near.lat).append("&lon=").append(near.lon).append("&location_bias_scale=0.3")
        val json = JSONObject(Http.get(sb.toString(), timeoutSec = 15))
        // Photon returns e.g. the city, its county and its state with the same name: keep the first
        return parsePhoton(json).distinctBy { it.name.lowercase() + "|" + it.address.lowercase() }
    }

    /**
     * Full search in OpenStreetMap's live database (Nominatim): finds places added in the last
     * days/weeks that Photon's index doesn't have yet. Only on explicit search (Nominatim's usage
     * policy forbids search-as-you-type).
     */
    suspend fun fullSearch(query: String, near: LatLon?, limit: Int = 10): List<Place> {
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?q=").append(enc(query))
            .append("&format=jsonv2&addressdetails=1&limit=").append(limit)
        if (near != null) {
            val d = 0.6
            sb.append("&viewbox=").append(near.lon - d).append(',').append(near.lat + d * 0.66).append(',')
                .append(near.lon + d).append(',').append(near.lat - d * 0.66)
        }
        val arr = JSONArray(Http.get(sb.toString(), timeoutSec = 20))
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
    suspend fun category(term: String, center: LatLon, radiusDeg: Double = 0.08): List<Place> {
        val vb = "${center.lon - radiusDeg},${center.lat + radiusDeg * 0.66},${center.lon + radiusDeg},${center.lat - radiusDeg * 0.66}"
        val url = "https://nominatim.openstreetmap.org/search?q=${enc(term)}&viewbox=$vb&bounded=1&format=jsonv2&limit=30&addressdetails=1"
        val arr = JSONArray(Http.get(url, timeoutSec = 20))
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
