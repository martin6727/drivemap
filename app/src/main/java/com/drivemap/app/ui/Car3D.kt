package com.drivemap.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.drivemap.app.i18n.t
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small low-poly 3D cars (and a 3D navigation arrow) drawn in code. Each model is rendered from
 * [FRAMES] directions for a tilted camera, plus once from straight above; the map picks the frame
 * closest to the car's heading relative to the camera and turns it by the small remainder, which
 * is how navigation apps make a flat sprite read as a 3D car.
 */
object Car3D {

    enum class Body { ARROW, HATCH, SEDAN, SUV, SPORT }

    class Skin(val id: String, private val nameEn: String, val body: Body, val color: Int) {
        val label: String get() = t(nameEn)
    }

    val skins = listOf(
        Skin("arrow", "Navigation arrow", Body.ARROW, 0xFF1A73E8.toInt()),
        Skin("nexia", "Daewoo Nexia (white)", Body.HATCH, 0xFFF1F1EC.toInt()),
        Skin("sedan", "Blue sedan", Body.SEDAN, 0xFF1F4E9E.toInt()),
        Skin("suv", "Black SUV", Body.SUV, 0xFF26282C.toInt()),
        Skin("sport", "Red sports car", Body.SPORT, 0xFFD32F2F.toInt()),
    )

    fun skin(id: String?): Skin = skins.firstOrNull { it.id == id } ?: skins[0]

    const val FRAMES = 24
    const val STEP = 360.0 / FRAMES
    /** Camera pitch the tilted frames are drawn for; above [TILT_MIN] the map uses them. */
    const val TILT = 50.0
    const val TILT_MIN = 18.0

    // ------------------------------------------------------------------ geometry

    private class V(val x: Double, val y: Double, val z: Double) {
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        fun times(k: Double) = V(x * k, y * k, z * k)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun norm(): V { val l = sqrt(dot(this)).coerceAtLeast(1e-9); return V(x / l, y / l, z / l) }
    }

    private enum class Mat { PAINT, GLASS, TYRE, HUB, HEAD, TAIL, TRIM, ARROW }

    private class Face(val pts: List<V>, val n: V, val mat: Mat, val decals: List<Face> = emptyList(), val outline: Boolean = false)

    /** Planar polygon with its normal pointing away from [inside]. */
    private fun face(pts: List<V>, inside: V, mat: Mat, decals: List<Face> = emptyList(), outline: Boolean = false): Face {
        var n = (pts[1] - pts[0]).cross(pts[2] - pts[0]).norm()
        if (abs(n.dot(n)) < 0.5) n = (pts[2] - pts[0]).cross(pts[3 % pts.size] - pts[0]).norm()
        val c = centroid(pts)
        if (n.dot(c - inside) < 0) n = n.times(-1.0)
        return Face(pts, n, mat, decals, outline)
    }

    private fun centroid(pts: List<V>) = V(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size, pts.sumOf { it.z } / pts.size)

