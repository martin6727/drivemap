package com.drivemap.app.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6371008.8

    fun dist(a: LatLon, b: LatLon): Double = dist(a.lat, a.lon, b.lat, b.lon)

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val s = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(min(1.0, sqrt(s)))
    }

    /** Initial bearing a -> b in degrees 0..360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference b - a in degrees, -180..180. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    fun destination(p: LatLon, bearingDeg: Double, distM: Double): LatLon {
        val d = distM / R
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(p.lat)
        val l1 = Math.toRadians(p.lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), (Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }

    // ---------------------------------------------------------------- polylines

    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var shift = 0
            var result = 0L
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            shift = 0
            result = 0L
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }

    fun encodePolyline(points: List<LatLon>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder(points.size * 8)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (value >= 0x20) {
            sb.append(((0x20L or (value and 0x1f)) + 63).toInt().toChar())
            value = value shr 5
        }
        sb.append((value + 63).toInt().toChar())
    }

    // ---------------------------------------------------------------- planar helpers

    /** Projects p onto segment a-b using a local flat approximation. Returns (t in 0..1, distance m). */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val kx = cos(Math.toRadians(p.lat)) * 111320.0
        val ky = 110574.0
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0) -(ax * dx + ay * dy) / len2 else 0.0
        t = t.coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return t to sqrt(px * px + py * py)
    }

    fun interpolate(a: LatLon, b: LatLon, t: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Douglas–Peucker simplification with tolerance in metres. */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = projectOnSegment(points[i], points[s], points[e]).second
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.add(s to idx)
                stack.add(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    fun bounds(points: List<LatLon>): DoubleArray {
        var s = 90.0
        var w = 180.0
        var n = -90.0
        var e = -180.0
        for (p in points) {
            s = min(s, p.lat); n = max(n, p.lat)
            w = min(w, p.lon); e = max(e, p.lon)
        }
        return doubleArrayOf(s, w, n, e)
    }

    // ---------------------------------------------------------------- geohash

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun geohash(lat: Double, lon: Double, precision: Int): String {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        val sb = StringBuilder()
        var bit = 0
        var ch = 0
        var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonMin = mid } else lonMax = mid
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latMin = mid } else latMax = mid
            }
            even = !even
            if (bit < 4) bit++ else {
                sb.append(BASE32[ch]); bit = 0; ch = 0
            }
        }
        return sb.toString()
    }

    /** Returns [south, west, north, east] of a geohash cell. */
    fun geohashBounds(hash: String): DoubleArray {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var even = true
        for (c in hash) {
            val cd = BASE32.indexOf(c)
            for (mask in intArrayOf(16, 8, 4, 2, 1)) {
                if (even) {
                    val mid = (lonMin + lonMax) / 2
                    if ((cd and mask) != 0) lonMin = mid else lonMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if ((cd and mask) != 0) latMin = mid else latMax = mid
                }
                even = !even
            }
        }
        return doubleArrayOf(latMin, lonMin, latMax, lonMax)
    }

    /** The cell itself plus its 8 neighbours. */
    fun geohashWithNeighbours(hash: String): List<String> {
        val b = geohashBounds(hash)
        val h = b[2] - b[0]
        val w = b[3] - b[1]
        val cLat = (b[0] + b[2]) / 2
        val cLon = (b[1] + b[3]) / 2
        val out = LinkedHashSet<String>()
        for (dy in -1..1) for (dx in -1..1) {
            val lat = (cLat + dy * h).coerceIn(-89.999, 89.999)
            var lon = cLon + dx * w
            if (lon > 180) lon -= 360
            if (lon < -180) lon += 360
            out.add(geohash(lat, lon, hash.length))
        }
        return out.toList()
    }

    fun formatDistance(m: Double): String = when {
        m < 0 -> ""
        m < 95 -> "${(m / 10).toInt() * 10} m"
        m < 1000 -> "${((m + 25) / 50).toInt() * 50} m"
        m < 9950 -> String.format("%.1f km", m / 1000)
        else -> "${(m / 1000).toInt()} km"
    }

    fun formatDuration(seconds: Double): String {
        val totalMin = (seconds / 60.0 + 0.5).toInt()
        if (totalMin < 1) return "1 min"
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun normalizeDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    const val DEG = 180.0 / PI

    fun absAngle(a: Double, b: Double) = abs(angleDiff(a, b))
}

/**
 * A polyline with cumulative distances, used for route progress and "distance along" maths.
 */
class PolylinePath(val points: List<LatLon>) {
    val cum: DoubleArray = DoubleArray(points.size)
    val length: Double

    init {
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += Geo.dist(points[i - 1], points[i])
            cum[i] = acc
        }
        length = acc
    }

    data class Hit(val segment: Int, val t: Double, val along: Double, val crossTrack: Double, val point: LatLon)

    /** Projects p onto the path, searching segments [from, to). */
    fun project(p: LatLon, from: Int = 0, to: Int = points.size - 1): Hit? {
        if (points.size < 2) return null
        val s = from.coerceIn(0, points.size - 2)
        val e = to.coerceIn(s + 1, points.size - 1)
        var best: Hit? = null
        for (i in s until e) {
            val (t, d) = Geo.projectOnSegment(p, points[i], points[i + 1])
            if (best == null || d < best.crossTrack) {
                val along = cum[i] + (cum[i + 1] - cum[i]) * t
                best = Hit(i, t, along, d, Geo.interpolate(points[i], points[i + 1], t))
            }
        }
        return best
    }

    fun segmentAt(along: Double): Int {
        if (along <= 0) return 0
        if (along >= length) return (points.size - 2).coerceAtLeast(0)
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= along) lo = mid else hi = mid
        }
        return lo
    }

    fun pointAt(along: Double): LatLon {
        if (points.isEmpty()) return LatLon(0.0, 0.0)
        if (points.size == 1) return points[0]
        val i = segmentAt(along)
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen > 0) ((along - cum[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
        return Geo.interpolate(points[i], points[i + 1], t)
    }

    fun bearingAt(along: Double): Double {
        if (points.size < 2) return 0.0
        val i = segmentAt(along)
        // skip zero-length segments
        var j = i
        while (j < points.size - 2 && cum[j + 1] - cum[j] < 0.5) j++
        return Geo.bearing(points[j], points[j + 1])
    }

    /** Sub-path between two distances. */
    fun slice(fromM: Double, toM: Double): List<LatLon> {
        if (points.size < 2) return points
        val a = fromM.coerceIn(0.0, length)
        val b = toM.coerceIn(a, length)
        val out = ArrayList<LatLon>()
        out.add(pointAt(a))
        val i0 = segmentAt(a) + 1
        val i1 = segmentAt(b)
        for (i in i0..i1) if (i in points.indices) out.add(points[i])
        out.add(pointAt(b))
        return out
    }
}
