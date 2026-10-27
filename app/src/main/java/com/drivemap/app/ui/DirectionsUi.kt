package com.drivemap.app.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivemap.app.i18n.t
import com.drivemap.app.core.Core
import com.drivemap.app.i18n.tn
import com.drivemap.app.data.Countries
import com.drivemap.app.geo.Geo
import com.drivemap.app.route.AvoidOptions
import com.drivemap.app.route.Route
import com.drivemap.app.route.RouteMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DirectionsOverlay(d: DirectionsState) {
    val landscape = isLandscape()
    var showOptions by remember { mutableStateOf(false) }
    val pressed by Ui.pressedPoint.collectAsState()
    val draft by Ui.cruiseDraft.collectAsState()
    val draftFor by Ui.cruiseDraftFor.collectAsState()
    val incoming by Core.pendingCruise.collectAsState()

    // from / stops / to + route types
    val header: @Composable () -> Unit = {
        Column(Modifier.padding(bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = { Ui.closeDirections() }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back"))
                }
                Column(Modifier.weight(1f).padding(top = 6.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StopField(
                        dot = Palette.Blue, text = d.origin?.name ?: t("Your location"),
                        onClick = { Ui.openSearch(SearchTarget.ORIGIN) }, onRemove = null,
                    )
                    // a cruise's shaping points are shown as one line instead of empty fields
                    if (d.stops.any { it.through }) {
                        Text(
                            "↻ " + t("Loop through {0} points", d.stops.count { it.through }), color = Palette.Purple,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    d.stops.forEachIndexed { i, s ->
                        if (s.through) return@forEachIndexed
                        val last = i == d.stops.size - 1
                        StopField(
                            dot = if (last) Palette.Red else if (s.convoyStopId != null) Palette.Purple else Palette.Gray,
                            text = s.name,
                            onClick = { if (last) Ui.openSearch(SearchTarget.DESTINATION) },
                            onRemove = if (d.stops.size > 1) ({ Ui.removeStop(i) }) else null,
                        )
                    }
                    Text(
                        t("+ Add stop"), color = Palette.Blue, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        modifier = Modifier.clickable { Ui.openSearch(SearchTarget.ADD_STOP) }.padding(vertical = 4.dp, horizontal = 4.dp),
                    )
                }
                // ⇅ swap start and destination, like Google Maps
                if (d.stops.none { it.through }) {
                    IconButton(onClick = { Ui.swapEnds() }, modifier = Modifier.padding(top = 34.dp, end = 4.dp)) {
                        Icon(Glyphs.Swap, t("Swap start and destination"))
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RouteMode.entries.forEach { m ->
                    FilterChip(
                        selected = d.mode == m,
                        onClick = { Ui.setMode(m) },
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Tints.blue, selectedLabelColor = Tints.onBlue),
                    )
                }
                FilterChip(
                    selected = d.avoid.tolls || d.avoid.motorways || d.avoid.ferries,
                    onClick = { showOptions = true },
                    label = { Text(t("Options")) },
                    leadingIcon = { Icon(Glyphs.Tune, null, Modifier.size(16.dp)) },
                )
            }
        }
    }
    // progress, errors and vignette notes
    val status: @Composable () -> Unit = {
        if (d.loading) {
            Text(d.progress, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        d.error?.let { err ->
            Text(err, color = Palette.Red, modifier = Modifier.padding(vertical = 10.dp))
            TextButton(onClick = { Ui.plan() }) { Text(t("Try again")) }
        }
        d.notes.forEach { n ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Tints.orange).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyphs.Ticket, null, tint = Palette.Orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(n, fontSize = 13.sp, color = Tints.onOrange)
            }
        }
    }
    val startRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { Ui.start(simulate = false) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Color.White),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Glyphs.Navigation, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(t("Start"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            OutlinedButton(onClick = { Ui.start(simulate = true) }, modifier = Modifier.height(52.dp)) { Text(t("Simulate")) }
        }
    }

    // drag the panel: up = more room for routes, down = just the chosen route and Start
    var level by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val dragToResize = Modifier.pointerInput(Unit) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total < -50) level = (level + 1).coerceAtMost(2)
                else if (total > 50) level = (level - 1).coerceAtLeast(0)
            },
        ) { _, dy -> total += dy }
    }
    val shownRoutes = if (level == 0) d.routes.withIndex().filter { it.index == d.selected } else d.routes.withIndex().toList()
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    if (landscape) {
        // Sideways: everything in one full-height panel on the left, the route on the map to the right
        Box(Modifier.fillMaxSize()) {
            Surface(
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp), shadowElevation = glassShadow(8.dp),
                color = MaterialTheme.colorScheme.surface, border = panelBorder,
                modifier = Modifier.width(LANDSCAPE_PANEL + 40.dp).fillMaxHeight(),
            ) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().then(dragToResize)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        DragHandle()
                        header()
                        pressed?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { PressedPointCard(it) } }
                        draft?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { CruiseSendCard(it, draftFor) } }
                        incoming?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { CruiseInviteCard(it) } }
                        Column(Modifier.padding(horizontal = 14.dp)) {
                            status()
                            shownRoutes.forEach { (i, r) -> RouteCard(r, i == d.selected, d.mode) { Ui.selectRoute(i) } }
                        }
                    }
                    if (d.routes.isNotEmpty()) Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) { startRow() }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    shadowElevation = glassShadow(8.dp), color = MaterialTheme.colorScheme.surface, border = panelBorder, modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.statusBarsPadding()) { header() }
                }
                // long-pressed point: add as a stop / make it the destination
                pressed?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { PressedPointCard(it) } }
                draft?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { CruiseSendCard(it, draftFor) } }
                incoming?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { CruiseInviteCard(it) } }
            }

            // routes sheet (slides up when directions open)
            val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
            AnimatedVisibility(
                visibleState = shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            ) {
                SheetSurface(dragToResize) {
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        status()
                        if (d.routes.isNotEmpty()) {
                            val maxH = when (level) { 0 -> 160.dp; 1 -> 330.dp; else -> screenH * 0.62f }
                            LazyColumn(Modifier.heightIn(max = maxH)) {
                                items(shownRoutes.size) { k ->
                                    val (i, r) = shownRoutes[k]
                                    RouteCard(r, i == d.selected, d.mode) { Ui.selectRoute(i) }
                                }
                            }
                            startRow()
                        }
                    }
                }
            }
        }
    }
    if (showOptions) AvoidDialog(d.avoid, onDismiss = { showOptions = false }) { Ui.setAvoid(it); showOptions = false }
}

