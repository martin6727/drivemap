package com.drivemap.app.auto

import com.drivemap.app.i18n.t
import com.drivemap.app.i18n.tn
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.drivemap.app.R
import com.drivemap.app.core.Core
import com.drivemap.app.geo.Geo
import com.drivemap.app.net.Search
import com.drivemap.app.radar.ReportType
import com.drivemap.app.route.Place
import com.drivemap.app.route.Route
import com.drivemap.app.route.RouteMode
import com.drivemap.app.route.RoutePlanner
import com.drivemap.app.route.Waypoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

private fun Screen.icon(res: Int): CarIcon = CarUtil.icon(carContext, res)

/** Main car screen: the map with turn-by-turn guidance (or "Where to?" when not navigating). */
class CarHomeScreen(carContext: CarContext, private val surface: CarMapSurface) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            // navigation info can change every second; refresh at most ~1×/s
            combine(Core.navigator.state, surface.panned, Core.settings.state) { n, p, _ -> Triple(n?.maneuverIdx, n?.arrived, p) }
                .conflate()
                .collect {
                    invalidate()
                    delay(900)
                }
        }
        lifecycleScope.launch {
            // a challenge / "drive with me" arriving pops up on the car screen
            var shown: String? = null
            Core.pendingCruise.collect { inv ->
                if (inv != null && inv.id != shown) {
                    shown = inv.id
                    screenManager.push(CarInviteScreen(carContext, inv))
                }
            }
        }
        lifecycleScope.launch { Core.convoy.state.collect { invalidate() } }
        lifecycleScope.launch {
            // StateFlow already drops values a slow collector misses: this refreshes ≤ 1×/s
            Core.navigator.state.collect {
                invalidate()
                delay(1000)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val nav = Core.navigator.state.value
        val b = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.createCustom(0xFF0F7B45.toInt(), 0xFF0B5E34.toInt()))
        val strip = ActionStrip.Builder()
        when {
            nav != null && nav.arrived -> {
                b.setNavigationInfo(
                    MessageInfo.Builder(t("You have arrived"))
                        .setText(nav.request.stops.lastOrNull()?.name ?: "")
                        .setImage(icon(R.drawable.ic_car_flag))
                        .build()
                )
                strip.addAction(Action.Builder().setTitle(t("Done")).setOnClickListener { Core.stopNavigation(); invalidate() }.build())
            }
            nav != null -> {
                b.setNavigationInfo(CarUtil.routingInfo(nav))
                b.setDestinationTravelEstimate(CarUtil.travelEstimate(nav))
                strip.addAction(Action.Builder().setTitle(t("End")).setOnClickListener { Core.stopNavigation(); invalidate() }.build())
            }
            else -> {
                strip.addAction(
                    Action.Builder().setTitle(t("Where to?")).setIcon(icon(R.drawable.ic_car_search))
                        .setOnClickListener { screenManager.push(CarDestinationsScreen(carContext, surface)) }.build()
                )
            }
        }
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_report)).setOnClickListener { screenManager.push(CarReportScreen(carContext)) }.build())
        val muted = Core.voice.muted
        strip.addAction(
            Action.Builder().setIcon(icon(if (muted) R.drawable.ic_car_volume_off else R.drawable.ic_car_volume_on))
                .setOnClickListener { Core.voice.muted = !Core.voice.muted; invalidate() }.build()
        )
        if (Core.convoy.state.value != null) {
            strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_group)).setOnClickListener { screenManager.push(CarConvoyScreen(carContext, surface)) }.build())
        }
        b.setActionStrip(strip.build())

        val mapStrip = ActionStrip.Builder().addAction(Action.PAN)
        if (surface.panned.value) {
            mapStrip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_recenter)).setOnClickListener { surface.recenter(); invalidate() }.build())
        }
        mapStrip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_in)).setOnClickListener { surface.zoom(1.0) }.build())
        mapStrip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_out)).setOnClickListener { surface.zoom(-1.0) }.build())
        b.setMapActionStrip(mapStrip.build())
        b.setPanModeListener { inPan -> if (inPan) surface.panned.value = true }
        return b.build()
    }
}

