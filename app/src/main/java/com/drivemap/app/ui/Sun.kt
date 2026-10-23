package com.drivemap.app.ui

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Solar elevation, used for automatic day/night map style. */
object Sun {
    fun elevation(lat: Double, lon: Double, timeMs: Long): Double {
        val d = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360)
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmst = ((18.697374558 + 24.06570982441908 * d) % 24 + 24) % 24
        val lst = Math.toRadians(((gmst * 15 + lon) % 360 + 360) % 360)
        val ha = lst - ra
        val lat0 = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(lat0) * sin(dec) + cos(lat0) * cos(dec) * cos(ha)))
    }

    fun isNight(lat: Double, lon: Double, timeMs: Long = System.currentTimeMillis()) = elevation(lat, lon, timeMs) < -3.0
}
