package com.drivemap.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/** Map marker bitmaps drawn in code (no image assets needed). */
object MapIcons {

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    private fun bitmap(ctx: Context, wDp: Float, hDp: Float, draw: Canvas.(Float) -> Unit): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val b = Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(1), (hDp * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(b).draw(d)
        return b
    }

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.style = style
        strokeWidth = stroke
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Camera glyph centred at (cx, cy) with size s. */
    private fun Canvas.cameraGlyph(cx: Float, cy: Float, s: Float, color: Int) {
        val p = paint(color)
        drawRoundRect(RectF(cx - s * 0.55f, cy - s * 0.32f, cx + s * 0.45f, cy + s * 0.38f), s * 0.12f, s * 0.12f, p)
        drawRect(cx + s * 0.40f, cy - s * 0.18f, cx + s * 0.62f, cy + s * 0.24f, p)
        drawCircle(cx - s * 0.05f, cy + s * 0.03f, s * 0.2f, paint(Color.WHITE))
        drawCircle(cx - s * 0.05f, cy + s * 0.03f, s * 0.12f, p)
    }

    fun camera(ctx: Context, accent: Int): Bitmap = bitmap(ctx, 30f, 30f) { d ->
        val c = 15f * d
        drawCircle(c, c, 13.5f * d, paint(Color.WHITE))
        drawCircle(c, c, 13.5f * d, paint(accent, Paint.Style.STROKE, 2.2f * d))
        cameraGlyph(c, c, 16f * d, accent)
    }

