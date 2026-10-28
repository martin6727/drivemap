package com.drivemap.app.net

import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.route.Place

/**
 * Orders search results like a map app: names that match what you typed come first (exact, then
 * "starts with", then a word starting with it, then "contains"), nearest first within each group,
 * and anything far away (other countries / continents) last.
 *
 * Matching ignores case, accents, spaces and punctuation, so "espressofix", "Espresso Fix" and
 * "ESPRESSO-FIX" are the same name.
 */
object SearchRank {
    /** Results farther than this are listed after all nearer ones. */
    private const val NEAR_M = 80_000.0

    /** Lower case, no accents; punctuation becomes a space. */
    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** [normalize] without spaces: "Espresso Fix" -> "espressofix". */
    fun compact(s: String): String = normalize(s).replace(" ", "")

    /**
     * How well [name] (and [address]) matches query [q]: 0 exact, 1 starts with, 2 a word starts
     * with it, 3 contains it, 4 every query word appears in name or address, 5 no visible match.
     */
    fun tier(q: String, name: String, address: String = ""): Int {
        val cq = compact(q)
        if (cq.isEmpty()) return 5
        val cn = compact(name)
        if (cn == cq) return 0
        if (cn.startsWith(cq)) return 1
        val words = normalize(name).split(' ')
        if (words.any { it.startsWith(cq) } || words.indices.any { i -> words.drop(i).joinToString("").startsWith(cq) }) return 2
        if (cn.contains(cq)) return 3
        val qWords = normalize(q).split(' ').filter { it.isNotEmpty() }
        val hay = (normalize(name) + " " + normalize(address)).split(' ').toSet()
        if (qWords.isNotEmpty() && qWords.all { w -> hay.any { it.startsWith(w) } }) return 4
        return 5
    }

    /** True when a result's name is a strong match (exact or starts with the query). */
    fun isStrong(q: String, p: Place) = tier(q, p.name) <= 1

    /** A strong name match near you (an "Espresso Fix" in Australia doesn't count). */
    fun hasStrongNear(q: String, near: LatLon?, list: List<Place>) =
        list.any { isStrong(q, it) && (near == null || Geo.dist(near, it.pos) <= NEAR_M) }

    /**
     * Best matches first, nearest first among equally good ones, far-away results last.
     * Names that are, start with, or have a word starting with the query count as equally good
     * (so a nearby "Lekáreň Hypericum" isn't pushed below a far "Lekáreň"). [byDistance]: a
     * kind of place ("lekáreň") was searched, so only distance matters.
     */
    fun rank(q: String, near: LatLon?, list: List<Place>, byDistance: Boolean = false): List<Place> {
        data class Scored(val p: Place, val far: Boolean, val group: Int, val kind: Int, val d: Double)
        return list.map { p ->
            val d = if (near != null) Geo.dist(near, p.pos) else 0.0
            val group = if (byDistance) 0 else when (val t = tier(q, p.name, p.address)) { 0, 1, 2 -> 0; else -> t }
            Scored(p, d > NEAR_M, group, if (byDistance) 1 else kind(p.category), d)
        }.sortedWith(compareBy<Scored>({ it.far }, { it.group }, { it.kind }, { it.d })).map { it.p }
    }

    private val settlements = setOf("city", "town", "village", "municipality", "hamlet", "suburb", "borough", "quarter", "neighbourhood")
    private val stops = setOf("bus_stop", "platform", "stop", "stop_position", "halt", "tram_stop", "stop_area")
    private val clutter = setOf("cadastral_community", "locality", "district", "county", "region", "state")

    /**
     * Among equally good name matches: a town / village first (0), then ordinary places (1),
     * then bus stops, platforms, cadastral areas, info boards and rivers that merely share the
     * name (2). Railway and bus *stations* stay ordinary places: people do look for those.
     */
    fun kind(category: String): Int {
        val key = category.substringBefore(':')
        val value = category.substringAfter(':', "")
        return when {
            key == "place" && value in settlements -> 0
            key == "boundary" && value == "administrative" -> 0
            key == "highway" && value == "bus_stop" -> 2
            key == "public_transport" && value != "station" -> 2
            key == "railway" && value in stops -> 2
            key == "place" && value in clutter -> 2
            key == "information" || key == "waterway" -> 2
            else -> 1
        }
    }

    /**
     * When a town or village is among the results, the things that merely carry its name (its bus
     * and rail stops, platforms, river sections, cadastral areas, info boards) are left out:
     * searching "Nitra" gives the town, not four "Nitra"s. Stations stay (people look for them).
     */
    fun declutter(list: List<Place>): List<Place> {
        val towns = list.filter { kind(it.category) == 0 }.map { compact(it.name) }.toSet()
        if (towns.isEmpty()) return list
        return list.filter { p -> !(kind(p.category) == 2 && compact(p.name.substringBefore(',')) in towns) }
    }

    /** A short type for results whose name alone doesn't say what they are (e.g. a station). */
    fun typeLabel(category: String): String? = when (category) {
        "railway:station", "public_transport:station" -> "Railway station"
        "amenity:bus_station" -> "Bus station"
        "railway:halt" -> "Railway stop"
        "aeroway:aerodrome" -> "Airfield"
        "aeroway:airport" -> "Airport"
        else -> null
    }

    /**
     * Drops repeats of the same place from different sources: same name (ignoring case, accents
     * and spacing) within [withinM] metres. The first one wins, so put the preferred source first.
     */
    fun dedupe(list: List<Place>, withinM: Double = 150.0): List<Place> {
        val out = ArrayList<Place>()
        for (p in list) {
            val cn = compact(p.name)
            if (out.none { compact(it.name) == cn && Geo.dist(it.pos, p.pos) < withinM }) out.add(p)
        }
        return out
    }
}