/** "Where to?": convoy destination, home, work, saved and recent places, nearby fuel. */
class CarDestinationsScreen(carContext: CarContext, private val surface: CarMapSurface) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val entries = ArrayList<Triple<String, Place, Int>>()
        Core.convoy.state.value?.destination?.let { entries += Triple(t("Convoy: {0}", it.name), Place(it.name, it.pos), R.drawable.ic_car_group) }
        Core.places.home.value?.let { entries += Triple(t("Home"), it, R.drawable.ic_car_home) }
        Core.places.work.value?.let { entries += Triple(t("Work"), it, R.drawable.ic_car_work) }
        Core.places.favorites.value.forEach { entries += Triple(it.name, it, R.drawable.ic_car_star) }
        Core.places.recents.value.forEach { entries += Triple(it.name, it, R.drawable.ic_car_history) }
        val seen = HashSet<String>()
        val fix = Core.location.fix.value
        val list = ItemList.Builder()
        entries.filter { seen.add(it.second.name + it.second.pos) }.take(6).forEach { (title, place, res) ->
            val sub = listOfNotNull(
                fix?.let { Geo.formatDistance(Geo.dist(it.pos, place.pos)) },
                place.address.ifBlank { null },
            ).joinToString(" · ").ifBlank { " " }
            list.addItem(
                Row.Builder().setTitle(title).addText(sub).setImage(icon(res))
                    .setOnClickListener { screenManager.push(CarRoutePreviewScreen(carContext, surface, place)) }
                    .build()
            )
        }
        list.setNoItemsMessage(t("No saved places yet – search, or save places on your phone"))
        return PlaceListNavigationTemplate.Builder()
            .setHeader(Header.Builder().setTitle(t("Where to?")).setStartHeaderAction(Action.BACK).build())
            .setItemList(list.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle(t("Search")).setIcon(icon(R.drawable.ic_car_search)).setOnClickListener { screenManager.push(CarSearchScreen(carContext, surface)) }.build())
                    .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_fuel)).setOnClickListener { screenManager.push(CarCategoryScreen(carContext, surface, t("Fuel"), "fuel")) }.build())
                    .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_parking)).setOnClickListener { screenManager.push(CarCategoryScreen(carContext, surface, t("Parking"), "parking")) }.build())
                    .build()
            )
            .build()
    }
}

/** Nearby places of a category (fuel, parking) sorted by distance. */
class CarCategoryScreen(
    carContext: CarContext,
    private val surface: CarMapSurface,
    private val title: String,
    private val term: String,
) : Screen(carContext) {
    private var places: List<Place>? = null

    init {
        lifecycleScope.launch {
            val center = Core.location.fix.value?.pos ?: surface.controller?.center()
            places = try {
                if (center == null) emptyList() else Search.category(term, center).sortedBy { Geo.dist(center, it.pos) }
            } catch (_: Exception) {
                emptyList()
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = PlaceListNavigationTemplate.Builder()
            .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
        val ps = places ?: return b.setLoading(true).build()
        val fix = Core.location.fix.value
        val list = ItemList.Builder()
        ps.take(6).forEach { p ->
            list.addItem(
                Row.Builder().setTitle(p.name)
                    .addText(listOfNotNull(fix?.let { Geo.formatDistance(Geo.dist(it.pos, p.pos)) }, p.address.ifBlank { null }).joinToString(" · ").ifBlank { " " })
                    .setOnClickListener { screenManager.push(CarRoutePreviewScreen(carContext, surface, p)) }
                    .build()
            )
        }
        list.setNoItemsMessage(t("Nothing found nearby"))
        return b.setItemList(list.build()).build()
    }
}

/** Address / place search (keyboard only while parked, as Android Auto requires). */
class CarSearchScreen(carContext: CarContext, private val surface: CarMapSurface) : Screen(carContext) {
    private var results: List<Place> = emptyList()
    private var searching = false
    private var job: Job? = null

    private fun search(q: String) {
        job?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            invalidate()
            return
        }
        job = lifecycleScope.launch {
            delay(350)
            searching = true
            invalidate()
            val near = Core.location.fix.value?.pos
            results = try {
                Search.autocomplete(q, near, 8)
            } catch (_: Exception) {
                emptyList()
            }
            invalidate()
            // businesses too (map places named like this nearby, registered companies)
            if (q.trim().length >= 3) {
                val more = com.drivemap.app.ui.Ui.businesses(q, near)
                results = com.drivemap.app.ui.Ui.rank(q, near, results + more.filter { m -> results.none { it.name.equals(m.name, true) && Geo.dist(it.pos, m.pos) < 40 } }).take(12)
            }
            searching = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText)
            override fun onSearchSubmitted(searchText: String) = search(searchText)
        }).setHeaderAction(Action.BACK).setSearchHint(t("Search places and addresses")).setShowKeyboardByDefault(true)
        if (searching && results.isEmpty()) return b.setLoading(true).build()
        val fix = Core.location.fix.value
        val list = ItemList.Builder()
        results.take(6).forEach { p ->
            list.addItem(
                Row.Builder().setTitle(p.name)
                    .addText(listOfNotNull(fix?.let { Geo.formatDistance(Geo.dist(it.pos, p.pos)) }, p.address.ifBlank { null }).joinToString(" · ").ifBlank { " " })
                    .setOnClickListener { screenManager.push(CarRoutePreviewScreen(carContext, surface, p)) }
                    .build()
            )
        }
        return b.setItemList(list.build()).build()
    }
}

