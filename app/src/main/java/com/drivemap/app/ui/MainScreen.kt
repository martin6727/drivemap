package com.drivemap.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.RowScope
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drivemap.app.i18n.t
import com.drivemap.app.core.Core
import com.drivemap.app.data.Countries
import com.drivemap.app.data.MapTheme
import com.drivemap.app.fines.FineEstimator
import com.drivemap.app.geo.Geo
import com.drivemap.app.route.Place
import com.drivemap.app.radar.ReportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView

@Composable
fun MainScreen() {
    val settings by Core.settings.state.collectAsState()
    val fix by Core.location.fix.collectAsState()
    var night by remember { mutableStateOf(isNightNow(settings.mapTheme)) }

    // automatic day/night
    LaunchedEffect(settings.mapTheme) {
        while (true) {
            night = isNightNow(settings.mapTheme)
            delay(60_000)
        }
    }

    DriveMapTheme(darkMode = night && !(settings.glass && settings.lightGlass), glass = settings.glass, lightGlass = settings.lightGlass, clearGlass = settings.clearGlass) {
        val screen by Ui.screen.collectAsState()
        val nav by Core.navigator.state.collectAsState()
        val dirs by Ui.directions.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        var controller by remember { mutableStateOf<MapController?>(null) }
        var bearing by remember { mutableDoubleStateOf(0.0) }

        LaunchedEffect(Unit) {
            Core.messages.collect { m ->
                val r = snackbar.showSnackbar(m.text, m.actionLabel, withDismissAction = m.actionLabel == null)
                if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
            }
        }

        // Back closes the top-most panel first (like Google Maps); on the bare map it exits.
        val selectedPlace by Ui.selected.collectAsState()
        val categoryState by Ui.category.collectAsState()
        val memberSel by Ui.selectedMember.collectAsState()
        androidx.activity.compose.BackHandler(
            enabled = screen == Screen.MAP && nav == null && (dirs != null || selectedPlace != null || categoryState != null || memberSel != null),
        ) {
            when {
                memberSel != null -> Ui.selectedMember.value = null
                dirs != null -> Ui.closeDirections()
                selectedPlace != null -> Ui.selected.value = null
                categoryState != null -> Ui.clearCategory()
            }
        }

        Box(Modifier.fillMaxSize()) {
            MapHost(night) { c ->
                controller = c
                c.onCameraMoved = { b, _ -> bearing = b }
            }
            controller?.let { MapBindings(it, night) }

            when {
                nav != null -> NavigationOverlay(nav!!, controller)
                dirs != null -> DirectionsOverlay(dirs!!)
                else -> BrowseOverlay(controller, bearing)
            }
            TripSummaryOverlay()
            CruiseSetupDialog()
            CarPickerDialog()
            UpdateDialog()
            AutoSetupDialog()

            Sheets()

            when (screen) {
                Screen.SEARCH -> SearchScreen()
                Screen.SETTINGS -> SettingsScreen()
                Screen.VIGNETTES -> VignettesScreen()
                Screen.CONVOY -> ConvoyScreen()
                Screen.MAP -> {}
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))
        }
        if (fix == null && !Core.location.hasPermission.collectAsState().value) {
            // nothing: MainActivity asks for permission on start
        }
    }
}

fun isNightNow(theme: MapTheme): Boolean {
    val f = Core.location.fix.value
    return when (theme) {
        MapTheme.DAY -> false
        MapTheme.NIGHT -> true
        MapTheme.AUTO -> if (f != null) Sun.isNight(f.pos.lat, f.pos.lon) else {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); h < 6 || h >= 19
        }
    }
}

/** Hosts the MapLibre MapView inside Compose and forwards lifecycle events. */
@Composable
fun MapHost(night: Boolean, onReady: (MapController) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var controller by remember { mutableStateOf<MapController?>(null) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            val c = MapController(context, mapView, map, scope)
            controller = c
            val f = Core.location.fix.value
            if (f != null) c.moveTo(f.pos, 15.0, animate = false) else c.moveTo(com.drivemap.app.geo.LatLon(48.7, 19.5), 6.5, animate = false)
            c.loadStyle(night) { onReady(c) }
        }
    }
    LaunchedEffect(night) {
        val c = controller ?: return@LaunchedEffect
        if (c.night != night) c.loadStyle(night)
    }
}

