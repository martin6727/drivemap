package com.drivemap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivemap.app.i18n.t
import com.drivemap.app.core.Core
import com.drivemap.app.geo.Geo
import com.drivemap.app.nav.NavState
import com.drivemap.app.route.LaneInfo
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NavigationOverlay(nav: NavState, controller: MapController?) {
    val following by Ui.following.collectAsState()
    val overview by Ui.overview.collectAsState()
    val convoy by Core.convoy.state.collectAsState()
    val simRunning by Core.simulator.running.collectAsState()
    var muted by remember { mutableStateOf(Core.voice.muted) }
    val landscape = isLandscape()

    val buttons: @Composable () -> Unit = {
        RoundIconButton(if (muted) Glyphs.VolumeOff else Glyphs.VolumeUp, t("Sound"), onClick = {
            muted = !muted
            Core.voice.muted = muted
        })
        RoundIconButton(Icons.Default.Warning, t("Report"), onClick = { Ui.showReportSheet.value = true })
        if (convoy != null) RoundIconButton(Glyphs.Group, t("Convoy"), onClick = { Ui.screen.value = Screen.CONVOY })
        if (convoy != null) RoundIconButton(Glyphs.Flag, t("Drive with me"), onClick = { Ui.inviteDriveWithMe() })
        val view3d = Core.settings.state.collectAsState().value.view3d
        RoundTextButton(if (view3d) "3D" else "2D", t("2D / 3D view"), onClick = { Core.settings.update { it.copy(view3d = !view3d) } })
        RoundIconButton(Glyphs.Route, t("Overview"), onClick = {
            if (!overview) {
                Ui.overview.value = true
                val pts = nav.route.path.slice(nav.alongM, nav.route.path.length)
                Ui.camera.tryEmit(CameraCommand.Fit(pts.filterIndexed { i, _ -> i % 5 == 0 } + pts.last(), 170, 220))
            } else {
                Ui.overview.value = false
                Ui.camera.tryEmit(CameraCommand.FollowNav)
            }
        }, tint = if (overview) Palette.Blue else MaterialTheme.colorScheme.onSurface)
    }
    val recentre: @Composable () -> Unit = {
        Button(
            onClick = { Ui.overview.value = false; Ui.turnPreviewReset.tryEmit(Unit); Ui.camera.tryEmit(CameraCommand.FollowNav) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = Palette.Blue),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
            border = panelBorder,
            modifier = Modifier.height(48.dp),
        ) {
            Icon(Glyphs.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Re-centre"), fontWeight = FontWeight.Medium)
        }
    }

    if (landscape) {
        // Phone lying sideways (car holder): instructions + trip bar in a left column, the map
        // (with the car centred in the free part) on the right.
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(LANDSCAPE_PANEL).fillMaxHeight().statusBarsPadding().navigationBarsPadding()
                    .padding(start = 10.dp, top = 6.dp),
            ) {
                if (nav.arrived) ArrivedCard(nav) else ManeuverBanner(nav)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { AlertStack() }
                if (simRunning) SimControls()
                QuickAddArea(nav)
                EtaBar(nav)
            }
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) { buttons() }
            Row(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!following || overview) recentre()
                LiveSpeedPanel()
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (nav.arrived) ArrivedCard(nav) else ManeuverBanner(nav)
            Spacer(Modifier.height(8.dp))
            AlertStack()
        }

        // right column
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { buttons() }

        // bottom
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                LiveSpeedPanel()
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = !following || overview,
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.85f),
                    exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
                ) { recentre() }
            }
            AnimatedVisibility(visible = simRunning, enter = fadeIn(), exit = fadeOut()) { SimControls() }
            QuickAddArea(nav)
            // the bar glides up when guidance starts
            val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
            AnimatedVisibility(visibleState = shown, enter = slideInVertically(tween(320)) { it } + fadeIn(tween(220))) {
                EtaBar(nav)
            }
        }
    }
}

/**
 * The green turn banner. Swipe it sideways to look at the turns coming up (like Google Maps): the
 * map shows each one, and after a few seconds it snaps back to the current turn.
 */