    /**
     * Side profile (y forward, z up) extruded across the car. Half width shrinks linearly from
     * [halfW] at [zLow] to [halfW]·[taper] at [zHigh] (tumblehome). [edgeMat] picks the material of
     * the surface between profile point i and i+1; [windows] are side-window outlines (y, z).
     */
    private fun extrude(
        profile: List<Pair<Double, Double>>, halfW: Double, zLow: Double, zHigh: Double, taper: Double,
        edgeMat: (Int) -> Mat, windows: List<List<Pair<Double, Double>>> = emptyList(),
        lights: Boolean = false,
    ): List<Face> {
        fun hw(z: Double) = halfW * (1 - (1 - taper) * ((z - zLow) / (zHigh - zLow)).coerceIn(0.0, 1.0))
        val inside = V(0.0, profile.sumOf { it.first } / profile.size, profile.sumOf { it.second } / profile.size)
        val out = ArrayList<Face>()
        for (side in listOf(1.0, -1.0)) {
            val pts = profile.map { (y, z) -> V(side * hw(z), y, z) }
            val decals = windows.map { w -> face(w.map { (y, z) -> V(side * (hw(z) + 0.012), y, z) }, inside, Mat.GLASS) }
            out += face(pts, inside, Mat.PAINT, decals)
        }
        for (i in profile.indices) {
            val (y0, z0) = profile[i]
            val (y1, z1) = profile[(i + 1) % profile.size]
            val pts = listOf(V(-hw(z0), y0, z0), V(hw(z0), y0, z0), V(hw(z1), y1, z1), V(-hw(z1), y1, z1))
            val mat = edgeMat(i)
            val decals = ArrayList<Face>()
            if (lights && (mat == Mat.HEAD || mat == Mat.TAIL)) {
                // a lamp near each corner of the front / rear face, the rest painted
                val zA = min(z0, z1) + abs(z1 - z0) * 0.25
                val zB = min(z0, z1) + abs(z1 - z0) * 0.8
                val yA = if (abs(z1 - z0) > 1e-6) y0 + (y1 - y0) * ((zA - z0) / (z1 - z0)) else y0
                val yB = if (abs(z1 - z0) > 1e-6) y0 + (y1 - y0) * ((zB - z0) / (z1 - z0)) else y1
                val off = if (mat == Mat.HEAD) 0.012 else -0.012
                for (s in listOf(1.0, -1.0)) {
                    val xa = s * hw(zA) * 0.92
                    val xb = s * hw(zA) * 0.52
                    decals += face(
                        listOf(V(xa, yA + off, zA), V(xb, yA + off, zA), V(xb, yB + off, zB), V(xa, yB + off, zB)),
                        inside, mat,
                    )
                }
                out += face(pts, inside, Mat.PAINT, decals)
            } else {
                out += face(pts, inside, mat)
            }
        }
        return out
    }

    /** Wheel: octagonal cylinder across the car at (side·x, y), radius r. */
    private fun wheel(x: Double, y: Double, r: Double, w: Double): List<Face> {
        val n = 10
        val c = V(x, y, r)
        val inner = x - w / 2 * Math.signum(x)
        val outer = x + w / 2 * Math.signum(x)
        val ring = (0 until n).map { i -> val a = 2 * Math.PI * i / n; Pair(y + r * cos(a), r + r * sin(a)) }
        val out = ArrayList<Face>()
        for (i in 0 until n) {
            val (y0, z0) = ring[i]
            val (y1, z1) = ring[(i + 1) % n]
            out += face(listOf(V(inner, y0, z0), V(outer, y0, z0), V(outer, y1, z1), V(inner, y1, z1)), c, Mat.TYRE)
        }
        val hub = ring.map { (yy, zz) -> V(outer + 0.01 * Math.signum(x), y + (yy - y) * 0.6, r + (zz - r) * 0.6) }
        out += face(ring.map { (yy, zz) -> V(outer, yy, zz) }, c, Mat.TYRE, listOf(face(hub, c, Mat.HUB)))
        return out
    }

    private class Model(val faces: List<Face>, val wheels: List<Face>, val halfLen: Double, val halfW: Double)