/** Feeds app state into the map controller. */
@Composable
fun MapBindings(c: MapController, night: Boolean) {
    // sideways while navigating: the left column covers the map, so centre the car in the rest
    val landscape = isLandscape()
    val navigating = Core.navigator.state.collectAsState().value != null
    val panelPx = with(androidx.compose.ui.platform.LocalDensity.current) { (LANDSCAPE_PANEL + 10.dp).roundToPx() }
    val landscapeNow = androidx.compose.runtime.rememberUpdatedState(landscape)
    // re-frame the last "show these points" view when the phone is turned
    val lastFit = remember { mutableStateOf<CameraCommand.Fit?>(null) }
    LaunchedEffect(landscape) {
        delay(350)
        lastFit.value?.let { f -> if (!Ui.following.value) Ui.camera.tryEmit(f) }
    }
    LaunchedEffect(c, landscape, navigating) {
        c.insets = if (landscape && navigating) intArrayOf(panelPx, 0, 0, 0) else intArrayOf(0, 0, 0, 0)
    }
    LaunchedEffect(c) {
        c.onLongPress = { Ui.longPress(it) }
        c.onMyCarClick = { Ui.carPicker.value = true }
        c.onTiltChanged = { Ui.browseTilted.value = it }
        c.onCameraClick = { Ui.selectedCamera.value = it }
        Core.onNavigationAutoStopped = {
            Ui.challenge.value = false
            Ui.overview.value = false
            Ui.camera.tryEmit(CameraCommand.FollowUser)
        }
        c.onMapClick = {
            Ui.selectedCamera.value = null
            if (Core.navigator.state.value == null && Ui.directions.value == null) {
                if (Ui.selected.value != null) Ui.selected.value = null
                Ui.selectedMember.value = null
            }
        }
        c.onPlaceClick = { p ->
            when {
                Core.navigator.state.value == null -> Ui.selectPlace(p)
                // quick-add results while navigating: the card offers "Add stop"
                Ui.category.value != null -> Ui.pressedPoint.value = p
            }
        }
        c.onRouteClick = { Ui.selectRoute(it) }
        c.onMemberClick = { Ui.selectedMember.value = it }
        c.onStopClick = { id -> Ui.selectedStop.value = id }
        c.onReportClick = { id -> Core.reports.reports.value.firstOrNull { it.id == id }?.let { r ->
            Core.message(t("{0} · reported {1} min ago", r.type.label, r.ageMin) + if (r.by.isNotBlank()) " " + t("by {0}", r.by) else "")
        } }
        c.onTrackingDismissed = { Ui.following.value = false }
        c.onCameraIdle = {
            Ui.mapCenter = c.center()
            refreshCameras(c)
        }
        // shared with Android Auto: location, navigation, convoy, reports, cameras, route colour
        syncMapWithCore(c) { if (Ui.following.value) c.followNavigation() }
        launch {
            // first fix: centre on the user
            Core.location.fix.filterNotNull().first()
            if (Ui.following.value && Core.navigator.state.value == null && Ui.directions.value == null) c.followUser(15.5)
        }
        launch {
            Ui.camera.collect { cmd ->
                when (cmd) {
                    is CameraCommand.Fit -> {
                        Ui.following.value = false
                        lastFit.value = cmd
                        // sideways the panels sit on the left: frame the points in the map to their right
                        // (while navigating the map already keeps clear of the panel)
                        if (landscapeNow.value) c.fit(cmd.points, 60, 30, 40, leftDp = if (Core.navigator.active) 0 else LANDSCAPE_PANEL.value.toInt() + 50)
                        else c.fit(cmd.points, cmd.topDp, cmd.bottomDp)
                    }
                    is CameraCommand.Center -> { lastFit.value = null; Ui.following.value = false; c.moveTo(cmd.pos, cmd.zoom) }
                    CameraCommand.FollowUser -> { lastFit.value = null; Ui.following.value = true; c.followUser() }
                    CameraCommand.FollowNav -> { lastFit.value = null; Ui.following.value = true; c.followNavigation() }
                }
            }
        }
        launch {
            Ui.directions.collect { d ->
                if (Core.navigator.state.value == null) {
                    c.setRoutes(d?.routes ?: emptyList(), d?.selected ?: 0)
                    c.setWaypoints(d?.stops ?: emptyList())
                }
            }
        }
        launch {
            combine(Ui.selected, Ui.category, Ui.pressedPoint) { s, cat, pressed -> (s ?: pressed) to (cat?.second ?: emptyList()) }.collect { (s, list) -> c.setPins(s, list) }
        }
    }
}

// ------------------------------------------------------------------ browse mode