@Composable
private fun ManeuverBanner(nav: NavState) {
    val mans = nav.route.geometry.maneuvers
    val first = nav.maneuverIdx
    val count = if (nav.rerouting || nav.next == null) 1 else (mans.size - first).coerceIn(1, 60)
    val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { count })
    var previewing by remember { mutableStateOf(false) }
    // a new current turn: show it
    androidx.compose.runtime.LaunchedEffect(first) { if (pager.currentPage != 0) pager.animateScrollToPage(0) }
    // "Re-centre" while looking at coming turns: back to the current one
    androidx.compose.runtime.LaunchedEffect(Unit) {
        Ui.turnPreviewReset.collect {
            if (pager.currentPage != 0) pager.animateScrollToPage(0, animationSpec = androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    androidx.compose.runtime.LaunchedEffect(pager.settledPage) {
        val page = pager.settledPage
        if (page > 0) {
            val m = mans.getOrNull(first + page)
            if (m != null) {
                previewing = true
                Ui.overview.value = true
                // seen the way you'll drive into it: facing the road just before the turn
                val path = nav.route.path
                val facing = path.bearingAt((m.alongM - 30.0).coerceAtLeast(0.0))
                Ui.camera.tryEmit(CameraCommand.Look(path.pointAt(m.alongM), 17.0, facing))
            }
            kotlinx.coroutines.delay(7000)
            pager.animateScrollToPage(0, animationSpec = androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        } else if (previewing) {
            previewing = false
            Ui.overview.value = false
            Ui.camera.tryEmit(CameraCommand.FollowNav)
        }
    }
    Column {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager, pageSpacing = 8.dp, beyondViewportPageCount = 1,
            // soft, settled snap instead of the stiff default
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pager,
                snapAnimationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                ),
            ),
            verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth(),
        ) { page ->
            if (page == 0) CurrentTurn(nav) else mans.getOrNull(first + page)?.let { UpcomingTurn(nav, it, page, count - 1) }
        }
        if (pager.currentPage == 0) TurnExtras(nav)
    }
}

