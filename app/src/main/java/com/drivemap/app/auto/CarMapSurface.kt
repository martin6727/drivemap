package com.drivemap.app.auto

import android.app.Presentation
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.drivemap.app.core.Core
import com.drivemap.app.ui.MapController
import com.drivemap.app.ui.refreshCameras
import com.drivemap.app.ui.syncMapWithCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapView
import kotlin.math.ln

/**
 * Renders the DriveMap map (same MapController as the phone) onto the surface Android Auto gives
 * us, via a virtual display + Presentation, with a small HUD (speed limit, speed, fine, radars).
 */
class CarMapSurface(private val carContext: CarContext, lifecycle: Lifecycle) : SurfaceCallback, DefaultLifecycleObserver {
    var controller: MapController? = null
        private set
    /** True after the driver panned the map (shows a "recentre" button). */
    val panned = MutableStateFlow(false)

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var hud: CarHud? = null
    private var mapScope: CoroutineScope? = null
    private var hudJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visibleArea: Rect? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private val readyCallbacks = ArrayList<(MapController) -> Unit>()

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        teardown()
        scope.cancel()
    }

    fun whenReady(f: (MapController) -> Unit) {
        val c = controller
        if (c != null) f(c) else readyCallbacks.add(f)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        teardown()
        val surface = surfaceContainer.surface ?: return
        if (surfaceContainer.width <= 0 || surfaceContainer.height <= 0) return
        surfaceW = surfaceContainer.width
        surfaceH = surfaceContainer.height
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "DriveMapCar", surfaceW, surfaceH, surfaceContainer.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        virtualDisplay = vd
        val p = Presentation(carContext, vd.display)
        val ctx = p.context
        val mv = MapView(ctx)
        mv.onCreate(null)
        val root = FrameLayout(ctx)
        root.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val h = CarHud(ctx)
        root.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.setContentView(root)
        p.show()
        mv.onStart()
        mv.onResume()
        presentation = p
        mapView = mv
        hud = h
        visibleArea?.let { h.area = it }

        mv.getMapAsync { map ->
            val ms = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            mapScope = ms
            val c = MapController(ctx, mv, map, ms)
            c.routeLetters = true
            c.onTrackingDismissed = { panned.value = true }
            c.onCameraIdle = { refreshCameras(c) }
            Core.location.fix.value?.let { c.moveTo(it.pos, 15.0, animate = false) }
            c.loadStyle(wantNight()) {
                controller = c
                applyInsets()
                ms.syncMapWithCore(c) { if (!panned.value) c.followNavigation() }
                // re-check day/night every minute and when the setting changes
                ms.launch { Core.settings.state.collect { setNight(wantNight()) } }
                ms.launch { while (true) { kotlinx.coroutines.delay(60_000); setNight(wantNight()) } }
                if (Core.navigator.active) c.followNavigation() else c.followUser(15.0)
                readyCallbacks.forEach { it(c) }
                readyCallbacks.clear()
            }
        }

        hudJob = scope.launch {
            combine(Core.location.fix, Core.speedLimits.road, Core.fine, Core.radar.alert, Core.radar.section) { f, road, fine, alert, section ->
                CarHud.State(
                    limit = road?.limit,
                    speed = f?.shownKmh ?: 0,
                    fine = fine?.takeIf { Core.settings.value.fineMeter },
                    alert = alert,
                    section = section,
                )
            }.collect { h.state = it }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        hud?.area = Rect(visibleArea)
        applyInsets()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        teardown()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val c = controller ?: return
        if (!panned.value) {
            panned.value = true
            c.stopFollow()
        }
        c.map.scrollBy(-distanceX, -distanceY, 0)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val c = controller ?: return
        if (scaleFactor <= 0f) return
        val delta = ln(scaleFactor.toDouble()) / ln(2.0)
        if (focusX < 0 || focusY < 0) c.map.moveCamera(CameraUpdateFactory.zoomBy(delta))
        else c.map.moveCamera(CameraUpdateFactory.zoomBy(delta, Point(focusX.toInt(), focusY.toInt())))
    }

    /** Browse map tilted (3D)? */
    var tilted3d = false
        private set

    fun toggleTilt() {
        tilted3d = controller?.toggleBrowseTilt() ?: false
    }

    fun zoom(by: Double) {
        controller?.map?.animateCamera(CameraUpdateFactory.zoomBy(by), 300)
    }

    fun recenter() {
        val c = controller ?: return
        panned.value = false
        if (Core.navigator.active) c.followNavigation() else c.followUser(15.0)
    }

    /** Same day/night rule as the phone (Auto = sunset at your position), or the car's night signal. */
    fun wantNight(): Boolean = when (Core.settings.value.mapTheme) {
        com.drivemap.app.data.MapTheme.DAY -> false
        com.drivemap.app.data.MapTheme.NIGHT -> true
        com.drivemap.app.data.MapTheme.AUTO -> carContext.isDarkMode || com.drivemap.app.ui.isNightNow(com.drivemap.app.data.MapTheme.AUTO)
    }

    fun setNight(night: Boolean) {
        val c = controller ?: return
        if (c.night != night) c.loadStyle(night)
    }

    private fun applyInsets() {
        val c = controller ?: return
        val a = visibleArea ?: return
        if (surfaceW <= 0 || surfaceH <= 0) return
        c.insets = intArrayOf(
            a.left.coerceAtLeast(0), a.top.coerceAtLeast(0),
            (surfaceW - a.right).coerceAtLeast(0), (surfaceH - a.bottom).coerceAtLeast(0),
        )
    }

    private fun teardown() {
        hudJob?.cancel()
        hudJob = null
        mapScope?.cancel()
        mapScope = null
        controller?.destroy()
        controller = null
        mapView?.let {
            try {
                it.onPause()
                it.onStop()
                it.onDestroy()
            } catch (_: Exception) {
            }
        }
        mapView = null
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        hud = null
    }
}
