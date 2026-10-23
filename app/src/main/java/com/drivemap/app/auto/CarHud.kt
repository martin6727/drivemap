package com.drivemap.app.auto

import com.drivemap.app.i18n.t
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.drivemap.app.fines.FineEstimate
import com.drivemap.app.geo.Geo
import com.drivemap.app.radar.AlertKind
import com.drivemap.app.radar.RadarAlert
import com.drivemap.app.radar.SectionStatus
import com.drivemap.app.ui.SpeedoPainter

/**
 * Overlay drawn on the car map: speed limit sign, current speed, fine estimate and the radar /
 * average-speed card. Kept inside the visible area the car host leaves for the map.
 */
class CarHud(context: Context) : View(context) {
    data class State(
        val limit: Int?,
        val speed: Int,
        val fine: FineEstimate?,
        val alert: RadarAlert?,
        val section: SectionStatus?,
    )

    var state: State = State(null, 0, null, null, null)
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }

    var area: Rect? = null
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = area ?: Rect(0, 0, width, height)
        val s = state
        val margin = 8 * d
        val size = 46 * d
        val bottom = a.bottom - margin

        // Waze-style speedometer (same painter as the phone): the ring fills towards the limit and
        // the limit sign pops up on its top-right only once you're over it
        val limit = s.limit
        val hasLimit = limit != null && limit > 0
        val speeding = hasLimit && s.speed > limit!! + 2
        val gr = 31 * d
        val gcx = a.left + margin + gr
        val gcy = bottom - gr
        fillPaint.color = Color.argb(70, 0, 0, 0) // soft shadows
        canvas.drawCircle(gcx, gcy + 1.5f * d, gr + 1.5f * d, fillPaint)
        val ringFill = if (hasLimit) (s.speed.toFloat() / limit!!).coerceIn(0f, 1f) else 0f
        val over = if (hasLimit) ((s.speed - limit!!) / limit.toFloat()).coerceIn(0f, 1f) else 0f
        SpeedoPainter.drawSpeedo(
            canvas, gcx, gcy, gr, s.speed, if (speeding) SpeedoPainter.NUMBER_OVER else SpeedoPainter.NUMBER, ringFill, over, hasLimit,
        )
        var rowTop = gcy - gr
        if (speeding && limit != null) {
            val sr = 15 * d
            val scx = gcx + gr * 0.80f
            val scy = gcy - gr * 0.92f
            canvas.drawCircle(scx, scy + d, sr + d, fillPaint)
            SpeedoPainter.drawLimit(canvas, scx, scy, sr, limit)
            rowTop = minOf(rowTop, scy - sr)
        }
        var x = gcx + gr + 10 * d
        val top = gcy - size / 2

        // fine
        val fine = s.fine
        if (fine != null) {
            val amount = fine.amountEur
            val label = when {
                amount > 0 -> "€" + String.format("%,.0f", amount)
                fine.isSpeeding && fine.maxEur > 0 -> "€0–" + String.format("%.0f", fine.maxEur)
                else -> "€0"
            }
            text.textSize = 18 * d
            val w = maxOf(56 * d, text.measureText(label) + 18 * d)
            val r = RectF(x, top, x + w, top + size)
            fillPaint.color = when {
                amount >= 150 || fine.ban != null -> Color.rgb(217, 48, 37)
                amount > 0 -> Color.rgb(227, 116, 0)
                else -> Color.rgb(24, 128, 56)
            }
            canvas.drawRoundRect(r, 12 * d, 12 * d, fillPaint)
            text.color = Color.WHITE
            canvas.drawText(label, r.centerX(), r.centerY() + 2 * d, text)
            text.textSize = 9 * d
            text.typeface = Typeface.DEFAULT
            canvas.drawText(if (fine.ban != null) t("fine + ban") else t("fine"), r.centerX(), r.bottom - 6 * d, text)
            text.typeface = Typeface.DEFAULT_BOLD
        }

        // radar / average speed card above the speed row
        val cardBottom = rowTop - 6 * d
        val section = s.section
        val alert = s.alert
        val line1: String
        val line2: String
        val accent: Int
        when {
            section != null -> {
                line1 = t("AVG {0} km/h", section.avgKmh.toInt()) + (section.limit?.let { " · " + t("limit {0}", it) } ?: "")
                line2 = section.maxAllowedKmh?.let { m ->
                    if (m <= 0) t("Average too high – slow down") else t("Keep ≤ {0} km/h", m.toInt()) + " · " + t("{0} left", Geo.formatDistance(section.remainingM))
                } ?: t("{0} left", Geo.formatDistance(section.remainingM))
                accent = if (section.over) Color.rgb(217, 48, 37) else Color.rgb(142, 36, 170)
            }
            alert != null -> {
                line1 = alert.title + " · " + Geo.formatDistance(alert.distanceM)
                line2 = alert.subtitle.ifBlank { if (alert.speeding) t("Slow down!") else "" }

                accent = when (alert.kind) {
                    AlertKind.MOBILE -> Color.rgb(245, 124, 0)
                    AlertKind.POLICE -> Color.rgb(21, 101, 192)
                    AlertKind.SECTION -> Color.rgb(142, 36, 170)
                    AlertKind.HAZARD -> Color.rgb(232, 162, 0)
                    AlertKind.FREQUENT -> Color.rgb(106, 27, 154)
                    else -> if (alert.speeding) Color.rgb(217, 48, 37) else Color.rgb(60, 64, 67)
                }
            }
            else -> return
        }
        text.textAlign = Paint.Align.LEFT
        text.textSize = 14 * d
        val w1 = text.measureText(line1)
        text.textSize = 12 * d
        val w2 = text.measureText(line2)
        val cardW = (maxOf(w1, w2) + 30 * d).coerceAtMost((a.width() - 2 * margin).toFloat())
        val cardH = if (line2.isBlank()) 30 * d else 46 * d
        val card = RectF(a.left + margin, cardBottom - cardH, a.left + margin + cardW, cardBottom)
        fillPaint.color = Color.argb(240, 0, 0, 0)
        canvas.drawRoundRect(card, 12 * d, 12 * d, fillPaint)
        fillPaint.color = accent
        canvas.drawRoundRect(RectF(card.left, card.top, card.left + 6 * d, card.bottom), 3 * d, 3 * d, fillPaint)
        text.color = Color.WHITE
        text.textSize = 14 * d
        canvas.drawText(line1, card.left + 16 * d, card.top + 19 * d, text)
        if (line2.isNotBlank()) {
            text.textSize = 12 * d
            text.typeface = Typeface.DEFAULT
            text.color = Color.argb(220, 255, 255, 255)
            canvas.drawText(line2, card.left + 16 * d, card.top + 37 * d, text)
            text.typeface = Typeface.DEFAULT_BOLD
        }
        text.textAlign = Paint.Align.CENTER
    }
}