    private fun model(body: Body): Model = when (body) {
        Body.ARROW -> {
            val tip = V(0.0, 2.0, 0.42); val rw = V(1.45, -1.55, 0.3); val notch = V(0.0, -0.8, 0.6); val lw = V(-1.45, -1.55, 0.3)
            val base = listOf(V(0.0, 2.0, 0.0), V(1.45, -1.55, 0.0), V(0.0, -0.8, 0.0), V(-1.45, -1.55, 0.0))
            val top = listOf(tip, rw, notch, lw)
            val inside = V(0.0, -0.2, 0.25)
            val faces = ArrayList<Face>()
            faces += face(listOf(tip, rw, notch), inside, Mat.ARROW, outline = true)
            faces += face(listOf(tip, notch, lw), inside, Mat.ARROW, outline = true)
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                faces += face(listOf(base[i], base[j], top[j], top[i]), inside, Mat.PAINT)
            }
            Model(faces, emptyList(), 2.0, 1.45)
        }
        Body.HATCH -> {
            // boxy early-90s hatchback (Nexia / Kadett E shape)
            val lower = listOf(
                -1.98 to 0.30, 1.98 to 0.30, 2.03 to 0.56, 1.92 to 0.74, 0.80 to 0.86, -1.82 to 0.90, -2.0 to 0.86, -2.02 to 0.40,
            )
            val cabin = listOf(
                0.80 to 0.86, -1.80 to 0.86, -1.84 to 0.96, -1.30 to 1.38, -0.10 to 1.40,
            )
            val lowerMat = { i: Int -> when (i) { 1 -> Mat.TRIM; 2 -> Mat.HEAD; 6 -> Mat.TAIL; 7 -> Mat.TRIM; else -> Mat.PAINT } }
            val cabinMat = { i: Int -> when (i) { 2 -> Mat.GLASS; 4 -> Mat.GLASS; else -> Mat.PAINT } }
            val windows = listOf(
                listOf(0.62 to 0.93, -0.55 to 0.93, -0.55 to 1.31, -0.12 to 1.32),
                listOf(-0.68 to 0.93, -1.68 to 0.96, -1.28 to 1.30, -0.68 to 1.31),
            )
            Model(
                extrude(lower, 0.83, 0.3, 0.9, 0.97, lowerMat, lights = true) +
                    extrude(cabin, 0.80, 0.86, 1.40, 0.84, cabinMat, windows),
                wheelsFor(1.30, -1.28, 0.29, 0.83), 2.03, 0.83,
            )
        }
        Body.SEDAN -> {
            val lower = listOf(
                -2.25 to 0.30, 2.2 to 0.30, 2.28 to 0.55, 2.12 to 0.72, 0.85 to 0.84, -1.6 to 0.88, -2.22 to 0.86, -2.3 to 0.45,
            )
            val cabin = listOf(0.85 to 0.84, -1.60 to 0.86, -1.05 to 1.36, 0.05 to 1.38)
            val lowerMat = { i: Int -> when (i) { 1 -> Mat.TRIM; 2 -> Mat.HEAD; 6 -> Mat.TAIL; 7 -> Mat.TRIM; else -> Mat.PAINT } }
            val cabinMat = { i: Int -> when (i) { 1 -> Mat.GLASS; 3 -> Mat.GLASS; else -> Mat.PAINT } }
            val windows = listOf(
                listOf(0.66 to 0.91, -0.45 to 0.91, -0.45 to 1.30, 0.02 to 1.31),
                listOf(-0.58 to 0.91, -1.42 to 0.92, -1.02 to 1.29, -0.58 to 1.30),
            )
            Model(
                extrude(lower, 0.88, 0.3, 0.88, 0.97, lowerMat, lights = true) +
                    extrude(cabin, 0.85, 0.84, 1.38, 0.84, cabinMat, windows),
                wheelsFor(1.42, -1.38, 0.31, 0.88), 2.3, 0.88,
            )
        }
        Body.SUV -> {
            val lower = listOf(
                -2.2 to 0.42, 2.18 to 0.42, 2.25 to 0.75, 2.08 to 0.98, 0.95 to 1.08, -2.08 to 1.12, -2.22 to 1.05, -2.25 to 0.55,
            )
            val cabin = listOf(0.95 to 1.08, -2.08 to 1.08, -2.05 to 1.18, -1.95 to 1.72, 0.25 to 1.74)
            val lowerMat = { i: Int -> when (i) { 1 -> Mat.TRIM; 2 -> Mat.HEAD; 6 -> Mat.TAIL; 7 -> Mat.TRIM; else -> Mat.PAINT } }
            val cabinMat = { i: Int -> when (i) { 2 -> Mat.GLASS; 4 -> Mat.GLASS; else -> Mat.PAINT } }
            val windows = listOf(
                listOf(0.78 to 1.15, -0.5 to 1.15, -0.5 to 1.64, 0.2 to 1.66),
                listOf(-0.62 to 1.15, -1.92 to 1.16, -1.88 to 1.64, -0.62 to 1.65),
            )
            Model(
                extrude(lower, 0.95, 0.42, 1.1, 0.97, lowerMat, lights = true) +
                    extrude(cabin, 0.92, 1.08, 1.74, 0.88, cabinMat, windows),
                wheelsFor(1.42, -1.4, 0.38, 0.95), 2.25, 0.95,
            )
        }
        Body.SPORT -> {
            val lower = listOf(
                -2.15 to 0.24, 2.15 to 0.24, 2.25 to 0.42, 2.05 to 0.56, 0.55 to 0.74, -1.85 to 0.80, -2.15 to 0.74, -2.2 to 0.36,
            )
            val cabin = listOf(0.55 to 0.74, -1.75 to 0.76, -0.75 to 1.14, -0.15 to 1.16)
            val lowerMat = { i: Int -> when (i) { 1 -> Mat.TRIM; 2 -> Mat.HEAD; 6 -> Mat.TAIL; 7 -> Mat.TRIM; else -> Mat.PAINT } }
            val cabinMat = { i: Int -> when (i) { 1 -> Mat.GLASS; 3 -> Mat.GLASS; else -> Mat.PAINT } }
            val windows = listOf(listOf(0.35 to 0.8, -1.25 to 0.82, -0.72 to 1.09, -0.18 to 1.1))
            Model(
                extrude(lower, 0.95, 0.24, 0.8, 0.95, lowerMat, lights = true) +
                    extrude(cabin, 0.80, 0.74, 1.16, 0.80, cabinMat, windows),
                wheelsFor(1.38, -1.35, 0.32, 0.95), 2.25, 0.95,
            )
        }
    }

    private fun wheelsFor(front: Double, rear: Double, r: Double, halfW: Double): List<Face> =
        listOf(front, rear).flatMap { y -> listOf(1.0, -1.0).flatMap { s -> wheel(s * (halfW - 0.06), y, r, 0.24) } }

    private val models = HashMap<Body, Model>()

    // ------------------------------------------------------------------ rendering

    private fun shade(color: Int, k: Double, spec: Double): Int {
        fun ch(s: Int) = (((color shr s) and 0xFF) * k + 255 * spec).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun blend(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Renders [body] in [color] heading [headingDeg] clockwise from screen-up, seen from a camera
     * pitched [pitchDeg] (0 = straight down). The car's ground centre is the bitmap centre.
     */
    fun render(ctx: Context, body: Body, color: Int, headingDeg: Double, pitchDeg: Double, lengthDp: Float): Bitmap {
        val m = models.getOrPut(body) { model(body) }
        val d = ctx.resources.displayMetrics.density
        val scale = lengthDp * d / (2 * m.halfLen)
        val reach = sqrt(m.halfLen * m.halfLen + m.halfW * m.halfW) + 0.4
        val sizePx = (2 * (reach + 1.35) * scale).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = sizePx / 2.0
        val cy = sizePx / 2.0
        val th = Math.toRadians(headingDeg)
        val p = Math.toRadians(pitchDeg)
        val sp = sin(p); val cp = cos(p)
        val toCam = V(0.0, -sp, cp)
        val light = V(-0.42, -0.30, 0.86).norm()
        val half = (light + toCam).norm()

        fun world(v: V) = V(v.x * cos(th) + v.y * sin(th), -v.x * sin(th) + v.y * cos(th), v.z)
        fun rot(n: V) = world(n)
        fun sx(w: V) = (cx + w.x * scale).toFloat()
        fun sy(w: V) = (cy - (w.y * cp + w.z * sp) * scale).toFloat()
        fun near(w: V) = -w.y * sp + w.z * cp

        // soft shadow on the ground, nudged away from the light
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0x70000000
            maskFilter = BlurMaskFilter((2.2f * d).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        val g = 0.12
        val foot = if (body == Body.ARROW) listOf(V(0.0, 2.0, 0.0), V(1.45, -1.55, 0.0), V(0.0, -0.8, 0.0), V(-1.45, -1.55, 0.0))
        else listOf(V(m.halfW + g, m.halfLen, 0.0), V(m.halfW + g, -m.halfLen, 0.0), V(-m.halfW - g, -m.halfLen, 0.0), V(-m.halfW - g, m.halfLen, 0.0))
        val sp0 = Path()
        foot.map { world(it) + V(0.12, -0.10, 0.0) }.forEachIndexed { i, w -> if (i == 0) sp0.moveTo(sx(w), sy(w)) else sp0.lineTo(sx(w), sy(w)) }
        sp0.close()
        c.drawPath(sp0, shadow)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.9f; strokeJoin = Paint.Join.ROUND }
        val night = blend(color, 0xFF000000.toInt(), 0.35)

        fun matColor(mat: Mat): Int = when (mat) {
            Mat.PAINT -> if (body == Body.ARROW) night else color
            Mat.ARROW -> color
            Mat.GLASS -> 0xFF223040.toInt()
            Mat.TYRE -> 0xFF18191B.toInt()
            Mat.HUB -> 0xFF9EA3A8.toInt()
            Mat.HEAD -> 0xFFFFF6D8.toInt()
            Mat.TAIL -> 0xFFD3261E.toInt()
            Mat.TRIM -> 0xFF2C2E31.toInt()
        }

        fun drawFace(f: Face) {
            val pts = f.pts.map { world(it) }
            val n = rot(f.n)
            if (n.dot(toCam) <= 0.02) return
            val diff = max(0.0, n.dot(light))
            val gloss = when (f.mat) { Mat.GLASS -> 0.45; Mat.PAINT, Mat.ARROW -> 0.22; Mat.HUB -> 0.2; else -> 0.05 }
            val spec = max(0.0, n.dot(half)).pow(18.0) * gloss
            val k = when (f.mat) {
                Mat.HEAD, Mat.TAIL -> 1.0
                Mat.GLASS -> 0.75 + 0.5 * diff
                else -> 0.42 + 0.62 * diff
            }
            val col = shade(matColor(f.mat), k, spec)
            val path = Path()
            pts.forEachIndexed { i, w -> if (i == 0) path.moveTo(sx(w), sy(w)) else path.lineTo(sx(w), sy(w)) }
            path.close()
            paint.color = col
            c.drawPath(path, paint)
            seam.color = col
            c.drawPath(path, seam) // hides hairline gaps between neighbouring faces
            f.decals.forEach { drawFace(it) }
        }

        // wheels first: the body covers whatever of them is inside it
        m.wheels.sortedBy { near(world(centroid(it.pts))) }.forEach { drawFace(it) }
        m.faces.sortedBy { near(world(centroid(it.pts))) }.forEach { drawFace(it) }

        // white rim around the arrow's top, like the classic navigation arrow
        if (body == Body.ARROW) {
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.8f * d; this.color = 0xFFFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND
            }
            val top = listOf(V(0.0, 2.0, 0.42), V(1.45, -1.55, 0.3), V(0.0, -0.8, 0.6), V(-1.45, -1.55, 0.3)).map { world(it) }
            val path = Path()
            top.forEachIndexed { i, w -> if (i == 0) path.moveTo(sx(w), sy(w)) else path.lineTo(sx(w), sy(w)) }
            path.close()
            c.drawPath(path, rim)
        }
        return bmp
    }

    /** Image names: "<prefix>-0" seen from above, "<prefix>-t<i>" tilted frame i. */
    fun frameName(prefix: String, tilted: Boolean, i: Int) = if (tilted) "$prefix-t$i" else "$prefix-0"

    /** All frames of one look, keyed by image name. */
    fun frames(ctx: Context, prefix: String, body: Body, color: Int, lengthDp: Float): Map<String, Bitmap> {
        val out = LinkedHashMap<String, Bitmap>()
        out[frameName(prefix, false, 0)] = render(ctx, body, color, 0.0, 0.0, lengthDp)
        for (i in 0 until FRAMES) out[frameName(prefix, true, i)] = render(ctx, body, color, i * STEP, TILT, lengthDp)
        return out
    }

    /** Frame and leftover rotation for a car heading [relDeg] relative to the camera. */
    fun pick(relDeg: Double, tilted: Boolean): Pair<Int, Double> {
        val rel = ((relDeg % 360) + 360) % 360
        if (!tilted) return 0 to rel
        val i = Math.round(rel / STEP).toInt() % FRAMES
        var rest = rel - i * STEP
        if (rest > 180) rest -= 360
        return i to rest
    }
}
