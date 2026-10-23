package com.drivemap.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.sqrt

/**
 * Waze-style speedometer (dark round dial, ring of ticks, big condensed number + "km/h") and
 * speed-limit sign. Drawn with android.graphics so the phone and Android Auto look identical.
 * Main thread only (shared paints).
 */
object SpeedoPainter {
    const val NUMBER = Color.WHITE
    const val NUMBER_OVER = 0xFFF7635A.toInt()

    private const val TICKS = 60
    private const val FACE_TOP = 0xFF4F5D69.toInt()
    private const val FACE_BOTTOM = 0xFF212A32.toInt()
    private const val RIM = 0xFF182027.toInt()
    private const val TICK = 0xFF9DA6AC.toInt()      // no known limit
    private const val TICK_LIT = 0xFFE4E8EB.toInt()  // filled up to the current speed
    private const val TICK_DIM = 0xFF55616B.toInt()  // not reached yet
    private const val OVER_START = 0xFFFF9100.toInt() // orange at the bottom…
    private const val OVER_END = 0xFFE8322B.toInt()   // …turning red as the arc grows
    private const val SIGN_RING = 0xFFF26B63.toInt()
    private const val SIGN_RING_SOFT = 0xFFF5A8A2.toInt() // limit guessed from the road type

    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val regular: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val bounds = android.graphics.Rect()
    private val faceShader = LinearGradient(0f, -1f, 0f, 1f, FACE_TOP, FACE_BOTTOM, Shader.TileMode.CLAMP)
    private val matrix = Matrix()

    /**
     * Like Waze: the tick ring fills from the bottom, clockwise, as the speed approaches the limit
     * ([fill] 0..1, full ring = at the limit); above it, the extra speed paints the ring
     * orange→red from the bottom again ([over] 0..1, full at double the limit). With no known limit the
     * ring stays neutral. Fractional values light the last tick partly, so the arcs glide.
     */
    fun drawSpeedo(c: Canvas, cx: Float, cy: Float, r: Float, speed: Int, numberColor: Int, fill: Float, over: Float, hasLimit: Boolean, glass: Boolean = false) {
        if (glass) {
            // clear glass: a faint tint so the map shows through, bright glass rim
            paint.color = android.graphics.Color.argb(56, 0, 0, 0)
            c.drawCircle(cx, cy, r, paint)
            stroke.strokeWidth = r * 0.045f
            stroke.color = android.graphics.Color.argb(170, 255, 255, 255)
        } else {
            // dial face: soft top-to-bottom gradient + dark rim
            matrix.setScale(r, r)
            matrix.postTranslate(cx, cy)
            faceShader.setLocalMatrix(matrix)
            paint.shader = faceShader
            c.drawCircle(cx, cy, r, paint)
            paint.shader = null
            stroke.strokeWidth = r * 0.045f
            stroke.color = RIM
        }
        c.drawCircle(cx, cy, r - stroke.strokeWidth / 2, stroke)

        // tick ring
        val r1 = r * 0.79f
        val r2 = r * 0.905f
        val tw = r * 0.052f
        val base = fill.coerceIn(0f, 1f) * TICKS
        val red = over.coerceIn(0f, 1f) * TICKS
        for (i in 0 until TICKS) {
            var col = if (!hasLimit) TICK else blend(TICK_DIM, TICK_LIT, (base - i).coerceIn(0f, 1f))
            if (red > i) col = blend(col, overColor(i), (red - i).coerceIn(0f, 1f))
            paint.color = col
            c.save()
            c.rotate(90f + (i + 0.5f) * 360f / TICKS, cx, cy) // 90° = bottom; growing clockwise
            rect.set(cx + r1, cy - tw / 2, cx + r2, cy + tw / 2)
            c.drawRoundRect(rect, tw * 0.3f, tw * 0.3f, paint)
            c.restore()
        }
        // faint inner edge for depth
        stroke.strokeWidth = r * 0.02f
        stroke.color = 0x30000000
        c.drawCircle(cx, cy, r * 0.75f, stroke)

        // number + unit, placed from the real glyph heights so the digits keep clear of the
        // tick ring whatever the font or number of digits
        val s = speed.toString()
        text.typeface = bold
        text.textSize = r * 0.88f
        val maxW = r * 1.24f
        val w = text.measureText(s)
        if (w > maxW) text.textSize *= maxW / w
        text.getTextBounds(s, 0, s.length, bounds)
        val numBaseline = cy - r * 0.58f - bounds.top // digits' top edge at 0.58 r above the centre
        text.color = numberColor
        c.drawText(s, cx, numBaseline, text)
        text.typeface = regular
        text.textSize = r * 0.30f
        text.getTextBounds("km/h", 0, 4, bounds)
        text.color = Color.WHITE
        c.drawText("km/h", cx, numBaseline + r * 0.11f - bounds.top, text)
    }

    /**
     * Speed-limit sign: white disc, coral ring, black condensed number. [limit] < 0 draws the
     * "no limit" sign (e.g. German autobahn), 0 an unknown limit.
     */
    fun drawLimit(c: Canvas, cx: Float, cy: Float, r: Float, limit: Int, soft: Boolean = false) {
        paint.shader = null
        paint.color = Color.WHITE
        c.drawCircle(cx, cy, r, paint)
        if (limit < 0) {
            stroke.color = 0xFF9AA0A6.toInt()
            stroke.strokeWidth = r * 0.07f
            c.drawCircle(cx, cy, r * 0.93f, stroke)
            // five diagonal stripes, bottom-left to top-right, clipped to the disc
            stroke.color = 0xFF3C4043.toInt()
            stroke.strokeWidth = r * 0.07f
            val inner = r * 0.78f
            val k = 0.7071f
            for (n in -2..2) {
                val o = n * r * 0.17f
                val half = sqrt((inner * inner - o * o).coerceAtLeast(0f))
                val mx = cx + o * k
                val my = cy + o * k
                c.drawLine(mx - half * k, my + half * k, mx + half * k, my - half * k, stroke)
            }
            return
        }
        val ring = r * 0.22f
        stroke.color = if (soft) SIGN_RING_SOFT else SIGN_RING
        stroke.strokeWidth = ring
        c.drawCircle(cx, cy, r - ring / 2, stroke)
        val s = if (limit == 0) "–" else limit.toString()
        text.typeface = bold
        text.textSize = r * 0.98f
        val maxW = (r - ring) * 1.6f
        val w = text.measureText(s)
        if (w > maxW) text.textSize *= maxW / w
        text.color = 0xFF111111.toInt()
        c.drawText(s, cx, cy + text.textSize * 0.355f, text)
    }

    private fun overColor(i: Int): Int = blend(OVER_START, OVER_END, (i / (TICKS * 0.25f)).coerceAtMost(1f))

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * u).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
