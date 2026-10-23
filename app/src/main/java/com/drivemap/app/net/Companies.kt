package com.drivemap.app.net

import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.route.Place
import org.json.JSONObject

/**
 * Finding businesses by name where they actually are: anything named like the query on the
 * OpenStreetMap map around you (shops, cafés, workshops, offices…), from the live map database.
 * (Company registers only list registered seats, often an accountant's or a home address.)
 */
object Companies {
    /** Places on the map within [radiusM] whose name contains [query] (case and accents ignored). */
    suspend fun nearbyByName(query: String, near: LatLon, radiusM: Int = 40_000, limit: Int = 15): List<Place> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        // letters match with or without accents: "kaviaren" finds "Kaviareň"
        val pattern = q.lowercase().map { ch -> accentClass(ch) }.joinToString("")
        val ql = "[out:json][timeout:20];nwr[\"name\"~\"$pattern\",i](around:$radiusM,${near.lat},${near.lon});out center tags $limit;"
        val json = JSONObject(Overpass.query(ql, attempts = 3, timeoutSec = 12))
        val els = json.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name").ifBlank { continue }
            // skip roads, rivers, bus stops etc. that merely share the word
            if (tags.has("highway") || tags.has("waterway") || tags.has("public_transport") || tags.has("railway")) continue
            val lat = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val street = listOf(tags.optString("addr:street"), tags.optString("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val city = tags.optString("addr:city")
            val kind = listOf("shop", "amenity", "craft", "office", "tourism", "leisure").firstOrNull { tags.has(it) }
            out += Place(
                name, LatLon(lat, lon), listOf(street, city).filter { it.isNotBlank() }.joinToString(", "),
                if (kind != null) "$kind:${tags.optString(kind)}" else "company",
            )
        }
        return out.sortedBy { Geo.dist(it.pos, near) }
    }

    private val accents = mapOf(
        'a' to "aáä", 'c' to "cč", 'd' to "dď", 'e' to "eéě", 'i' to "ií", 'l' to "lĺľ", 'n' to "nň",
        'o' to "oóô", 'r' to "rŕř", 's' to "sš", 't' to "tť", 'u' to "uúů", 'y' to "yý", 'z' to "zž",
    )

    /** One query letter as a regex that also matches its accented forms; other characters escaped. */
    private fun accentClass(ch: Char): String {
        val base = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD).first()
        accents[base]?.let { return "[$it]" }
        return if (ch.isLetterOrDigit() || ch == ' ') ch.toString() else "." // punctuation: any character
    }
}
