package com.drivemap.app.route

import com.drivemap.app.i18n.t
import com.drivemap.app.geo.LatLon
import com.drivemap.app.geo.PolylinePath

enum class RouteMode(val en: String, private val shortEn: String) {
    FASTEST("Fastest", "Fastest"),
    ECONOMICAL("Economical", "Eco"),
    SPORT("Sport", "Sport"),
    CHEAPEST("Cheapest", "Cheapest"),
    FEWEST_RADARS("Fewest radars", "Radars");

    val label: String get() = t(en)
    val short: String get() = t(shortEn)
}

data class Place(
    val name: String,
    val pos: LatLon,
    val address: String = "",
    val category: String = "",
    /** Fuel prices, for fuel stations found with the Fuel button. */
    val fuel: com.drivemap.app.fuel.StationFuel? = null,
)

/** A stop along the way. Convoy stops carry the convoy stop id so they can be synced. */
data class Waypoint(
    val name: String,
    val pos: LatLon,
    val convoyStopId: String? = null,
    /** Route passes through it without stopping or announcing (shapes a cruise loop). */
    val through: Boolean = false,
    /** Direction of travel there (keeps a shared route going the same way). */
    val heading: Double? = null,
)

data class AvoidOptions(
    val tolls: Boolean = false,
    val motorways: Boolean = false,
    val ferries: Boolean = false,
    /** Avoid toll/vignette roads in countries where the user has no valid vignette. */
    val unpaidVignettes: Boolean = true,
)

/** Motorway/exit signage of a maneuver ("Exit 50 · R1 · toward Trnava"). */
data class ManeuverSign(
    val exitNumber: String?,
    val branches: List<String>,
    val toward: List<String>,
)

/**
 * One lane at a junction. [indications] are the painted arrows ("left", "straight", ...),
 * [valid] = usable for our maneuver, [active] = recommended, [validIndication] = arrow to follow.
 */
data class LaneInfo(
    val indications: List<String>,
    val valid: Boolean,
    val active: Boolean,
    val validIndication: String?,
)

data class Maneuver(
    val type: Int,
    val instruction: String,
    val verbalAlert: String?,
    val verbalPre: String?,
    val verbalPost: String?,
    val streetNames: List<String>,
    val beginIndex: Int,
    val lengthKm: Double,
    val timeS: Double,
    val roundaboutExit: Int,
    val toll: Boolean,
    val highway: Boolean,
    val sign: ManeuverSign? = null,
    /** Distance along route (m) where the maneuver starts. Filled in by Route. */
    var alongM: Double = 0.0,
) {
    val isArrival get() = type == 4 || type == 5 || type == 6
}

/** Road attributes for one stretch of the route, mapped to distance along the route. */
data class EdgeSpan(
    val startM: Double,
    val endM: Double,
    val lengthKm: Double,
    val speedKmh: Double,
    /** Posted limit. null = unknown, -1 = no limit (e.g. German Autobahn). */
    val speedLimit: Int?,
    val roadClass: String,
    val use: String,
    val toll: Boolean,
    val density: Int,
    val country: String?,
)

/** Raw geometry + instructions of a route, possibly stitched from several requests. */
class RouteGeometry {
    val shape = ArrayList<LatLon>()
    val maneuvers = ArrayList<Maneuver>()
    /** Shape index where each intermediate waypoint is reached. */
    val waypointIndex = ArrayList<Int>()
    var timeS = 0.0
    var lengthKm = 0.0
    var hasToll = false
    var hasHighway = false
    var hasFerry = false

    /**
     * Appends a Valhalla leg. When [joinAsThrough] is true the previous arrival maneuver is dropped
     * and the new departure becomes a plain "continue" (used when stitching per-country pieces).
     */
    fun appendLeg(legShape: List<LatLon>, legManeuvers: List<Maneuver>, legTime: Double, legLen: Double, joinAsThrough: Boolean, markWaypoint: Boolean) {
        val offset = shape.size
        if (markWaypoint && offset > 0) waypointIndex.add(offset - 1)
        if (joinAsThrough && maneuvers.isNotEmpty() && maneuvers.last().isArrival) {
            maneuvers.removeAt(maneuvers.size - 1)
        }
        shape.addAll(legShape)
        legManeuvers.forEachIndexed { i, m ->
            var mm = m.copy(beginIndex = m.beginIndex + offset)
            if (joinAsThrough && i == 0 && offset > 0 && (m.type in 1..3)) {
                mm = mm.copy(type = 8, verbalAlert = null, verbalPre = null)
            }
            maneuvers.add(mm)
        }
        timeS += legTime
        lengthKm += legLen
    }

