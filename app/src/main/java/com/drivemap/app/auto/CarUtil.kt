package com.drivemap.app.auto

import com.drivemap.app.i18n.t
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import com.drivemap.app.nav.NavState
import com.drivemap.app.route.LaneInfo
import com.drivemap.app.route.Maneuver as RouteManeuver
import com.drivemap.app.ui.ArrowPainter
import java.util.TimeZone

/** Conversions from DriveMap navigation state to Android Auto templates. */
object CarUtil {

    fun icon(ctx: CarContext, res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

    fun distance(m: Double): Distance = when {
        m < 1000 -> Distance.create(((m / 10).toInt() * 10).toDouble().coerceAtLeast(0.0), Distance.UNIT_METERS)
        m < 10_000 -> Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS)
    }

    /** Valhalla maneuver type → Android Auto maneuver type. */
    fun carType(t: Int, roundaboutExit: Int): Int = when (t) {
        1, 2, 3 -> Maneuver.TYPE_DEPART
        4 -> Maneuver.TYPE_DESTINATION
        5 -> Maneuver.TYPE_DESTINATION_RIGHT
        6 -> Maneuver.TYPE_DESTINATION_LEFT
        7 -> Maneuver.TYPE_NAME_CHANGE
        8, 17, 22 -> Maneuver.TYPE_STRAIGHT
        9 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        10 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        11 -> Maneuver.TYPE_TURN_SHARP_RIGHT
        12 -> Maneuver.TYPE_U_TURN_RIGHT
        13 -> Maneuver.TYPE_U_TURN_LEFT
        14 -> Maneuver.TYPE_TURN_SHARP_LEFT
        15 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        16 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        18 -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
        19 -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
        20 -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        21 -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
        23 -> Maneuver.TYPE_KEEP_RIGHT
        24 -> Maneuver.TYPE_KEEP_LEFT
        25 -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        37 -> Maneuver.TYPE_MERGE_RIGHT
        38 -> Maneuver.TYPE_MERGE_LEFT
        26 -> if (roundaboutExit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        27 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
        28 -> Maneuver.TYPE_FERRY_BOAT
        else -> Maneuver.TYPE_STRAIGHT
    }

    private fun laneShape(indication: String): Int = when (indication) {
        "slight right" -> LaneDirection.SHAPE_SLIGHT_RIGHT
        "right" -> LaneDirection.SHAPE_NORMAL_RIGHT
        "sharp right" -> LaneDirection.SHAPE_SHARP_RIGHT
        "slight left" -> LaneDirection.SHAPE_SLIGHT_LEFT
        "left" -> LaneDirection.SHAPE_NORMAL_LEFT
        "sharp left" -> LaneDirection.SHAPE_SHARP_LEFT
        "uturn" -> LaneDirection.SHAPE_U_TURN_LEFT
        "straight", "none", "" -> LaneDirection.SHAPE_STRAIGHT
        else -> LaneDirection.SHAPE_UNKNOWN
    }

    fun step(m: RouteManeuver, lanes: List<LaneInfo>?): Step {
        val type = carType(m.type, m.roundaboutExit)
        val maneuver = Maneuver.Builder(type)
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(ArrowPainter.bitmap(m.type, m.roundaboutExit, 128, Color.WHITE))).build())
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) maneuver.setRoundaboutExitNumber(m.roundaboutExit.coerceAtLeast(1))
        val road = m.streetNames.firstOrNull()
        val toward = m.sign?.toward?.takeIf { it.isNotEmpty() }?.let { t("toward {0}", it.take(2).joinToString(", ")) }

        val cue = road ?: toward ?: m.instruction
        val b = Step.Builder(cue).setManeuver(maneuver.build())
        if (road != null) b.setRoad(road)
        if (!lanes.isNullOrEmpty()) {
            for (lane in lanes) {
                val lb = Lane.Builder()
                val inds = lane.indications.filter { it.isNotBlank() && it != "none" }.ifEmpty { listOf("straight") }
                for (ind in inds) {
                    val recommended = lane.valid && (lane.validIndication == null || lane.validIndication == ind)
                    lb.addDirection(LaneDirection.create(laneShape(ind), recommended))
                }
                b.addLane(lb.build())
            }
            b.setLanesImage(CarIcon.Builder(IconCompat.createWithBitmap(lanesBitmap(lanes))).build())
        }
        return b.build()
    }

    /** Lane arrows side by side: recommended white, others faded (also used by the phone UI style). */
    fun lanesBitmap(lanes: List<LaneInfo>): Bitmap {
        val cell = 64
        val gap = 10
        val w = lanes.size * cell + (lanes.size - 1) * gap
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), cell, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val divider = Paint().apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 3f }
        lanes.forEachIndexed { i, lane ->
            val x = i * (cell + gap)
            if (i > 0) c.drawLine(x - gap / 2f, 8f, x - gap / 2f, cell - 8f, divider)
            c.save()
            c.translate(x.toFloat(), 0f)
            ArrowPainter.drawLane(c, lane, cell.toFloat(), cell.toFloat())
            c.restore()
        }
        return bmp
    }

    fun routingInfo(nav: NavState): RoutingInfo {
        if (nav.rerouting) return RoutingInfo.Builder().setLoading(true).build()
        val m = nav.next ?: return RoutingInfo.Builder().setLoading(true).build()
        val lanes = nav.route.lanes[nav.maneuverIdx]?.takeIf { nav.distToManeuverM < 1500 }
        val b = RoutingInfo.Builder().setCurrentStep(step(m, lanes), distance(nav.distToManeuverM))
        val then = nav.then
        if (then != null && then.alongM - m.alongM < 1000) b.setNextStep(step(then, null))
        return b.build()
    }

    fun travelEstimate(nav: NavState): TravelEstimate =
        TravelEstimate.Builder(
            distance(nav.remainingM),
            DateTimeWithZone.create(nav.etaMs, TimeZone.getDefault()),
        ).setRemainingTimeSeconds(nav.remainingS.toLong().coerceAtLeast(0)).build()
}
