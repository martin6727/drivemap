package com.drivemap.app.ui

import com.drivemap.app.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a [MapController] in sync with the app engine: location, navigation route + car,
 * convoy cars and stops, radar reports, speed cameras and the route colour.
 * Used by the phone screen and by the Android Auto screen.
 */
fun CoroutineScope.syncMapWithCore(c: MapController, onNavigationStarted: () -> Unit) {
    launch { Core.location.fix.filterNotNull().collect { c.updateLocation(it) } }
    launch {
        var lastVersion = -1
        var lastWp = -1
        Core.navigator.state.collect { n ->
            c.setPoisVisible(n == null)
            c.setNavigation(n)
            c.setManeuverArrow(n?.route, n?.maneuverIdx ?: -1)
            if (n == null) {
                if (lastVersion != -1) {
                    c.setWaypoints(emptyList())
                    lastVersion = -1
                    lastWp = -1
                }
            } else {
                if (n.routeVersion != lastVersion || n.nextWaypoint != lastWp) {
                    c.setWaypoints(n.request.stops.drop(n.nextWaypoint))
                }
                if (lastVersion == -1) onNavigationStarted()
                lastVersion = n.routeVersion
                lastWp = n.nextWaypoint
            }
        }
    }
    launch {
        Core.settings.state.collect {
            c.setRouteColor(it.routeColor)
            c.mySkin = it.carSkin
            c.view3d = it.view3d
        }
    }
    // bends coloured (green / orange / red) in a challenge, and for Sport routes (planning and driving)
    launch {
        kotlinx.coroutines.flow.combine(
            Ui.challenge,
            Core.navigator.state.map { it?.request?.mode },
            Ui.directions.map { it?.mode },
        ) { ch, navMode, planMode ->
            ch || navMode == com.drivemap.app.route.RouteMode.SPORT || (navMode == null && planMode == com.drivemap.app.route.RouteMode.SPORT)
        }.distinctUntilChanged().collect { c.challengeMode = it }
    }
    launch {
        Core.convoy.state.collect { st ->
            c.setConvoy(st?.members ?: emptyList())
            c.setConvoyStops(st?.stops ?: emptyList(), st?.destination)
        }
    }
    launch { Core.reports.reports.collect { c.setReports(it) } }
    launch { Core.cameras.version.collect { refreshCameras(c) } }
    launch { Core.radar.blocked.collect { refreshCameras(c) } }
}

/** Shows fixed cameras in view (hidden where warnings are illegal or alerts are off). */
fun refreshCameras(c: MapController) {
    val zoom = c.map.cameraPosition.zoom
    val blocked = Core.radar.blocked.value || !Core.settings.value.radarAlerts
    if (zoom < 9.5) {
        c.setCameras(emptyList(), blocked)
        return
    }
    val b = c.visibleBounds()
    c.setCameras(Core.cameras.inBounds(b[0], b[1], b[2], b[3], limit = 1500), blocked)
}
