package com.drivemap.app.ui

import com.drivemap.app.i18n.t
import com.drivemap.app.core.Core
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.HttpException
import com.drivemap.app.net.Search
import com.drivemap.app.net.SearchRank
import com.drivemap.app.route.AvoidOptions
import com.drivemap.app.route.Place
import com.drivemap.app.route.Route
import com.drivemap.app.route.RouteMode
import com.drivemap.app.route.RoutePlanner
import com.drivemap.app.route.Waypoint
import com.drivemap.app.fuel.FuelPrices
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { MAP, SEARCH, SETTINGS, VIGNETTES, CONVOY }

/** What a search result is used for. */
enum class SearchTarget { PLACE, DESTINATION, ADD_STOP, ORIGIN, HOME, WORK, CONVOY_STOP, CONVOY_DEST }

data class DirectionsState(
    /** null = my location */
    val origin: Place?,
    /** Intermediate stops + destination (last). */
    val stops: List<Waypoint>,
    val mode: RouteMode,
    val avoid: AvoidOptions,
    val loading: Boolean = false,
    val progress: String = "",
    val routes: List<Route> = emptyList(),
    val selected: Int = 0,
    val notes: List<String> = emptyList(),
    val error: String? = null,
    val request: RoutePlanner.Request? = null,
) {
    val destination get() = stops.lastOrNull()
}

sealed class CameraCommand {
    data class Fit(val points: List<LatLon>, val topDp: Int, val bottomDp: Int) : CameraCommand()
    data class Center(val pos: LatLon, val zoom: Double?) : CameraCommand()
    object FollowUser : CameraCommand()
    object FollowNav : CameraCommand()
}

/** UI state that outlives recompositions and activity restarts. */
object Ui {
    val screen = MutableStateFlow(Screen.MAP)
    val searchTarget = MutableStateFlow(SearchTarget.PLACE)
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<Place>>(emptyList())
    val searching = MutableStateFlow(false)
    val selected = MutableStateFlow<Place?>(null)
    val category = MutableStateFlow<Pair<String, List<Place>>?>(null)
    val categoryLoading = MutableStateFlow(false)
    val directions = MutableStateFlow<DirectionsState?>(null)
    val camera = MutableSharedFlow<CameraCommand>(extraBufferCapacity = 8)
    /** Map follows the user (browse) / navigation camera active. */
    val following = MutableStateFlow(true)
    val overview = MutableStateFlow(false)
    val selectedMember = MutableStateFlow<String?>(null)
    /** Radar the user tapped on the map: its details show in a card at the top. */
    val selectedCamera = MutableStateFlow<com.drivemap.app.radar.Camera?>(null)
    /** Car look picker (opened by tapping your car). */
    val carPicker = MutableStateFlow(false)
    /** A challenge is on (sent or accepted): its route is coloured by bends. */
    val challenge = MutableStateFlow(false)
    /** Browse map currently tilted (3D). */
    val browseTilted = MutableStateFlow(false)
    val selectedStop = MutableStateFlow<String?>(null)
    /** Convoy code from an invite link, waiting for the user to enter a name. */
    val pendingJoinCode = MutableStateFlow<String?>(null)
    val showFineDetails = MutableStateFlow(false)
    val showReportSheet = MutableStateFlow(false)

    /** Map centre, kept up to date by the map screen (used for search bias). */
    @Volatile var mapCenter: LatLon? = null

    private var searchJob: Job? = null
    private var planJob: Job? = null
    private var categoryJob: Job? = null

    private fun scope() = Core.scope

    fun myPos(): LatLon? = Core.location.fix.value?.pos

    // ---------------------------------------------------------------- search

    fun openSearch(target: SearchTarget) {
        searchTarget.value = target
        query.value = ""
        results.value = emptyList()
        screen.value = Screen.SEARCH
    }