@Composable
private fun StopField(dot: Color, text: String, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, t("Remove"), Modifier.size(18.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteCard(r: Route, selected: Boolean, mode: RouteMode, onClick: () -> Unit) {
    val m = r.metrics
    val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (r.timeS * 1000).toLong()))
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(shape)
            .background(if (selected) Palette.Blue.copy(alpha = 0.07f) else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.Blue else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Geo.formatDuration(r.timeS), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = if (selected) Palette.Green else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(
                String.format("%.0f km", r.lengthKm) + " · " + t("arrive {0}", eta)
, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            if (m != null) {
                Text(String.format("€%.2f", m.totalCostEur), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        val via = r.viaText()
        if (via.isNotBlank()) Text(via, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            r.tags.forEach { tag ->
                val color = when {
                    tag.startsWith("Needs vignette") -> Palette.Red
                    tag.startsWith("Best for") -> Palette.Blue
                    tag == "Via your roads" -> Palette.Blue
                    tag.startsWith("Only roads") -> Palette.Green
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                // tags are kept in English (the colours key off them) and translated for display
                val label = if (tag.startsWith("Needs vignette: ")) t("Needs vignette:") + " " + tag.removePrefix("Needs vignette: ") else t(tag)
                Tag(label, color)
            }
        }
        if (m != null) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val unit = com.drivemap.app.core.Core.settings.value.fuelType.unit
                Detail(Glyphs.Fuel, String.format("%.1f %s · €%.2f", m.fuelLiters, unit, m.fuelCostEur))
                if (m.vignettes.isNotEmpty()) {
                    m.vignettes.forEach { v ->
                        Detail(
                            Glyphs.Ticket,
                            if (v.covered) "${Countries.flag(v.country)} ✓" else String.format("${Countries.flag(v.country)} %s €%.2f", v.productLabel, v.priceEur),
                            if (v.covered) Palette.Green else Palette.Red,
                        )
                    }
                }
                if (m.tollCostEur > 0.05) Detail(Glyphs.Ticket, t("tolls ≈ {0}", String.format("€%.2f", m.tollCostEur)), Palette.Orange)
                if (r.cameraCount >= 0) Detail(Glyphs.Camera, tn(r.cameraCount, "{0} radar", "{0} radars"))
                if (mode == RouteMode.SPORT || m.sharpCorners >= 10) Detail(Glyphs.Route, tn(m.sharpCorners, "{0} sharp corner", "{0} sharp corners") + " · ${m.curvatureDegPerKm.toInt()}°/km")
                if (m.countries.size > 1) Text(m.countries.joinToString(" ") { Countries.flag(it) }, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Detail(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 13.sp, color = color)
    }
}

@Composable
fun AvoidDialog(current: AvoidOptions, onDismiss: () -> Unit, onApply: (AvoidOptions) -> Unit) {
    var a by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Route options")) },
        text = {
            Column {
                OptionRow(t("Avoid motorways where I have no valid vignette"), a.unpaidVignettes) { a = a.copy(unpaidVignettes = it) }
                OptionRow(t("Avoid all tolls and vignette roads"), a.tolls) { a = a.copy(tolls = it) }
                OptionRow(t("Avoid motorways"), a.motorways) { a = a.copy(motorways = it) }
                OptionRow(t("Avoid ferries"), a.ferries) { a = a.copy(ferries = it) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onDismiss(); Ui.screen.value = Screen.VIGNETTES }) {
                    Icon(Icons.Default.Info, null, tint = Palette.Blue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("Manage my vignettes"), color = Palette.Blue)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(a) }) { Text(t("Apply")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}

@Composable
fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AddButtonRow(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Add, null, tint = Palette.Blue)
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.Blue, fontWeight = FontWeight.Medium)
    }
}


/** The little bar showing a panel can be dragged. */
@Composable
private fun DragHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 36.dp, height = 4.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        )
    }
}