/** Route choice on the car screen (all DriveMap route modes), then Start. */
class CarRoutePreviewScreen(
    carContext: CarContext,
    private val surface: CarMapSurface,
    private val place: Place,
) : Screen(carContext) {
    private var routes: List<Route>? = null
    private var request: RoutePlanner.Request? = null
    private var selected = 0
    private var error: String? = null
    private var mode = Core.settings.value.defaultMode
    private var started = false
    private var job: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                job?.cancel()
                if (!started) surface.controller?.setRoutes(emptyList(), 0)
            }
        })
        plan()
    }

    private fun plan() {
        routes = null
        error = null
        job?.cancel()
        invalidate()
        job = lifecycleScope.launch {
            val fix = Core.location.fix.value
            if (fix == null) {
                error = t("Waiting for GPS…")
                invalidate()
                return@launch
            }
            val req = RoutePlanner.Request(
                origin = fix.pos,
                originHeading = if (fix.hasBearing && fix.speedMps > 3) fix.bearing else null,
                stops = listOf(Waypoint(place.name, place.pos)),
                mode = mode,
                avoid = Core.settings.value.avoid,
            )
            try {
                val res = Core.planner.plan(req)
                routes = res.routes
                request = req
                selected = 0
                Core.places.addRecent(place)
                surface.whenReady { c ->
                    c.setRoutes(res.routes, 0)
                    c.fit(res.routes.flatMap { r -> r.geometry.shape.filterIndexed { i, _ -> i % 10 == 0 } } + fix.pos, 40, 40)
                }
            } catch (e: Exception) {
                error = e.message ?: t("Routing failed")
            }
            invalidate()
        }
    }

    private fun start() {
        val r = routes?.getOrNull(selected) ?: return
        val req = request ?: return
        started = true
        surface.controller?.setRoutes(emptyList(), 0)
        Core.startNavigation(r, req, simulate = false)
        surface.recenter()
        screenManager.popToRoot()
    }

    override fun onGetTemplate(): Template {
        error?.let { msg ->
            return MessageTemplate.Builder(msg)
                .setHeader(Header.Builder().setTitle(place.name).setStartHeaderAction(Action.BACK).build())
                .addAction(Action.Builder().setTitle(t("Try again")).setOnClickListener { plan() }.build())
                .build()
        }
        val b = RoutePreviewNavigationTemplate.Builder()
            .setHeader(Header.Builder().setTitle(place.name).setStartHeaderAction(Action.BACK).build())
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle(mode.short).setOnClickListener {
                        mode = RouteMode.entries[(mode.ordinal + 1) % RouteMode.entries.size]
                        plan()
                    }.build()
                ).build()
            )
        val rs = routes ?: return b.setLoading(true).build()
        val list = ItemList.Builder()
        rs.forEach { r ->
            val m = r.metrics
            val parts = ArrayList<String>()
            parts += String.format("%.0f km", r.lengthKm)
            if (m != null) parts += String.format("€%.2f", m.totalCostEur)
            if (r.cameraCount > 0) parts += tn(r.cameraCount, "{0} radar", "{0} radars")
            m?.missingVignettes?.takeIf { it.isNotEmpty() }?.let { v -> parts += t("needs vignette {0}", v.joinToString { it.country }) }
            list.addItem(Row.Builder().setTitle(Geo.formatDuration(r.timeS)).addText(parts.joinToString(" · ")).build())
        }
        list.setSelectedIndex(selected)
        list.setOnSelectedListener { i ->
            selected = i
            surface.controller?.setRoutes(rs, i)
            invalidate()
        }
        return b.setItemList(list.build())
            .setNavigateAction(Action.Builder().setTitle(t("Start")).setOnClickListener { start() }.build())
            .build()
    }
}

