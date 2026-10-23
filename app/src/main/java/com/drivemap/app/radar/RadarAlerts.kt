package com.drivemap.app.radar

import com.drivemap.app.i18n.t
import com.drivemap.app.i18n.tn
import com.drivemap.app.data.Countries
import com.drivemap.app.data.SettingsStore
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.location.Fix
import com.drivemap.app.nav.NavState
import com.drivemap.app.nav.Phrases
import com.drivemap.app.nav.Voice
import com.drivemap.app.speed.RoadInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sin

enum class AlertKind { CAMERA, RED_LIGHT, SECTION, MOBILE, POLICE, HAZARD, FREQUENT }

data class RadarAlert(
    val key: String,
    val kind: AlertKind,
    val pos: LatLon,
    val distanceM: Double,
    val limit: Int?,
    val title: String,
    val subtitle: String,
    val speeding: Boolean,
    val reportId: String? = null,
)

data class SectionStatus(
    val id: Long,
    val limit: Int?,
    val avgKmh: Double,
    val elapsedS: Double,
    val traveledM: Double,
    val lengthM: Double,
    /** Highest speed for the rest of the section that keeps the average at the limit. */
    val maxAllowedKmh: Double?,
) {
    val remainingM get() = (lengthM - traveledM).coerceAtLeast(0.0)
    val over get() = limit != null && avgKmh > limit
}

data class VotePrompt(val reportId: String, val type: ReportType, val shownAt: Long)

/**
 * "Intelligent radar": warns only about cameras that are ahead on your path and check your
 * direction, escalates when you are too fast, tracks average-speed sections and asks whether
 * reported mobile radars are still there.
 */
