package com.drivemap.app.core

import com.drivemap.app.i18n.t
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.drivemap.app.convoy.ConvoyEvent
import com.drivemap.app.convoy.ConvoyManager
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.data.PlacesStore
import com.drivemap.app.data.SettingsStore
import com.drivemap.app.fines.FineEstimate
import com.drivemap.app.fines.FineEstimator
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.location.Fix
import com.drivemap.app.location.LocationSource
import com.drivemap.app.nav.Navigator
import com.drivemap.app.nav.Phrases
import com.drivemap.app.nav.Simulator
import com.drivemap.app.nav.Voice
import com.drivemap.app.net.Valhalla
import com.drivemap.app.radar.CameraRepository
import com.drivemap.app.radar.RadarAlerter
import com.drivemap.app.radar.ReportsRepository
import com.drivemap.app.route.Route
import com.drivemap.app.route.RoutePlanner
import com.drivemap.app.route.Waypoint
import com.drivemap.app.service.DriveService
import com.drivemap.app.speed.SpeedLimitProvider
import com.drivemap.app.vignette.VignetteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A message for the snackbar, optionally with an action button. */
data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/**
 * App-wide engine: owns the location source, navigation, radar, fine meter and convoy, and
 * survives activity recreation. Everything runs on the main thread except network calls.
 */
object Core {
    lateinit var app: Context
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsStore
    lateinit var places: PlacesStore
    lateinit var myRoads: com.drivemap.app.data.MyRoadsStore
    lateinit var vignettes: VignetteStore
    lateinit var location: LocationSource
    lateinit var valhalla: Valhalla
    lateinit var cameras: CameraRepository
    lateinit var planner: RoutePlanner
    lateinit var voice: Voice
    lateinit var navigator: Navigator
    lateinit var simulator: Simulator
    lateinit var speedLimits: SpeedLimitProvider
    lateinit var mqtt: MqttHub
    lateinit var reports: ReportsRepository
    lateinit var convoy: ConvoyManager
    lateinit var radar: RadarAlerter
    lateinit var hotspots: com.drivemap.app.radar.HotspotStore

    private val _fine = MutableStateFlow<FineEstimate?>(null)
    val fine: StateFlow<FineEstimate?> = _fine
    private val _driveMode = MutableStateFlow(false)
    /** Drive mode keeps radar alerts / convoy running with the screen off (no route). */
    val driveMode: StateFlow<Boolean> = _driveMode
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages
    private val _pendingConvoyDestination = MutableStateFlow<com.drivemap.app.convoy.ConvoyStop?>(null)
    val pendingConvoyDestination: StateFlow<com.drivemap.app.convoy.ConvoyStop?> = _pendingConvoyDestination

    private var initialized = false
    private var foreground = false
    private var lastCameraCenter: LatLon? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        settings = SettingsStore(app)
        places = PlacesStore(app)
        myRoads = com.drivemap.app.data.MyRoadsStore(app)
        vignettes = VignetteStore(app)
        location = LocationSource(app)
        valhalla = Valhalla { settings.value.valhallaUrl }
        cameras = CameraRepository(app).also { it.start() }
        planner = RoutePlanner(valhalla, settings, vignettes, cameras) { myRoads.value }
        voice = Voice(app).also { it.init(settings.value.voiceLang) }
        navigator = Navigator(scope, planner, voice, settings)
        navigator.carConnected = { carActive }
        simulator = Simulator(scope)
        speedLimits = SpeedLimitProvider(valhalla, scope)
        mqtt = MqttHub(scope) { settings.value.mqttUrl }
        reports = ReportsRepository(mqtt, settings)
        convoy = ConvoyManager(app, mqtt, settings, reports, scope)
        hotspots = com.drivemap.app.radar.HotspotStore(app, mqtt, settings, scope)
        reports.onReportSeen = { hotspots.record(it) }
        radar = RadarAlerter(cameras, reports, settings, voice, hotspots)

        location.onFix = { onFix(it) }
        VignetteStore.ensureChannel(app)
        VignetteStore.scheduleReminders(app, vignettes.items.value)