    fun section(ctx: Context): Bitmap = bitmap(ctx, 38f, 24f) { d ->
        val r = RectF(1f * d, 1f * d, 37f * d, 23f * d)
        drawRoundRect(r, 7f * d, 7f * d, paint(0xFF8E24AA.toInt()))
        drawRoundRect(r, 7f * d, 7f * d, paint(Color.WHITE, Paint.Style.STROKE, 1.6f * d))
        val tp = paint(Color.WHITE).apply { textSize = 11f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        drawText("AVG", 19f * d, 16f * d, tp)
    }

    fun report(ctx: Context, bg: Int, kind: String): Bitmap = bitmap(ctx, 32f, 32f) { d ->
        val c = 16f * d
        drawCircle(c, c, 14.5f * d, paint(bg))
        drawCircle(c, c, 14.5f * d, paint(Color.WHITE, Paint.Style.STROKE, 2.4f * d))
        when (kind) {
            "mobile" -> cameraGlyph(c, c, 16f * d, Color.WHITE)
            "police" -> {
                // shield
                val s = 9f * d
                val path = Path().apply {
                    moveTo(c, c - s)
                    lineTo(c + s * 0.85f, c - s * 0.6f)
                    lineTo(c + s * 0.7f, c + s * 0.35f)
                    lineTo(c, c + s)
                    lineTo(c - s * 0.7f, c + s * 0.35f)
                    lineTo(c - s * 0.85f, c - s * 0.6f)
                    close()
                }
                drawPath(path, paint(Color.WHITE))
                drawCircle(c, c - s * 0.05f, s * 0.28f, paint(bg))
            }
            else -> {
                val tp = paint(Color.WHITE).apply { textSize = 18f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
                drawText("!", c, c + 6.5f * d, tp)
            }
        }
    }

    /** Google-style red teardrop pin. */
    fun pin(ctx: Context, color: Int = 0xFFEA4335.toInt(), dot: Int = 0xFFB31412.toInt()): Bitmap = bitmap(ctx, 28f, 40f) { d ->
        val cx = 14f * d
        val r = 12f * d
        val path = Path().apply {
            moveTo(cx, 39f * d)
            cubicTo(cx - 3f * d, 30f * d, cx - r, 24f * d, cx - r, 14f * d)
            arcTo(RectF(cx - r, 2f * d, cx + r, 26f * d), 180f, 180f)
            cubicTo(cx + r, 24f * d, cx + 3f * d, 30f * d, cx, 39f * d)
            close()
        }
        drawPath(path, paint(color))
        drawPath(path, paint(0x33000000, Paint.Style.STROKE, 1f * d))
        drawCircle(cx, 14f * d, 4.5f * d, paint(dot))
    }

    fun dot(ctx: Context, color: Int): Bitmap = bitmap(ctx, 18f, 18f) { d ->
        drawCircle(9f * d, 9f * d, 7.5f * d, paint(color))
        drawCircle(9f * d, 9f * d, 7.5f * d, paint(Color.WHITE, Paint.Style.STROKE, 2f * d))
    }

    fun flag(ctx: Context, color: Int): Bitmap = bitmap(ctx, 30f, 36f) { d ->
        drawLine(7f * d, 4f * d, 7f * d, 34f * d, paint(0xFF3C4043.toInt(), Paint.Style.STROKE, 2.4f * d))
        val path = Path().apply {
            moveTo(8f * d, 4f * d)
            lineTo(27f * d, 9.5f * d)
            lineTo(8f * d, 16f * d)
            close()
        }
        drawPath(path, paint(color))
        drawPath(path, paint(Color.WHITE, Paint.Style.STROKE, 1.5f * d))
        drawCircle(7f * d, 34f * d, 2.5f * d, paint(0xFF3C4043.toInt()))
    }

    /**
     * Top-down car pointing up (north), shaded so it reads as a 3D body: soft shadow, wheels,
     * a body that is lighter along the centre and darker at the sides, tinted glass, roof,
     * mirrors, headlights and tail lights. Rotated by bearing and laid flat on the map.
     */
    fun car(ctx: Context, color: Int): Bitmap = bitmap(ctx, 34f, 52f) { d ->
        fun r(l: Float, t: Float, rr: Float, b: Float) = RectF(l * d, t * d, rr * d, b * d)
        val dark = blend(color, Color.BLACK, 0.45f)
        val light = blend(color, Color.WHITE, 0.35f)
        // soft ground shadow
        val shadow = paint(0x66000000).apply { maskFilter = android.graphics.BlurMaskFilter(3.5f * d, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        drawRoundRect(r(7f, 7f, 27f, 48f), 8f * d, 8f * d, shadow)
        // wheels peeking out
        val tyre = paint(0xFF1B1B1F.toInt())
        for (y in listOf(11.5f, 36.5f)) {
            drawRoundRect(r(5.3f, y, 9.5f, y + 7.5f), 1.5f * d, 1.5f * d, tyre)
            drawRoundRect(r(24.5f, y, 28.7f, y + 7.5f), 1.5f * d, 1.5f * d, tyre)
        }
        // body: dark sides → light centre → dark sides
        val body = Path().apply {
            moveTo(17f * d, 3f * d)
            cubicTo(23.5f * d, 3f * d, 26f * d, 5.5f * d, 26f * d, 11f * d)
            lineTo(26.5f * d, 41f * d)
            cubicTo(26.5f * d, 46f * d, 23f * d, 47.5f * d, 17f * d, 47.5f * d)
            cubicTo(11f * d, 47.5f * d, 7.5f * d, 46f * d, 7.5f * d, 41f * d)
            lineTo(8f * d, 11f * d)
            cubicTo(8f * d, 5.5f * d, 10.5f * d, 3f * d, 17f * d, 3f * d)
            close()
        }
        drawPath(body, paint(color).apply {
            shader = android.graphics.LinearGradient(7.5f * d, 0f, 26.5f * d, 0f,
                intArrayOf(dark, color, light, color, dark), floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), android.graphics.Shader.TileMode.CLAMP)
        })
        drawPath(body, paint(0xE6FFFFFF.toInt(), Paint.Style.STROKE, 1.3f * d))
        // mirrors
        val mirror = paint(dark)
        drawRoundRect(r(5f, 15.5f, 8.5f, 18.5f), 1.2f * d, 1.2f * d, mirror)
        drawRoundRect(r(25.5f, 15.5f, 29f, 18.5f), 1.2f * d, 1.2f * d, mirror)
        // glass: windscreen, side windows, rear window (dark tint with a sky reflection)
        val glass = paint(0xFF1E2833.toInt()).apply {
            shader = android.graphics.LinearGradient(0f, 13f * d, 0f, 38f * d, 0xFF4A6178.toInt(), 0xFF141B22.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        val windscreen = Path().apply {
            moveTo(10.5f * d, 19f * d); quadTo(17f * d, 12.5f * d, 23.5f * d, 19f * d)
            lineTo(22.5f * d, 22.5f * d); lineTo(11.5f * d, 22.5f * d); close()
        }
        drawPath(windscreen, glass)
        drawRoundRect(r(9.3f, 23.5f, 10.8f, 33f), 0.8f * d, 0.8f * d, glass)
        drawRoundRect(r(23.2f, 23.5f, 24.7f, 33f), 0.8f * d, 0.8f * d, glass)
        val rear = Path().apply {
            moveTo(11.5f * d, 34.5f * d); lineTo(22.5f * d, 34.5f * d); lineTo(21.3f * d, 38.6f * d)
            quadTo(17f * d, 39.6f * d, 12.7f * d, 38.6f * d); close()
        }
        drawPath(rear, glass)
        // roof with a highlight
        drawRoundRect(r(11.8f, 22.8f, 22.2f, 34f), 2.5f * d, 2.5f * d, paint(color).apply {
            shader = android.graphics.LinearGradient(11.8f * d, 0f, 22.2f * d, 0f,
                intArrayOf(color, light, color), null, android.graphics.Shader.TileMode.CLAMP)
        })
        drawLine(14f * d, 20f * d, 19.5f * d, 17.5f * d, paint(0x55FFFFFF, Paint.Style.STROKE, 1.1f * d))
        // headlights and tail lights
        val head = paint(0xFFFFF8D6.toInt())
        drawRoundRect(r(9.5f, 4.8f, 13.5f, 6.8f), 1f * d, 1f * d, head)
        drawRoundRect(r(20.5f, 4.8f, 24.5f, 6.8f), 1f * d, 1f * d, head)
        val tail = paint(0xFFE53935.toInt())
        drawRoundRect(r(9f, 44.3f, 13f, 46f), 0.8f * d, 0.8f * d, tail)
        drawRoundRect(r(21f, 44.3f, 25f, 46f), 0.8f * d, 0.8f * d, tail)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** White rounded label background (stretched with icon-text-fit). */
    fun bubble(ctx: Context, fill: Int, stroke: Int): Bitmap = bitmap(ctx, 24f, 24f) { d ->
        val r = RectF(1f * d, 1f * d, 23f * d, 23f * d)
        drawRoundRect(r, 8f * d, 8f * d, paint(fill))
        drawRoundRect(r, 8f * d, 8f * d, paint(stroke, Paint.Style.STROKE, 1.2f * d))
    }

    /** Head of the turn arrow drawn on the route (points up; rotated on the map). */
    fun arrowHead(ctx: Context, outline: Int): Bitmap = bitmap(ctx, 26f, 22f) { d ->
        val path = Path().apply {
            moveTo(13f * d, 1.5f * d)
            lineTo(24.5f * d, 20.5f * d)
            lineTo(1.5f * d, 20.5f * d)
            close()
        }
        drawPath(path, paint(outline, Paint.Style.STROKE, 2.6f * d))
        drawPath(path, paint(Color.WHITE))
    }

    /** Small chevron pointing along the route (drawn on the line every ~100 dp). */
    fun chevron(ctx: Context): Bitmap = bitmap(ctx, 14f, 14f) { d ->
        val path = Path().apply {
            moveTo(4f * d, 2.5f * d)
            lineTo(10f * d, 7f * d)
            lineTo(4f * d, 11.5f * d)
        }
        drawPath(path, paint(0xE6FFFFFF.toInt(), Paint.Style.STROKE, 2.4f * d).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })
    }

    fun waypoint(ctx: Context): Bitmap = bitmap(ctx, 22f, 22f) { d ->
        drawCircle(11f * d, 11f * d, 9f * d, paint(Color.WHITE))
        drawCircle(11f * d, 11f * d, 9f * d, paint(0xFF3C4043.toInt(), Paint.Style.STROKE, 3f * d))
    }
}
