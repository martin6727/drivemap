package com.drivemap.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Clean Google-style turn arrows (thick stem, smooth bend, solid arrowhead) drawn with
 * android.graphics so the same icons are used on the phone (Compose) and in Android Auto.
 * Types are Valhalla maneuver types; lane arrows map their indication to one of these.
 */
object ArrowPainter {

    private const val STEM = 0.13f      // stem width (fraction of icon size)
    private const val HEAD_LEN = 0.30f  // arrowhead length
    private const val HEAD_HALF = 0.22f // half of arrowhead width

    fun bitmap(type: Int, roundaboutExit: Int, sizePx: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), type, roundaboutExit, sizePx.toFloat(), sizePx.toFloat(), color)
        return b
    }

    /**
     * Draws one arrow. Translucent colours are drawn solid into a layer that is faded once, so
     * the overlapping stem/head pieces don't show seams.
     */
    fun draw(canvas: Canvas, type: Int, roundaboutExit: Int, w: Float, h: Float, color: Int) {
        val alpha = Color.alpha(color)
        if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, w, h, alpha)
        drawSolid(canvas, type, roundaboutExit, w, h, color or 0xFF000000.toInt())
        if (alpha < 255) canvas.restore()
    }

    /** Painted lane arrows → arrow type. */
    fun laneType(indication: String): Int = when (indication) {
        "slight right" -> 9
        "right" -> 10
        "sharp right" -> 11
        "uturn" -> 13
        "sharp left" -> 14
        "left" -> 15
        "slight left" -> 16
        "merge_to_left" -> 38
        "merge_to_right" -> 37
        else -> 8
    }

    /**
     * One lane cell: the arrows you shouldn't follow faded together (one layer, no seams), the
     * arrow to follow solid on top.
     */
    fun drawLane(canvas: Canvas, lane: com.drivemap.app.route.LaneInfo, w: Float, h: Float, color: Int = Color.WHITE, fadedAlpha: Int = 84) {
        val inds = lane.indications.map { it.trim().replace('_', ' ') }
            .filter { it.isNotBlank() && it != "none" }.ifEmpty { listOf("straight") }.distinct()
        val valid = lane.validIndication?.trim()?.replace('_', ' ')
        fun highlighted(ind: String) = lane.valid && (valid == null || valid == ind)
        val bright = inds.filter { highlighted(it) }
        val dim = inds.filterNot { highlighted(it) }
        val s = minOf(w, h)
        val solid = color or 0xFF000000.toInt()
        canvas.save()
        canvas.translate((w - s) / 2f, (h - s) / 2f)
        canvas.scale(s, s)
        // Every arrow of a lane grows from one shared stem at the bottom centre (like Google
        // Maps): the directions you shouldn't take are faded together, the one to take is solid.
        if (dim.isNotEmpty()) {
            canvas.saveLayerAlpha(0f, 0f, 1f, 1f, fadedAlpha)
            if (bright.isEmpty()) laneStem(canvas, solid)
            dim.forEach { laneBranch(canvas, it, solid) }
            canvas.restore()
        }
        if (bright.isNotEmpty()) {
            laneStem(canvas, solid)
            bright.forEach { laneBranch(canvas, it, solid) }
        }
        canvas.restore()
    }

    // lane arrow geometry (unit box): stem from the bottom centre up to BRANCH_Y, then branches
    private const val L_STEM = 0.12f
    private const val L_HEAD_LEN = 0.24f
    private const val L_HEAD_HALF = 0.16f
    private const val BRANCH_Y = 0.68f

    private fun lanePaint(color: Int, fill: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (fill) style = Paint.Style.FILL else {
            style = Paint.Style.STROKE
            strokeWidth = L_STEM
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.BUTT
        }
    }

    private fun laneStem(c: Canvas, color: Int) {
        c.drawLine(0.5f, 0.97f, 0.5f, BRANCH_Y - 0.01f, lanePaint(color, false))
    }

    /** One branch (from the top of the shared stem) + its arrowhead. Left shapes mirror right. */
    private fun laneBranch(c: Canvas, ind: String, color: Int) {
        val (kind, mirror) = when (ind) {
            "slight right", "merge to right" -> "slight" to false
            "slight left", "merge to left" -> "slight" to true
            "right" -> "turn" to false
            "left" -> "turn" to true
            "sharp right" -> "sharp" to false
            "sharp left" -> "sharp" to true
            "uturn" -> "uturn" to false // U-turn to the left (right-hand traffic)
            else -> "straight" to false
        }
        if (mirror) {
            c.save()
            c.scale(-1f, 1f, 0.5f, 0.5f)
        }
        val path = Path().apply { moveTo(0.5f, BRANCH_Y) }
        val d = 0.7071f
        val (bx, by, dx, dy) = when (kind) {
            "slight" -> { path.cubicTo(0.5f, 0.58f, 0.64f, 0.52f, 0.72f, 0.44f); floatArrayOf(0.72f, 0.44f, d, -d) }
            "turn" -> { path.lineTo(0.5f, 0.62f); path.quadTo(0.5f, 0.48f, 0.64f, 0.48f); path.lineTo(0.70f, 0.48f); floatArrayOf(0.70f, 0.48f, 1f, 0f) }
            "sharp" -> { path.lineTo(0.5f, 0.50f); path.quadTo(0.5f, 0.36f, 0.62f, 0.44f); floatArrayOf(0.63f, 0.45f, d, d) }
            "uturn" -> { path.lineTo(0.5f, 0.42f); path.cubicTo(0.5f, 0.18f, 0.20f, 0.18f, 0.20f, 0.42f); path.lineTo(0.20f, 0.46f); floatArrayOf(0.20f, 0.46f, 0f, 1f) }
            else -> { path.lineTo(0.5f, 0.04f + L_HEAD_LEN); floatArrayOf(0.5f, 0.04f + L_HEAD_LEN, 0f, -1f) }
        }
        c.drawPath(path, lanePaint(color, false))
        // head: its base tucks slightly over the branch end so there's no hairline
        val ox = bx - dx * 0.015f
        val oy = by - dy * 0.015f
        val head = Path().apply {
            moveTo(bx + dx * L_HEAD_LEN, by + dy * L_HEAD_LEN)
            lineTo(ox - dy * L_HEAD_HALF, oy + dx * L_HEAD_HALF)
            lineTo(ox + dy * L_HEAD_HALF, oy - dx * L_HEAD_HALF)
            close()
        }
        c.drawPath(head, lanePaint(color, true))
        if (mirror) c.restore()
    }

    private fun drawSolid(canvas: Canvas, type: Int, roundaboutExit: Int, w: Float, h: Float, color: Int) {
        val s = minOf(w, h)
        canvas.save()
        canvas.translate((w - s) / 2f, (h - s) / 2f)
        canvas.scale(s, s)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = STEM
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.BUTT
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        when (type) {
            9, 18, 20, 23, 37 -> slight(canvas, stroke, fill, mirror = false)
            16, 19, 21, 24, 38 -> slight(canvas, stroke, fill, mirror = true)
            10, 2 -> turn(canvas, stroke, fill, mirror = false)
            15, 3 -> turn(canvas, stroke, fill, mirror = true)
            11 -> sharp(canvas, stroke, fill, mirror = false)
            14 -> sharp(canvas, stroke, fill, mirror = true)
            13 -> uturn(canvas, stroke, fill, mirror = false)
            12 -> uturn(canvas, stroke, fill, mirror = true)
            26, 27 -> roundabout(canvas, stroke, fill, color, roundaboutExit)
            25 -> merge(canvas, stroke, fill, color)
            4, 5, 6 -> destination(canvas, fill)
            else -> straight(canvas, stroke, fill)
        }
        canvas.restore()
    }

    /** Stem path ending at the arrowhead base, plus a solid head in direction (dx, dy). */
    private fun finish(c: Canvas, stem: Path, stroke: Paint, fill: Paint, baseX: Float, baseY: Float, dx: Float, dy: Float, mirror: Boolean) {
        if (mirror) {
            c.save()
            c.scale(-1f, 1f, 0.5f, 0.5f)
        }
        // round start of the stem
        c.drawPath(stem, stroke)
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val tipX = baseX + ux * HEAD_LEN
        val tipY = baseY + uy * HEAD_LEN
        val px = -uy
        val py = ux
        // the head's base sits slightly over the stem end so there's no hairline between them
        val bx = baseX - ux * 0.03f
        val by = baseY - uy * 0.03f
        val head = Path().apply {
            moveTo(tipX, tipY)
            lineTo(bx + px * HEAD_HALF, by + py * HEAD_HALF)
            lineTo(bx - px * HEAD_HALF, by - py * HEAD_HALF)
            close()
        }
        c.drawPath(head, fill)
        if (mirror) c.restore()
    }

    private fun straight(c: Canvas, stroke: Paint, fill: Paint) {
        val stem = Path().apply { moveTo(0.5f, 0.94f); lineTo(0.5f, 0.06f + HEAD_LEN) }
        finish(c, stem, stroke, fill, 0.5f, 0.06f + HEAD_LEN, 0f, -1f, false)
    }

    private fun turn(c: Canvas, stroke: Paint, fill: Paint, mirror: Boolean) {
        val stem = Path().apply {
            moveTo(0.30f, 0.95f)
            lineTo(0.30f, 0.58f)
            quadTo(0.30f, 0.38f, 0.50f, 0.38f)
            lineTo(0.94f - HEAD_LEN, 0.38f)
        }
        finish(c, stem, stroke, fill, 0.94f - HEAD_LEN, 0.38f, 1f, 0f, mirror)
    }

    private fun slight(c: Canvas, stroke: Paint, fill: Paint, mirror: Boolean) {
        val d = 0.7071f
        val baseX = 0.62f
        val baseY = 0.27f
        val stem = Path().apply {
            moveTo(0.36f, 0.95f)
            lineTo(0.36f, 0.64f)
            quadTo(0.36f, 0.52f, 0.45f, 0.44f)
            lineTo(baseX, baseY)
        }
        finish(c, stem, stroke, fill, baseX, baseY, d, -d, mirror)
    }

    private fun sharp(c: Canvas, stroke: Paint, fill: Paint, mirror: Boolean) {
        val d = 0.7071f
        val stem = Path().apply {
            moveTo(0.30f, 0.95f)
            lineTo(0.30f, 0.40f)
            quadTo(0.30f, 0.20f, 0.45f, 0.30f)
            lineTo(0.52f, 0.37f)
        }
        finish(c, stem, stroke, fill, 0.52f, 0.37f, d, d, mirror)
    }

    /** U-turn to the left (right-hand traffic). */
    private fun uturn(c: Canvas, stroke: Paint, fill: Paint, mirror: Boolean) {
        val stem = Path().apply {
            moveTo(0.68f, 0.95f)
            lineTo(0.68f, 0.42f)
            cubicTo(0.68f, 0.14f, 0.32f, 0.14f, 0.32f, 0.42f)
            lineTo(0.32f, 0.52f)
        }
        finish(c, stem, stroke, fill, 0.32f, 0.52f, 0f, 1f, mirror)
    }

    private fun merge(c: Canvas, stroke: Paint, fill: Paint, color: Int) {
        val side = Paint(stroke).apply { this.color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color)) }
        val joining = Path().apply { moveTo(0.22f, 0.95f); quadTo(0.22f, 0.66f, 0.5f, 0.54f) }
        c.drawPath(joining, side)
        straight(c, stroke, fill)
    }

    /**
     * Roundabout (counter-clockwise): faded ring plus one path for the way you drive: up from the
     * bottom, round the ring to your exit and straight out into the arrow (1st exit right,
     * 2nd straight, 3rd left, 4th back). The bends onto and off the ring are rounded curves that
     * meet each piece tangentially, so there are no corners or notches at the joins.
     */
    private fun roundabout(c: Canvas, stroke: Paint, fill: Paint, color: Int, exitNumber: Int) {
        val cx = 0.5f
        val cy = 0.50f
        val r = 0.19f
        val faded = Paint(stroke).apply {
            this.color = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
            strokeWidth = STEM * 0.75f
        }
        c.drawCircle(cx, cy, r, faded)

        val exit = if (exitNumber <= 0) 2 else exitNumber
        val deg = (90.0 - 90.0 * (exit - 1)).coerceIn(-135.0, 90.0) // compass: 0 = up, 90 = right
        val outA = deg - 90.0 // canvas angle of the exit (0 = right, 90 = bottom)
        val bend = 18.0       // size of the rounded bends, in degrees of ring
        // the bend's corner sits where the straight part meets the ring's tangent
        val kc = (r / cos(Math.toRadians(bend))).toFloat()
        val kt = (r * tan(Math.toRadians(bend))).toFloat()
        val ux = cos(Math.toRadians(outA)).toFloat()
        val uy = sin(Math.toRadians(outA)).toFloat()
        val startA = 90.0 - bend
        val endA = outA + bend
        val bx = cx + ux * (kc + kt)
        val by = cy + uy * (kc + kt)

        val path = Path().apply {
            moveTo(cx, 0.97f)
            lineTo(cx, cy + kc + kt)
            quadTo(cx, cy + kc, cx + r * cos(Math.toRadians(startA)).toFloat(), cy + r * sin(Math.toRadians(startA)).toFloat())
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), startA.toFloat(), (endA - startA).toFloat(), false)
            quadTo(cx + ux * kc, cy + uy * kc, bx, by)
        }
        c.drawPath(path, Paint(stroke).apply { strokeJoin = Paint.Join.ROUND })

        // arrowhead, its base tucked over the end of the path
        val small = 0.75f
        val ox = bx - ux * 0.015f
        val oy = by - uy * 0.015f
        val head = Path().apply {
            moveTo(bx + ux * HEAD_LEN * small, by + uy * HEAD_LEN * small)
            lineTo(ox - uy * HEAD_HALF * small, oy + ux * HEAD_HALF * small)
            lineTo(ox + uy * HEAD_HALF * small, oy - ux * HEAD_HALF * small)
            close()
        }
        c.drawPath(head, fill)
    }

    private fun destination(c: Canvas, fill: Paint) {
        val pin = Path().apply {
            moveTo(0.5f, 0.95f)
            cubicTo(0.42f, 0.72f, 0.20f, 0.56f, 0.20f, 0.38f)
            cubicTo(0.20f, 0.18f, 0.34f, 0.06f, 0.5f, 0.06f)
            cubicTo(0.66f, 0.06f, 0.80f, 0.18f, 0.80f, 0.38f)
            cubicTo(0.80f, 0.56f, 0.58f, 0.72f, 0.5f, 0.95f)
            close()
        }
        c.drawPath(pin, fill)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
        c.drawCircle(0.5f, 0.37f, 0.11f, hole)
    }
}