    /**
     * Search-as-you-type: your saved places at once, then Photon once typing pauses for 300 ms.
     * Every keystroke cancels the previous request (its network call is dropped too). Nothing
     * else is asked while typing: Nominatim's policy forbids it, and Overpass is far too slow
     * (measured 60–110 s per name search, often "server at capacity").
     */
    fun onQuery(q: String) {
        query.value = q
        searchJob?.cancel()
        searching.value = false
        if (q.isBlank()) {
            results.value = emptyList()
            return
        }
        val saved = savedMatching(q)
        results.value = saved
        if (q.trim().length < 2) return
        searchJob = scope().launch {
            delay(300)
            searching.value = true
            val near = myPos() ?: mapCenter
            val quick = attempt { Search.autocomplete(q, near) }
            results.value = combine(q, near, saved, quick.getOrDefault(emptyList()))
            searching.value = false
        }
    }

    /**
     * Keyboard "Search": Photon and OpenStreetMap's live database (Nominatim, which also has places
     * added in the last days) at the same time; results appear as each one answers. A kind of
     * place ("lekáreň", "autoservis") lists all of those nearby instead.
     */
    fun submitQuery(q: String) {
        if (q.isBlank()) return
        searchJob?.cancel()
        searching.value = true
        searchJob = scope().launch {
            val near = myPos() ?: mapCenter
            val saved = savedMatching(q)
            val isKind = com.drivemap.app.net.Categories.match(q) != null
            var quick: List<Place> = emptyList()
            var live: List<Place> = emptyList()
            val failed = ArrayList<Throwable>()
            fun show() { results.value = combine(q, near, saved, live + quick) }
            coroutineScope {
                launch {
                    attempt { Search.autocomplete(q, near) }.onSuccess { quick = it; show() }.onFailure { failed += it }
                }
                launch {
                    val r = attempt { if (isKind && near != null) placesOfKind(q, near) else Search.fullSearch(q, near) }
                    r.onSuccess { live = it; show() }.onFailure { failed += it }
                }
            }
            show()
            searching.value = false
            if (results.value.isEmpty() && failed.size == 2) {
                Core.message(
                    if (failed.any { it is com.drivemap.app.net.SearchBusyException || (it as? HttpException)?.code == 429 }) t("Search is busy. Try again in a minute.")
                    else t("Search isn't reachable. Check your connection and try again."),
                )
            }
        }
    }

    /** Runs a search request; a cancelled search stays cancelled (no stale results afterwards). */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Saved places first, then the rest without repeats, best name matches and nearest first. */
    private fun combine(q: String, near: LatLon?, saved: List<Place>, found: List<Place>): List<Place> =
        merge(saved, SearchRank.rank(q, near, SearchRank.dedupe(found), byDistance = com.drivemap.app.net.Categories.match(q) != null))

    /** Search results: best name matches first, nearest first among equals, far away last. */
    fun rank(q: String, near: LatLon?, list: List<Place>): List<Place> = SearchRank.rank(q, near, list)

    /**
     * Extra results for the car's search: on an explicit search, a kind of place lists all of
     * those nearby; otherwise OpenStreetMap's live database (newest places).
     */
    suspend fun businesses(q: String, near: LatLon?, explicit: Boolean = false): List<Place> {
        if (near == null || !explicit) return emptyList()
        val kinds = placesOfKind(q, near)
        if (kinds.isNotEmpty()) return kinds
        return attempt { Search.fullSearch(q, near) }.getOrDefault(emptyList())
    }

    /** "lekáreň", "autoservis", "benzínka"…: all places of that kind around [near]; empty for other queries. */
    suspend fun placesOfKind(q: String, near: LatLon): List<Place> {
        val kinds = com.drivemap.app.net.Categories.match(q) ?: return emptyList()
        // Search spaces these requests a second apart (the map search's limit); nearby first,
        // a wider area when the town has few of them
        val close = kinds.flatMap { k -> attempt { Search.category(k, near, 0.08) }.getOrDefault(emptyList()) }
        val all = if (close.size >= 5) close else close + kinds.flatMap { k -> attempt { Search.category(k, near, 0.3) }.getOrDefault(emptyList()) }
        return all.distinctBy { "%.4f,%.4f".format(it.pos.lat, it.pos.lon) }
    }

