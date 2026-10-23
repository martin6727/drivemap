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
import com.drivemap.app.ui.Car3D
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
        // convoy: members with live speed, "drive with me" and challenges (sound is in the gear menu)
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_group)).setOnClickListener { screenManager.push(CarConvoyScreen(carContext, surface)) }.build())
        // the phone's other buttons (your car, settings…) live behind the gear
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_settings)).setOnClickListener { screenManager.push(CarMoreScreen(carContext, surface)) }.build())
        b.setActionStrip(strip.build())

        val mapStrip = ActionStrip.Builder().addAction(Action.PAN)
        if (surface.panned.value) {
            mapStrip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_recenter)).setOnClickListener { surface.recenter(); invalidate() }.build())
        } else {
            // 2D / 3D like the phone: the label shows the current view
            val is3d = if (nav != null) Core.settings.value.view3d else surface.tilted3d
            mapStrip.addAction(
                Action.Builder().setIcon(CarUtil.textIcon(carContext, if (is3d) "3D" else "2D")).setOnClickListener {
                    if (nav != null) Core.settings.update { it.copy(view3d = !it.view3d) } else surface.toggleTilt()
                    invalidate()
                }.build()
            )
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
            list.addItem(
                Row.Builder().setTitle(title).addText(placeRowText(fix?.pos ?: surface.controller?.center(), place)).setImage(icon(res))
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
                    .addText(placeRowText(fix?.pos ?: surface.controller?.center(), p))
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
                    .addText(placeRowText(fix?.pos, p))
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
        // Route choices as a place-style list (renders on every Android Auto version); tap = go
        val b = PlaceListNavigationTemplate.Builder()
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
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        rs.forEachIndexed { i, r ->
            val m = r.metrics
            // like the phone's route card: green time · arrival, then km · cost · fuel · radars, then labels
            val dur = Geo.formatDuration(r.timeS)
            val eta = fmt.format(java.util.Date(System.currentTimeMillis() + (r.timeS * 1000).toLong()))
            val title = android.text.SpannableString("${'A' + i} · $dur · " + t("arrive {0}", eta))
            val parts = ArrayList<String>()
            if (m != null) parts += String.format("€%.2f", m.totalCostEur)
            if (m != null) parts += String.format("%.1f %s", m.fuelLiters, Core.settings.value.fuelType.unit)
            parts += tn(r.cameraCount, "{0} radar", "{0} radars")
            // the distance as a DistanceSpan (required on these rows)
            val km = androidx.car.app.model.Distance.create(r.lengthKm, androidx.car.app.model.Distance.UNIT_KILOMETERS)
            val text = android.text.SpannableString(" · " + parts.joinToString(" · "))
            text.setSpan(androidx.car.app.model.DistanceSpan.create(km), 0, 1, 0)
            val via = r.viaText()
            val tags = listOfNotNull(via.ifBlank { null }) + r.tags.map { tag -> if (tag.startsWith("Needs vignette: ")) t("Needs vignette:") + " " + tag.removePrefix("Needs vignette: ") else t(tag) }
            val row = Row.Builder().setTitle(title).addText(text)
            if (tags.isNotEmpty()) {
                val tagText = android.text.SpannableString(tags.take(3).joinToString(" · "))
                row.addText(tagText)
            }
            list.addItem(
                row
                    .setImage(icon(R.drawable.ic_car_flag))
                    .setOnClickListener {
                        selected = i
                        start()
                    }.build()
            )
        }
        return b.setItemList(list.build()).build()
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
        if (navigating && convoy != null) {
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
                Row.Builder().setTitle(m.name).addText(parts.joinToString(" · ")).setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarMemberScreen(carContext, surface, m.id)) }.build()
            )
        }
        if (convoy == null) list.setNoItemsMessage(t("Join or create a convoy on your phone (menu → Convoy)"))
        else if (convoy.members.none { !it.isMe } && !navigating) list.setNoItemsMessage(t("Nobody else in your convoy yet"))
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