        com.drivemap.app.i18n.L10n.apply(settings.value.appLanguage)
        scope.launch { Updates.check(app) }
        scope.launch {
            settings.state.collect {
                com.drivemap.app.i18n.L10n.apply(it.appLanguage)
                voice.setLanguage(it.voiceLang)
            }
        }
        // fuel prices: weekly EU averages (cached, refreshed every few days) + the country we're in
        com.drivemap.app.fuel.FuelPrices.init(app, mqtt, scope)
        scope.launch { speedLimits.country.collect { c -> if (c != null) com.drivemap.app.fuel.FuelPrices.currentCountry = c } }
        // trip stats count simulated time at the simulator's speed-up
        navigator.timeScale = { if (simulator.running.value) simulator.speedUp.value.toDouble() else 1.0 }
        scope.launch { convoy.events.collect { onConvoyEvent(it) } }
        scope.launch { convoy.state.collect { updateService() } }
    }

    private var convoyResumed = false

    fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, actionLabel, action))
    }

    // ---------------------------------------------------------------- lifecycle

    fun onForeground(fg: Boolean) {
        foreground = fg
        if (fg) {
            location.start()
            reports.setActive(true)
            hotspots.setActive(true)
            // Rejoin a saved convoy only when the user opens the app (not when an alarm wakes us).
            if (!convoyResumed) {
                convoyResumed = true
                convoy.resume()
            }
        } else if (!serviceNeeded()) {
            location.stop()
            reports.setActive(false)
            hotspots.setActive(false)
        }
    }

    private fun serviceNeeded() = navigator.active || convoy.active || _driveMode.value || carActive

    @Volatile private var carActive = false

    /** Android Auto session started/stopped: keep GPS, radar and convoy running for the car. */
    fun onCarSession(active: Boolean) {
        carActive = active
        if (active) {
            location.start()
            reports.setActive(true)
            hotspots.setActive(true)
            if (!convoyResumed) {
                convoyResumed = true
                convoy.resume()
            }
        }
        updateService()
        if (!active && !foreground && !serviceNeeded()) {
            location.stop()
            reports.setActive(false)
            hotspots.setActive(false)
        }
    }

    fun updateService() {
        val need = serviceNeeded()
        val i = Intent(app, DriveService::class.java)
        if (need) {
            if (!DriveService.running) {
                try { ContextCompat.startForegroundService(app, i) } catch (_: Exception) {}
            } else DriveService.refresh()
        } else if (DriveService.running) {
            app.stopService(i)
            if (!foreground) {
                location.stop()
                reports.setActive(false)
                hotspots.setActive(false)
            }
        }
    }

    fun setDriveMode(on: Boolean) {
        _driveMode.value = on
        updateService()
    }

    // ---------------------------------------------------------------- fixes

    private fun onFix(fix: Fix) {
        navigator.onFix(fix)
        val nav = navigator.state.value
        speedLimits.onFix(fix, nav?.route, nav?.alongM)
        val road = speedLimits.road.value
        val country = road?.country ?: speedLimits.country.value
        radar.onFix(fix, nav, road, country)
        updateFine(fix)
        convoy.onFix(fix, nav)
        reports.onPosition(fix.pos)
        hotspots.onPosition(fix.pos)
        if (nav?.arrived == true && !arrivalHandled) onArrived()
        val c = lastCameraCenter
        if (c == null || Geo.dist(c, fix.pos) > 15_000) {
            lastCameraCenter = fix.pos
            cameras.ensure(fix.pos.lat - 0.3, fix.pos.lon - 0.4, fix.pos.lat + 0.3, fix.pos.lon + 0.4, maxTiles = 4)
        }
        if (nav?.arrived == true && simulator.running.value) simulator.stop()
    }

    private fun updateFine(fix: Fix) {
        val s = settings.value
        val road = speedLimits.road.value
        val limit = road?.limit
        if (!s.fineMeter || road == null || limit == null || limit <= 0) {
            _fine.value = null
            return
        }
        _fine.value = FineEstimator.estimate(
            road.country ?: speedLimits.country.value, fix.speedKmh, limit, road.urban, road.motorway, s.toleranceInFine,
        )
    }

    // ---------------------------------------------------------------- navigation

    fun startNavigation(route: Route, req: RoutePlanner.Request, simulate: Boolean) {
        simulator.stop()
        arrivalHandled = false
        radar.tripAlerts = 0
        _tripSummary.value = null
        location.simulating = simulate
        navigator.start(route, req)
        updateService()
        if (simulate) {
            simulator.start({ navigator.state.value?.route }) { fix -> location.inject(fix) }
        } else {
            location.start()
            location.lastRealFix()?.let { navigator.onFix(it) }
        }
    }

    private var arrivalHandled = false
    private val _tripSummary = MutableStateFlow<com.drivemap.app.nav.TripSummary?>(null)
    /** Trip stats shown for 5 s after arriving, then the app goes back to the normal map. */
    val tripSummary: StateFlow<com.drivemap.app.nav.TripSummary?> = _tripSummary

    private fun onArrived() {
        arrivalHandled = true
        val summary = navigator.tripSummary(radar.tripAlerts)
        scope.launch {
            delay(1500) // let "You have arrived" finish
            if (navigator.state.value?.arrived != true) return@launch
            stopNavigation()
            onNavigationAutoStopped()
            _tripSummary.value = summary
            delay(5000)
            if (_tripSummary.value === summary) _tripSummary.value = null
        }
    }

    fun dismissTripSummary() {
        _tripSummary.value = null
    }

    /** UI hook: reset camera etc. when navigation ends by itself (set by the UI layer). */
    var onNavigationAutoStopped: () -> Unit = {}

    fun stopNavigation() {
        simulator.stop()
        location.simulating = false
        navigator.stop()
        speedLimits.clear()
        _fine.value = null
        updateService()
    }

    // ---------------------------------------------------------------- convoy events

    private fun onConvoyEvent(e: ConvoyEvent) {
        val lang = settings.value.voiceLang
        when (e) {
            is ConvoyEvent.StopAdded -> {
                val st = e.stop
                if (navigator.active && settings.value.autoAddConvoyStops) {
                    addStopToNavigation(Waypoint(st.name, st.pos, st.id))
                    if (settings.value.voice) voice.speak(Phrases.get(lang, "stopAdded"))
                    message(t("{0} added a stop: {1} — added to your route", st.addedByName, st.name))
                } else {
                    message(t("{0} added a convoy stop: {1}", st.addedByName, st.name))
                }
            }
            is ConvoyEvent.StopRemoved -> {
                if (navigator.active) {
                    val stops = navigator.remainingStops()
                    if (stops.size > 1 && stops.any { it.convoyStopId == e.stop.id }) {
                        navigator.updateStops(stops.filter { it.convoyStopId != e.stop.id })
                    }
                }
                message(t("Convoy stop removed: {0}", e.stop.name))
            }
            is ConvoyEvent.DestinationSet -> {
                _pendingConvoyDestination.value = e.stop
                message(t("{0} set the convoy destination: {1}", e.stop.addedByName, e.stop.name))
            }
            is ConvoyEvent.MemberJoined -> message(t("{0} joined the convoy", e.name))
            is ConvoyEvent.CruiseInvited -> {
                _pendingCruise.value = e.invite
                if (!foreground) notifyCruise(e.invite)
                if (settings.value.voice) voice.chime(urgent = false)
            }
            is ConvoyEvent.CruiseAnswered -> {
                val text = if (e.invite.isJoin) { if (e.accepted) t("{0} is coming with you", e.name) else t("{0} can't come", e.name) }
                else if (e.accepted) t("{0} accepted your challenge!", e.name) else t("{0} declined your challenge", e.name)
                message(text)
                // said out loud too: the accept is the start signal, no need to look at the screen
                if (e.accepted && settings.value.voice) voice.speak(text)
            }

        }
    }

    fun clearPendingDestination() {
        _pendingConvoyDestination.value = null
    }

    private val _pendingCruise = MutableStateFlow<com.drivemap.app.route.CruiseInvite?>(null)
    /** Cruise challenge from a convoy member, waiting for Accept / Decline. */
    val pendingCruise: StateFlow<com.drivemap.app.route.CruiseInvite?> = _pendingCruise

    fun clearPendingCruise() {
        _pendingCruise.value = null
        app.getSystemService(android.app.NotificationManager::class.java)?.cancel(CRUISE_NOTIFICATION)
    }

    private const val CRUISE_NOTIFICATION = 4711

    /** System notification when the app isn't open; tapping it opens the app with the challenge. */
    private fun notifyCruise(inv: com.drivemap.app.route.CruiseInvite) {
        try {
            val nm = app.getSystemService(android.app.NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                android.app.NotificationChannel("convoy", t("Convoy"), android.app.NotificationManager.IMPORTANCE_HIGH),
            )
            val open = android.app.PendingIntent.getActivity(
                app, 0, Intent(app, com.drivemap.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = androidx.core.app.NotificationCompat.Builder(app, "convoy")
                .setSmallIcon(com.drivemap.app.R.drawable.ic_stat_car)
                .setContentTitle(if (inv.isJoin) t("Drive with me") else t("Challenge"))
                .setContentText(
                    if (inv.isJoin) t("{0} wants you to drive with them to {1}", inv.fromName, inv.destName ?: "")
                    else t("{0} challenges you to a {1}-minute drive", inv.fromName, inv.minutes),
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open).setAutoCancel(true)
                .build()
            nm.notify(CRUISE_NOTIFICATION, n)
        } catch (_: Exception) {
        }
    }

    /** Inserts a stop in driving order (by its position along the current route) and re-plans. */
    fun addStopToNavigation(wp: Waypoint) {
        val nav = navigator.state.value ?: return
        val stops = navigator.remainingStops().toMutableList()
        if (stops.any { it.convoyStopId != null && it.convoyStopId == wp.convoyStopId }) return
        val path = nav.route.path
        val wpAlong = path.project(wp.pos)?.along ?: Double.MAX_VALUE
        var insertAt = stops.size - 1
        for (i in 0 until stops.size - 1) {
            val a = path.project(stops[i].pos)?.along ?: continue
            if (wpAlong < a) { insertAt = i; break }
        }
        stops.add(insertAt.coerceAtLeast(0), wp)
        navigator.updateStops(stops)
    }
}