private data class Cat(val label: String, val term: String, val icon: ImageVector)

private val categories = listOf(
    Cat("Fuel", "fuel", Glyphs.Fuel),
    Cat("EV charging", "charging station", Glyphs.EvStation),
    Cat("Parking", "parking", Glyphs.Parking),
    Cat("Restaurants", "restaurant", Glyphs.Restaurant),
    Cat("Cafés", "cafe", Glyphs.Cafe),
    Cat("Hotels", "hotel", Glyphs.Hotel),
)

@Composable
fun BrowseOverlay(controller: MapController?, bearing: Double) {
    val landscape = isLandscape()
    val selected by Ui.selected.collectAsState()
    val category by Ui.category.collectAsState()
    val fix by Core.location.fix.collectAsState()
    val convoy by Core.convoy.state.collectAsState()
    val driveMode by Core.driveMode.collectAsState()
    val following by Ui.following.collectAsState()
    val member by Ui.selectedMember.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
            // search pill
            Surface(
                shape = RoundedCornerShape(28.dp), shadowElevation = glassShadow(6.dp), color = MaterialTheme.colorScheme.surface, border = panelBorder,
                modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 520.dp).fillMaxWidth().height(52.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { Ui.screen.value = Screen.SETTINGS }) { Icon(Icons.Default.Menu, t("Menu")) }
                    Text(
                        t("Search here"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp,
                        modifier = Modifier.weight(1f).clickable { Ui.openSearch(SearchTarget.PLACE) }.padding(vertical = 14.dp),
                    )
                    Box {
                        IconButton(onClick = { Ui.screen.value = Screen.CONVOY }) {
                            Icon(Glyphs.Group, t("Convoy"), tint = if (convoy != null) Palette.Blue else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (convoy != null) {
                            val n = convoy!!.members.count { !it.isMe && !it.stale }
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(16.dp).clip(CircleShape).background(Palette.Green),
                                contentAlignment = Alignment.Center,
                            ) { Text("$n", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            // category chips
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { cat ->
                    val active = category?.first == cat.label
                    Surface(
                        onClick = { if (active) Ui.clearCategory() else Ui.searchCategory(cat.label, cat.term) },
                        shape = RoundedCornerShape(18.dp), shadowElevation = glassShadow(3.dp),
                        color = if (active) Tints.blue else MaterialTheme.colorScheme.surface, border = panelBorder,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(cat.icon, null, Modifier.size(17.dp), tint = if (active) Palette.Blue else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(t(cat.label), fontSize = 14.sp, color = if (active) Palette.Blue else MaterialTheme.colorScheme.onSurface)
                            if (active) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Close, t("Clear"), Modifier.size(16.dp), tint = Palette.Blue)
                            }
                        }
                    }
                }
            }
            AlertStack(Modifier.padding(horizontal = 12.dp))
        }

        // right-side buttons (glide up above an open sheet)
        val buttonsLift by animateDpAsState(
            if (!landscape && (selected != null || category != null || member != null)) 300.dp else 24.dp, tween(300), label = "buttonsLift",
        )
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .padding(end = 14.dp, bottom = buttonsLift),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (Math.abs(bearing) > 1) {
                Surface(onClick = { controller?.resetNorth() }, shape = CircleShape, shadowElevation = glassShadow(4.dp), border = panelBorder, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Glyphs.Navigation, t("North"), tint = Palette.Red, modifier = Modifier.size(24.dp).rotate(-bearing.toFloat()))
                    }
                }
            }
            val tilted by Ui.browseTilted.collectAsState()
            RoundTextButton(if (tilted) "3D" else "2D", t("2D / 3D view"), onClick = { controller?.toggleBrowseTilt() })
            RoundIconButton(
                Glyphs.Car, t("Drive mode"), onClick = {
                    Core.setDriveMode(!driveMode)
                    Core.message(if (!driveMode) t("Drive mode on: radar alerts, fine meter and convoy keep running with the screen off") else t("Drive mode off"))
                },
                tint = if (driveMode) Color.White else MaterialTheme.colorScheme.onSurface,
                bg = if (driveMode) Palette.Blue else MaterialTheme.colorScheme.surface,
            )
            RoundIconButton(
                Glyphs.MyLocation, t("My location"),
                onClick = { if (fix != null) Ui.camera.tryEmit(CameraCommand.FollowUser) else Core.message(t("Waiting for GPS…")) },
                tint = if (following) Palette.Blue else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 52.dp,
            )
            Surface(
                onClick = {
                    val sel = Ui.selected.value
                    if (sel != null) Ui.directionsTo(sel) else Ui.openSearch(SearchTarget.DESTINATION)
                },
                shape = RoundedCornerShape(18.dp), color = Palette.Blue, shadowElevation = glassShadow(6.dp),
                modifier = Modifier.size(58.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Glyphs.Directions, t("Directions"), tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
        }

        // speed panel when driving
        val f = fix
        if (f != null && (f.speedKmh > 8 || driveMode) && selected == null && category == null) {
            Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 12.dp, bottom = 24.dp)) {
                LiveSpeedPanel()
            }
        }

        // bottom sheets: slide up / down instead of popping in
        val sheet: SheetKind? = member?.let { SheetKind.Member(it) }
            ?: selected?.let { SheetKind.PlaceInfo(it) }
            ?: category?.let { SheetKind.Category(it.first, it.second) }
        AnimatedContent(
            targetState = sheet,
            modifier = Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter),
            contentAlignment = Alignment.BottomCenter,
            // live updates of the same sheet (results arriving) don't replay the animation
            contentKey = { if (it is SheetKind.Category) "category:" + it.title else it },
            transitionSpec = {
                (slideInVertically(tween(300)) { it } + fadeIn(tween(200))) togetherWith
                    (slideOutVertically(tween(220)) { it } + fadeOut(tween(180))) using SizeTransform(clip = false)
            },
            label = "sheet",
        ) { s ->
            when (s) {
                is SheetKind.Member -> MemberSheet(s.id)
                is SheetKind.PlaceInfo -> PlaceSheet(s.place)
                is SheetKind.Category -> CategorySheet(s.title, s.list)
                null -> Spacer(Modifier.fillMaxWidth())
            }
        }
    }
}

