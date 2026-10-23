package com.drivemap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivemap.app.i18n.t
import com.drivemap.app.fines.FineEstimate
import com.drivemap.app.geo.Geo
import com.drivemap.app.radar.AlertKind
import com.drivemap.app.radar.RadarAlert
import com.drivemap.app.radar.SectionStatus
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

// ------------------------------------------------------------------ maneuver arrows

/** Turn arrow for a Valhalla maneuver type (shared painter, also used for Android Auto). */
@Composable
fun ManeuverIcon(type: Int, roundaboutExit: Int, modifier: Modifier = Modifier, color: Color = Color.White) {
    val argb = color.toArgb()
    Canvas(modifier) {
        drawIntoCanvas { ArrowPainter.draw(it.nativeCanvas, type, roundaboutExit, size.width, size.height, argb) }
    }
}

// ------------------------------------------------------------------ speed / limit / fine

/** Waze-style limit sign (white disc, coral ring); null = unknown, < 0 = no limit. */
@Composable
fun SpeedLimitSign(limit: Int?, diameter: Dp = 54.dp, implied: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(diameter).shadow(4.dp, CircleShape)) {
        drawIntoCanvas {
            SpeedoPainter.drawLimit(it.nativeCanvas, size.width / 2, size.height / 2, size.minDimension / 2, limit ?: 0, implied)
        }
    }
}

/**
 * Waze-style speedometer. The number counts smoothly to each new GPS speed (landing exactly on
 * it), turns red when speeding, and the tick ring fills orange→red with how far over you are
 * (full ring at +20 km/h).
 */
@Composable
fun Speedometer(speedKmh: Int, limit: Int?, speeding: Boolean, diameter: Dp = 78.dp) {
    // the number and both ring arcs follow one smoothly animated speed
    val shown by animateFloatAsState(speedKmh.toFloat(), tween(450, easing = LinearEasing), label = "speed")
    val hasLimit = limit != null && limit > 0
    val fill = if (hasLimit) (shown / limit!!).coerceIn(0f, 1f) else 0f
    // over the limit the ring refills orange→red: half at 1.5× the limit, full at 2× (75 / 100 km/h in a 50 zone)
    val over = if (hasLimit) ((shown - limit!!) / limit.toFloat()).coerceIn(0f, 1f) else 0f
    val numberColor by animateColorAsState(
        if (speeding) Color(SpeedoPainter.NUMBER_OVER) else Color(SpeedoPainter.NUMBER), tween(350), label = "number",
    )
    val clear = com.drivemap.app.core.Core.settings.state.collectAsState().value.let { it.glass && it.clearGlass }
    Canvas(Modifier.size(diameter).then(if (clear) Modifier else Modifier.shadow(8.dp, CircleShape))) {
        drawIntoCanvas {
            SpeedoPainter.drawSpeedo(
                it.nativeCanvas, size.width / 2, size.height / 2, size.minDimension / 2,
                shown.roundToInt(), numberColor.toArgb(), fill, over, hasLimit, glass = clear,
            )
        }
    }
}

