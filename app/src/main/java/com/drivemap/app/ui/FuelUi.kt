package com.drivemap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivemap.app.core.Core
import com.drivemap.app.data.Countries
import com.drivemap.app.data.FuelType
import com.drivemap.app.fuel.FuelPrices
import com.drivemap.app.fuel.StationFuel
import com.drivemap.app.geo.Geo
import com.drivemap.app.i18n.t
import com.drivemap.app.route.Place

/** "just now" / "3 h ago" / "2 d ago" for a price's last update. */
fun priceAge(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 60 -> t("just now")
        min < 48 * 60 -> t("{0} h ago", min / 60)
        else -> t("{0} d ago", min / (24 * 60))
    }
}

fun isFuelStation(p: Place) = p.fuel != null || p.category.substringAfter(':') == "fuel"

/** Price at the right of a fuel station in the list; the cheapest one is green. */
@Composable
fun FuelPriceTag(st: StationFuel, type: FuelType, cheapest: Boolean) {
    val price = st.forType(type) ?: return
    Column(horizontalAlignment = Alignment.End) {
        Text(
            String.format("€%.3f", price), fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = if (cheapest) Tints.onGreen else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            (if (cheapest) t("cheapest") + " · " else "") + priceAge(st.updatedMs),
            fontSize = 11.sp, color = if (cheapest) Tints.onGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fuel prices block in a station's sheet, with the button to report what you see at the pump. */
@Composable
fun FuelSection(place: Place) {
    val s by Core.settings.state.collectAsState()
    val averages by FuelPrices.averages.collectAsState()
    var fromDrivers by remember(place) { mutableStateOf<StationFuel?>(null) }
    var showReport by remember { mutableStateOf(false) }
    LaunchedEffect(place) {
        // tapped on the map (no prices attached): look for prices other drivers reported
        if (place.fuel == null) {
            fromDrivers = try {
                FuelPrices.driverReportsNear(place.pos).filter { Geo.dist(it.pos, place.pos) < 120 }.maxByOrNull { it.updatedMs ?: 0 }
            } catch (_: Exception) {
                null
            }
        }
    }
    val st = place.fuel ?: fromDrivers
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(t("Fuel prices"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        if (st != null && (st.petrol != null || st.diesel != null || st.lpg != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                st.petrol?.let { PriceCell(t("Petrol 95"), it, s.fuelType == FuelType.PETROL) }
                st.diesel?.let { PriceCell(t("Diesel"), it, s.fuelType == FuelType.DIESEL) }
                st.lpg?.let { PriceCell("LPG", it, s.fuelType == FuelType.LPG) }
            }
            Text(
                if (st.official) t("Official price · {0}", priceAge(st.updatedMs))
                else t("Reported by {0} · {1}", st.reportedBy ?: t("a driver"), priceAge(st.updatedMs)),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(t("No prices yet for this station."), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // the country's weekly average, for comparison
        val country = FuelPrices.lastSearchCountry ?: FuelPrices.currentCountry
        averages[country]?.forType(s.fuelType)?.let { avg ->
            Text(
                t("{0} average this week: {1}", Countries.name(country), String.format("€%.3f", avg)),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (st?.official != true) {
            OutlinedButton(onClick = { showReport = true }, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Glyphs.Fuel, null, Modifier.size(18.dp), tint = Palette.Blue)
                Spacer(Modifier.width(6.dp))
                Text(t("Report prices"))
            }
        }
    }
    if (showReport) {
        ReportFuelDialog(place, st, onDismiss = { showReport = false }) { p, d, l ->
            val by = s.userName.ifBlank { "DriveMap" }
            FuelPrices.report(place, p, d, l, by)
            fromDrivers = StationFuel("me", place.name, place.address, place.pos, p, d, l, System.currentTimeMillis(), official = false, reportedBy = by)
            Ui.selected.value = place.copy(fuel = fromDrivers)
            showReport = false
            Core.message(t("Thanks! Prices shared with other drivers"))
        }
    }
}

@Composable
private fun PriceCell(label: String, price: Double, mine: Boolean) {
    Column {
        Text(
            String.format("€%.3f", price), fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = if (mine) Palette.Blue else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportFuelDialog(place: Place, current: StationFuel?, onDismiss: () -> Unit, onSave: (Double?, Double?, Double?) -> Unit) {
    fun fmt(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: ""
    var petrol by remember { mutableStateOf(fmt(current?.petrol)) }
    var diesel by remember { mutableStateOf(fmt(current?.diesel)) }
    var lpg by remember { mutableStateOf(fmt(current?.lpg)) }
    fun parse(s: String) = s.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it in 0.3..5.0 }
    val any = parse(petrol) != null || parse(diesel) != null || parse(lpg) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Prices at {0}", place.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("Type the prices on the station's sign (€ per litre). Other DriveMap drivers will see them for two weeks."), fontSize = 13.sp)
                listOf(
                    Triple(t("Petrol 95"), petrol) { v: String -> petrol = v },
                    Triple(t("Diesel"), diesel) { v: String -> diesel = v },
                    Triple("LPG", lpg) { v: String -> lpg = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(
                        value = value, onValueChange = set, label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = value.isNotBlank() && parse(value) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = any, onClick = { onSave(parse(petrol), parse(diesel), parse(lpg)) }) { Text(t("Share")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}
