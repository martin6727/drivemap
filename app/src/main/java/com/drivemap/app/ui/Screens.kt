package com.drivemap.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivemap.app.i18n.t
import com.drivemap.app.i18n.tn
import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.core.Core
import com.drivemap.app.data.AppSettings
import com.drivemap.app.data.Countries
import com.drivemap.app.data.FuelType
import com.drivemap.app.data.MapTheme
import com.drivemap.app.data.VoiceLevel
import com.drivemap.app.geo.Geo
import com.drivemap.app.route.Place
import com.drivemap.app.route.RouteMode
import com.drivemap.app.vignette.OwnedVignette
import com.drivemap.app.vignette.VignetteCatalog
import com.drivemap.app.vignette.VignetteCountry
import com.drivemap.app.vignette.VignetteStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back")) }
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

// ------------------------------------------------------------------ search

@Composable
fun SearchScreen() {
    val q by Ui.query.collectAsState()
    val results by Ui.results.collectAsState()
    val searching by Ui.searching.collectAsState()
    val target by Ui.searchTarget.collectAsState()
    val recents by Core.places.recents.collectAsState()
    val favorites by Core.places.favorites.collectAsState()
    val home by Core.places.home.collectAsState()
    val work by Core.places.work.collectAsState()
    val fix by Core.location.fix.collectAsState()
    val focus = remember { FocusRequester() }

    fun back() {
        Ui.screen.value = when (target) {
            SearchTarget.HOME, SearchTarget.WORK -> Screen.SETTINGS
            SearchTarget.CONVOY_STOP, SearchTarget.CONVOY_DEST -> Screen.CONVOY
            else -> Screen.MAP
        }
        Ui.searchTarget.value = SearchTarget.PLACE
    }
    BackHandler { back() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back")) }
                TextField(
                    value = q,
                    onValueChange = { Ui.onQuery(it) },
                    placeholder = {
                        Text(
                            when (target) {
                                SearchTarget.DESTINATION -> t("Where to?")
                                SearchTarget.ADD_STOP -> t("Add a stop")
                                SearchTarget.ORIGIN -> t("Start from")
                                SearchTarget.HOME -> t("Set home address")
                                SearchTarget.WORK -> t("Set work address")
                                SearchTarget.CONVOY_STOP -> t("Stop for the whole convoy")
                                SearchTarget.CONVOY_DEST -> t("Convoy destination")
                                SearchTarget.PLACE -> t("Search places and addresses")
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { Ui.submitQuery(q) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { Ui.onQuery("") }) { Icon(Icons.Default.Clear, t("Clear")) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                if (q.isBlank()) {
                    if (target == SearchTarget.ORIGIN) {
                        item { PlaceRow(Glyphs.MyLocation, t("Your location"), "", Palette.Blue) {
                            Ui.directions.value = Ui.directions.value?.copy(origin = null); Ui.screen.value = Screen.MAP; Ui.plan()
                        } }
                    }
                    home?.let { h -> item { PlaceRow(Icons.Default.Home, t("Home"), h.address.ifBlank { h.name }, Palette.Blue) { Ui.pick(h) } } }
                    work?.let { w -> item { PlaceRow(Glyphs.Car, t("Work"), w.address.ifBlank { w.name }, Palette.Blue) { Ui.pick(w) } } }
                    if (favorites.isNotEmpty()) {
                        item { SectionTitle(t("Saved")) }
                        items(favorites) { p -> PlaceRow(Icons.Default.Star, p.name, p.address, Color(0xFFF9AB00)) { Ui.pick(p) } }
                    }
                    if (recents.isNotEmpty()) {
                        item { SectionTitle(t("Recent")) }
                        items(recents) { p -> PlaceRow(Icons.Default.Refresh, p.name, p.address, Palette.Gray) { Ui.pick(p) } }
                    }
                } else {
                    items(results) { p ->
                        val dist = fix?.let { Geo.formatDistance(Geo.dist(it.pos, p.pos)) } ?: ""
                        PlaceRow(Icons.Default.LocationOn, p.name, listOf(p.address, dist).filter { it.isNotBlank() }.joinToString(" · "), Palette.Gray) { Ui.pick(p) }
                    }
                    if (!searching && results.isEmpty()) item { CenteredText(t("No results"), Modifier.padding(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s by Core.settings.state.collectAsState()
    val home by Core.places.home.collectAsState()
    val work by Core.places.work.collectAsState()
    val vignettes by Core.vignettes.items.collectAsState()
    fun upd(f: (AppSettings) -> AppSettings) = Core.settings.update(f)

    PageScaffold(t("Settings"), onBack = { Ui.screen.value = Screen.MAP }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            SectionTitle(t("You"))
            NameField(s.userName) { v -> upd { it.copy(userName = v) } }

            SectionTitle(t("Vignettes"))
            val valid = vignettes.filter { it.validOn(LocalDate.now()) }.map { it.country }.distinct()
            SettingRow(
                Glyphs.Ticket, t("My vignettes"),
                if (valid.isEmpty()) t("None valid today — routes avoid motorways that need one")
                else t("Valid today:") + " " + valid.joinToString(" ") { Countries.flag(it) + " " + it },
            ) { Ui.screen.value = Screen.VIGNETTES }

            SectionTitle(t("Car & fuel"))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FuelType.entries.forEach { t ->
                    FilterChip(selected = s.fuelType == t, onClick = {
                        upd { it.copy(fuelType = t, fuelPrice = t.defaultPrice, consumption = t.defaultConsumption) }
                    }, label = { Text(t.label) })
                }
            }
            NumberField(t("Average consumption ({0}/100 km)", s.fuelType.unit), s.consumption) { v -> upd { it.copy(consumption = v) } }
            if (s.fuelType != FuelType.ELECTRIC) {
                val avgs by com.drivemap.app.fuel.FuelPrices.averages.collectAsState()
                val avgDate by com.drivemap.app.fuel.FuelPrices.averagesDate.collectAsState()
                val here = com.drivemap.app.fuel.FuelPrices.currentCountry ?: Core.speedLimits.country.collectAsState().value
                val hereAvg = here?.let { avgs[it]?.forType(s.fuelType) }
                ToggleRow(
                    t("Automatic fuel price"),
                    s.autoFuelPrice,
                    if (hereAvg != null) {
                        t("Country average from the EU Weekly Oil Bulletin ({0}). Now: {1} {2}", avgDate, Countries.name(here), String.format("€%.3f", hereAvg))
                    } else {
                        t("Uses each country's weekly average (EU Weekly Oil Bulletin), also for route costs abroad")
                    },
                ) { v -> upd { it.copy(autoFuelPrice = v) } }
            }
            NumberField(
                if (s.autoFuelPrice && s.fuelType != FuelType.ELECTRIC) t("Your price, when no average is known (€ per {0})", s.fuelType.unit)
                else t("Price (€ per {0})", s.fuelType.unit),
                s.fuelPrice,
            ) { v -> upd { it.copy(fuelPrice = v) } }

            SectionTitle(t("Routes"))
            Text(t("Default route type"), Modifier.padding(horizontal = 16.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(horizontal = 12.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RouteMode.entries.forEach { m ->
                    FilterChip(selected = s.defaultMode == m, onClick = { upd { it.copy(defaultMode = m) } }, label = { Text(m.short) })
                }
            }
            ToggleRow(t("Avoid motorways where I have no vignette"), s.avoid.unpaidVignettes) { v -> upd { it.copy(avoid = it.avoid.copy(unpaidVignettes = v)) } }
            ToggleRow(t("Avoid all tolls"), s.avoid.tolls) { v -> upd { it.copy(avoid = it.avoid.copy(tolls = v)) } }
            ToggleRow(t("Avoid motorways"), s.avoid.motorways) { v -> upd { it.copy(avoid = it.avoid.copy(motorways = v)) } }
            ToggleRow(t("Avoid ferries"), s.avoid.ferries) { v -> upd { it.copy(avoid = it.avoid.copy(ferries = v)) } }
            ToggleRow(
                t("Drive through “destination only” zones"), s.throughDestOnly,
                t("Routes use roads signed for local access only (dopravná obsluha) as shortcuts. Using them just to pass through can be fined."),
            ) { v -> upd { it.copy(throughDestOnly = v) } }

            SectionTitle(t("Radars"))
            ToggleRow(t("Intelligent radar alerts"), s.radarAlerts, t("Only cameras ahead that check your direction; louder when you're too fast; average-speed zones")) { v -> upd { it.copy(radarAlerts = v) } }
            ToggleRow(t("Alert sounds"), s.radarSound) { v -> upd { it.copy(radarSound = v) } }
            ToggleRow(t("Mobile radar & police reports"), s.mobileReports, t("Share and receive reports with other DriveMap drivers nearby")) { v -> upd { it.copy(mobileReports = v) } }
            val spots by Core.hotspots.frequentCount.collectAsState()
            ToggleRow(
                t("Warn about frequent speed-check spots"), s.hotspotAlerts,
                t("Places where police measured on several days, learned from radar & police reports. Known so far: {0}", spots),
            ) { v -> upd { it.copy(hotspotAlerts = v) } }
            ToggleRow(t("Turn off radar warnings where they're illegal"), s.respectCameraBans, t("Germany, Switzerland, France, Cyprus")) { v -> upd { it.copy(respectCameraBans = v) } }

            SectionTitle(t("Fine meter"))
            ToggleRow(t("Show estimated fine next to my speed"), s.fineMeter) { v -> upd { it.copy(fineMeter = v) } }
            ToggleRow(t("Deduct radar tolerance (3 km/h or 3 %)"), s.toleranceInFine) { v -> upd { it.copy(toleranceInFine = v) } }

            SectionTitle(t("Language"))
            Row(Modifier.padding(horizontal = 12.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to t("Auto (phone)"), "en" to "English", "sk" to "Slovenčina").forEach { (code, label) ->
                    FilterChip(selected = s.appLanguage == code, onClick = {
                        upd {
                            // switch the voice along when it was on the other one of the two
                            val voice = when {
                                code == "sk" && it.voiceLang == "en-US" -> "sk-SK"
                                code == "en" && it.voiceLang == "sk-SK" -> "en-US"
                                else -> it.voiceLang
                            }
                            it.copy(appLanguage = code, voiceLang = voice)
                        }
                    }, label = { Text(label) })
                }
            }

            SectionTitle(t("Voice"))
            ToggleRow(t("Voice guidance"), s.voice) { v -> upd { it.copy(voice = v) } }
            Row(Modifier.padding(horizontal = 12.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VoiceLevel.entries.forEach { l ->
                    FilterChip(selected = s.voiceLevel == l, onClick = { upd { it.copy(voiceLevel = l) } }, label = { Text(l.label) })
                }
            }
            Text(
                s.voiceLevel.hint + ". " + t("Radar and police warnings are always spoken."),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(horizontal = 12.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("en-US" to "English", "sk-SK" to "Slovenčina", "cs-CZ" to "Čeština", "de-DE" to "Deutsch", "hu-HU" to "Magyar", "pl-PL" to "Polski").forEach { (code, label) ->
                    FilterChip(selected = s.voiceLang == code, onClick = { upd { it.copy(voiceLang = code) } }, label = { Text(label) })
                }
            }

            SectionTitle(t("Map"))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapTheme.entries.forEach { t -> FilterChip(selected = s.mapTheme == t, onClick = { upd { it.copy(mapTheme = t) } }, label = { Text(t.label) }) }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !s.glass, onClick = { upd { it.copy(glass = false) } }, label = { Text(t("Standard look")) })
                FilterChip(selected = s.glass && !s.lightGlass && !s.clearGlass, onClick = { upd { it.copy(glass = true, lightGlass = false, clearGlass = false) } }, label = { Text(t("Liquid glass")) })
                FilterChip(selected = s.glass && s.lightGlass, onClick = { upd { it.copy(glass = true, lightGlass = true, clearGlass = false) } }, label = { Text(t("Light glass")) })
                FilterChip(selected = s.glass && s.clearGlass, onClick = { upd { it.copy(glass = true, lightGlass = false, clearGlass = true) } }, label = { Text(t("Clear glass")) })
            }
            Text(
                t("Route line colour"), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(horizontal = 16.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppSettings.ROUTE_COLORS.forEach { (argb, name) ->
                    val selected = s.routeColor == argb
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(Color(argb))
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { upd { it.copy(routeColor = argb) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
            // preview of the chosen colour as a route line
            Box(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(10.dp)
                    .clip(RoundedCornerShape(5.dp)).background(Color(s.routeColor)),
            )

            SectionTitle(t("Places"))
            SettingRow(Icons.Default.Home, t("Home"), home?.let { it.address.ifBlank { it.name } } ?: t("Not set")) { Ui.openSearch(SearchTarget.HOME) }
            SettingRow(Glyphs.Car, t("Work"), work?.let { it.address.ifBlank { it.name } } ?: t("Not set")) { Ui.openSearch(SearchTarget.WORK) }

            SectionTitle(t("Your roads"))
            Text(
                t("Roads you can drive that the map marks as private, closed or a track. Routes go through them when they're on the way and clearly shorter. To add one, long-press the road on the map and tap I can drive here."),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val myRoads by Core.myRoads.items.collectAsState()
            myRoads.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Car, null, tint = Palette.Blue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(r.name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { Core.myRoads.remove(r.id) }) { Icon(Icons.Default.Close, t("Remove")) }
                }
            }

            SectionTitle(t("Android Auto"))
            val aaReady = remember { com.drivemap.app.core.AutoSetup.ready(ctx) }
            SettingRow(
                Glyphs.Car, t("Show in Android Auto"),
                if (aaReady) t("Ready — DriveMap appears in your car") else t("Not set up — tap to enable"),
            ) { if (!aaReady) com.drivemap.app.core.AutoSetup.enable(ctx) }

            SectionTitle(t("Advanced"))
            TextSetting(t("Routing server (Valhalla)"), s.valhallaUrl) { v -> upd { it.copy(valhallaUrl = v.ifBlank { AppSettings.DEFAULT_VALHALLA }) } }
            TextSetting(t("Convoy relay (MQTT)"), s.mqttUrl) { v ->
                upd { it.copy(mqttUrl = v.ifBlank { AppSettings.DEFAULT_MQTT }) }
                Core.mqtt.brokerChanged()
            }

            SectionTitle(t("About"))
            val updScope = androidx.compose.runtime.rememberCoroutineScope()
            var checking by remember { mutableStateOf(false) }
            SettingRow(
                Icons.Default.Refresh, t("Check for updates"),
                if (checking) t("Checking…") else t("Version {0}", com.drivemap.app.core.Updates.currentVersion(ctx)),
            ) {
                if (!checking) {
                    checking = true
                    updScope.launch {
                        try {
                            // a newer version opens the Update prompt by itself
                            if (!com.drivemap.app.core.Updates.checkNow(ctx)) Core.message(t("You have the latest version"))
                        } catch (_: Exception) {
                            Core.message(t("Couldn't check for updates. Are you online?"))
                        }
                        checking = false
                    }
                }
            }
            Text(
                t("Map data © OpenStreetMap contributors. Tiles: OpenFreeMap / OpenMapTiles. Routing: Valhalla (FOSSGIS). Search: Photon (komoot) and Nominatim. Speed cameras: OpenStreetMap. Fines and vignette prices are estimates researched in October 2026. Convoy positions are end-to-end encrypted; the relay only sees random bytes."),
                Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Blue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NameField(value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(24); onSave(text.trim()) },
        label = { Text(t("Your name (shown to your convoy)")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun NumberField(label: String, value: Double, onSave: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 1000 }?.let(onSave)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun TextSetting(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var dirty by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it; dirty = true }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (dirty) TextButton(onClick = { onSave(text.trim()); dirty = false }) { Text(t("Save")) }
    }
}

// ------------------------------------------------------------------ vignettes

@Composable
fun VignettesScreen() {
    val items by Core.vignettes.items.collectAsState()
    var adding by remember { mutableStateOf<VignetteCountry?>(null) }
    PageScaffold(t("My vignettes"), onBack = { Ui.screen.value = Screen.MAP }) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Text(
                    t("Add the vignettes you have bought. Routes avoid motorways in countries where you have no valid vignette (like Waze), and the cheapest-route mode adds the price of the one you'd need. You get a reminder 3 days before one expires."),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 12.dp),
                )
            }
            items(VignetteCatalog.countries) { c ->
                VignetteCountryCard(c, items.filter { it.country == c.code }) { adding = c }
            }
            item {
                Text(
                    t("Car prices 2026 (≤ 3.5 t). Tunnels such as Tauern, Brenner or Karawanken are charged separately."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 30.dp),
                )
            }
        }
    }
    adding?.let { c -> AddVignetteDialog(c, onDismiss = { adding = null }) { v -> Core.vignettes.upsert(v); adding = null } }
}

@Composable
private fun VignetteCountryCard(c: VignetteCountry, owned: List<OwnedVignette>, onAdd: () -> Unit) {
    val (status, current) = Core.vignettes.status(c.code)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.let { it.copy(alpha = it.alpha * 0.6f) }),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Countries.flag(c.code), fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(Countries.name(c.code), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val today = LocalDate.now()
                    val (text, color) = when (status) {
                        VignetteStatus.VALID -> t("Valid until {0}", current!!.end.format(dateFmt)) to Palette.Green
                        VignetteStatus.EXPIRING -> {
                            val days = java.time.temporal.ChronoUnit.DAYS.between(today, current!!.end)
                            (if (days == 0L) t("Expires today") else tn(days.toInt(), "Expires in {0} day", "Expires in {0} days")) to Palette.Orange
                        }
                        VignetteStatus.FUTURE -> t("Starts {0}", current!!.start.format(dateFmt)) to Palette.Blue
                        VignetteStatus.EXPIRED -> t("Expired {0}", current!!.end.format(dateFmt)) to Palette.Red
                        VignetteStatus.NONE -> t("No vignette — its motorways are avoided") to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                TextButton(onClick = onAdd) { Text(t("Add")) }
            }
            owned.sortedByDescending { it.end }.forEach { v ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.label(), fontSize = 14.sp)
                        Text("${v.start.format(dateFmt)} – ${v.end.format(dateFmt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Core.vignettes.remove(v.id) }) { Icon(Icons.Default.Delete, t("Remove"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(
                c.products.joinToString(" · ") { "${it.label.substringBefore(" (")} ${it.priceText()}" },
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVignetteDialog(c: VignetteCountry, onDismiss: () -> Unit, onSave: (OwnedVignette) -> Unit) {
    var productId by remember { mutableStateOf(c.products.first().id) }
    var start by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }
    val product = c.products.first { it.id == productId }
    val end = product.validity.endDate(start)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("{0} {1} vignette", Countries.flag(c.code), Countries.name(c.code))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                c.products.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { productId = p.id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.id == productId, onClick = { productId = p.id })
                        Column {
                            Text(p.label, fontSize = 14.sp)
                            Text(p.priceText(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text(t("Valid from {0}", start.format(dateFmt))) }
                Text(t("Valid until {0} (inclusive)", end.format(dateFmt)), fontSize = 13.sp, color = Palette.Green, modifier = Modifier.padding(top = 6.dp))
                Text(c.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(OwnedVignette(country = c.code, productId = productId, start = start, end = end)) }) { Text(t("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { start = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text(t("OK")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(t("Cancel")) } },
        ) { DatePicker(state = state) }
    }
}

// ------------------------------------------------------------------ convoy

@Composable
fun ConvoyScreen() {
    val st by Core.convoy.state.collectAsState()
    val s by Core.settings.state.collectAsState()
    val fix by Core.location.fix.collectAsState()
    val context = LocalContext.current
    // just the 12 code characters; the dashes are drawn by CodeDashes
    var joinField by remember {
        val c = ConvoyCrypto.fromInput(Ui.pendingJoinCode.value ?: "")
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(c, androidx.compose.ui.text.TextRange(c.length)))
    }
    val joinCode = joinField.text
    LaunchedEffect(Unit) { Ui.pendingJoinCode.value = null }
    var confirmLeave by remember { mutableStateOf(false) }

    PageScaffold(t("Convoy"), onBack = { Ui.screen.value = Screen.MAP }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 30.dp)) {
            val c = st
            if (c == null) {
                Text(
                    t("Drive together: everyone in a private convoy sees each other's car on the map (updated every 3 seconds), and anyone can add stops for the whole group. Only people with the code can join; positions are end-to-end encrypted."),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                NameFieldPlain(s.userName) { v -> Core.settings.update { it.copy(userName = v) } }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (s.userName.isBlank()) Core.message(t("Enter your name first")) else {
                            Core.convoy.create()
                            Core.updateService()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Color.White),
                ) { Icon(Glyphs.Group, null); Spacer(Modifier.width(8.dp)); Text(t("Create a convoy")) }
                Spacer(Modifier.height(22.dp))
                Text(t("Join with a code"), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        // typing or pasting a code or a whole invite link both work; dashes appear by themselves
                        value = joinField,
                        onValueChange = { v ->
                            val clean = ConvoyCrypto.fromInput(v.text)
                            // keep the cursor after the same characters it was after (lowercase / spaces / dashes are cleaned up)
                            val cursor = if (clean.length == ConvoyCrypto.normalize(v.text).length) ConvoyCrypto.normalize(v.text.take(v.selection.end)).length.coerceAtMost(clean.length) else clean.length
                            joinField = androidx.compose.ui.text.input.TextFieldValue(clean, androidx.compose.ui.text.TextRange(cursor))
                        },
                        placeholder = { Text(t("XXXX-XXXX-XXXX")) }, singleLine = true, modifier = Modifier.weight(1f),
                        visualTransformation = CodeDashes,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, letterSpacing = 1.sp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        when {
                            s.userName.isBlank() -> Core.message(t("Enter your name first"))
                            !Core.convoy.join(joinCode) -> Core.message(t("That code isn't valid (12 letters/numbers)"))
                            else -> Core.updateService()
                        }
                    }) { Text(t("Join")) }
                }
            } else {
                Text(t("Convoy code"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(ConvoyCrypto.pretty(c.code), fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                if (!c.ready) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                val connected by Core.mqtt.connected.collectAsState()
                Text(if (connected) t("● Connected") else t("○ Connecting…"), color = if (connected) Palette.Green else Palette.Orange, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Convoy code", ConvoyCrypto.pretty(c.code)))
                        Core.message(t("Code copied"))
                    }) { Icon(Glyphs.Copy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Copy")) }
                    Button(onClick = {
                        // a normal https link (chat apps make it tappable); it opens DriveMap and joins
                        val text = t("Join my DriveMap convoy!") + "\n" + ConvoyCrypto.inviteLink(c.code) + "\n" + t("Code:") + " ${ConvoyCrypto.pretty(c.code)}"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), t("Invite to convoy")))
                    }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Invite")) }
                }

                Text(t("Members"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                c.members.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !m.isMe) {
                        Ui.screen.value = Screen.MAP; Ui.selectedMember.value = m.id; Ui.camera.tryEmit(CameraCommand.Center(m.pos, 15.0))
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(if (m.stale) Color(0xFF9AA0A6) else Color(m.color)), contentAlignment = Alignment.Center) {
                            Text(m.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name + if (m.isMe) " (" + t("you") + ")" else "", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val parts = ArrayList<String>()
                            if (!m.isMe) fix?.let { parts += t("{0} away", Geo.formatDistance(Geo.dist(it.pos, m.pos))) }
                            parts += "${Math.round(m.speedKmh)} km/h"
                            parts += if (m.stale) t("seen {0} min ago", m.ageS / 60) else t("live")
                            Text(parts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (c.members.none { !it.isMe }) Text(t("Nobody else yet — send the invite."), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Text(t("Destination"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                val dest = c.destination
                if (dest != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyphs.Flag, null, tint = Palette.Red)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dest.name, fontSize = 15.sp)
                            Text(t("set by {0}", dest.addedByName), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            Ui.screen.value = Screen.MAP
                            Ui.directionsTo(Place(dest.name, dest.pos), c.stops.map { com.drivemap.app.route.Waypoint(it.name, it.pos, it.id) })
                        }) { Text(t("Go")) }
                        IconButton(onClick = { Core.convoy.setDestination(null) }) { Icon(Icons.Default.Clear, t("Clear")) }
                    }
                } else {
                    AddButtonRow(t("Set a destination for everyone")) { Ui.openSearch(SearchTarget.CONVOY_DEST) }
                }

                Text(t("Stops for the whole convoy"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                c.stops.forEach { stop ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Icon(Glyphs.Flag, null, tint = Palette.Purple)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stop.name, fontSize = 15.sp)
                            Text(t("added by {0}", stop.addedByName), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { Core.convoy.removeStop(stop.id) }) { Icon(Icons.Default.Delete, t("Remove")) }
                    }
                }
                AddButtonRow(t("Add a stop for everyone")) { Ui.openSearch(SearchTarget.CONVOY_STOP) }
                AddButtonRow(t("Challenge everyone")) { Ui.cruiseSetup.value = Ui.CruiseSetup(null, null) }
                Text(t("Tip: long-press the map or open any place and tap ‘Convoy stop’."),
 fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(12.dp))
                ToggleRow(t("Share my location"), s.shareLocation) { v -> Core.settings.update { it.copy(shareLocation = v) } }
                ToggleRow(t("Add new convoy stops to my route automatically"), s.autoAddConvoyStops) { v -> Core.settings.update { it.copy(autoAddConvoyStops = v) } }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) { Text(t("Leave convoy"), color = Palette.Red) }
            }
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(t("Leave the convoy?")) },
            text = { Text(t("Others stop seeing you. You can join again with the same code.")) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; Core.convoy.leave(); Core.updateService() }) { Text(t("Leave")) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(t("Cancel")) } },
        )
    }
}

@Composable
private fun NameFieldPlain(value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(24); onSave(text.trim()) },
        label = { Text(t("Your name")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Shows a convoy code as XXXX-XXXX-XXXX while typing: a dash appears after every 4 characters. */
private object CodeDashes : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val out = StringBuilder()
        raw.forEachIndexed { i, ch ->
            out.append(ch)
            if ((i + 1) % 4 == 0 && i + 1 < 12) out.append('-')
        }
        val map = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int) =
                offset + (if (offset >= 4) 1 else 0) + (if (offset >= 8) 1 else 0)
            override fun transformedToOriginal(offset: Int) =
                (offset - (if (offset > 4) 1 else 0) - (if (offset > 9) 1 else 0)).coerceIn(0, raw.length)
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(out.toString()), map)
    }
}
