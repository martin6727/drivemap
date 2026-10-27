package com.drivemap.app.nav

import com.drivemap.app.geo.Geo
import com.drivemap.app.route.Route

/**
 * Rally-style pace notes from the route's shape: every bend with its direction and a grade from
 * how tight it is (hairpin, 1 = very tight … 6 = barely a bend), plus "long", "tightens",
 * "opens", bends that run straight into each other and the gap to the next one.
 *
 * Only describes the road ahead; it never changes the route or the turn-by-turn guidance.
 * Junction turns are left out here: they keep their normal navigation instruction.
 */
data class Bend(
    val startM: Double,
    val endM: Double,
    val left: Boolean,
    /** Total direction change, degrees. */
    val angle: Double,
    /** Radius of the tightest part, metres. */
    val radius: Double,
    /** 0 = hairpin, 1 (tightest) … 6 (gentlest). */
    val grade: Int,
    val tightens: Boolean,
    val opens: Boolean,
    val long: Boolean,
)

object PaceNotes {
    private const val STEP = 5.0
    /** Turning less than this per step doesn't count as part of a bend (radius ~ 240 m). */
    private const val MIN_RATE = 1.2
    /** Smaller total direction changes are just kinks in the road, not bends. */
    private const val MIN_ANGLE = 22.0

    fun grade(radius: Double, angle: Double): Int = when {
        angle >= 130 && radius < 30 -> 0
        radius < 25 -> 1
        radius < 45 -> 2
        radius < 70 -> 3
        radius < 110 -> 4
        radius < 170 -> 5
        else -> 6
    }

    /**
     * The bends of [route]. Bends at junction turns (within 40 m of a maneuver) and on slow town
     * streets (expected speed under 25 km/h) are skipped.
     */
    fun bends(route: Route): List<Bend> {
        val path = route.path
        val n = (path.length / STEP).toInt()
        if (n < 8) return emptyList()
        val h = DoubleArray(n) { i -> Geo.bearing(path.pointAt(i * STEP), path.pointAt(i * STEP + STEP)) }
        // turn per step, lightly smoothed (GPS-drawn roads zig-zag a little)
        val raw = DoubleArray(n) { i -> if (i == 0) 0.0 else Geo.angleDiff(h[i - 1], h[i]) }
        val r = DoubleArray(n) { i ->
            val a = raw.getOrElse(i - 1) { 0.0 }
            val b = raw[i]
            val c = raw.getOrElse(i + 1) { 0.0 }
            (a + 2 * b + c) / 4.0
        }
        val maneuvers = route.geometry.maneuvers.filter { it.type !in setOf(0, 1, 2, 3, 4, 5, 6, 8) }.map { it.alongM }
        val out = ArrayList<Bend>()
        var i = 1
        while (i < n) {
            if (Math.abs(r[i]) < MIN_RATE) { i++; continue }
            val sign = Math.signum(r[i])
            var j = i
            var gap = 0
            // extend while turning the same way; allow two quiet steps inside one bend
            while (j + 1 < n) {
                val next = r[j + 1]
                if (Math.signum(next) == sign && Math.abs(next) >= MIN_RATE * 0.6) { j++; gap = 0 }
                else if (gap < 2 && Math.signum(next) != -sign) { j++; gap++ }
                else break
            }
            j -= gap
            val s = i
            i = j + 1
            val seg = (s..j)
            val angle = Math.abs(seg.sumOf { r[it] })
            val startM = (s - 1) * STEP
            val endM = (j + 1) * STEP
            if (angle < MIN_ANGLE) continue
            if (maneuvers.any { it in (startM - 40)..(endM + 40) }) continue
            val apex = (startM + endM) / 2
            val speed = route.edgeAt(apex)?.speedKmh
            if (speed != null && speed < 25) continue
            // tightest part: the strongest 4 steps (20 m)
            var best = 0.0
            for (k in seg) {
                val w = (k until minOf(k + 4, j + 1)).sumOf { Math.abs(r[it]) }
                val len = (minOf(k + 4, j + 1) - k).coerceAtLeast(1) * STEP
                best = maxOf(best, w / len)
            }
            val radius = if (best > 0) 1.0 / Math.toRadians(best) else 9999.0
            val third = ((j - s + 1) / 3).coerceAtLeast(1)
            val firstRate = (s until minOf(s + third, j + 1)).sumOf { Math.abs(r[it]) }
            val lastRate = (maxOf(j - third + 1, s)..j).sumOf { Math.abs(r[it]) }
            val long = endM - startM >= 140 && angle >= 70
            val g = grade(radius, angle)
            out += Bend(
                startM, endM, left = sign < 0, angle = angle, radius = radius, grade = g,
                tightens = g > 0 && endM - startM >= 50 && lastRate > firstRate * 1.6,
                opens = g > 0 && endM - startM >= 50 && firstRate > lastRate * 1.6,
                long = long,
            )
        }
        return out
    }
}