/** "More" on the car screen: the phone's extra buttons — convoy, 2D/3D, your car and settings. */
class CarMoreScreen(carContext: CarContext, private val surface: CarMapSurface) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s = Core.settings.value
        val convoy = Core.convoy.state.value
        val navigating = Core.navigator.state.value != null
        val list = ItemList.Builder()
        if (convoy != null) {
            list.addItem(
                Row.Builder().setTitle(t("Convoy")).addText(tn(convoy.members.count { !it.isMe }, "{0} other driver", "{0} other drivers"))
                    .setImage(icon(R.drawable.ic_car_group)).setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarConvoyScreen(carContext, surface)) }.build()
            )
            if (navigating) {
                list.addItem(
                    Row.Builder().setTitle(t("Drive with me")).addText(t("Send your destination to the whole convoy"))
                        .setImage(icon(R.drawable.ic_car_flag))
                        .setOnClickListener {
                            com.drivemap.app.ui.Ui.inviteDriveWithMe()
                            CarToast.makeText(carContext, t("Sent to your convoy"), CarToast.LENGTH_SHORT).show()
                            screenManager.pop()
                        }.build()
                )
            }
        }
        list.addItem(
            Row.Builder().setTitle(t("Sound"))
                .setToggle(androidx.car.app.model.Toggle.Builder { on -> Core.voice.muted = !on }.setChecked(!Core.voice.muted).build())
                .build()
        )
        list.addItem(
            Row.Builder().setTitle(t("3D view"))
                .setToggle(androidx.car.app.model.Toggle.Builder { on -> Core.settings.update { it.copy(view3d = on) } }.setChecked(s.view3d).build())
                .build()
        )
        list.addItem(
            Row.Builder().setTitle(t("Your car")).addText(Car3D.skin(s.carSkin).label).setBrowsable(true)
                .setOnClickListener { screenManager.push(CarSkinScreen(carContext)) }.build()
        )
        list.addItem(
            Row.Builder().setTitle(t("Settings")).setImage(icon(R.drawable.ic_car_settings)).setBrowsable(true)
                .setOnClickListener { screenManager.push(CarSettingsScreen(carContext)) }.build()
        )
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(t("More")).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** Pick your car on the car screen (same 3D looks as the phone). */
class CarSkinScreen(carContext: CarContext) : Screen(carContext) {
    private val previews = Car3D.skins.associate { sk ->
        sk.id to androidx.car.app.model.CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(com.drivemap.app.ui.cropTransparent(Car3D.render(carContext, sk.body, sk.color, 35.0, Car3D.TILT, 90f))),
        ).build()
    }

    override fun onGetTemplate(): Template {
        val current = Core.settings.value.carSkin
        val list = ItemList.Builder()
        Car3D.skins.forEach { sk ->
            list.addItem(
                Row.Builder().setTitle(sk.label + if (sk.id == current) "  ✓" else "")
                    .setImage(previews.getValue(sk.id), Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener {
                        Core.settings.update { it.copy(carSkin = sk.id) }
                        screenManager.pop()
                    }.build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(t("Your car")).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** The settings that matter in the car. Everything else is on the phone. */
class CarSettingsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s = Core.settings.value
        fun toggle(title: String, on: Boolean, set: (com.drivemap.app.data.AppSettings, Boolean) -> com.drivemap.app.data.AppSettings) =
            Row.Builder().setTitle(title)
                .setToggle(androidx.car.app.model.Toggle.Builder { v -> Core.settings.update { set(it, v) }; invalidate() }.setChecked(on).build())
                .build()
        val list = ItemList.Builder()
            .addItem(toggle(t("Voice guidance"), s.voice) { a, v -> a.copy(voice = v) })
            .addItem(
                Row.Builder().setTitle(t("Voice level")).addText(s.voiceLevel.label)
                    .setOnClickListener {
                        val all = com.drivemap.app.data.VoiceLevel.entries
                        Core.settings.update { it.copy(voiceLevel = all[(it.voiceLevel.ordinal + 1) % all.size]) }
                        invalidate()
                    }.build()
            )
            .addItem(toggle(t("Speed camera alerts"), s.radarAlerts) { a, v -> a.copy(radarAlerts = v) })
            .addItem(toggle(t("Alert sounds"), s.radarSound) { a, v -> a.copy(radarSound = v) })
            .addItem(
                Row.Builder().setTitle(t("Map")).addText(s.mapTheme.label)
                    .setOnClickListener {
                        val all = com.drivemap.app.data.MapTheme.entries
                        Core.settings.update { it.copy(mapTheme = all[(it.mapTheme.ordinal + 1) % all.size]) }
                        invalidate()
                    }.build()
            )
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(t("Settings")).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}


/** One convoy member on the car screen: Challenge (your route, while navigating) or drive to them. */
class CarMemberScreen(carContext: CarContext, private val surface: CarMapSurface, private val id: String) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val m = Core.convoy.state.value?.members?.firstOrNull { it.id == id }
            ?: return MessageTemplate.Builder(t("Nobody else in your convoy yet"))
                .setHeader(Header.Builder().setTitle(t("Convoy")).setStartHeaderAction(Action.BACK).build()).build()
        val navigating = Core.navigator.state.value != null
        val me = Core.location.fix.value?.pos
        val info = listOfNotNull(
            "${Math.round(m.speedKmh)} km/h",
            me?.let { Geo.formatDistance(Geo.dist(it, m.pos)) },
            if (m.stale) t("offline") else null,
        ).joinToString(" · ")
        val text = if (navigating) info + "\n" + t("Challenge sends your route — accepting starts their navigation.")
        else info + "\n" + t("Start navigating, then challenge to share your route.")
        val b = MessageTemplate.Builder(text)
            .setHeader(Header.Builder().setTitle(m.name).setStartHeaderAction(Action.BACK).build())
            .setIcon(icon(R.drawable.ic_car_group))
        if (navigating) {
            b.addAction(
                Action.Builder().setTitle(t("Challenge")).setFlags(Action.FLAG_PRIMARY).setOnClickListener {
                    com.drivemap.app.ui.Ui.inviteDriveWithMe(m.id, m.name)
                    CarToast.makeText(carContext, t("Challenge sent to {0}", m.name), CarToast.LENGTH_SHORT).show()
                    screenManager.popToRoot()
                }.build()
            )
        }
        b.addAction(
            Action.Builder().setTitle(t("Drive to {0}", m.name)).setOnClickListener {
                screenManager.push(CarRoutePreviewScreen(carContext, surface, Place(m.name, m.pos)))
            }.build()
        )
        return b.build()
    }
}