    /**
     * Your saved places (home, work, favourites and places you added yourself) matching the query,
     * ignoring case, accents and spaces ("espresso fix" finds "EspressoFix").
     */
    private fun savedMatching(q: String): List<Place> {
        val cq = SearchRank.compact(q)
        if (cq.length < 2) return emptyList()
        val nq = SearchRank.normalize(q)
        return (Core.places.favorites.value + listOfNotNull(Core.places.home.value, Core.places.work.value))
            .filter { SearchRank.compact(it.name).contains(cq) || SearchRank.normalize(it.address).contains(nq) }
            .sortedBy { SearchRank.tier(q, it.name) }
    }

    private fun merge(first: List<Place>, rest: List<Place>): List<Place> {
        val out = ArrayList(first)
        for (p in rest) {
            val cn = SearchRank.compact(p.name)
            if (out.none { Geo.dist(it.pos, p.pos) < 150 && SearchRank.compact(it.name) == cn }) out.add(p)
        }
        return out
    }

    /** "Can't find it?": the next long-press on the map saves a place with this name. */
    val addPlaceName = MutableStateFlow<String?>(null)
    private var addPlaceUntil = 0L

    fun startAddPlace(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        addPlaceName.value = n
        addPlaceUntil = System.currentTimeMillis() + 3 * 60_000
        screen.value = Screen.MAP
        Core.message(t("Long-press the exact spot of ‘{0}’ on the map to save it", n), t("Cancel")) { addPlaceName.value = null }
    }

    /**
     * Saves a place the map doesn't know, at the spot the user long-pressed (never a guessed
     * location). It's one of your saved places, so search finds it from now on.
     */
    private fun saveAddedPlace(name: String, pos: LatLon) {
        scope().launch {
            val addr = attempt { kotlinx.coroutines.withTimeout(5000) { Search.reverse(pos) } }.getOrNull()
                ?.let { r -> listOf(r.name, r.address).filter { it.isNotBlank() && it != "Dropped pin" }.joinToString(", ") }.orEmpty()
            val p = Place(name, pos, addr, "custom:place")
            if (!Core.places.isFavorite(p)) Core.places.toggleFavorite(p)
            selectPlace(p)
            Core.message(t("Saved ‘{0}’. Search finds it from now on.", name))
        }
    }

    /** A search result / saved place was picked. */
    fun pick(place: Place) {
        Core.places.addRecent(place)
        screen.value = Screen.MAP
        when (searchTarget.value) {
            SearchTarget.PLACE -> selectPlace(place)
            SearchTarget.DESTINATION -> directionsTo(place)
            SearchTarget.ADD_STOP -> if (Core.navigator.active) {
                Core.addStopToNavigation(Waypoint(place.name, place.pos))
                Core.message(t("Stop added: {0}", place.name))
            } else addStop(place)
            SearchTarget.ORIGIN -> {
                directions.update { it?.copy(origin = place) }
                plan()
            }
            SearchTarget.HOME -> { Core.places.setHome(place); screen.value = Screen.SETTINGS }
            SearchTarget.WORK -> { Core.places.setWork(place); screen.value = Screen.SETTINGS }
            SearchTarget.CONVOY_STOP -> { Core.convoy.addStop(place); screen.value = Screen.CONVOY }
            SearchTarget.CONVOY_DEST -> { Core.convoy.setDestination(place); screen.value = Screen.CONVOY }
        }
        searchTarget.value = SearchTarget.PLACE
    }

    fun selectPlace(place: Place?) {
        selected.value = place
        if (place != null) camera.tryEmit(CameraCommand.Center(place.pos, 15.5))
    }

    /** Point long-pressed while navigating or planning a route; a card offers "Add stop" / "Go here". */
    val pressedPoint = MutableStateFlow<Place?>(null)

    /** Long press on the map: a pin with its place card when browsing, otherwise the add-stop card. */
    fun longPress(pos: LatLon) {
        // "Can't find it? Add it": this long-press marks where the missing place is
        val adding = addPlaceName.value
        if (adding != null && Core.navigator.state.value == null) {
            addPlaceName.value = null
            if (System.currentTimeMillis() < addPlaceUntil) { saveAddedPlace(adding, pos); return }
        }
        if (Core.navigator.state.value == null && directions.value == null) {
            dropPin(pos)
            return
        }
        val tmp = Place(t("Dropped pin"), pos, String.format("%.5f, %.5f", pos.lat, pos.lon))
        pressedPoint.value = tmp
        scope().launch {
            try {
                val r = Search.reverse(pos)
                if (r != null && pressedPoint.value === tmp) pressedPoint.value = Place(r.name, pos, r.address.ifBlank { tmp.address }, r.category)
            } catch (_: Exception) {
            }
        }
    }