    /** Appends a whole geometry (all its legs/waypoints), see [appendLeg] for the flags. */
    fun append(other: RouteGeometry, joinAsThrough: Boolean, markWaypoint: Boolean) {
        val offset = shape.size
        appendLeg(other.shape, other.maneuvers, other.timeS, other.lengthKm, joinAsThrough, markWaypoint)
        other.waypointIndex.forEach { waypointIndex.add(it + offset) }
        hasToll = hasToll || other.hasToll
        hasHighway = hasHighway || other.hasHighway
        hasFerry = hasFerry || other.hasFerry
    }
}

data class VignetteNeed(
    val country: String,
    val km: Double,
    val productLabel: String,
    val priceEur: Double,
    /** True when the user already has a valid vignette for the time of travel. */
    val covered: Boolean,
)

data class RouteMetrics(
    val fuelLiters: Double,
    val fuelCostEur: Double,
    val tollCostEur: Double,
    val vignettes: List<VignetteNeed>,
    val curvatureDegPerKm: Double,
    val sharpCorners: Int,
    val countries: List<String>,
    val motorwayKm: Double,
) {
    val missingVignettes get() = vignettes.filter { !it.covered }
    val vignetteCostEur get() = missingVignettes.sumOf { it.priceEur }
    val totalCostEur get() = fuelCostEur + tollCostEur + vignetteCostEur
}

class Route(
    val geometry: RouteGeometry,
    val variant: String,
    val waypoints: List<Waypoint>,
) {
    val path = PolylinePath(geometry.shape)
    val timeS get() = geometry.timeS
    val lengthKm get() = geometry.lengthKm
    var edges: List<EdgeSpan> = emptyList()
    var metrics: RouteMetrics? = null
    var cameras: List<com.drivemap.app.radar.RouteCamera> = emptyList()
    /** Lane guidance per maneuver index (filled in after navigation starts). */
    @Volatile var lanes: Map<Int, List<LaneInfo>> = emptyMap()
    var cameraCount: Int = -1
    var tags: MutableSet<String> = linkedSetOf()

    /** Distance along the route at which each intermediate waypoint is reached. */
    val waypointAlong: List<Double> = geometry.waypointIndex.map { path.cum[it.coerceIn(0, path.cum.size - 1)] }

    init {
        for (m in geometry.maneuvers) {
            m.alongM = if (path.cum.isEmpty()) 0.0 else path.cum[m.beginIndex.coerceIn(0, path.cum.size - 1)]
        }
    }

    /** Main road names, used for "via ..." labels. */
    fun viaText(): String {
        val byName = HashMap<String, Double>()
        for (m in geometry.maneuvers) {
            val n = m.streetNames.firstOrNull() ?: continue
            byName[n] = (byName[n] ?: 0.0) + m.lengthKm
        }
        val top = byName.entries.sortedByDescending { it.value }.take(2).map { it.key }
        return if (top.isEmpty()) "" else t("via {0}", top.joinToString(" " + t("and") + " "))

    }

    fun edgeAt(alongM: Double): EdgeSpan? {
        val list = edges
        if (list.isEmpty()) return null
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val e = list[mid]
            when {
                alongM < e.startM -> hi = mid - 1
                alongM > e.endM -> lo = mid + 1
                else -> return e
            }
        }
        return list[lo.coerceIn(0, list.size - 1)]
    }

    fun signature(): String {
        val pts = geometry.shape
        if (pts.isEmpty()) return ""
        val samples = (0..8).map { pts[(pts.size - 1) * it / 8] }
        return samples.joinToString("|") { String.format("%.3f,%.3f", it.lat, it.lon) }
    }
}
