package com.drivemap.app.auto

import com.drivemap.app.i18n.t
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.drivemap.app.core.Core
import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.Search
import com.drivemap.app.route.Place
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Android Auto entry point (navigation app category). */
class DriveMapCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = DriveMapSession()
}

class DriveMapSession : Session() {
    private lateinit var surface: CarMapSurface

    override fun onCreateScreen(intent: Intent): Screen {
        surface = CarMapSurface(carContext, lifecycle)
        val navManager = carContext.getCarService(NavigationManager::class.java)
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                Core.stopNavigation()
            }

            /** "Auto drive" in the desktop head unit: simulate the current route. */
            override fun onAutoDriveEnabled() {
                val n = Core.navigator.state.value ?: return
                Core.startNavigation(n.route, n.request, simulate = true)
            }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = Core.onCarSession(true)
            override fun onStop(owner: LifecycleOwner) = Core.onCarSession(false)
        })
        // Tell the car when we start / stop guiding, so it can stop other navigation apps.
        if (Core.navigator.active) try { navManager.navigationStarted() } catch (_: Exception) {}
        lifecycleScope.launch {
            Core.navigator.state.map { it != null }.distinctUntilChanged().drop(1).collect { navigating ->
                try {
                    if (navigating) navManager.navigationStarted() else navManager.navigationEnded()
                } catch (_: Exception) {
                }
            }
        }
        val home = CarHomeScreen(carContext, surface)
        handleIntent(intent, home)
        return home
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent, null)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        surface.setNight(carContext.isDarkMode)
    }

    /** "Navigate to …" from Google Assistant / other apps: geo:lat,lon or geo:0,0?q=address. */
    private fun handleIntent(intent: Intent, home: Screen?) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val ssp = intent.data?.schemeSpecificPart ?: return
        val q = ssp.substringAfter("q=", "").let { java.net.URLDecoder.decode(it, "UTF-8") }
        val num = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)")
        val m = num.find(q) ?: num.find(ssp.substringBefore('?'))
        val screens = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        if (m != null) {
            val pos = LatLon(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            if (pos.lat != 0.0 || pos.lon != 0.0) {
                screens.push(CarRoutePreviewScreen(carContext, surface, Place(q.substringAfter('(').substringBefore(')').ifBlank { t("Destination") }
, pos)))
                return
            }
        }
        if (q.isBlank()) return
        lifecycleScope.launch {
            val place = try {
                Search.autocomplete(q, Core.location.fix.value?.pos, 1).firstOrNull()
            } catch (_: Exception) {
                null
            }
            if (place != null) screens.push(CarRoutePreviewScreen(carContext, surface, place))
        }
    }
}