private sealed interface SheetKind {
    data class Member(val id: String) : SheetKind
    data class PlaceInfo(val place: Place) : SheetKind
    data class Category(val title: String, val list: List<Place>) : SheetKind
}

/** Radar alert, average-speed zone, "still there?" and convoy destination prompts. */
@Composable
fun AlertStack(modifier: Modifier = Modifier) {
    val alert by Core.radar.alert.collectAsState()
    val section by Core.radar.section.collectAsState()
    val vote by Core.radar.vote.collectAsState()
    val dest by Core.pendingConvoyDestination.collectAsState()
    val cam by Ui.selectedCamera.collectAsState()
    // a tapped radar's details stay up for a few seconds
    LaunchedEffect(cam) { if (cam != null) { delay(8000); Ui.selectedCamera.value = null } }
    var lastCam by remember { mutableStateOf(cam) }
    if (cam != null) lastCam = cam
    val pressed by Ui.pressedPoint.collectAsState()
    var lastPressed by remember { mutableStateOf(pressed) }
    if (pressed != null) lastPressed = pressed
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val cruise by Core.pendingCruise.collectAsState()
        cruise?.let { CruiseInviteCard(it) }
        // while navigating a tapped convoy car shows a small card (the full sheet is for browsing)
        val memberId by Ui.selectedMember.collectAsState()
        if (Core.navigator.state.collectAsState().value != null) memberId?.let { NavMemberCard(it) }
        AnimatedVisibility(visible = pressed != null) { lastPressed?.let { PressedPointCard(it) } }
        AnimatedVisibility(visible = cam != null) { lastCam?.let { CameraInfoCard(it) } }
        section?.let { SectionCard(it) }
        alert?.let { if (section == null || it.kind != com.drivemap.app.radar.AlertKind.SECTION) RadarCard(it) }
        vote?.let { v ->
            Surface(shape = RoundedCornerShape(16.dp), shadowElevation = glassShadow(6.dp), border = panelBorder, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("{0} still there?", v.type.label), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Core.reports.vote(v.reportId, false); Core.radar.dismissVote() }) { Text(t("Gone")) }
                    Button(onClick = { Core.reports.vote(v.reportId, true); Core.radar.dismissVote() }) {
                        Icon(Icons.Default.ThumbUp, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(t("Yes"))
                    }
                }
            }
        }
        dest?.let { d ->
            Surface(shape = RoundedCornerShape(16.dp), shadowElevation = glassShadow(6.dp), border = panelBorder, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(t("Convoy destination"), color = Palette.Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(t("{0} wants everyone to go to {1}", d.addedByName, d.name), fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { Core.clearPendingDestination() }) { Text(t("Ignore")) }
                        Button(onClick = {
                            Core.clearPendingDestination()
                            val p = Place(d.name, d.pos)
                            if (Core.navigator.active) {
                                Ui.stopNavigation()
                            }
                            val stops = Core.convoy.state.value?.stops?.map { com.drivemap.app.route.Waypoint(it.name, it.pos, it.id) } ?: emptyList()
                            Ui.directionsTo(p, stops)
                        }) { Text(t("Navigate")) }
                    }
                }
            }
        }
    }
}