/** Live "fine if caught now" box next to the speedometer. */
@Composable
fun FineBox(fine: FineEstimate?, onClick: () -> Unit) {
    val amount = fine?.amountEur ?: 0.0
    val possible = fine != null && fine.isSpeeding && amount == 0.0 && fine.maxEur > 0
    val (bg, fg) = when {
        fine == null -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant
        amount >= 150 || fine.ban != null -> Palette.Red to Color.White
        amount > 0 -> Palette.Orange to Color.White
        possible -> Tints.orange to Tints.onOrange
        else -> Tints.green to Tints.onGreen
    }
    val solid = fine != null && (amount > 0 || fine.ban != null)
    Surface(
        onClick = onClick, shape = RoundedCornerShape(14.dp), color = bg, shadowElevation = glassShadow(4.dp),
        border = if (solid) null else panelBorder,
    ) {
        Column(Modifier.widthIn(min = 64.dp).padding(horizontal = 10.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val text = when {
                fine == null -> "–"
                amount > 0 -> "€" + String.format("%,.0f", amount)
                possible -> "€0–" + String.format("%.0f", fine.maxEur)
                else -> "€0"
            }
            Text(text, fontSize = if (text.length > 6) 17.sp else 22.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
            Text(if (fine?.ban != null) t("fine + ban") else t("fine"), fontSize = 10.sp, color = fg)
        }
    }
}

/**
 * Waze-style speedometer with the fine box beside it. Like Waze, the limit sign stays hidden
 * while you're within the limit and pops up on the dial's top-right once you're over it.
 */
@Composable
fun SpeedPanel(limit: Int?, implied: Boolean, speedKmh: Int, fine: FineEstimate?, showFine: Boolean, onFineClick: () -> Unit) {
    val speeding = limit != null && limit > 0 && speedKmh > limit + 2
    // keep the limit so the sign can still animate out after you slow down
    var shownLimit by remember { mutableIntStateOf(limit ?: 0) }
    if (speeding) shownLimit = limit!!
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SpeedoWithSign(speedKmh, limit, speeding, shownLimit, implied)
        if (showFine) Box(Modifier.padding(top = 16.dp)) { FineBox(fine, onFineClick) }
    }
}

@Composable
private fun SpeedoWithSign(speedKmh: Int, limit: Int?, speeding: Boolean, shownLimit: Int, implied: Boolean) {
    Box(Modifier.padding(top = 16.dp, end = 12.dp)) {
        Speedometer(speedKmh, limit, speeding)
        AnimatedVisibility(
            visible = speeding,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-16).dp),
            enter = scaleIn(spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium), initialScale = 0.3f) + fadeIn(tween(120)),
            exit = scaleOut(tween(180), targetScale = 0.5f) + fadeOut(tween(180)),
        ) {
            SpeedLimitSign(shownLimit, diameter = 40.dp, implied = implied)
        }
    }
}

// ------------------------------------------------------------------ radar cards

@Composable
fun RadarCard(alert: RadarAlert, onClick: () -> Unit = {}) {
    val accent = when (alert.kind) {
        AlertKind.CAMERA, AlertKind.RED_LIGHT -> if (alert.speeding) Palette.Red else Color(0xFF3C4043)
        AlertKind.SECTION -> Palette.Purple
        AlertKind.MOBILE -> Color(0xFFF57C00)
        AlertKind.POLICE -> Color(0xFF1565C0)
        AlertKind.HAZARD -> Color(0xFFE8A200)
        AlertKind.FREQUENT -> Color(0xFF6A1B9A)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (alert.speeding) Tints.red else MaterialTheme.colorScheme.surface.let { if (it.alpha < 0.5f) it.copy(alpha = 0.85f) else it }, // warnings stay readable on clear glass
        shadowElevation = glassShadow(6.dp), border = panelBorder,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(Glyphs.Camera, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(alert.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (alert.speeding) Palette.Red else MaterialTheme.colorScheme.onSurface)
                if (alert.subtitle.isNotBlank()) Text(alert.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (alert.speeding) Text(t("Slow down!"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Red)
            }
            if (alert.limit != null && alert.limit > 0) {
                SpeedLimitSign(alert.limit, diameter = 40.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(Geo.formatDistance(alert.distanceM), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = accent)
        }
    }
}

@Composable
fun SectionCard(s: SectionStatus) {
    val over = s.over
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (over) Tints.red else MaterialTheme.colorScheme.surface,
        shadowElevation = glassShadow(6.dp), border = panelBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Purple).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(t("AVG"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(t("Average speed zone"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(t("{0} left", Geo.formatDistance(s.remainingM)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format("%.0f", s.avgKmh), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (over) Palette.Red else Palette.Green)
                Text(t(" km/h average"), fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (s.limit != null) Text(t("limit {0}", s.limit), fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            val max = s.maxAllowedKmh
            if (s.limit != null && max != null) {
                Text(
                    when {
                        max <= 0 -> t("Average is already too high — slow down for the rest of the zone")
                        max < s.limit -> t("Keep ≤ {0} km/h until the end to stay legal", max.toInt())
                        else -> t("You can drive up to the limit for the rest of the zone")

                    },
                    fontSize = 13.sp,
                    color = if (max < s.limit) Palette.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.lengthM > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (s.traveledM / s.lengthM).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Palette.Purple,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ small building blocks

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface, bg: Color = MaterialTheme.colorScheme.surface, size: Dp = 48.dp) {
    Surface(
        onClick = onClick, shape = CircleShape, color = bg, shadowElevation = glassShadow(4.dp), modifier = Modifier.size(size),
        border = if (bg == MaterialTheme.colorScheme.surface) panelBorder else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun Tag(text: String, color: Color, bg: Color = color.copy(alpha = 0.12f)) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