    /** The quick-add menu of the "+" button while navigating. */
    val showQuickAdd = MutableStateFlow(false)

    /**
     * Quick add while navigating (like Google Maps): places of a kind along the next 25 km of the
     * route, shown on the zoomed-out map and in a strip; tapping one offers "Add stop".
     */
    fun findAlongRoute(label: String, term: String) {
        val nav = Core.navigator.state.value ?: return
        showQuickAdd.value = false
        categoryJob?.cancel()
        selected.value = null
        pressedPoint.value = null
        val path = nav.route.path
        val from = nav.alongM
        val to = (from + 25_000.0).coerceAtMost(path.length)
        if (to - from < 50) return
        category.value = label to emptyList()
        categoryLoading.value = true
        overview.value = true
        val ahead = path.slice(from, to)
        camera.tryEmit(CameraCommand.Fit(ahead.filterIndexed { i, _ -> i % 4 == 0 } + ahead.last(), 230, 330))
        categoryJob = scope().launch {
            val found = LinkedHashMap<String, Pair<Place, Double>>()
            var a = from
            while (a < to) {
                // one box per 6 km stretch, so results hug the route
                val seg = path.slice(a, (a + 6000.0).coerceAtMost(to))
                val bb = Geo.bounds(seg)
                val list = try {
                    Search.categoryIn(term, bb[0] - 0.008, bb[1] - 0.012, bb[2] + 0.008, bb[3] + 0.012)
                } catch (_: Exception) { emptyList() }
                for (p in list) {
                    val hit = path.project(p.pos) ?: continue
                    if (hit.crossTrack > 900 || hit.along < from - 50) continue
                    found.putIfAbsent("%.4f,%.4f".format(p.pos.lat, p.pos.lon), p to hit.along)
                }
                category.value = label to found.values.sortedBy { it.second }.map { it.first }
                a += 6000.0
                if (a < to) delay(1100) // the map search allows about one request a second
            }
            categoryLoading.value = false
            val res = category.value?.second.orEmpty()
            if (res.isEmpty()) Core.message(t("No {0} along the next 25 km", t(label).lowercase()))
            else camera.tryEmit(CameraCommand.Fit(res.map { it.pos } + listOf(path.pointAt(from)), 230, 330))
        }
    }

    /** Close the quick-add results and follow the car again. */
    fun closeAlongRoute() {
        categoryJob?.cancel()
        categoryLoading.value = false
        category.value = null
        pressedPoint.value = null
        overview.value = false
        camera.tryEmit(CameraCommand.FollowNav)
    }

    /** "I can drive here": save the road at [p] to your roads and re-plan so the route can use it. */
    fun markDrivable(p: Place) {
        val name = if (p.category == DROPPED || p.name == t("Dropped pin")) p.address.ifBlank { p.name } else p.name
        Core.myRoads.add(name, p.pos)
        pressedPoint.value = null
        selected.value = null
        Core.message(t("Saved to your roads — routes will use it when it's on the way"))
        if (Core.navigator.active) Core.navigator.updateStops(Core.navigator.remainingStops())
        else if (directions.value != null) plan()
    }

    /** The long-pressed point becomes an extra stop: in driving order while navigating, before the destination when planning. */
    fun addPressedAsStop() {
        val p = pressedPoint.value ?: return
        pressedPoint.value = null
        if (Core.navigator.active) {
            Core.addStopToNavigation(Waypoint(p.name, p.pos))
            Core.message(t("Stop added: {0}", p.name))
            if (category.value != null) closeAlongRoute()
        } else {
            addStop(p)
        }
    }