/** Trip stats after arriving: shows for 5 s (tap to close sooner), then the normal map. */
@Composable
fun TripSummaryOverlay() {
    val summary by Core.tripSummary.collectAsState()
    var last by remember { mutableStateOf(summary) }
    if (summary != null) last = summary
    AnimatedVisibility(
        visible = summary != null,
        enter = fadeIn(tween(250)) + scaleIn(tween(300), initialScale = 0.9f),
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.95f),
    ) {
        val s = last ?: return@AnimatedVisibility
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Core.dismissTripSummary() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = glassShadow(16.dp), border = panelBorder, modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(Palette.Green), contentAlignment = Alignment.Center) {
                            Icon(Glyphs.Flag, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(t("You've arrived"), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            if (s.destination.isNotBlank()) {
                                Text(s.destination, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row {
                        TripStat(Geo.formatDistance(s.distanceKm * 1000), t("distance"))
                        TripStat(Geo.formatDuration(s.durationS.toDouble()), t("time"))
                        TripStat("${s.avgKmh.roundToInt()} km/h", t("average"))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        TripStat("${s.maxKmh.roundToInt()} km/h", t("top speed"))
                        TripStat(String.format("%.1f %s", s.fuel, s.fuelUnit), t("fuel ≈ {0}", String.format("€%.2f", s.fuelCostEur)))
                        TripStat("${s.radars}", t("radar warnings"))
                    }
                    Spacer(Modifier.height(20.dp))
                    // 5-second countdown until the normal map comes back
                    val progress = remember { Animatable(1f) }
                    LaunchedEffect(s) { progress.snapTo(1f); progress.animateTo(0f, tween(5000, easing = LinearEasing)) }
                    LinearProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Palette.Green, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TripStat(value: String, label: String) {
    Column(Modifier.weight(1f)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Long-pressed point while navigating / planning: add it as a stop or drive there instead. */
@Composable
fun PressedPointCard(p: Place) {
    val navigating = Core.navigator.state.collectAsState().value != null
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = glassShadow(6.dp), border = panelBorder, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Flag, null, tint = Palette.Red, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.address.isNotBlank()) {
                        Text(p.address, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { Ui.pressedPoint.value = null }) { Icon(Icons.Default.Close, t("Close")) }
            }
            Row(Modifier.padding(end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { Ui.addPressedAsStop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Color.White),
                ) { Text(t("Add stop")) }
                OutlinedButton(onClick = { Ui.goToPressed() }) { Text(if (navigating) t("Go here instead") else t("Set as destination")) }
            }
            // the map says you can't drive here, but you can: routes may use it from now on
            TextButton(onClick = { Ui.markDrivable(p) }) { Text(t("I can drive here")) }
        }
    }
}

/** Details of a radar the user tapped on the map. */
@Composable
fun CameraInfoCard(cam: com.drivemap.app.radar.Camera) {
    val fix by Core.location.fix.collectAsState()
    val (title, color) = when (cam.type) {
        com.drivemap.app.radar.CamType.SPEED -> t("Speed camera") to Color(0xFF3C4043)
        com.drivemap.app.radar.CamType.RED_LIGHT -> t("Red-light camera") to Palette.Red
        com.drivemap.app.radar.CamType.SECTION_START -> t("Average speed check – start") to Palette.Purple
        com.drivemap.app.radar.CamType.SECTION_END -> t("Average speed check – end") to Palette.Purple
    }
    val details = buildList {
        fix?.let { add(t("{0} away", Geo.formatDistance(Geo.dist(it.pos, cam.pos)))) }
        (cam.travelDir ?: cam.facing)?.let { add(t("checks traffic going {0}", t(compassWord(it)))) }
        cam.section?.let { add(t("section {0}", Geo.formatDistance(it.straightM))) }
    }.joinToString(" · ")
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = glassShadow(6.dp), border = panelBorder, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(Glyphs.Camera, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (details.isNotBlank()) Text(details, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (cam.maxspeed ?: cam.section?.maxspeed)?.let { SpeedLimitSign(it, diameter = 40.dp) }
            IconButton(onClick = { Ui.selectedCamera.value = null }) { Icon(Icons.Default.Close, t("Close")) }
        }
    }
}

private fun compassWord(deg: Double): String {
    val names = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    return names[(((deg % 360 + 360) % 360) / 45 + 0.5).toInt() % 8]
}

@Composable
fun LiveSpeedPanel() {
    val fix by Core.location.fix.collectAsState()
    val road by Core.speedLimits.road.collectAsState()
    val fine by Core.fine.collectAsState()
    val settings by Core.settings.state.collectAsState()
    SpeedPanel(
        limit = road?.limit,
        implied = road?.implied == true,
        speedKmh = fix?.shownKmh ?: 0,
        fine = fine,
        showFine = settings.fineMeter,
        onFineClick = { Ui.showFineDetails.value = true },
    )
}

@Composable
fun SheetSurface(dragModifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = glassShadow(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = panelBorder,
        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
    ) {
        // grows / shrinks smoothly when its content changes (e.g. routes arriving)
        Column(Modifier.animateContentSize(tween(260)).navigationBarsPadding().padding(bottom = 10.dp).then(dragModifier)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                )
            }
            content()
        }
    }
}

@Composable
fun PlaceSheet(place: Place) {
    val context = LocalContext.current
    val fix by Core.location.fix.collectAsState()
    val convoy by Core.convoy.state.collectAsState()
    val favorites by Core.places.favorites.collectAsState()
    val fav = favorites.any { it.name == place.name && Geo.dist(it.pos, place.pos) < 15 }
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        // saving a long-pressed pin: give it your own name (e.g. a shop the map doesn't know)
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(t("Save this place")) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(t("Name")) }, placeholder = { Text(place.name) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = place.copy(name = name.trim().ifBlank { place.name }, category = "")
                    Core.places.toggleFavorite(saved)
                    Ui.selected.value = saved
                    naming = false
                    Core.message(t("Saved – find it by name in search"))
                }) { Text(t("Save")) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(t("Cancel")) } },
        )
    }
    SheetSurface {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(
                        place.category.takeIf { it != DROPPED }.orEmpty().substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { null },
                        fix?.let { Geo.formatDistance(Geo.dist(it.pos, place.pos)) },
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    if (place.address.isNotBlank()) Text(place.address, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 2)
                }
                IconButton(onClick = { Ui.selected.value = null }) { Icon(Icons.Default.Close, t("Close")) }
            }
            if (isFuelStation(place)) FuelSection(place)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Ui.directionsTo(place) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Color.White)) {
                    Icon(Glyphs.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Directions"))
                }
                OutlinedButton(onClick = { Ui.startTo(place) }) {
                    Icon(Glyphs.Navigation, null, Modifier.size(18.dp), tint = Palette.Blue); Spacer(Modifier.width(6.dp)); Text(t("Start"))
                }
                OutlinedButton(onClick = { if (place.category == DROPPED && !fav) naming = true else Core.places.toggleFavorite(place) }) {
                    Icon(if (fav) Icons.Filled.Star else Icons.Outlined.Star, null, Modifier.size(18.dp), tint = if (fav) Color(0xFFF9AB00) else Palette.Blue)
                    Spacer(Modifier.width(6.dp)); Text(if (fav) t("Saved") else t("Save"))
                }
                if (convoy != null) {
                    OutlinedButton(onClick = {
                        Core.convoy.addStop(place)
                        Core.message(t("Added ‘{0}’ as a convoy stop for everyone", place.name))
                        if (Core.navigator.active) Core.addStopToNavigation(com.drivemap.app.route.Waypoint(place.name, place.pos))
                    }) {
                        Icon(Glyphs.Flag, null, Modifier.size(18.dp), tint = Palette.Purple); Spacer(Modifier.width(6.dp)); Text(t("Convoy stop"))
                    }
                }
                OutlinedButton(onClick = {
                    val text = "${place.name}\nhttps://www.openstreetmap.org/?mlat=${place.pos.lat}&mlon=${place.pos.lon}#map=17/${place.pos.lat}/${place.pos.lon}"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), t("Share place")))
                }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp), tint = Palette.Blue); Spacer(Modifier.width(6.dp)); Text(t("Share"))
                }
                if (place.category == DROPPED) {
                    OutlinedButton(onClick = { Ui.markDrivable(place) }) {
                        Icon(Glyphs.Car, null, Modifier.size(18.dp), tint = Palette.Blue); Spacer(Modifier.width(6.dp)); Text(t("I can drive here"))
                    }
                }
                OutlinedButton(onClick = { Core.places.setHome(place); Core.message(t("Saved as Home")) }) {
                    Icon(Icons.Default.Home, null, Modifier.size(18.dp), tint = Palette.Blue); Spacer(Modifier.width(6.dp)); Text(t("Set home"))
                }
            }
        }
    }
}