/** One of the next turns, while swiping through them. */
@Composable
private fun UpcomingTurn(nav: NavState, m: com.drivemap.app.route.Maneuver, n: Int, of: Int) {
    Surface(shape = RoundedCornerShape(16.dp), color = Palette.NavGreenDark, shadowElevation = glassShadow(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverIcon(m.type, m.roundaboutExit, Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(Geo.formatDistance((m.alongM - nav.alongM).coerceAtLeast(0.0)), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val street = m.streetNames.firstOrNull()
                Text(street ?: m.instruction, color = Color.White, fontSize = if (street != null) 18.sp else 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("$n/$of", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
    }
}

/** The current turn (first page of the banner). */
@Composable
private fun CurrentTurn(nav: NavState) {
    val next = nav.next
        Surface(shape = RoundedCornerShape(16.dp), color = Palette.NavGreen, shadowElevation = glassShadow(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ManeuverIcon(next?.type ?: 8, next?.roundaboutExit ?: 0, Modifier.size(54.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (nav.rerouting) {
                        Text(t("Rerouting…"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    } else {
                        // motorway signage: exit number + road shields (e.g. "Exit 50  R1  E 75")
                        val sign = next?.sign
                        if (sign != null && (sign.exitNumber != null || sign.branches.isNotEmpty())) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                sign.exitNumber?.let { ExitBadge(it) }
                                sign.branches.take(3).forEach { RoadShield(it) }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(Geo.formatDistance(nav.distToManeuverM), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        val street = next?.streetNames?.firstOrNull()
                        Text(
                            street ?: next?.instruction ?: "",
                            color = Color.White, fontSize = if (street != null) 20.sp else 16.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (sign != null && sign.toward.isNotEmpty()) {
                            Text(
                                t("toward {0}", sign.toward.take(3).joinToString(", ")),
                                color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (next != null && next.roundaboutExit > 0) {
                            Text(t("Exit {0}", next.roundaboutExit), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
}

/** Lanes and the "Then" hint under the current turn. */
@Composable
private fun TurnExtras(nav: NavState) {
    val next = nav.next
    Column {
        // Lane guidance: lanes you can use are bright, the others faded (like Google Maps)
        val lanes = next?.let { nav.route.lanes[nav.maneuverIdx] }
        if (!nav.rerouting && lanes != null && nav.distToManeuverM < (if (next.highway) 2000.0 else 600.0)) {
            LaneStrip(lanes, big = next.highway || next.type in 18..21)
        }
        // "Then" hint when the following maneuver comes quickly
        val then = nav.then
        if (!nav.rerouting && next != null && then != null && then.alongM - next.alongM < 450) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp), color = Palette.NavGreenDark,
                modifier = Modifier.padding(start = 14.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Then"), color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    ManeuverIcon(then.type, then.roundaboutExit, Modifier.size(22.dp))
                }
            }
        }
    }
}

/** Row of lane arrows under the turn banner. */
@Composable
private fun LaneStrip(lanes: List<LaneInfo>, big: Boolean) {
    val size = if (big) 44.dp else 34.dp
    Surface(
        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp), color = Palette.NavGreenDark,
        modifier = Modifier.padding(horizontal = 14.dp).semantics {
            contentDescription = "Lanes: " + lanes.joinToString(" | ") { l -> l.indications.joinToString("+") + if (l.valid) " (use)" else "" }
        },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            lanes.forEachIndexed { i, lane ->
                if (i > 0) Box(Modifier.padding(horizontal = 3.dp).width(2.dp).height(size * 0.8f).background(Color.White.copy(alpha = 0.3f)))
                LaneIcon(lane, Modifier.size(size))
            }
        }
    }
}

/** One lane: faded arrows drawn as one layer (no seams), the arrow to follow solid white. */
@Composable
fun LaneIcon(lane: LaneInfo, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        drawIntoCanvas { ArrowPainter.drawLane(it.nativeCanvas, lane, size.width, size.height) }
    }
}

@Composable
private fun ExitBadge(number: String) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFDD663)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(t("EXIT {0}", number), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Road number shield: E-roads green, motorways/expressways red, others white. */
@Composable
private fun RoadShield(ref: String) {
    val r = ref.trim()
    val (bg, fg) = when {
        r.startsWith("E ") || (r.startsWith("E") && r.getOrNull(1)?.isDigit() == true) -> Color(0xFF00843D) to Color.White
        r.length >= 2 && r[0] in "DRAMS" && r[1].isDigit() -> Color(0xFFC62828) to Color.White
        else -> Color.White to Color.Black
    }
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(r, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ArrivedCard(nav: NavState) {
    Surface(shape = RoundedCornerShape(16.dp), color = Palette.NavGreen, shadowElevation = glassShadow(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverIcon(4, 0, Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t("You have arrived"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(nav.request.stops.lastOrNull()?.name ?: "", color = Color.White, fontSize = 16.sp)
            }
            Button(onClick = { Ui.stopNavigation() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.NavGreen)) {
                Text(t("Done"))
            }
        }
    }
}

@Composable
private fun EtaBar(nav: NavState) {
    val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nav.etaMs))
    val nextStop = nav.request.stops.getOrNull(nav.nextWaypoint)?.takeIf { nav.nextWaypoint < nav.request.stops.size - 1 && !it.through }
    // floating card: round on every corner, lifted off the screen edges
    Surface(
        shape = RoundedCornerShape(30.dp), shadowElevation = glassShadow(10.dp),
        color = MaterialTheme.colorScheme.surface, border = panelBorder,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = { Ui.stopNavigation() }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(50.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, t("End navigation"), tint = MaterialTheme.colorScheme.onSurface) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Geo.formatDuration(nav.remainingS), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Green)
                Text("${Geo.formatDistance(nav.remainingM)} · $eta", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (nextStop != null) Text(t("Next stop: {0}", nextStop.name), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(onClick = { Ui.showQuickAdd.value = !Ui.showQuickAdd.value }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(50.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, t("Add stop"), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable
private fun SimControls() {
    val speedUp by Core.simulator.speedUp.collectAsState()
    val extra by Core.simulator.extraKmh.collectAsState()
    Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SimChip(t("SIMULATION"), Color(0xFFFDD663), bold = true)
        SimChip(t("×{0} time", speedUp)) { Core.simulator.speedUp.value = when (speedUp) { 1 -> 2; 2 -> 4; else -> 1 } }
        SimChip(if (extra == 0) t("at limit") else "+$extra km/h")
 {
            Core.simulator.extraKmh.value = when (extra) { 0 -> 10; 10 -> 25; 25 -> 45; else -> 0 }
        }
    }
}

@Composable
private fun SimChip(text: String, color: Color = Color.White, bold: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    var m = Modifier.clip(shape).background(Color(0xE6000000))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}


private data class QuickCat(val label: String, val term: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val quickCats = listOf(
    QuickCat("Fuel", "fuel", Glyphs.Fuel),
    QuickCat("EV charging", "charging station", Glyphs.EvStation),
    QuickCat("Parking", "parking", Glyphs.Parking),
    QuickCat("Restaurants", "restaurant", Glyphs.Restaurant),
    QuickCat("Fast food", "fast food", Glyphs.Restaurant),
    QuickCat("Cafés", "cafe", Glyphs.Cafe),
    QuickCat("Supermarkets", "supermarket", Icons.Default.ShoppingCart),
    QuickCat("Toilets", "toilets", Icons.Default.Place),
)

/** The "+" menu and the places it finds along the route, above the trip bar. */
@Composable
private fun QuickAddArea(nav: NavState) {
    val open by Ui.showQuickAdd.collectAsState()
    AnimatedVisibility(visible = open, enter = fadeIn(tween(150)) + slideInVertically(tween(220)) { it / 3 }, exit = fadeOut(tween(120))) {
        QuickAddMenu()
    }
    AlongRouteResults(nav)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickAddMenu() {
    Surface(
        shape = RoundedCornerShape(22.dp), shadowElevation = glassShadow(10.dp), color = MaterialTheme.colorScheme.surface, border = panelBorder,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Add along the route"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { Ui.showQuickAdd.value = false }) { Icon(Icons.Default.Close, t("Close")) }
            }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(end = 8.dp),
            ) {
                quickCats.forEach { c -> QuickChip(c.icon, t(c.label)) { Ui.findAlongRoute(c.label, c.term) } }
                QuickChip(Icons.Default.Search, t("Search…")) {
                    Ui.showQuickAdd.value = false
                    Ui.openSearch(SearchTarget.ADD_STOP)
                }
            }
        }
    }
}

@Composable
private fun QuickChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = Palette.Blue)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 14.sp)
        }
    }
}

/** Places found along the route: tap one (here or on the map) to add it as a stop. */
@Composable
private fun AlongRouteResults(nav: NavState) {
    val cat by Ui.category.collectAsState()
    val loading by Ui.categoryLoading.collectAsState()
    val c = cat ?: return
    Surface(
        shape = RoundedCornerShape(22.dp), shadowElevation = glassShadow(10.dp), color = MaterialTheme.colorScheme.surface, border = panelBorder,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t(c.first), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (c.second.isEmpty()) t("None found") else t("Tap one to add it as a stop"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Ui.closeAlongRoute() }) { Icon(Icons.Default.Close, t("Close")) }
            }
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(c.second.size) { i ->
                    val p = c.second[i]
                    val ahead = nav.route.path.project(p.pos)?.along?.minus(nav.alongM)
                    Surface(
                        onClick = { Ui.pressedPoint.value = p; Ui.camera.tryEmit(CameraCommand.Center(p.pos, 16.0)) },
                        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.width(170.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(p.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                ahead?.let { t("{0} ahead", Geo.formatDistance(it.coerceAtLeast(0.0))) } ?: p.address,
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