    /** The long-pressed point becomes the destination (navigation restarts towards it). */
    fun goToPressed() {
        val p = pressedPoint.value ?: return
        pressedPoint.value = null
        val d = directions.value
        if (Core.navigator.active) {
            stopNavigation()
            startTo(p)
        } else if (d != null) {
            directions.value = d.copy(stops = d.stops.dropLast(1) + Waypoint(p.name, p.pos))
            plan()
        } else {
            directionsTo(p)
        }
    }

    fun dropPin(pos: LatLon) {
        val tmp = Place(t("Dropped pin"), pos, String.format("%.5f, %.5f", pos.lat, pos.lon), DROPPED)
        selected.value = tmp
        scope().launch {
            try {
                val r = Search.reverse(pos)
                if (r != null && selected.value === tmp) {
                    selected.value = Place(r.name, pos, r.address.ifBlank { tmp.address }, DROPPED)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun searchCategory(label: String, term: String) {
        categoryJob?.cancel()
        selected.value = null
        categoryLoading.value = true
        category.value = label to emptyList()
        categoryJob = scope().launch {
            try {
                val center = mapCenter ?: myPos() ?: LatLon(48.15, 17.11)
                val list = if (term == "fuel") fuelStations(center) else Search.category(term, center)
                val me = myPos()
                val sorted = if (me != null) list.sortedBy { Geo.dist(me, it.pos) } else list
                category.value = label to sorted
                if (sorted.isNotEmpty()) camera.tryEmit(CameraCommand.Fit(sorted.map { it.pos } + listOfNotNull(me), 170, 340))
            } catch (_: Exception) {
                category.value = label to emptyList()
                Core.message(t("Couldn't load {0} nearby", t(label)))
            }
            categoryLoading.value = false
        }
    }

    /**
     * Fuel stations around [center] with prices: live government prices where a country publishes
     * them (Spain, Austria, France), otherwise the map's stations plus prices reported by drivers.
     */
    private suspend fun fuelStations(center: LatLon): List<Place> = kotlinx.coroutines.coroutineScope {
        val type = Core.settings.value.fuelType
        val reports = async { try { FuelPrices.driverReportsNear(center) } catch (_: Exception) { emptyList() } }
        val country = try { Search.countryAt(center) } catch (_: Exception) { null } ?: Core.speedLimits.country.value
        FuelPrices.lastSearchCountry = country
        val official = if (country in FuelPrices.officialCountries) FuelPrices.officialNear(center, country, type) else emptyList()
        if (official.isNotEmpty()) {
            reports.cancel()
            official.take(40).map { Place(it.name, it.pos, it.address, "amenity:fuel", it) }
        } else {
            val mapStations = try { Search.category("fuel", center) } catch (_: Exception) { emptyList() }
            val drivers = reports.await()
            val withPrices = mapStations.map { p ->
                p.copy(category = "amenity:fuel", fuel = drivers.filter { Geo.dist(it.pos, p.pos) < 120 }.maxByOrNull { it.updatedMs ?: 0 })
            }
            // stations drivers priced that the map search didn't return
            withPrices + drivers.filter { d -> withPrices.none { Geo.dist(it.pos, d.pos) < 120 } }
                .map { Place(it.name, it.pos, it.address, "amenity:fuel", it) }
        }
    }

    fun clearCategory() {
        categoryJob?.cancel()
        category.value = null
        categoryLoading.value = false
    }

    // ---------------------------------------------------------------- directions

    fun directionsTo(place: Place, extraStops: List<Waypoint> = emptyList()) {
        val s = Core.settings.value
        selected.value = null
        clearCategory()
        directions.value = DirectionsState(
            origin = null,
            stops = extraStops + Waypoint(place.name, place.pos),
            mode = s.defaultMode,
            avoid = s.avoid,
        )
        plan()
    }

    // ---------------------------------------------------------------- cruise challenges

    /** Who the cruise setup dialog is for (member id + name; id null = whole convoy). */
    data class CruiseSetup(val toId: String?, val toName: String?)
    val cruiseSetup = MutableStateFlow<CruiseSetup?>(null)
    /** Planned cruise shown in directions, waiting for "Send challenge". */
    val cruiseDraft = MutableStateFlow<com.drivemap.app.route.CruiseInvite?>(null)
    val cruiseDraftFor = MutableStateFlow<String?>(null)
    val cruisePlanning = MutableStateFlow(false)

    fun planCruise(setup: CruiseSetup, minutes: Int, scenic: Boolean) {
        val me = myPos() ?: run { Core.message(t("Waiting for GPS…")); return }
        cruisePlanning.value = true
        scope().launch {
            try {
                val loop = com.drivemap.app.route.CruisePlanner.plan(Core.valhalla, me, minutes, scenic, Core.settings.value.voiceLang)
                val inv = com.drivemap.app.route.CruiseInvite(
                    id = java.util.UUID.randomUUID().toString().take(12), fromId = Core.convoy.myId,
                    fromName = Core.settings.value.userName.ifBlank { "DriveMap" }, toId = setup.toId,
                    minutes = minutes, scenic = scenic, start = me, loop = loop, ts = System.currentTimeMillis(),
                )
                cruiseSetup.value = null
                openCruise(inv, includeMeeting = false)
                challenge.value = true
                cruiseDraft.value = inv
                cruiseDraftFor.value = setup.toName
            } catch (_: Exception) {
                Core.message(t("Couldn't plan a challenge here"))
            }
            cruisePlanning.value = false
        }
    }

    /** Shows the cruise route in directions (from the meeting point when [includeMeeting]). */
    private fun openCruise(inv: com.drivemap.app.route.CruiseInvite, includeMeeting: Boolean, go: Boolean = false) {
        val s = Core.settings.value
        screen.value = Screen.MAP
        selected.value = null
        selectedMember.value = null
        clearCategory()
        directions.value = DirectionsState(
            origin = null,
            stops = inv.stops(t("Meeting point"), if (inv.isJoin) inv.destName ?: t("Destination") else t("Finish"), includeMeeting),
            mode = if (inv.isJoin) s.defaultMode else if (inv.scenic) RouteMode.SPORT else RouteMode.ECONOMICAL,
            avoid = s.avoid,
        )
        // accepted: no extra screens, navigation starts as soon as the route is ready
        if (go) autoStart = true
        plan()
    }

    fun sendCruise() {
        var inv = cruiseDraft.value ?: return
        // send the exact route shown here, so both phones navigate the same roads
        directions.value?.let { d -> d.routes.getOrNull(d.selected) }?.let { inv = com.drivemap.app.route.pinToRoute(inv, it) }
        Core.convoy.inviteCruise(inv.copy(ts = System.currentTimeMillis()))
        cruiseDraft.value = null
        Core.message(t("Challenge sent"))
    }

    /**
     * One tap while navigating: invite the convoy (or one member, [toId]) to drive with you to your
     * destination, pinned to your exact route so both phones take the same roads.
     */
    fun inviteDriveWithMe(toId: String? = null, toName: String? = null) {
        val nav = Core.navigator.state.value ?: return
        val dest = nav.request.stops.lastOrNull() ?: return
        var inv = com.drivemap.app.route.CruiseInvite(
            id = java.util.UUID.randomUUID().toString().take(12), fromId = Core.convoy.myId,
            fromName = Core.settings.value.userName.ifBlank { "DriveMap" }, toId = toId,
            minutes = (nav.remainingS / 60).toInt().coerceAtLeast(1), scenic = false, start = myPos() ?: dest.pos, loop = emptyList(),
            ts = System.currentTimeMillis(), destName = dest.name, end = dest.pos,
        )
        inv = com.drivemap.app.route.pinToRoute(inv, nav.route, fromAlong = nav.alongM)
        Core.convoy.inviteCruise(inv)
        if (toId != null) challenge.value = true
        Core.message(if (toName != null) t("Challenge sent to {0}", toName) else t("Sent to your convoy"))
    }

    fun acceptCruise(inv: com.drivemap.app.route.CruiseInvite) {
        Core.convoy.answerCruise(inv, true)
        Core.clearPendingCruise()
        if (inv.isJoin) {
            // the host's route, straight into navigation: join it at the nearest of its points
            // (or its start, when that's closer), then follow it to the end like the host
            if (inv.loop.isEmpty()) { startTo(Place(inv.destName ?: t("Destination"), inv.end ?: inv.start)); return }
            val me = myPos()
            var from = 0
            if (me != null) {
                val k = inv.loop.indices.minBy { Geo.dist(me, inv.loop[it]) }
                if (Geo.dist(me, inv.loop[k]) < Geo.dist(me, inv.start)) from = k
            }
            openCruise(inv.copy(loop = inv.loop.drop(from), headings = inv.headings.drop(from)), includeMeeting = false, go = true)
            challenge.value = inv.toId != null
            return
        }
        val near = myPos()?.let { Geo.dist(it, inv.start) < 300 } ?: false
        openCruise(inv, includeMeeting = !near, go = true)
        challenge.value = true
    }

    fun declineCruise(inv: com.drivemap.app.route.CruiseInvite) {
        Core.convoy.answerCruise(inv, false)
        Core.clearPendingCruise()
    }

    fun closeDirections() {
        cruiseDraft.value = null
        if (Core.navigator.state.value == null) challenge.value = false
        planJob?.cancel()
        autoStart = false
        directions.value = null
    }

    /** Google-style "Start": plan and go straight into navigation with the recommended route. */
    @Volatile private var autoStart = false

    fun startTo(place: Place) {
        autoStart = true
        directionsTo(place)
    }

    fun setMode(m: RouteMode) {
        val d = directions.value ?: return
        if (d.mode == m && d.routes.isNotEmpty()) return
        directions.value = d.copy(mode = m)
        plan()
    }

    fun setAvoid(a: AvoidOptions) {
        directions.update { it?.copy(avoid = a) }
        plan()
    }

    fun addStop(place: Place) {
        val d = directions.value
        if (d == null) {
            directionsTo(place)
            return
        }
        val stops = d.stops.toMutableList()
        stops.add((stops.size - 1).coerceAtLeast(0), Waypoint(place.name, place.pos))
        directions.value = d.copy(stops = stops)
        plan()
    }

    fun removeStop(index: Int) {
        val d = directions.value ?: return
        if (d.stops.size <= 1) return
        directions.value = d.copy(stops = d.stops.filterIndexed { i, _ -> i != index })
        plan()
    }

    fun selectRoute(i: Int) {
        directions.update { it?.copy(selected = i) }
    }

    fun plan() {
        val d = directions.value ?: return
        planJob?.cancel()
        val fix = Core.location.fix.value
        val origin = d.origin?.pos ?: fix?.pos
        if (origin == null) {
            directions.value = d.copy(loading = false, error = t("Waiting for your location… (turn on GPS)"))
            return
        }
        val heading = if (d.origin == null && fix != null && fix.hasBearing && fix.speedMps > 3) fix.bearing else null
        val req = RoutePlanner.Request(origin, heading, d.stops, d.mode, d.avoid)
        directions.value = d.copy(loading = true, progress = t("Finding routes…"), error = null, request = req)
        planJob = scope().launch {
            try {
                val res = Core.planner.plan(req) { p -> directions.update { it?.copy(progress = p) } }
                directions.update { it?.copy(loading = false, routes = res.routes, selected = 0, notes = res.notes, error = null) }
                if (autoStart && res.routes.isNotEmpty()) {
                    autoStart = false
                    start(simulate = false)
                    return@launch
                }
                val pts = res.routes.flatMap { r -> r.geometry.shape.filterIndexed { i, _ -> i % 10 == 0 } } + origin
                camera.tryEmit(CameraCommand.Fit(pts, 190, 380))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                directions.update { it?.copy(loading = false, error = e.message ?: t("Routing failed"))
 }
            }
        }
    }

    fun start(simulate: Boolean) {
        val d = directions.value ?: return
        val r = d.routes.getOrNull(d.selected) ?: return
        val req = d.request ?: return
        Core.startNavigation(r, req, simulate)
        directions.value = null
        overview.value = false
        following.value = true
        camera.tryEmit(CameraCommand.FollowNav)
    }

    fun stopNavigation() {
        Core.stopNavigation()
        challenge.value = false
        pressedPoint.value = null
        overview.value = false
        camera.tryEmit(CameraCommand.FollowUser)
    }
}

/** Category of a long-pressed pin (saving it asks for a name). */
const val DROPPED = "dropped"