@Composable
fun CategorySheet(title: String, list: List<Place>) {
    val loading by Ui.categoryLoading.collectAsState()
    val fix by Core.location.fix.collectAsState()
    SheetSurface {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t(title), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { Ui.clearCategory() }) { Icon(Icons.Default.Close, t("Close")) }
        }
        if (loading) CenteredText(t("Searching nearby…"), Modifier.padding(16.dp))
        else if (list.isEmpty()) CenteredText(t("Nothing found around the map centre"), Modifier.padding(16.dp))
        val fuelType = Core.settings.state.collectAsState().value.fuelType
        val cheapest = list.mapNotNull { it.fuel?.forType(fuelType) }.minOrNull()
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
            items(list) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { Ui.selectPlace(p) }.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        val sub = listOfNotNull(fix?.let { Geo.formatDistance(Geo.dist(it.pos, p.pos)) }, p.address.ifBlank { null }).joinToString(" · ")
                        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    p.fuel?.let { st -> FuelPriceTag(st, fuelType, cheapest != null && st.forType(fuelType) == cheapest) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
fun MemberSheet(id: String) {
    val convoy by Core.convoy.state.collectAsState()
    val fix by Core.location.fix.collectAsState()
    val m = convoy?.members?.firstOrNull { it.id == id }
    if (m == null) {
        LaunchedEffect(id) { Ui.selectedMember.value = null }
        return
    }
    SheetSurface {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(m.color)), contentAlignment = Alignment.Center) {
                    Text(m.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val parts = mutableListOf("${Math.round(m.speedKmh)} km/h")
                    fix?.let { parts += t("{0} from you", Geo.formatDistance(Geo.dist(it.pos, m.pos))) }
                    parts += if (m.stale) t("last seen {0} min ago", m.ageS / 60) else t("live")
                    Text(parts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    if (m.destName != null) {
                        val eta = m.etaMs?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) }
                        Text(t("Going to {0}", m.destName ?: "") + (eta?.let { ", " + t("arrives {0}", it) } ?: ""), fontSize = 14.sp)
                    }
                }
                IconButton(onClick = { Ui.selectedMember.value = null }) { Icon(Icons.Default.Close, t("Close")) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Ui.selectedMember.value = null; Ui.directionsTo(Place(m.name, m.pos)) }) {
                    Icon(Glyphs.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Drive to {0}", m.name))
                }
                OutlinedButton(onClick = { Ui.camera.tryEmit(CameraCommand.Center(m.pos, 15.0)) }) { Text(t("Show")) }
                OutlinedButton(onClick = {
                    // navigating: challenge them to your destination right away; else plan a loop
                    if (Core.navigator.state.value != null) Ui.inviteDriveWithMe(m.id, m.name)
                    else Ui.cruiseSetup.value = Ui.CruiseSetup(m.id, m.name)
                }) { Text(t("Challenge"), color = Palette.Purple) }
            }
        }
    }
}