class RadarAlerter(
    private val cameras: CameraRepository,
    private val reports: ReportsRepository,
    private val settings: SettingsStore,
    private val voice: Voice,
    private val hotspots: HotspotStore,
) {
    private val _alert = MutableStateFlow<RadarAlert?>(null)
    val alert: StateFlow<RadarAlert?> = _alert
    private val _section = MutableStateFlow<SectionStatus?>(null)
    val section: StateFlow<SectionStatus?> = _section
    private val _vote = MutableStateFlow<VotePrompt?>(null)
    val vote: StateFlow<VotePrompt?> = _vote
    private val _blocked = MutableStateFlow(false)
    /** True while in a country where camera warnings are illegal. */
    val blocked: StateFlow<Boolean> = _blocked

    private val announced = HashMap<String, Long>()
    /** Warnings announced since navigation started (for the arrival summary). */
    var tripAlerts = 0
    private val urgentDone = HashSet<String>()
    private val passedReports = HashSet<String>()
    private var lastAlertKey: String? = null
    private var lastAlertDist = Double.MAX_VALUE

    // section control tracking
    private var secStart: Long = 0
    private var secOdo = 0.0
    private var secCamera: Camera? = null
    private var secLength = 0.0
    private var lastFix: Fix? = null

    fun onFix(fix: Fix, nav: NavState?, road: RoadInfo?, country: String?) {
        val s = settings.value
        val prev = lastFix
        lastFix = fix
        val banned = s.respectCameraBans && country != null && country in Countries.cameraWarningBanned
        _blocked.value = banned
        if (!s.radarAlerts || banned) {
            _alert.value = null
            _section.value = null
            secCamera = null
            return
        }
        if (prev != null) secOdo += Geo.dist(prev.pos, fix.pos)
        val speed = fix.speedKmh
        val alertDist = (fix.speedMps * 25).coerceIn(250.0, 1200.0)

        // --- candidates ahead
        data class Cand(val key: String, val kind: AlertKind, val pos: LatLon, val dist: Double, val limit: Int?, val camera: Camera?, val report: Report?, val spot: Hotspot? = null)
        val cands = ArrayList<Cand>()

        if (nav != null && nav.route.cameras.isNotEmpty()) {
            for (rc in nav.route.cameras) {
                val d = rc.alongM - nav.alongM
                if (d < -15 || d > alertDist) continue
                cands += Cand("c${rc.camera.id}", kindOf(rc.camera), rc.camera.pos, d.coerceAtLeast(0.0), rc.camera.maxspeed ?: rc.camera.section?.maxspeed, rc.camera, null)
            }
        } else if (fix.hasBearing && fix.speedMps > 2) {
            for (c in cameras.near(fix.pos, alertDist + 50)) {
                val d = aheadDistance(fix, c.pos) ?: continue
                if (!cameras.appliesTo(c, fix.bearing)) continue
                cands += Cand("c${c.id}", kindOf(c), c.pos, d, c.maxspeed ?: c.section?.maxspeed, c, null)
            }
        }
        if (s.mobileReports || reports.convoyChannel != null) {
            for (r in reports.reports.value) {
                if (r.expired) continue
                val d: Double = if (nav != null) {
                    val hit = nav.route.path.project(r.pos, (nav.route.path.segmentAt(nav.alongM) - 2).coerceAtLeast(0),
                        (nav.route.path.segmentAt(nav.alongM + alertDist) + 2).coerceAtMost(nav.route.path.points.size - 1))
                    if (hit == null || hit.crossTrack > 60) continue
                    val dd = hit.along - nav.alongM
                    if (dd < -15 || dd > alertDist) continue
                    dd.coerceAtLeast(0.0)
                } else {
                    if (!fix.hasBearing || fix.speedMps < 2) continue
                    aheadDistance(fix, r.pos, maxCross = 60.0) ?: continue
                }
                if (r.bearing != null && fix.hasBearing && Geo.absAngle(r.bearing, fix.bearing) > 70 && r.type == ReportType.MOBILE_RADAR) continue
                val kind = when (r.type) {
                    ReportType.MOBILE_RADAR -> AlertKind.MOBILE
                    ReportType.POLICE -> AlertKind.POLICE
                    else -> AlertKind.HAZARD
                }
                cands += Cand("r${r.id}", kind, r.pos, d, null, null, r)
                // "Still there?" when passing close by
                if (d < 40 && r.id !in passedReports && (r.type == ReportType.MOBILE_RADAR || r.type == ReportType.POLICE)) {
                    passedReports.add(r.id)
                    _vote.value = VotePrompt(r.id, r.type, System.currentTimeMillis())
                }
            }
        }
        // places where police often measure speed (learned from past reports)
        if (s.hotspotAlerts) {
            val live = reports.reports.value.filter { !it.expired }
            for (h in hotspots.near(fix.pos, alertDist + 100)) {
                if (live.any { Geo.dist(it.pos, h.pos) < 250 }) continue // a live report already warns here
                if (h.bearing != null && fix.hasBearing && Geo.absAngle(h.bearing, fix.bearing) > 70) continue
                val d: Double = if (nav != null) {
                    val hit = nav.route.path.project(h.pos, (nav.route.path.segmentAt(nav.alongM) - 2).coerceAtLeast(0),
                        (nav.route.path.segmentAt(nav.alongM + alertDist) + 2).coerceAtMost(nav.route.path.points.size - 1))
                    if (hit == null || hit.crossTrack > 60) continue
                    val dd = hit.along - nav.alongM
                    if (dd < -15 || dd > alertDist) continue
                    dd.coerceAtLeast(0.0)
                } else {
                    if (!fix.hasBearing || fix.speedMps < 2) continue
                    aheadDistance(fix, h.pos, maxCross = 60.0) ?: continue
                }
                cands += Cand("h${h.id}", AlertKind.FREQUENT, h.pos, d, null, null, null, h)
            }
        }
        _vote.value?.let { if (System.currentTimeMillis() - it.shownAt > 15_000) _vote.value = null }

        val best = cands.minByOrNull { it.dist }
        if (best == null) {
            _alert.value = null
            lastAlertKey = null
        } else {
            val limit = best.limit ?: road?.limit?.takeIf { it > 0 }
            val speeding = limit != null && speed > limit + 3
            val title = when (best.kind) {
                AlertKind.CAMERA -> t("Speed camera")
                AlertKind.RED_LIGHT -> t("Red light camera")
                AlertKind.SECTION -> if (best.camera?.type == CamType.SECTION_END) t("End of average speed zone") else t("Average speed check")
                AlertKind.MOBILE -> t("Mobile radar reported")
                AlertKind.POLICE -> t("Police reported")
                AlertKind.HAZARD -> best.report?.type?.label ?: t("Hazard")
                AlertKind.FREQUENT -> t("Frequent speed checks")
            }
            val subtitle = best.report?.let { r ->
                val who = if (r.viaConvoy) " " + t("by {0}", r.by) else ""
                t("{0} min ago", r.ageMin) + who + if (r.confirms > 0) " · " + t("{0}× confirmed", r.confirms) else ""
            } ?: best.spot?.let { h ->
                val ago = ((System.currentTimeMillis() - h.lastTs) / 86_400_000L).toInt()
                tn(h.days.size, "Police measured here on {0} day", "Police measured here on {0} days") + " · " +
                    if (ago > 0) tn(ago, "last {0} day ago", "last {0} days ago") else t("last today")
            } ?: (if (limit != null) t("Limit {0} km/h", limit) else "")

            _alert.value = RadarAlert(best.key, best.kind, best.pos, best.dist, limit, title, subtitle, speeding, best.report?.id)
            val now = System.currentTimeMillis()
            if (best.key != lastAlertKey && (announced[best.key]?.let { now - it > 10 * 60_000 } != false)) {
                announced[best.key] = now
                nearDone.remove(best.key) // the closer warning can sound again on a later pass
                tripAlerts++
                if (s.radarSound) {
                    if (best.kind == AlertKind.HAZARD) voice.chime(urgent = false) else voice.radarSound(urgent = speeding)
                }
                if (s.voice) {
                    val phrase = when (best.kind) {
                        AlertKind.CAMERA -> "camera"
                        AlertKind.RED_LIGHT -> "redlight"
                        AlertKind.SECTION -> if (best.camera?.type == CamType.SECTION_END) "sectionEnd" else "section"
                        AlertKind.MOBILE -> "mobile"
                        AlertKind.POLICE -> "police"
                        AlertKind.HAZARD -> "hazard"
                        AlertKind.FREQUENT -> "frequent"
                    }
                    var text = Phrases.get(s.voiceLang, phrase)
                    if (limit != null && best.kind != AlertKind.HAZARD && best.kind != AlertKind.POLICE) text += ", $limit"
                    voice.speak(text)
                }
            } else if (speeding && best.dist < 300 && best.key !in urgentDone && best.kind != AlertKind.HAZARD) {
                urgentDone.add(best.key)
                if (s.radarSound) voice.radarSound(urgent = true)
            } else if (best.dist < 200 && best.key !in nearDone && best.kind != AlertKind.HAZARD && best.kind != AlertKind.FREQUENT) {
                // second, closer warning
                nearDone.add(best.key)
                if (s.radarSound) voice.radarSound(urgent = speeding)
            }
            lastAlertKey = best.key
            lastAlertDist = best.dist
        }

        updateSection(fix, nav)
    }

    private val nearDone = HashSet<String>()

    private fun kindOf(c: Camera) = when (c.type) {
        CamType.SPEED -> AlertKind.CAMERA
        CamType.RED_LIGHT -> AlertKind.RED_LIGHT
        CamType.SECTION_START, CamType.SECTION_END -> AlertKind.SECTION
    }

    /** Distance to a point that lies ahead of us roughly along our heading, or null. */
    private fun aheadDistance(fix: Fix, p: LatLon, maxCross: Double = 40.0): Double? {
        val d = Geo.dist(fix.pos, p)
        if (d < 25) return d
        val b = Geo.bearing(fix.pos, p)
        val diff = Geo.absAngle(fix.bearing, b)
        if (diff > 30) return null
        val cross = abs(d * sin(Math.toRadians(diff)))
        return if (cross <= maxCross) d else null
    }

    private fun updateSection(fix: Fix, nav: NavState?) {
        val now = System.currentTimeMillis()
        val cur = secCamera
        if (cur == null) {
            // One fix per second is ~36 m at 130 km/h, so look in a generous radius.
            val radius = (fix.speedMps * 1.5).coerceIn(30.0, 70.0)
            val start = cameras.near(fix.pos, radius).firstOrNull {
                it.type == CamType.SECTION_START && (!fix.hasBearing || cameras.appliesTo(it, fix.bearing))
            } ?: return
            val sec = start.section ?: return
            secCamera = start
            secStart = now
            secOdo = 0.0
            secLength = nav?.route?.cameras?.let { list ->
                val a = list.firstOrNull { it.camera.id == start.id }?.alongM
                val b = list.firstOrNull { it.camera.section?.id == sec.id && it.camera.type == CamType.SECTION_END }?.alongM
                if (a != null && b != null && b > a) b - a else null
            } ?: (sec.straightM * 1.08)
        } else {
            val sec = cur.section ?: return
            val elapsed = (now - secStart) / 1000.0
            val endRadius = (fix.speedMps * 1.5).coerceIn(30.0, 70.0)
            val endReached = Geo.dist(fix.pos, sec.end) < endRadius || secOdo > secLength + 800 || elapsed > 3600
            if (endReached) {
                secCamera = null
                _section.value = null
                return
            }
            val avg = if (elapsed > 1) secOdo / elapsed * 3.6 else fix.speedKmh
            val limit = sec.maxspeed
            val maxAllowed = limit?.let {
                val remainingM = (secLength - secOdo).coerceAtLeast(1.0)
                val allowedTotalS = secLength / (it / 3.6)
                val leftS = allowedTotalS - elapsed
                if (leftS <= 0) 0.0 else (remainingM / leftS * 3.6)
            }
            _section.value = SectionStatus(sec.id, limit, avg, elapsed, secOdo, secLength, maxAllowed)
        }
    }

    fun dismissVote() {
        _vote.value = null
    }
}