/** Quick report of a mobile radar / police / hazard at the current position. */
class CarReportScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        listOf(ReportType.MOBILE_RADAR, ReportType.POLICE, ReportType.HAZARD, ReportType.ACCIDENT).forEach { type ->
            list.addItem(
                Row.Builder().setTitle(type.label).setImage(icon(if (type == ReportType.POLICE) R.drawable.ic_car_group else R.drawable.ic_car_report))
                    .setOnClickListener {
                        val f = Core.location.fix.value
                        if (f == null) {
                            CarToast.makeText(carContext, t("No GPS position yet"), CarToast.LENGTH_SHORT).show()
                        } else {
                            Core.reports.report(type, f.pos, if (f.hasBearing) f.bearing else null)
                            CarToast.makeText(carContext, t("{0} reported — thanks!", type.label), CarToast.LENGTH_SHORT).show()
                        }
                        finish()
                    }.build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(t("Report"))
.setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** Convoy on the car screen: "drive with me" for everyone, members with live speed; tap = challenge. */
class CarConvoyScreen(carContext: CarContext, private val surface: CarMapSurface) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            Core.convoy.state.collect {
                invalidate()
                delay(2000)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val convoy = Core.convoy.state.value
        val navigating = Core.navigator.state.value != null
        val me = Core.location.fix.value?.pos
        val list = ItemList.Builder()
        if (navigating) {
            list.addItem(
                Row.Builder().setTitle(t("Drive with me")).addText(t("Send your destination to the whole convoy"))
                    .setImage(icon(R.drawable.ic_car_group))
                    .setOnClickListener {
                        com.drivemap.app.ui.Ui.inviteDriveWithMe()
                        CarToast.makeText(carContext, t("Sent to your convoy"), CarToast.LENGTH_SHORT).show()
                        screenManager.pop()
                    }.build()
            )
        }
        convoy?.members?.filter { !it.isMe }?.forEach { m ->
            val parts = mutableListOf("${Math.round(m.speedKmh)} km/h")
            me?.let { parts += Geo.formatDistance(Geo.dist(it, m.pos)) }
            if (m.stale) parts += t("offline")
            list.addItem(
                Row.Builder().setTitle(m.name)
                    .addText(parts.joinToString(" · ") + if (navigating) " — " + t("tap to challenge") else "")
                    .setOnClickListener {
                        if (navigating) {
                            com.drivemap.app.ui.Ui.inviteDriveWithMe(m.id, m.name)
                            CarToast.makeText(carContext, t("Challenge sent to {0}", m.name), CarToast.LENGTH_SHORT).show()
                            screenManager.pop()
                        } else {
                            screenManager.push(CarRoutePreviewScreen(carContext, surface, Place(m.name, m.pos)))
                        }
                    }.build()
            )
        }
        if (convoy == null || (convoy.members.none { !it.isMe } && !navigating)) {
            list.setNoItemsMessage(t("Nobody else in your convoy yet"))
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(t("Convoy")).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** An incoming challenge / "drive with me" on the car screen: accept = navigation starts. */
class CarInviteScreen(carContext: CarContext, private val inv: com.drivemap.app.route.CruiseInvite) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val text = when {
            inv.isJoin && inv.toId != null -> t("{0} challenges you to drive together to {1}", inv.fromName, inv.destName ?: "")
            inv.isJoin -> t("{0} wants you to drive with them to {1}", inv.fromName, inv.destName ?: "")
            else -> t("{0} challenges you to a {1}-minute drive", inv.fromName, inv.minutes)
        }
        return MessageTemplate.Builder(text)
            .setHeader(
                Header.Builder().setTitle(if (inv.isJoin && inv.toId == null) t("Drive with me") else t("Challenge"))
                    .setStartHeaderAction(Action.BACK).build()
            )
            .setIcon(icon(R.drawable.ic_car_group))
            .addAction(
                Action.Builder().setTitle(t("Accept")).setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener {
                        com.drivemap.app.ui.Ui.acceptCruise(inv)
                        screenManager.pop()
                    }.build()
            )
            .addAction(
                Action.Builder().setTitle(t("Decline")).setOnClickListener {
                    com.drivemap.app.ui.Ui.declineCruise(inv)
                    screenManager.pop()
                }.build()
            )
            .build()
    }
}