// ------------------------------------------------------------------ modal sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheets() {
    val showFine by Ui.showFineDetails.collectAsState()
    val showReport by Ui.showReportSheet.collectAsState()
    val stopId by Ui.selectedStop.collectAsState()
    if (showFine) {
        ModalBottomSheet(onDismissRequest = { Ui.showFineDetails.value = false }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) { FineDetails() }
    }
    if (showReport) {
        ModalBottomSheet(onDismissRequest = { Ui.showReportSheet.value = false }) { ReportPicker() }
    }
    if (stopId != null) {
        ModalBottomSheet(onDismissRequest = { Ui.selectedStop.value = null }) { StopDetails(stopId!!) }
    }
}

@Composable
fun FineDetails() {
    val fine by Core.fine.collectAsState()
    val road by Core.speedLimits.road.collectAsState()
    val fix by Core.location.fix.collectAsState()
    val country = road?.country ?: Core.speedLimits.country.collectAsState().value
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
        Text(t("Speeding fine estimate"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "${Countries.flag(country)} ${Countries.name(country)} · " + when {
                road == null -> t("road unknown")
                road!!.motorway -> t("motorway")
                road!!.urban -> t("in town")
                else -> t("outside town")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val f = fine
        if (f == null) {
            Text(t("No speed limit known for this road yet, so no estimate."))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedLimitSign(f.limit, implied = road?.implied == true)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(t("Your speed {0} km/h", fix?.shownKmh ?: 0), fontWeight = FontWeight.Medium)
                    Text(
                        if (f.overAfterTolerance > 0) t("{0} km/h over after the {1} km/h radar tolerance", f.overAfterTolerance, f.toleranceKmh)
                        else t("Within the limit (incl. {0} km/h radar tolerance)", f.toleranceKmh),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                    )
                    if (road?.implied == true) Text(t("Limit inferred from road type (no sign data)"), color = Palette.Orange, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (f.amountEur > 0) String.format("≈ €%,.0f", f.amountEur) else "€0",
                fontSize = 34.sp, fontWeight = FontWeight.Bold,
                color = if (f.amountEur > 0) Palette.Red else Palette.Green,
            )
            if (f.maxEur > f.amountEur) Text(t("Range up to {0}", String.format("€%,.0f", f.maxEur)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.currency != "EUR" && f.amountLocal > 0) Text(String.format("%,.0f %s", f.amountLocal, f.currency), color = MaterialTheme.colorScheme.onSurfaceVariant)
            f.points?.let { Text(t("Penalty: {0}", it), color = Palette.Orange) }
            f.ban?.let { Text(it, color = Palette.Red, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.height(8.dp))
            Text(FineEstimator.basis(country) + if (f.roughEstimate) "" else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Text(t("Table for this road"), fontWeight = FontWeight.Bold)
            FineEstimator.table(country, f.limit, road?.urban == true, road?.motorway == true).forEach { (k, v) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(k, Modifier.width(110.dp), fontSize = 14.sp)
                    Text(v, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(t("Estimates only, researched Oct 2026 — not legal advice."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ReportPicker() {
    val fix by Core.location.fix.collectAsState()
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
        Text(t("Report"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(t("Shared with your convoy and other DriveMap drivers nearby"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        val items = listOf(
            Triple(ReportType.MOBILE_RADAR, Color(0xFFF57C00), Glyphs.Camera),
            Triple(ReportType.POLICE, Color(0xFF1565C0), Glyphs.Group),
        )
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            items.forEach { (type, color, icon) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        val f = fix
                        if (f == null) Core.message(t("No GPS position yet")) else {
                            Core.reports.report(type, f.pos, if (f.hasBearing) f.bearing else null)
                            Core.message(t("{0} reported — thanks!", type.label))
                        }
                        Ui.showReportSheet.value = false
                    }.padding(8.dp),
                ) {
                    Box(Modifier.size(62.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    // one line: long words ("Nebezpečenstvo") get a slightly smaller font instead of wrapping
                    Text(type.label, fontSize = if (type.label.length > 11) 11.5.sp else 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
fun StopDetails(id: String) {
    val convoy by Core.convoy.state.collectAsState()
    val nav by Core.navigator.state.collectAsState()
    val stop = convoy?.stops?.firstOrNull { it.id == id } ?: convoy?.destination?.takeIf { id == "dest" }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
        if (stop == null) {
            val wp = nav?.request?.stops?.getOrNull(id.removePrefix("wp").toIntOrNull() ?: -1)
                ?: Ui.directions.value?.stops?.getOrNull(id.removePrefix("wp").toIntOrNull() ?: -1)
            Text(wp?.name ?: t("Stop"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            return@Column
        }
        Text(stop.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(if (id == "dest") t("Convoy destination, set by {0}", stop.addedByName) else t("Convoy stop, added by {0}", stop.addedByName), color
 = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nav != null && id != "dest") {
                Button(onClick = {
                    Core.addStopToNavigation(com.drivemap.app.route.Waypoint(stop.name, stop.pos, stop.id))
                    Ui.selectedStop.value = null
                }) { Text(t("Add to my route")) }
            } else {
                Button(onClick = { Ui.selectedStop.value = null; Ui.directionsTo(Place(stop.name, stop.pos)) }) { Text(t("Directions")) }
            }
            if (id != "dest") {
                OutlinedButton(onClick = { Core.convoy.removeStop(stop.id); Ui.selectedStop.value = null }) { Text(t("Remove for everyone")) }
            }
        }
    }
}
