package com.drivemap.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.location.Location
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import com.drivemap.app.nav.NavState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.min
import com.drivemap.app.convoy.ConvoyManager
import com.drivemap.app.convoy.ConvoyStop
import com.drivemap.app.convoy.Member
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.location.Fix
import com.drivemap.app.radar.CamType
import com.drivemap.app.radar.Camera
import com.drivemap.app.radar.Report
import com.drivemap.app.radar.ReportType
import com.drivemap.app.route.Place
import com.drivemap.app.route.Route
import com.drivemap.app.route.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Owns the MapLibre map: style (day/night), our overlay sources/layers, the location puck and
 * camera behaviour (follow, navigation tracking, fitting routes).
 */
class MapController(
    private val context: Context,
    private val mapView: MapView,
    val map: MapLibreMap,
    private val scope: CoroutineScope,
) {
    var night = false
        private set
    private var style: Style? = null
    private var locationReady = false
    private var navTracking = false

    var onMapClick: (LatLon) -> Unit = {}
    var onLongPress: (LatLon) -> Unit = {}
    var onPlaceClick: (Place) -> Unit = {}
    var onMemberClick: (String) -> Unit = {}
    var onStopClick: (String) -> Unit = {}
    var onRouteClick: (Int) -> Unit = {}
    var onReportClick: (String) -> Unit = {}
    var onCameraClick: (Camera) -> Unit = {}
    var onTrackingDismissed: () -> Unit = {}
    var onCameraMoved: (bearing: Double, zoom: Double) -> Unit = { _, _ -> }

    // last data, re-applied after a style switch
    private var lastRoutes: List<Route> = emptyList()
    private var lastSelected = 0
    // route colours (user setting); casing and alternatives are derived from it
    private var routeColor = 0xFF1A73E8.toInt()
    private val routeCasing get() = shade(routeColor, 0.70f)
    private val altFill get() = if (night) mix(routeColor, 0xFF242F3E.toInt(), 0.55f) else mix(routeColor, Color.WHITE, 0.62f)
    private val altCasing get() = if (night) mix(routeColor, 0xFF242F3E.toInt(), 0.25f) else mix(routeColor, Color.WHITE, 0.35f)

    // navigation: car snapped to the route + line cut exactly at the car
    private var navRoute: Route? = null
    private var navTargetAlong = 0.0
    private var navTargetTime = 0L
    private var navSpeed = 0.0
    private var navOffRoute = false
    private var dispAlong = -1.0
    private var dispBearing = 0.0
    private var bulkStart = -1.0
    private var lastFrameMs = 0L
    private var lastZoomMs = 0L
    private var lastZoom = 0.0
    private var navJob: Job? = null
    private var lastRawFix: Fix? = null
    private var lastPins: Pair<Place?, List<Place>> = null to emptyList()
    private var lastWaypoints: List<Waypoint> = emptyList()
    private var lastStops: Pair<List<ConvoyStop>, ConvoyStop?> = emptyList<ConvoyStop>() to null
    private var lastReports: List<Report> = emptyList()
    private var lastCameras: List<Camera> = emptyList()
    private var camerasHidden = false

    // convoy animation
    private class Anim(var from: LatLon, var to: LatLon, var start: Long, var bearing: Double, var m: Member)
    private val anims = HashMap<String, Anim>()
    private var animJob: Job? = null

    init {
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.attributionGravity = Gravity.TOP or Gravity.END
        val d = context.resources.displayMetrics.density
        map.uiSettings.setAttributionMargins(0, (110 * d).toInt(), (8 * d).toInt(), 0)
        map.addOnMapClickListener { ll ->
            handleClick(ll)
            true
        }
        map.addOnMapLongClickListener { ll ->
            onLongPress(LatLon(ll.latitude, ll.longitude))
            true
        }
        map.addOnCameraMoveListener {
            val cp = map.cameraPosition
            onCameraMoved(cp.bearing, cp.zoom)
            updateMyCar()
            convoyFollowCamera()
        }
        map.addOnCameraIdleListener {
            onCameraIdle()
            onTiltChanged(map.cameraPosition.tilt > 10)
        }
        tuneGestures()
    }

    /**
     * Google-Maps-like touch feel: two-finger tilt starts even when the fingers aren't perfectly
     * level (MapLibre's default only accepts ~20°), pinch-zoom needs a slightly larger spread so it
     * doesn't steal the tilt, and flings glide a little longer.
     */
    private fun tuneGestures() {
        val d = context.resources.displayMetrics.density
        try {
            val gm = map.gesturesManager
            gm.shoveGestureDetector.maxShoveAngle = 45f
            gm.shoveGestureDetector.pixelDeltaThreshold = 6f * d
            val scale = gm.standardScaleGestureDetector
            scale.spanSinceStartThreshold = maxOf(scale.spanSinceStartThreshold, 14f * d)
            gm.moveGestureDetector.moveThreshold = 2f * d
        } catch (_: Exception) {
        }
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isFlingVelocityAnimationEnabled = true
        map.uiSettings.flingThreshold = 300L
        map.uiSettings.flingAnimationBaseTime = 220L
        map.uiSettings.isIncreaseScaleThresholdWhenRotating = true
        map.uiSettings.isIncreaseRotateThresholdWhenScaling = true
    }

    var onCameraIdle: () -> Unit = {}

    fun loadStyle(night: Boolean, onReady: () -> Unit = {}) {
        this.night = night
        style = null
        locationReady = false
        map.setStyle(Style.Builder().fromJson(MapStyle.build(if (night) MapStyle.night else MapStyle.day))) { s ->
            style = s
            setup(s)
            onReady()
        }
    }

    private fun setup(s: Style) {
        // images
        s.addImage("cam", MapIcons.camera(context, 0xFF3C4043.toInt()))
        s.addImage("cam-red", MapIcons.camera(context, 0xFFD93025.toInt()))
        s.addImage("cam-section", MapIcons.section(context))
        s.addImage("rep-mobile", MapIcons.report(context, 0xFFF57C00.toInt(), "mobile"))
        s.addImage("rep-police", MapIcons.report(context, 0xFF1565C0.toInt(), "police"))
        s.addImage("rep-hazard", MapIcons.report(context, 0xFFE8A200.toInt(), "hazard"))
        s.addImage("pin", MapIcons.pin(context))
        s.addImage("dot", MapIcons.dot(context, 0xFFEA4335.toInt()))
        s.addImage("stop-flag", MapIcons.flag(context, 0xFF8E24AA.toInt()))
        s.addImage("dest-flag", MapIcons.flag(context, 0xFFD93025.toInt()))
        s.addImage("waypoint", MapIcons.waypoint(context))
        s.addImage("bubble", MapIcons.bubble(context, if (night) 0xFF2B3544.toInt() else Color.WHITE, if (night) 0xFF47515F.toInt() else 0xFFDADCE0.toInt()))
        s.addImage("bubble-sel", MapIcons.bubble(context, routeColor, routeCasing))
        looks.clear() // 3D car frames are added to each new style on demand

        // sources
        s.addSource(GeoJsonSource("routes-alt"))
        s.addSource(GeoJsonSource("route-main"))
        s.addSource(GeoJsonSource("route-head"))
        s.addSource(GeoJsonSource("corners"))
        s.addSource(GeoJsonSource("corners-head"))
        s.addSource(GeoJsonSource("maneuver-arrow"))
        s.addSource(GeoJsonSource("maneuver-arrow-head"))
        s.addImage("arrow-head", MapIcons.arrowHead(context, shade(routeColor, 0.45f)))
        s.addImage("route-chevron", MapIcons.chevron(context))
        s.addSource(GeoJsonSource("route-labels"))
        s.addSource(GeoJsonSource("cameras"))
        s.addSource(GeoJsonSource("reports"))
        s.addSource(GeoJsonSource("stops"))
        s.addSource(GeoJsonSource("pins"))
        s.addSource(GeoJsonSource("convoy"))

        val below = if (s.getLayer("road-name") != null) "road-name" else null
        fun caseWidth() = Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(8, 6.5f), Expression.stop(16, 14f), Expression.stop(19, 22f))
        fun fillWidth() = Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(8, 4.2f), Expression.stop(16, 10f), Expression.stop(19, 16f))
        fun routeLayer(id: String, src: String, color: Int, width: Expression) = LineLayer(id, src).withProperties(
            lineColor(hex(color)), lineWidth(width), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        )
        // order: all casings first, then fills, so the car-to-route "head" joins the rest seamlessly
        val layers = listOf(
            routeLayer("routes-alt-case", "routes-alt", altCasing,
                Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(8, 5f), Expression.stop(16, 12f))),
            routeLayer("routes-alt-line", "routes-alt", altFill,
                Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(8, 3.2f), Expression.stop(16, 8f))),
            routeLayer("route-main-case", "route-main", routeCasing, caseWidth()),
            routeLayer("route-head-case", "route-head", routeCasing, caseWidth()),
            routeLayer("route-main-line", "route-main", routeColor, fillWidth()),
            routeLayer("route-head-line", "route-head", routeColor, fillWidth()),
            // challenge routes: the line coloured by bends (red sharp, orange medium, green straight);
            // bends are drawn over the green so their ends stay visible
            LineLayer("corners-line", "corners").withProperties(
                lineColor(Expression.toColor(get("c"))), lineWidth(fillWidth()), lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND), lineOpacity(0.95f), lineSortKey(Expression.toNumber(get("k"))),
            ),
            LineLayer("corners-head-line", "corners-head").withProperties(
                lineColor(Expression.toColor(get("c"))), lineWidth(fillWidth()), lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND), lineOpacity(0.95f), lineSortKey(Expression.toNumber(get("k"))),
            ),
            // chevrons along the route (not on the short head, which is redrawn as the car moves),
            // so a route that doubles back on itself reads as one line going out and back
            SymbolLayer("route-main-arrows", "route-main").withProperties(
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(110f),
                iconImage("route-chevron"), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP), iconKeepUpright(false),
                iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(10, 0.5f), Expression.stop(16, 0.85f), Expression.stop(19, 1.2f))),
            ).also { it.minZoom = 10f },
            // Google-style white turn arrow on the route at the next maneuver
            routeLayer("maneuver-arrow-case", "maneuver-arrow", shade(routeColor, 0.45f),
                Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(10, 3.5f), Expression.stop(16, 10.5f), Expression.stop(19, 17f))),
            routeLayer("maneuver-arrow-line", "maneuver-arrow", Color.WHITE,
                Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(10, 2f), Expression.stop(16, 7f), Expression.stop(19, 12f))),
        )
        layers.forEach { if (below != null) s.addLayerBelow(it, below) else s.addLayer(it) }
        val head = SymbolLayer("maneuver-arrow-head-layer", "maneuver-arrow-head").withProperties(
            iconImage("arrow-head"),
            iconRotate(Expression.toNumber(get("bearing"))),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(true), iconIgnorePlacement(true),
            iconSize(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(10, 0.35f), Expression.stop(16, 1.0f), Expression.stop(19, 1.7f))),
        ).also { it.minZoom = 10f }
        if (below != null) s.addLayerBelow(head, below) else s.addLayer(head)

        s.addLayer(
            SymbolLayer("cameras-layer", "cameras").withProperties(
                iconImage(get("icon")),
                iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 0.6f), Expression.stop(15, 0.9f))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ).also { it.minZoom = 13f }
        )
        s.addLayer(
            SymbolLayer("reports-layer", "reports").withProperties(
                iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(9, 0.6f), Expression.stop(15, 1f))),
            ).also { it.minZoom = 11f }
        )
        s.addLayer(
            SymbolLayer("stops-layer", "stops").withProperties(
                iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT), iconOffset(arrayOf(-7f, 0f)),
                textField(get("name")), textFont(arrayOf("Noto Sans Bold")), textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.4f)), textOptional(true),
                textColor(if (night) "#E8EAED" else "#3C4043"), textHaloColor(if (night) "#17202B" else "#FFFFFF"), textHaloWidth(1.5f),
            )
        )
        s.addLayer(
            SymbolLayer("pins-layer", "pins").withProperties(
                iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconAnchor(Expression.match(get("icon"), literal(Property.ICON_ANCHOR_CENTER), Expression.stop("pin", Property.ICON_ANCHOR_BOTTOM))),
                textField(get("name")), textFont(arrayOf("Noto Sans Regular")), textSize(11f),
                textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.7f)), textOptional(true),
                textColor(if (night) "#F28B82" else "#C5221F"), textHaloColor(if (night) "#17202B" else "#FFFFFF"), textHaloWidth(1.5f),
            )
        )
        s.addLayer(
            SymbolLayer("route-labels-layer", "route-labels").withProperties(
                iconImage(get("bg")), iconTextFit(Property.ICON_TEXT_FIT_BOTH), iconTextFitPadding(arrayOf(5f, 9f, 5f, 9f)),
                textField(get("text")), textFont(arrayOf("Noto Sans Bold")), textSize(13f),
                textColor(Expression.toColor(get("color"))), iconAllowOverlap(false), textAllowOverlap(false),
            )
        )
        s.addLayer(
            SymbolLayer("convoy-layer", "convoy").withProperties(
                // 3D car frames already face the camera: frame + small turn are picked in renderConvoy
                iconImage(get("icon")), iconRotate(Expression.toNumber(get("rot"))), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 0.4f), Expression.stop(14, 0.7f), Expression.stop(16, 0.95f), Expression.stop(18, 1.3f))),
                textField(get("name")), textFont(arrayOf("Noto Sans Bold")), textSize(12.5f),
                textAnchor(Property.TEXT_ANCHOR_BOTTOM), textOffset(arrayOf(0f, -2.2f)), textAllowOverlap(true),
                textColor(Expression.toColor(get("color"))), textHaloColor(if (night) "#17202B" else "#FFFFFF"), textHaloWidth(2f),
            )
        )

        activateLocation(s)
        setupMyCar(s)
        setPoisVisible(poisVisible, force = true)
        // re-apply data
        setRoutes(lastRoutes, lastSelected)
        bulkStart = -1.0 // navigation line is redrawn on the next frame
        arrowKey = ""
        setManeuverArrow(arrowRoute, arrowIdx)
        cornersRoute.also { cornersRoute = null; setCorners(it) }
        setPins(lastPins.first, lastPins.second)
        setWaypoints(lastWaypoints)
        setConvoyStops(lastStops.first, lastStops.second)
        setReports(lastReports)
        setCameras(lastCameras, camerasHidden)
    }

    @SuppressLint("MissingPermission")
    private fun activateLocation(s: Style) {
        try {
            val lc = map.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(puckOptions(navRoute != null))
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = if (navRoute != null) RenderMode.GPS else RenderMode.COMPASS
            lc.addOnLocationClickListener { onMyCarClick() }
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { onTrackingDismissed() }
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            locationReady = true
        } catch (_: Exception) {
            locationReady = false
        }
    }

    /**
     * Location marker look: browsing = blue dot with white ring and accuracy halo; navigating =
     * only the outlined arrow (no white circle, shadow or halo under it).
     */
    private fun puckOptions(navigating: Boolean): LocationComponentOptions {
        val b = LocationComponentOptions.builder(context)
            .foregroundTintColor(0xFF1A73E8.toInt())
            .bearingTintColor(0xFF1A73E8.toInt())
            .accuracyColor(0xFF1A73E8.toInt())
            .accuracyAlpha(if (navigating) 0f else 0.12f)
            .pulseEnabled(false)
            .compassAnimationEnabled(!navigating) // smooth compass turning while the car follows the phone
            .elevation(if (navigating) 0f else 3f)
            // your chosen 3D car ("me-car" layer) is always drawn; the built-in dot / arrow stay hidden
            .gpsDrawable(com.drivemap.app.R.drawable.puck_none)
            .foregroundDrawable(com.drivemap.app.R.drawable.puck_none)
            .foregroundDrawableStale(com.drivemap.app.R.drawable.puck_none)
            .bearingDrawable(com.drivemap.app.R.drawable.puck_none)
            .backgroundDrawable(com.drivemap.app.R.drawable.puck_none)
            .backgroundDrawableStale(com.drivemap.app.R.drawable.puck_none)
        if (navigating) b.backgroundTintColor(Color.TRANSPARENT).backgroundStaleTintColor(Color.TRANSPARENT)
        return b.build()
    }

    private fun applyPuck(navigating: Boolean) {
        if (!locationReady) return
        try {
            val lc = map.locationComponent
            lc.applyStyle(puckOptions(navigating))
            lc.renderMode = if (navigating) RenderMode.GPS else RenderMode.COMPASS
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- location / camera

    private var hadFirstFix = false

    fun updateLocation(fix: Fix) {
        lastRawFix = fix
        navSpeed = fix.speedMps
        hadFirstFix = true
        updateMyCar() // switches between compass (standing / walking) and driving direction
        if (!locationReady) return
        // While navigating on the route, the navigation loop draws the car snapped to the route.
        if (navRoute != null && !navOffRoute) return
        try {
            map.locationComponent.forceLocationUpdate(fix.toLocation())
        } catch (_: Exception) {
        }
        updateHeadingFollow(fix)
    }

    /** Navigation camera: heading-up, tilted, user arrow in the lower part of the screen. */
    /** Space (px) covered by other UI, e.g. Android Auto's panels: left, top, right, bottom. */
    var insets = intArrayOf(0, 0, 0, 0)
        set(value) {
            field = value
            if (navTracking) followNavigation() else map.setPadding(value[0], value[1], value[2], value[3])
        }

    fun followNavigation() {
        if (!locationReady) return
        navTracking = true
        browseTracking = false
        val lc = map.locationComponent
        lc.renderMode = RenderMode.GPS
        val h = mapView.height.toDouble().coerceAtLeast(1.0)
        val visibleH = (h - insets[1] - insets[3]).coerceAtLeast(1.0)
        // car in the lower part of the visible area, like Google Maps
        map.setPadding(insets[0], insets[1] + (visibleH * 0.42).toInt(), insets[2], insets[3])
        // zoom/tilt must be part of the transition: *WhileTracking calls are ignored mid-transition
        lc.setCameraMode(CameraMode.TRACKING_GPS, 900, 16.5, null, if (view3d) NAV_TILT else 0.0, null)
    }

    /** Browse-mode "my location": follows you; while you drive the map turns to your direction. */
    fun followUser(zoom: Double? = 16.0) {
        if (!locationReady) return
        navTracking = false
        val lc = map.locationComponent
        lc.renderMode = RenderMode.COMPASS
        map.setPadding(insets[0], insets[1], insets[2], insets[3])
        lc.setCameraMode(CameraMode.TRACKING, 800, zoom, null, 0.0, null)
        browseTracking = true
        headingUp = false
        lastRawFix?.let { updateHeadingFollow(it) }
    }

    /** Following you outside navigation (the "my location" button). */
    private var browseTracking = false
    /** The map currently turns with your travel direction. */
    private var headingUp = false

    /**
     * Like Google Maps: once you drive (over about 11 km/h with a known direction) the map turns so
     * your direction points up, with a short smooth turn; when you stop it stays as it is. A finger
     * on the map ends following, and this stays off until "my location" is tapped again.
     */
    private fun updateHeadingFollow(fix: Fix) {
        if (!browseTracking || navTracking || !locationReady) return
        val lc = map.locationComponent
        if (lc.cameraMode == CameraMode.NONE) { browseTracking = false; headingUp = false; return }
        val want = if (headingUp) fix.speedMps > 1.0 else fix.speedMps > 3.0 && fix.hasBearing
        if (want == headingUp) return
        headingUp = want
        try {
            lc.setCameraMode(if (want) CameraMode.TRACKING_GPS else CameraMode.TRACKING, 600, null, null, null, null)
        } catch (_: Exception) {
        }
    }

    /** Shops/restaurants are hidden while navigating, like Google Maps. */
    private var poisVisible = true

    fun setPoisVisible(visible: Boolean, force: Boolean = false) {
        if (visible == poisVisible && !force) return
        poisVisible = visible
        val s = style ?: return
        val v = if (visible) Property.VISIBLE else Property.NONE
        listOf("poi", "poi-major", "housenumber").forEach { id -> s.getLayer(id)?.setProperties(visibility(v)) }
    }

    fun stopFollow() {
        navTracking = false
        browseTracking = false
        headingUp = false
        map.setPadding(insets[0], insets[1], insets[2], insets[3])
        if (!locationReady) return
        map.locationComponent.cameraMode = CameraMode.NONE
        // keep the arrow while navigating, even when the user pans the map
        map.locationComponent.renderMode = if (navRoute != null) RenderMode.GPS else RenderMode.COMPASS
    }

    fun resetNorth() {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).bearing(0.0).tilt(0.0).build()), 500)
    }

    fun moveTo(pos: LatLon, zoom: Double? = null, animate: Boolean = true) {
        stopFollow()
        val b = CameraPosition.Builder(map.cameraPosition).target(LatLng(pos.lat, pos.lon)).tilt(0.0)
        if (zoom != null) b.zoom(zoom)
        val u = CameraUpdateFactory.newCameraPosition(b.build())
        if (animate) map.animateCamera(u, 700) else map.moveCamera(u)
    }

    /**
     * Turns and moves the camera to [pos] facing [bearing] in one quick animation (keeps the
     * 2D / 3D tilt of navigation), e.g. to show a coming turn the way you'll drive into it.
     */
    fun lookAt(pos: LatLon, zoom: Double, bearing: Double) {
        stopFollow()
        val tilt = if (navRoute != null && view3d) NAV_TILT else 0.0
        val cp = CameraPosition.Builder().target(LatLng(pos.lat, pos.lon)).zoom(zoom).bearing(bearing).tilt(tilt).build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 550)
    }

    fun fit(points: List<LatLon>, topDp: Int, bottomDp: Int, sideDp: Int = 40, leftDp: Int = 0) {
        if (points.isEmpty()) return
        stopFollow()
        val d = context.resources.displayMetrics.density
        if (points.size == 1 || Geo.bounds(points).let { (it[2] - it[0]) < 1e-5 && (it[3] - it[1]) < 1e-5 }) {
            moveTo(points[0], 15.0)
            return
        }
        val bb = Geo.bounds(points)
        val bounds = LatLngBounds.Builder().include(LatLng(bb[0], bb[1])).include(LatLng(bb[2], bb[3])).build()
        map.setPadding(0, 0, 0, 0)
        try {
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds, 0.0, 0.0,
                    ((sideDp + leftDp) * d).toInt() + insets[0], (topDp * d).toInt() + insets[1],
                    (sideDp * d).toInt() + insets[2], (bottomDp * d).toInt() + insets[3],
                ),
                800,
            )
        } catch (_: Exception) {
        }
    }

    fun center(): LatLon = map.cameraPosition.target?.let { LatLon(it.latitude, it.longitude) } ?: LatLon(48.15, 17.11)

    fun visibleBounds(): DoubleArray {
        val b = map.projection.visibleRegion.latLngBounds
        return doubleArrayOf(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }

    // ---------------------------------------------------------------- routes

    private fun line(points: List<LatLon>): LineString = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })

    /** Directions preview: selected route blue, alternatives light, with time bubbles. */
    fun setRoutes(routes: List<Route>, selected: Int) {
        lastRoutes = routes
        lastSelected = selected
        if (navRoute == null) setCorners(routes.getOrNull(selected))
        val s = style ?: return
        val alts = routes.withIndex().filter { it.index != selected }.map { (i, r) ->
            Feature.fromGeometry(line(r.geometry.shape)).also { it.addNumberProperty("idx", i) }
        }
        s.getSourceAs<GeoJsonSource>("routes-alt")?.setGeoJson(FeatureCollection.fromFeatures(alts))
        if (navRoute == null) {
            val sel = routes.getOrNull(selected)
            s.getSourceAs<GeoJsonSource>("route-main")?.setGeoJson(
                if (sel != null) FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line(sel.geometry.shape)).also { it.addNumberProperty("idx", selected) }))
                else FeatureCollection.fromFeatures(emptyList())
            )
            s.getSourceAs<GeoJsonSource>("route-head")?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
        // time bubbles at a point unique to each route
        val labels = routes.withIndex().mapNotNull { (i, r) ->
            if (routes.size < 2 && i == selected) return@mapNotNull null
            val p = labelPoint(r, routes) ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                it.addStringProperty("text", (if (routeLetters) "${'A' + i} · " else "") + Geo.formatDuration(r.timeS))
                it.addStringProperty("bg", if (i == selected) "bubble-sel" else "bubble")
                it.addStringProperty("color", if (i == selected) "#FFFFFF" else if (night) "#E8EAED" else "#3C4043")
                it.addNumberProperty("idx", i)
            }
        }
        s.getSourceAs<GeoJsonSource>("route-labels")?.setGeoJson(FeatureCollection.fromFeatures(labels))
    }

    /** A point on [r] far from the other routes so the bubble is unambiguous. */
    private fun labelPoint(r: Route, all: List<Route>): LatLon? {
        val path = r.path
        if (path.length <= 0) return null
        var best: LatLon? = null
        var bestD = -1.0
        for (k in 1..9) {
            val p = path.pointAt(path.length * k / 10.0)
            var minD = Double.MAX_VALUE
            for (o in all) {
                if (o === r) continue
                val step = maxOf(1, o.geometry.shape.size / 200)
                var i = 0
                while (i < o.geometry.shape.size) {
                    minD = minOf(minD, Geo.dist(p, o.geometry.shape[i]))
                    i += step
                }
            }
            if (minD > bestD) { bestD = minD; best = p }
        }
        return best
    }

    // ---------------------------------------------------------------- navigation car + line

    /**
     * Navigation display, done like Google Maps: the car is snapped onto the route and moved
     * smoothly at ~30 fps (dead-reckoning between GPS fixes), and the route line starts exactly
     * under the car. The line is split in two sources: a short "head" from the car to a point a few
     * hundred metres ahead (redrawn every frame) and the long rest (redrawn only occasionally).
     */
    fun setNavigation(state: NavState?) {
        val r = state?.route
        if (r == null) {
            if (navRoute != null) {
                navRoute = null
                setCorners(null)
                applyPuck(false)
                navJob?.cancel()
                style?.let { s ->
                    s.getSourceAs<GeoJsonSource>("route-main")?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                    s.getSourceAs<GeoJsonSource>("route-head")?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                }
                lastRawFix?.let { updateLocation(it) }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (r !== navRoute) {
            if (navRoute == null) applyPuck(true)
            navRoute = r
            setCorners(r)
            dispAlong = state.alongM
            dispBearing = r.path.bearingAt(state.alongM + 3)
            bulkStart = -1.0
            lastHeadAlong = -1.0
            style?.let { s ->
                s.getSourceAs<GeoJsonSource>("routes-alt")?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                s.getSourceAs<GeoJsonSource>("route-labels")?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            }
        }
        navTargetAlong = state.alongM
        navTargetTime = now
        val wasOff = navOffRoute
        navOffRoute = state.offRoute
        if (navOffRoute && !wasOff) lastRawFix?.let { f -> try { map.locationComponent.forceLocationUpdate(f.toLocation()) } catch (_: Exception) {} }
        if (!frameLoopRunning) {
            frameLoopRunning = true
            lastFrameMs = now
            lastFrameNanos = 0L
            choreographer.postFrameCallback(frameCallback)
        }
    }

    // Driven by the display's vsync (capped ~60 fps) so the car and camera glide smoothly.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var frameLoopRunning = false
    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (navRoute == null || destroyed) {
                frameLoopRunning = false
                return
            }
            if (frameTimeNanos - lastFrameNanos >= 14_000_000L) {
                lastFrameNanos = frameTimeNanos
                try { navFrame() } catch (_: Exception) {}
            }
            choreographer.postFrameCallback(this)
        }
    }

    private var destroyed = false

    /** Stops frame callbacks (call when the map view goes away). */
    fun destroy() {
        destroyed = true
        navJob?.cancel()
        animJob?.cancel()
        choreographer.removeFrameCallback(frameCallback)
        frameLoopRunning = false
    }

    private val emptyFc get() = FeatureCollection.fromFeatures(emptyList())

    private fun navFrame() {
        val r = navRoute ?: return
        val s = style ?: return
        val path = r.path
        val now = System.currentTimeMillis()
        val dt = (now - lastFrameMs).coerceIn(0, 250) / 1000.0
        lastFrameMs = now

        // where the car should be now: last snapped position + distance driven since that fix
        val ahead = navSpeed * ((now - navTargetTime) / 1000.0).coerceIn(0.0, 1.2)
        val predicted = (navTargetAlong + ahead).coerceIn(0.0, path.length)
        dispAlong = if (dispAlong < 0 || abs(predicted - dispAlong) > 120) predicted
        else dispAlong + (predicted - dispAlong) * min(1.0, dt * 6.0)
        dispAlong = dispAlong.coerceIn(0.0, path.length)

        // car (snapped to the route) – raw GPS is shown instead while off the route.
        // The location marker animates itself between updates, so it is fed ~10×/s with the spot
        // it should reach by the next update (feeding every frame would restart its animation
        // before it could move).
        // heading towards a point a little ahead (further when faster), so the map turns
        // gradually through a bend instead of snapping round at the corner
        val look = (navSpeed * 1.3).coerceIn(12.0, 45.0)
        val aheadPt = path.pointAt(min(dispAlong + look, path.length))
        val targetBearing = if (Geo.dist(path.pointAt(dispAlong), aheadPt) > 2) Geo.bearing(path.pointAt(dispAlong), aheadPt) else path.bearingAt(dispAlong)
        dispBearing = Geo.normalizeDeg(dispBearing + Geo.angleDiff(dispBearing, targetBearing) * min(1.0, dt * 3.5))
        if (locationReady && !navOffRoute && now - lastPuckMs >= 100) {
            lastPuckMs = now
            val aheadAlong = (dispAlong + navSpeed * 0.1).coerceAtMost(path.length)
            val p = path.pointAt(aheadAlong)
            val loc = Location("drivemap-nav").apply {
                latitude = p.lat
                longitude = p.lon
                bearing = Geo.normalizeDeg(dispBearing + Geo.angleDiff(dispBearing, path.bearingAt(aheadAlong + 3)) * 0.5).toFloat()
                speed = navSpeed.toFloat()
                accuracy = 3f
                time = now
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            try { map.locationComponent.forceLocationUpdate(loc) } catch (_: Exception) {}
        }

        // navigation camera zoom follows speed
        if (navTracking && locationReady && now - lastZoomMs > 1500) {
            val kmh = navSpeed * 3.6
            val z = when {
                kmh > 100 -> 15.3
                kmh > 70 -> 15.8
                kmh > 40 -> 16.4
                else -> 17.0
            }
            if (abs(z - lastZoom) > 0.1) {
                try { map.locationComponent.zoomWhileTracking(z, 1200) } catch (_: Exception) {}
                lastZoom = z
            }
            lastZoomMs = now
        }

        // the line: long rest from bulkStart (rarely redrawn) + head from the car to bulkStart
        if (bulkStart < 0 || dispAlong > bulkStart - 80 || dispAlong < bulkStart - 700) {
            bulkStart = min(path.length, dispAlong + 400)
            val rest = path.slice(bulkStart, path.length)
            s.getSourceAs<GeoJsonSource>("route-main")?.setGeoJson(
                if (rest.size >= 2 && bulkStart < path.length - 1) FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line(rest))))
                else emptyFc
            )
            cornersRoute?.takeIf { it === navRoute }?.let { cr ->
                s.getSourceAs<GeoJsonSource>("corners")?.setGeoJson(FeatureCollection.fromFeatures(cornerFeatures(cr, bulkStart, path.length)))
            }
        }
        // the short head only needs redrawing when the car actually moved (at most ~30×/s)
        if ((abs(dispAlong - lastHeadAlong) > 0.25 && now - lastHeadMs >= 30) || headBulk != bulkStart) {
            lastHeadAlong = dispAlong
            lastHeadMs = now
            headBulk = bulkStart
            val head = path.slice(headStartAlong(path, dispAlong), bulkStart)
            s.getSourceAs<GeoJsonSource>("route-head")?.setGeoJson(
                if (head.size >= 2) FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line(head)))) else emptyFc
            )
            cornersRoute?.takeIf { it === navRoute }?.let { cr ->
                s.getSourceAs<GeoJsonSource>("corners-head")?.setGeoJson(
                    FeatureCollection.fromFeatures(cornerFeatures(cr, headStartAlong(path, dispAlong), bulkStart)),
                )
            }
        }
    }

    /**
     * Where the drawn line begins: a little ahead of the arrow's centre, so the line's rounded end
     * (as wide as the arrow) stays hidden under the arrow instead of poking out behind it. Also
     * leads by the distance driven while the map processes the update (~50 ms).
     */
    private fun headStartAlong(path: com.drivemap.app.geo.PolylinePath, along: Double): Double {
        val z = try { map.cameraPosition.zoom } catch (_: Exception) { 16.0 }
        val lat = path.pointAt(along).lat
        val metersPerDp = 40_075_016.686 * cos(Math.toRadians(lat)) / (512.0 * 2.0.pow(z))
        val aheadDp = (caseWidthDp(z) / 2 - 2.5).coerceAtLeast(2.0)
        return min(along + aheadDp * metersPerDp + navSpeed * 0.05, path.length)
    }

    /** Route casing width (dp) at zoom [z]; mirrors caseWidth() in setup(). */
    private fun caseWidthDp(z: Double): Double {
        fun exp15(z0: Double, v0: Double, z1: Double, v1: Double): Double {
            val t = (z.coerceIn(z0, z1) - z0)
            return v0 + (v1 - v0) * (1.5.pow(t) - 1) / (1.5.pow(z1 - z0) - 1)
        }
        return if (z <= 16) exp15(8.0, 6.5, 16.0, 14.0) else exp15(16.0, 14.0, 19.0, 22.0)
    }

    private var lastPuckMs = 0L
    private var lastHeadAlong = -1.0
    private var lastHeadMs = 0L
    private var headBulk = -2.0

    // ---------------------------------------------------------------- turn arrow

    private var arrowRoute: Route? = null
    private var arrowIdx = -1
    private var arrowKey = ""

    /** Draws a white arrow on the route showing the shape of the next turn (like Google Maps). */
    private var cornersRoute: Route? = null

    /** In a challenge (host or guest) the route is coloured by its bends. */
    var challengeMode = false
        set(v) {
            if (field == v) return
            field = v
            cornersRoute = null
            setCorners(navRoute ?: lastRoutes.getOrNull(lastSelected))
        }

    /** Marks the bends of a challenge route (red sharp, orange medium); other routes get none. */
    fun setCorners(route: Route?) {
        val r = route?.takeIf { challengeMode }
        if (r === cornersRoute && style?.getSourceAs<GeoJsonSource>("corners") != null && r != null) return
        cornersRoute = r
        cornerSegs = r?.let { cornerSegments(it) } ?: emptyList()
        val s = style ?: return
        // while navigating this route the nav loop draws it trimmed to what's ahead of the car
        s.getSourceAs<GeoJsonSource>("corners")?.setGeoJson(
            if (r == null || r === navRoute) emptyFc else FeatureCollection.fromFeatures(cornerFeatures(r, 0.0, r.path.length)),
        )
        s.getSourceAs<GeoJsonSource>("corners-head")?.setGeoJson(emptyFc)
        if (r != null && r === navRoute) bulkStart = -1.0
    }

    /** The whole route split into straight (green) and bend (orange / red) stretches. */
    private var cornerSegs: List<Triple<Double, Double, Int>> = emptyList()

    private fun cornerSegments(rt: Route): List<Triple<Double, Double, Int>> {
        val out = ArrayList<Triple<Double, Double, Int>>()
        var pos = 0.0
        for (c in com.drivemap.app.route.Costs.corners(rt)) {
            if (c.fromM > pos + 1) out += Triple(pos, c.fromM, 0)
            out += Triple(c.fromM, c.toM, if (c.sharp) 2 else 1)
            pos = maxOf(pos, c.toM)
        }
        if (pos < rt.path.length - 1) out += Triple(pos, rt.path.length, 0)
        return out
    }

    private fun cornerFeatures(rt: Route, from: Double, to: Double): List<Feature> = cornerSegs.mapNotNull { (a, b, level) ->
        val s0 = maxOf(a, from)
        val e0 = minOf(b, to)
        if (e0 - s0 < 1) return@mapNotNull null
        val pts = rt.path.slice(s0, e0)
        if (pts.size < 2) null
        else Feature.fromGeometry(line(pts)).also {
            it.addStringProperty("c", when (level) { 2 -> "#E53935"; 1 -> "#FB8C00"; else -> "#2E9E4F" })
            it.addNumberProperty("k", level)
        }
    }

    fun setManeuverArrow(route: Route?, idx: Int) {
        arrowRoute = route
        arrowIdx = idx
        val key = "${System.identityHashCode(route)}:$idx"
        if (key == arrowKey) return
        arrowKey = key
        val s = style ?: return
        val arrowSrc = s.getSourceAs<GeoJsonSource>("maneuver-arrow") ?: return
        val headSrc = s.getSourceAs<GeoJsonSource>("maneuver-arrow-head") ?: return
        val mans = route?.geometry?.maneuvers
        val m = mans?.getOrNull(idx)
        // no arrow for "keep going straight", departure and arrival
        if (route == null || m == null || m.type in setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 17, 22, 28, 29)) {
            arrowSrc.setGeoJson(emptyFc)
            headSrc.setGeoJson(emptyFc)
            return
        }
        val path = route.path
        val prevAlong = mans.getOrNull(idx - 1)?.alongM ?: 0.0
        val nextAlong = mans.getOrNull(idx + 1)?.alongM ?: path.length
        val before = min(32.0, (m.alongM - prevAlong) * 0.6).coerceAtLeast(6.0)
        val after = min(26.0, (nextAlong - m.alongM) * 0.6).coerceAtLeast(6.0)
        val pts = path.slice(m.alongM - before, m.alongM + after)
        if (pts.size < 2) {
            arrowSrc.setGeoJson(emptyFc)
            headSrc.setGeoJson(emptyFc)
            return
        }
        arrowSrc.setGeoJson(FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line(pts)))))
        val end = pts.last()
        val bearing = Geo.bearing(path.pointAt(m.alongM + after - 4), end)
        headSrc.setGeoJson(Feature.fromGeometry(Point.fromLngLat(end.lon, end.lat)).also { it.addNumberProperty("bearing", bearing) })
    }

    // ---------------------------------------------------------------- route colour

    fun setRouteColor(color: Int) {
        if (color == routeColor) return
        routeColor = color
        val s = style ?: return
        (s.getLayer("route-main-line") as? LineLayer)?.setProperties(lineColor(hex(routeColor)))
        (s.getLayer("route-head-line") as? LineLayer)?.setProperties(lineColor(hex(routeColor)))
        (s.getLayer("route-main-case") as? LineLayer)?.setProperties(lineColor(hex(routeCasing)))
        (s.getLayer("route-head-case") as? LineLayer)?.setProperties(lineColor(hex(routeCasing)))
        (s.getLayer("routes-alt-line") as? LineLayer)?.setProperties(lineColor(hex(altFill)))
        (s.getLayer("routes-alt-case") as? LineLayer)?.setProperties(lineColor(hex(altCasing)))
        (s.getLayer("maneuver-arrow-case") as? LineLayer)?.setProperties(lineColor(hex(shade(routeColor, 0.45f))))
        s.addImage("bubble-sel", MapIcons.bubble(context, routeColor, routeCasing))
        s.addImage("arrow-head", MapIcons.arrowHead(context, shade(routeColor, 0.45f)))
        s.addImage("route-chevron", MapIcons.chevron(context))
    }

    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    private fun shade(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255), (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255),
    )

    /** Blend [a] towards [b]; t = share of b. */
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    fun setWaypoints(stops: List<Waypoint>) {
        lastWaypoints = stops.filterNot { it.through } // a challenge loop's shaping points get no flag
        refreshStops()
    }

    // ---------------------------------------------------------------- pins / stops / reports / cameras

    fun setPins(selected: Place?, results: List<Place>) {
        lastPins = selected to results
        val s = style ?: return
        val f = ArrayList<Feature>()
        results.forEachIndexed { i, p ->
            if (selected != null && p.pos == selected.pos) return@forEachIndexed
            f += Feature.fromGeometry(Point.fromLngLat(p.pos.lon, p.pos.lat)).also {
                it.addStringProperty("icon", "dot"); it.addStringProperty("name", p.name); it.addNumberProperty("res", i)
            }
        }
        if (selected != null) {
            f += Feature.fromGeometry(Point.fromLngLat(selected.pos.lon, selected.pos.lat)).also {
                it.addStringProperty("icon", "pin"); it.addStringProperty("name", ""); it.addNumberProperty("res", -1)
            }
        }
        s.getSourceAs<GeoJsonSource>("pins")?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    fun setConvoyStops(stops: List<ConvoyStop>, dest: ConvoyStop?) {
        lastStops = stops to dest
        refreshStops()
    }

    private fun refreshStops() {
        val s = style ?: return
        val f = ArrayList<Feature>()
        val convoyIds = lastStops.first.map { it.id }.toSet()
        lastStops.first.forEach { st ->
            f += Feature.fromGeometry(Point.fromLngLat(st.pos.lon, st.pos.lat)).also {
                it.addStringProperty("icon", "stop-flag"); it.addStringProperty("name", st.name); it.addStringProperty("id", st.id)
            }
        }
        lastStops.second?.let { d ->
            f += Feature.fromGeometry(Point.fromLngLat(d.pos.lon, d.pos.lat)).also {
                it.addStringProperty("icon", "dest-flag"); it.addStringProperty("name", d.name); it.addStringProperty("id", "dest")
            }
        }
        // route waypoints that are not convoy stops
        lastWaypoints.forEachIndexed { i, w ->
            if (w.convoyStopId != null && w.convoyStopId in convoyIds) return@forEachIndexed
            val last = i == lastWaypoints.size - 1
            f += Feature.fromGeometry(Point.fromLngLat(w.pos.lon, w.pos.lat)).also {
                it.addStringProperty("icon", if (last) "dest-flag" else "stop-flag")
                it.addStringProperty("name", w.name)
                it.addStringProperty("id", "wp$i")
            }
        }
        s.getSourceAs<GeoJsonSource>("stops")?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    fun setReports(list: List<Report>) {
        lastReports = list
        val s = style ?: return
        val f = list.filter { !it.expired }.map { r ->
            Feature.fromGeometry(Point.fromLngLat(r.pos.lon, r.pos.lat)).also {
                it.addStringProperty("icon", when (r.type) {
                    ReportType.MOBILE_RADAR -> "rep-mobile"
                    ReportType.POLICE -> "rep-police"
                    else -> "rep-hazard"
                })
                it.addStringProperty("id", r.id)
            }
        }
        s.getSourceAs<GeoJsonSource>("reports")?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    fun setCameras(list: List<Camera>, hidden: Boolean) {
        lastCameras = list
        camerasHidden = hidden
        val s = style ?: return
        val f = if (hidden) emptyList() else list.map { c ->
            Feature.fromGeometry(Point.fromLngLat(c.pos.lon, c.pos.lat)).also {
                it.addStringProperty("id", c.id.toString())
                it.addStringProperty("icon", when (c.type) {
                    CamType.RED_LIGHT -> "cam-red"
                    CamType.SECTION_START, CamType.SECTION_END -> "cam-section"
                    else -> "cam"
                })
            }
        }
        s.getSourceAs<GeoJsonSource>("cameras")?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    /** Convoy member whose car is at screen point (x, y), if any (generous box for car screens). */
    fun memberAt(x: Float, y: Float): String? {
        val d = context.resources.displayMetrics.density
        val box = android.graphics.RectF(x - 28 * d, y - 28 * d, x + 28 * d, y + 28 * d)
        return map.queryRenderedFeatures(box, "convoy-layer").firstOrNull()?.getStringProperty("id")
    }

    /** Car screen: route bubbles start with A, B, C… to match the route list. */
    var routeLetters = false

    // ---------------------------------------------------------------- 3D cars

    /** Navigation camera tilted (3D) or flat (2D). */
    var view3d = true
        set(v) {
            if (field == v) return
            field = v
            if (navTracking) try { map.locationComponent.tiltWhileTracking(if (v) NAV_TILT else 0.0, 700) } catch (_: Exception) {}
        }

    /** Browse mode 2D/3D: tilts the camera; returns true when now 3D. */
    fun toggleBrowseTilt(): Boolean {
        val on = map.cameraPosition.tilt < 10
        map.animateCamera(CameraUpdateFactory.tiltTo(if (on) 55.0 else 0.0), 600)
        return on
    }

    var onTiltChanged: (Boolean) -> Unit = {}
    var onMyCarClick: () -> Unit = {}

    /** Look of your own car while navigating (Car3D skin id). */
    var mySkin: String = "arrow"
        set(v) {
            if (field == v) return
            field = v
            style?.let { setupMyCar(it) }
        }

    private val looks = HashSet<String>()
    private var myLook = ""
    private var myCarKey = ""
    private var convoyCamKey = ""

    private fun ensureLook(s: Style, name: String, body: Car3D.Body, color: Int, lengthDp: Float) {
        if (!looks.add(name)) return
        val dpi = context.resources.displayMetrics.densityDpi
        // tag each frame with this screen's density, or the car screen (other density) shows it tiny
        Car3D.frames(context, name, body, color, lengthDp).forEach { (n, b) -> b.density = dpi; s.addImage(n, b) }
    }

    /** Your car: drawn from the location component's own (smoothly animated) source. */
    private fun setupMyCar(s: Style) {
        val sk = Car3D.skin(mySkin)
        myLook = "me-${sk.id}"
        ensureLook(s, myLook, sk.body, sk.color, 56f)
        if (s.getLayer("me-car") == null && s.getSource(LOCATION_SOURCE) != null) {
            s.addLayer(
                SymbolLayer("me-car", LOCATION_SOURCE).withProperties(
                    iconAllowOverlap(true), iconIgnorePlacement(true),
                    iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 0.4f), Expression.stop(14, 0.7f), Expression.stop(16, 0.95f), Expression.stop(18, 1.35f))),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                    iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                )
            )
        }
        myCarKey = ""
        updateMyCar()
    }

    /**
     * Your car points where the phone points (compass) while you stand or walk, like Google Maps'
     * blue dot; while driving or navigating it points along your travel direction. Turning the map
     * with your fingers never turns the car: it keeps its real-world direction.
     */
    private fun carFollowsCompass(): Boolean =
        navRoute == null && (lastRawFix?.speedMps ?: 0.0) < 2.5

    /** Picks the frame of your car for the current camera; the heading comes from the source itself. */
    private fun updateMyCar() {
        val s = style ?: return
        val l = s.getLayer("me-car") as? SymbolLayer ?: return
        val cp = map.cameraPosition
        val tilted = cp.tilt >= Car3D.TILT_MIN
        val mb = Math.round(cp.bearing).toDouble()
        val compass = carFollowsCompass()
        val key = "$myLook|$tilted|$mb|$compass"
        if (key == myCarKey) return
        myCarKey = key
        val heading = if (compass) Expression.coalesce(get(COMPASS_BEARING), get(GPS_BEARING), literal(0.0)) else get(GPS_BEARING)
        val rel = Expression.mod(
            Expression.sum(Expression.subtract(Expression.toNumber(heading), literal(mb)), literal(720.0)), literal(360.0),
        )
        if (tilted) {
            val idx = Expression.round(Expression.division(rel, literal(Car3D.STEP)))
            l.setProperties(
                iconImage(Expression.concat(literal("$myLook-t"), Expression.toString(Expression.mod(idx, literal(Car3D.FRAMES))))),
                iconRotate(Expression.subtract(rel, Expression.product(idx, literal(Car3D.STEP)))),
            )
        } else {
            l.setProperties(iconImage("$myLook-0"), iconRotate(rel))
        }
    }

    /** Convoy car look: the member's own skin, else a hatchback in their colour; grey when offline. */
    private fun convoyLook(s: Style, m: Member): String {
        val sk = Car3D.skins.firstOrNull { it.id == m.skin && it.body != Car3D.Body.ARROW }
        val body = sk?.body ?: Car3D.Body.HATCH
        val color = if (m.stale) 0xFF8A8F96.toInt() else sk?.color ?: m.color
        val name = "cv-${body.name}-${Integer.toHexString(color)}"
        ensureLook(s, name, body, color, 48f)
        return name
    }

    private fun camKey(): String {
        val cp = map.cameraPosition
        return "${cp.tilt >= Car3D.TILT_MIN}|${Math.round(cp.bearing / 3)}"
    }

    /** Keeps convoy cars facing the right way while the map turns. */
    private fun convoyFollowCamera() {
        if (anims.isEmpty() || camKey() == convoyCamKey) return
        renderConvoy()
    }

    // ---------------------------------------------------------------- convoy cars (animated)

    fun setConvoy(members: List<Member>) {
        val now = System.currentTimeMillis()
        val ids = members.filter { !it.isMe }.map { it.id }.toSet()
        anims.keys.retainAll(ids)
        for (m in members) {
            if (m.isMe) continue
            val a = anims[m.id]
            if (a == null) {
                anims[m.id] = Anim(m.pos, m.pos, now, m.bearing, m)
            } else if (a.to != m.pos) {
                val cur = interp(a, now)
                a.from = cur
                a.to = m.pos
                a.start = now
                a.bearing = m.bearing
                a.m = m
            } else {
                a.m = m
            }
        }
        if (animJob?.isActive != true) {
            renderConvoy() // reflect name / offline changes even when nobody moves
            animJob = scope.launch {
                // animate only while someone is gliding to a new position (saves battery)
                while (isActive && anims.values.any { System.currentTimeMillis() - it.start < 3100 && it.from != it.to }) {
                    renderConvoy()
                    delay(100)
                }
                renderConvoy()
            }
        }
    }

    private fun interp(a: Anim, now: Long): LatLon {
        val t = ((now - a.start) / 3000.0).coerceIn(0.0, 1.0)
        return Geo.interpolate(a.from, a.to, t)
    }

    private fun renderConvoy() {
        val s = style ?: return
        val now = System.currentTimeMillis()
        val cp = map.cameraPosition
        val tilted = cp.tilt >= Car3D.TILT_MIN
        convoyCamKey = camKey()
        val f = anims.values.map { a ->
            val p = interp(a, now)
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                val look = convoyLook(s, a.m)
                val (frame, rest) = Car3D.pick(a.bearing - cp.bearing, tilted)
                it.addStringProperty("icon", Car3D.frameName(look, tilted, frame))
                it.addNumberProperty("rot", rest)
                // live speed under the name while they're moving
                val speed = if (!a.m.stale && a.m.speedKmh >= 3) "\n" + Math.round(a.m.speedKmh) + " km/h" else ""
                it.addStringProperty("name", a.m.name + (if (a.m.stale) " (offline)" else "") + speed)
                it.addStringProperty("color", String.format("#%06X", 0xFFFFFF and (if (a.m.stale) 0xFF5F6368.toInt() else a.m.color)))
                it.addStringProperty("id", a.m.id)
            }
        }
        s.getSourceAs<GeoJsonSource>("convoy")?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    // ---------------------------------------------------------------- clicks

    private fun handleClick(ll: LatLng) {
        val pt: PointF = map.projection.toScreenLocation(ll)
        val d = context.resources.displayMetrics.density
        val box = android.graphics.RectF(pt.x - 14 * d, pt.y - 14 * d, pt.x + 14 * d, pt.y + 14 * d)
        // convoy cars first: tapping a friend next to you must not open your own car picker
        map.queryRenderedFeatures(box, "convoy-layer").firstOrNull()?.let { f ->
            f.getStringProperty("id")?.let { onMemberClick(it); return }
        }
        if (map.queryRenderedFeatures(box, "me-car").isNotEmpty()) { onMyCarClick(); return }
        // tapping your own dot / arrow opens the car picker too
        val me = try { map.locationComponent.lastKnownLocation } catch (_: Exception) { null }
        if (me != null) {
            val mp = map.projection.toScreenLocation(LatLng(me.latitude, me.longitude))
            if (Math.hypot((mp.x - pt.x).toDouble(), (mp.y - pt.y).toDouble()) < 26 * d) { onMyCarClick(); return }
        }
        map.queryRenderedFeatures(box, "stops-layer").firstOrNull()?.let { f ->
            f.getStringProperty("id")?.let { onStopClick(it); return }
        }
        map.queryRenderedFeatures(box, "reports-layer").firstOrNull()?.let { f ->
            f.getStringProperty("id")?.let { onReportClick(it); return }
        }
        map.queryRenderedFeatures(box, "cameras-layer").firstOrNull()?.let { f ->
            val id = f.getStringProperty("id")?.toLongOrNull()
            lastCameras.firstOrNull { it.id == id }?.let { onCameraClick(it); return }
        }
        map.queryRenderedFeatures(box, "route-labels-layer", "routes-alt-line", "routes-alt-case").firstOrNull()?.let { f ->
            if (f.hasProperty("idx")) { onRouteClick(f.getNumberProperty("idx").toInt()); return }
        }
        map.queryRenderedFeatures(box, "pins-layer").firstOrNull()?.let { f ->
            val idx = if (f.hasProperty("res")) f.getNumberProperty("res").toInt() else -1
            val list = lastPins.second
            if (idx in list.indices) { onPlaceClick(list[idx]); return }
        }
        // a POI of the base map
        map.queryRenderedFeatures(box, "poi", "poi-major").firstOrNull()?.let { f ->
            val name = f.getStringProperty("name") ?: f.getStringProperty("name:latin")
            val g = f.geometry()
            if (name != null && g is Point) {
                onPlaceClick(Place(name, LatLon(g.latitude(), g.longitude()), "", f.getStringProperty("class") ?: ""))
                return
            }
        }
        onMapClick(LatLon(ll.latitude, ll.longitude))
    }
}

private const val NAV_TILT = 52.0
private const val LOCATION_SOURCE = "mapbox-location-source"
private const val GPS_BEARING = "mapbox-property-gps-bearing"
/** Phone compass direction, filled in by the location component in COMPASS render mode. */
private const val COMPASS_BEARING = "mapbox-property-compass-bearing"
