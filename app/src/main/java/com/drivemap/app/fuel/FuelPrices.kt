package com.drivemap.app.fuel

import android.content.Context
import android.util.Log
import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.data.AppSettings
import com.drivemap.app.data.FuelType
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.Http
import com.drivemap.app.route.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** Average pump prices of one country, EUR per litre. */
data class CountryFuel(val petrol: Double?, val diesel: Double?, val lpg: Double?) {
    fun forType(t: FuelType): Double? = when (t) {
        FuelType.PETROL -> petrol
        FuelType.DIESEL -> diesel
        FuelType.LPG -> lpg
        FuelType.ELECTRIC -> null
    }
}

/** Prices at one station, EUR per litre. */
data class StationFuel(
    val id: String,
    val name: String,
    val address: String,
    val pos: LatLon,
    val petrol: Double?,
    val diesel: Double?,
    val lpg: Double?,
    val updatedMs: Long?,
    /** true = government price feed; false = reported by DriveMap drivers. */
    val official: Boolean,
    val reportedBy: String? = null,
) {
    fun forType(t: FuelType): Double? = when (t) {
        FuelType.PETROL -> petrol
        FuelType.DIESEL -> diesel
        FuelType.LPG -> lpg
        FuelType.ELECTRIC -> null
    }
}

/**
 * Fuel prices:
 *  - every EU country: the European Commission's Weekly Oil Bulletin (average petrol 95, diesel and
 *    LPG prices with taxes), refreshed every few days and cached;
 *  - station by station where governments publish live prices: Spain (incl. the Canary Islands),
 *    Austria and France;
 *  - anywhere else (Slovakia, Czechia, …): prices reported by DriveMap drivers, shared like radar
 *    reports and kept for two weeks.
 */
object FuelPrices {
    private const val BULLETIN_PAGE = "https://energy.ec.europa.eu/data-and-analysis/weekly-oil-bulletin_en"
    private const val ES_URL = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres"
    private const val AT_URL = "https://api.e-control.at/sprit/1.0/search/gas-stations/by-address"
    private const val FR_URL = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records"
    private const val REPORT_DAYS = 14

    /** Countries with live, station-level government prices. */
    val officialCountries = setOf("ES", "AT", "FR")

    private lateinit var cacheFile: File
    private var hub: MqttHub? = null
    private val _averages = MutableStateFlow<Map<String, CountryFuel>>(emptyMap())
    val averages: StateFlow<Map<String, CountryFuel>> = _averages
    private val _averagesDate = MutableStateFlow("")
    /** Date of the weekly bulletin in use (yyyy-mm-dd), "" before the first download. */
    val averagesDate: StateFlow<String> = _averagesDate
    /** Country the driver is in (set by Core), used when a route edge has no country. */
    @Volatile var currentCountry: String? = null
    /** Country of the latest Fuel search (for the station sheet's "average this week" line). */
    @Volatile var lastSearchCountry: String? = null
    private val myReports = ConcurrentHashMap<String, StationFuel>()

    fun init(context: Context, mqtt: MqttHub, scope: CoroutineScope) {
        cacheFile = File(context.filesDir, "fuel_eu.json")
        hub = mqtt
        load()
        scope.launch(Dispatchers.IO) {
            while (true) {
                val age = System.currentTimeMillis() - (if (cacheFile.exists()) cacheFile.lastModified() else 0L)
                if (_averages.value.isEmpty() || age > 3 * 86_400_000L) {
                    try {
                        refreshAverages()
                    } catch (e: Exception) {
                        Log.w("Fuel", "bulletin: ${e.message}")
                    }
                }
                delay(6 * 3_600_000L)
            }
        }
    }

    /**
     * Price per litre to calculate with in [country]: that country's weekly average when automatic
     * prices are on (and known), otherwise the price typed in Settings.
     */
    fun price(country: String?, s: AppSettings): Double {
        if (!s.autoFuelPrice) return s.fuelPrice
        val c = (country ?: currentCountry)?.let { _averages.value[it] } ?: return s.fuelPrice
        return c.forType(s.fuelType) ?: s.fuelPrice
    }

    fun average(country: String?): CountryFuel? = country?.let { _averages.value[it] }

    // ---------------------------------------------------------------- EU Weekly Oil Bulletin

    private val names = mapOf(
        "Austria" to "AT", "Belgium" to "BE", "Bulgaria" to "BG", "Croatia" to "HR", "Cyprus" to "CY",
        "Czechia" to "CZ", "Czech Republic" to "CZ", "Denmark" to "DK", "Estonia" to "EE", "Finland" to "FI",
        "France" to "FR", "Germany" to "DE", "Greece" to "GR", "Hungary" to "HU", "Ireland" to "IE",
        "Italy" to "IT", "Latvia" to "LV", "Lithuania" to "LT", "Luxembourg" to "LU", "Malta" to "MT",
        "Netherlands" to "NL", "Poland" to "PL", "Portugal" to "PT", "Romania" to "RO", "Slovakia" to "SK",
        "Slovenia" to "SI", "Spain" to "ES", "Sweden" to "SE",
    )

    suspend fun refreshAverages() {
        val page = Http.get(BULLETIN_PAGE, timeoutSec = 30)
        val link = Regex("""href="(/document/download/[^"]+?filename=Weekly%20Oil%20Bulletin%20Weekly%20prices%20with%20taxes[^"]*)"""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)?.replace("&amp;", "&") ?: throw IllegalStateException("bulletin link not found")
        val (date, map) = parseBulletin(Http.getBytes("https://energy.ec.europa.eu$link"))
        if (map.size < 10) throw IllegalStateException("bulletin had only ${map.size} countries")
        _averages.value = map
        _averagesDate.value = date
        save(date, map)
    }

    /** Reads the bulletin spreadsheet: one row per country, prices per 1000 l with taxes. */
    internal fun parseBulletin(xlsx: ByteArray): Pair<String, Map<String, CountryFuel>> {
        var shared = emptyList<String>()
        var sheet = ""
        ZipInputStream(xlsx.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                when (e.name) {
                    "xl/sharedStrings.xml" -> shared = parseShared(String(z.readBytes(), Charsets.UTF_8))
                    "xl/worksheets/sheet1.xml" -> sheet = String(z.readBytes(), Charsets.UTF_8)
                }
                e = z.nextEntry
            }
        }
        val cell = Regex("""<c r="([A-Z]+)(\d+)"([^>]*?)(?:/>|>(?:<f>[^<]*</f>)?<v>([^<]*)</v></c>)""")
        val rows = HashMap<Int, HashMap<String, String>>()
        for (m in cell.findAll(sheet)) {
            val v = m.groupValues[4]
            if (v.isEmpty()) continue
            val text = if (m.groupValues[3].contains("t=\"s\"")) shared.getOrNull(v.toIntOrNull() ?: -1) ?: continue else v
            rows.getOrPut(m.groupValues[2].toInt()) { HashMap() }[m.groupValues[1]] = text
        }
        // find the columns by their headers (fall back to the usual layout)
        val header = rows[1].orEmpty()
        fun col(vararg keys: String, default: String) =
            header.entries.firstOrNull { (_, h) -> keys.any { h.contains(it, ignoreCase = true) } }?.key ?: default
        val petrolCol = col("Euro-super 95", default = "B")
        val dieselCol = col("Automotive gas oil", "Gas oil automobile", default = "C")
        val lpgCol = col("LPG", default = "G")
        val date = rows[2]?.get("A")?.toDoubleOrNull()?.let { LocalDate.of(1899, 12, 30).plusDays(it.toLong()).toString() } ?: ""
        val out = HashMap<String, CountryFuel>()
        for ((_, r) in rows) {
            val code = names[r["A"]?.trim()] ?: continue
            fun litre(c: String) = r[c]?.toDoubleOrNull()?.takeIf { it in 200.0..5000.0 }?.div(1000.0)
            out[code] = CountryFuel(litre(petrolCol), litre(dieselCol), litre(lpgCol))
        }
        return date to out
    }

    private fun parseShared(xml: String): List<String> =
        Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
            Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] }
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        }.toList()

    private fun save(date: String, map: Map<String, CountryFuel>) {
        try {
            val o = JSONObject().put("date", date)
            val c = JSONObject()
            map.forEach { (k, v) ->
                c.put(k, JSONObject().put("p", v.petrol ?: -1.0).put("d", v.diesel ?: -1.0).put("l", v.lpg ?: -1.0))
            }
            cacheFile.writeText(o.put("countries", c).toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!cacheFile.exists()) return
            val o = JSONObject(cacheFile.readText())
            val c = o.getJSONObject("countries")
            val map = HashMap<String, CountryFuel>()
            for (k in c.keys()) {
                val v = c.getJSONObject(k)
                fun p(n: String) = v.optDouble(n, -1.0).takeIf { it > 0 }
                map[k] = CountryFuel(p("p"), p("d"), p("l"))
            }
            _averages.value = map
            _averagesDate.value = o.optString("date")
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- official station prices

    /** Stations with live government prices near [pos] (Spain, Austria, France), nearest first. */
    suspend fun officialNear(pos: LatLon, country: String?, type: FuelType): List<StationFuel> = withContext(Dispatchers.IO) {
        try {
            when (country) {
                "ES" -> spain(pos)
                "AT" -> austria(pos, type)
                "FR" -> france(pos)
                else -> emptyList()
            }.sortedBy { Geo.dist(pos, it.pos) }
        } catch (e: Exception) {
            Log.w("Fuel", "stations $country: ${e.message}")
            emptyList()
        }
    }

    private val esCache = ConcurrentHashMap<String, Pair<Long, List<StationFuel>>>()

    private var esProvinces: Map<String, String>? = null // normalised name → ID

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").uppercase().replace(Regex("[^A-Z]"), "")

    /**
     * Spanish province number for [pos]: the first two digits of the postcode (Photon, then
     * Nominatim), or the province name matched against the price service's own list.
     */
    private suspend fun spainProvince(pos: LatLon): String? {
        fun id(postcode: String?) = postcode?.trim()?.take(2)?.takeIf { it.length == 2 && it.all(Char::isDigit) }
        val props = try {
            JSONObject(Http.get("https://photon.komoot.io/reverse?lat=${pos.lat}&lon=${pos.lon}&limit=1", timeoutSec = 15))
                .optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties")
        } catch (_: Exception) {
            null
        }
        id(props?.optString("postcode"))?.let { return it }
        val addr = try {
            JSONObject(Http.get("https://nominatim.openstreetmap.org/reverse?lat=${pos.lat}&lon=${pos.lon}&format=jsonv2&zoom=18&addressdetails=1", timeoutSec = 15))
                .optJSONObject("address")
        } catch (_: Exception) {
            null
        }
        id(addr?.optString("postcode"))?.let { return it }
        val names = listOfNotNull(addr?.optString("province"), props?.optString("county"), addr?.optString("county"), addr?.optString("state"))
            .filter { it.isNotBlank() }.map { norm(it) }
        if (names.isEmpty()) return null
        val list = esProvinces ?: try {
            val arr = JSONArray(Http.get(ES_URL.replace("EstacionesTerrestres", "Listados/Provincias/"), timeoutSec = 20))
            (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { norm(it.optString("Provincia")) to it.optString("IDPovincia") } }
                .also { esProvinces = it }
        } catch (_: Exception) {
            return null
        }
        return names.firstNotNullOfOrNull { n -> list[n] ?: list.entries.firstOrNull { (k, _) -> k.contains(n) || n.contains(k) }?.value }
    }

    /** Spain: the province's stations, within 15 km. */
    private suspend fun spain(pos: LatLon): List<StationFuel> {
        val province = spainProvince(pos) ?: return emptyList()
        val cached = esCache[province]
        val all = if (cached != null && System.currentTimeMillis() - cached.first < 3 * 3_600_000L) cached.second else {
            val json = JSONObject(Http.get("$ES_URL/FiltroProvincia/$province", timeoutSec = 45))
            val updated = parseDate(json.optString("Fecha"), "dd/MM/yyyy H:mm:ss", "Europe/Madrid")
            val arr = json.optJSONArray("ListaEESSPrecio") ?: JSONArray()
            fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val lat = num(o.optString("Latitud")) ?: return@mapNotNull null
                val lon = num(o.optString("Longitud (WGS84)")) ?: return@mapNotNull null
                StationFuel(
                    id = "es" + o.optString("IDEESS"),
                    name = o.optString("Rótulo").trim().lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Gasolinera" },
                    address = listOf(o.optString("Dirección"), o.optString("Localidad")).filter { it.isNotBlank() }
                        .joinToString(", ") { it.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } },
                    pos = LatLon(lat, lon),
                    petrol = num(o.optString("Precio Gasolina 95 E5")) ?: num(o.optString("Precio Gasolina 95 E10")),
                    diesel = num(o.optString("Precio Gasoleo A")),
                    lpg = num(o.optString("Precio Gases licuados del petróleo")),
                    updatedMs = updated,
                    official = true,
                )
            }
            esCache[province] = System.currentTimeMillis() to list
            list
        }
        return all.filter { Geo.dist(pos, it.pos) < 15_000 }
    }

    /** Austria (E-Control): the cheapest stations around, for one fuel type. */
    private suspend fun austria(pos: LatLon, type: FuelType): List<StationFuel> {
        val code = when (type) {
            FuelType.DIESEL -> "DIE"
            FuelType.PETROL -> "SUP"
            else -> return emptyList()
        }
        val arr = JSONArray(Http.get("$AT_URL?latitude=${pos.lat}&longitude=${pos.lon}&fuelType=$code&includeClosed=false", timeoutSec = 20))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val loc = o.optJSONObject("location") ?: return@mapNotNull null
            val price = o.optJSONArray("prices")?.optJSONObject(0)?.optDouble("amount", -1.0)?.takeIf { it > 0 }
            StationFuel(
                id = "at" + o.optLong("id"),
                name = o.optString("name").ifBlank { "Tankstelle" },
                address = listOf(loc.optString("address"), loc.optString("city")).filter { it.isNotBlank() }.joinToString(", "),
                pos = LatLon(loc.optDouble("latitude"), loc.optDouble("longitude")),
                petrol = if (type == FuelType.PETROL) price else null,
                diesel = if (type == FuelType.DIESEL) price else null,
                lpg = null,
                updatedMs = System.currentTimeMillis(),
                official = true,
            )
        }
    }

    /** France: all stations within 10 km (prix-carburants.gouv.fr open data). */
    private suspend fun france(pos: LatLon): List<StationFuel> {
        val where = URLEncoder.encode("within_distance(geom, geom'POINT(${pos.lon} ${pos.lat})', 10km)", "UTF-8")
        val url = "$FR_URL?where=$where&limit=60&select=id,adresse,ville,geom,gazole_prix,sp95_prix,e10_prix,gplc_prix,gazole_maj,sp95_maj,e10_maj"
        val arr = JSONObject(Http.get(url, timeoutSec = 25)).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val g = o.optJSONObject("geom") ?: return@mapNotNull null
            fun p(n: String) = if (o.isNull(n)) null else o.optDouble(n, -1.0).takeIf { it > 0 }
            val maj = listOf("gazole_maj", "sp95_maj", "e10_maj").mapNotNull { k -> o.optString(k).takeIf { it.length >= 19 } }.maxOrNull()
            StationFuel(
                id = "fr" + o.optLong("id"),
                name = "Station",
                address = listOf(o.optString("adresse"), o.optString("ville")).filter { it.isNotBlank() }.joinToString(", "),
                pos = LatLon(g.optDouble("lat"), g.optDouble("lon")),
                petrol = p("sp95_prix") ?: p("e10_prix"),
                diesel = p("gazole_prix"),
                lpg = p("gplc_prix"),
                updatedMs = maj?.let { parseDate(it.take(19), "yyyy-MM-dd'T'HH:mm:ss", "UTC") },
                official = true,
            )
        }
    }

    private fun parseDate(s: String, pattern: String, zone: String): Long? = try {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }.parse(s)?.time
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- prices reported by drivers

    private fun cellTopic(cell: String) = "dm1/f/$cell/+"

    /** Shares the prices you saw at a station with other DriveMap drivers (kept two weeks). */
    fun report(station: Place, petrol: Double?, diesel: Double?, lpg: Double?, by: String) {
        val key = Geo.geohash(station.pos.lat, station.pos.lon, 8)
        val now = System.currentTimeMillis()
        val s = StationFuel("u$key", station.name, station.address, station.pos, petrol, diesel, lpg, now, official = false, reportedBy = by)
        myReports[s.id] = s
        val o = JSONObject().put("n", station.name).put("a", station.address).put("la", station.pos.lat).put("lo", station.pos.lon)
            .put("p", petrol ?: -1.0).put("d", diesel ?: -1.0).put("l", lpg ?: -1.0).put("ts", now).put("by", by)
        val cell = Geo.geohash(station.pos.lat, station.pos.lon, 5)
        hub?.publish(
            "dm1/f/$cell/$key", ConvoyCrypto.encrypt(ConvoyCrypto.publicKey, o.toString().toByteArray()),
            retained = true, expirySec = REPORT_DAYS * 86_400L,
        )
    }

    /** Prices reported by drivers around [pos] (about 15 km), including your own. */
    suspend fun driverReportsNear(pos: LatLon): List<StationFuel> {
        val h = hub ?: return emptyList()
        val center = Geo.geohash(pos.lat, pos.lon, 5)
        val cells = listOf(center) + Geo.geohashWithNeighbours(center).filter { it != center }
        val found = ConcurrentHashMap<String, StationFuel>()
        h.acquire("fuel")
        try {
            for (c in cells) h.subscribe(cellTopic(c)) { topic, payload -> parseReport(topic.substringAfterLast('/'), payload)?.let { found[it.id] = it } }
            withTimeoutOrNull(6000) { h.connected.first { it } }
            delay(2500) // retained messages arrive right after subscribing
        } finally {
            for (c in cells) h.unsubscribe(cellTopic(c))
            h.release("fuel")
        }
        myReports.values.forEach { mine -> if (found[mine.id].let { it == null || (it.updatedMs ?: 0) < (mine.updatedMs ?: 0) }) found[mine.id] = mine }
        val cutoff = System.currentTimeMillis() - REPORT_DAYS * 86_400_000L
        return found.values.filter { (it.updatedMs ?: 0) > cutoff && Geo.dist(pos, it.pos) < 20_000 }
    }

    private fun parseReport(key: String, payload: ByteArray): StationFuel? {
        if (payload.isEmpty()) return null
        val plain = ConvoyCrypto.decrypt(ConvoyCrypto.publicKey, payload) ?: return null
        return try {
            val o = JSONObject(String(plain))
            fun p(n: String) = o.optDouble(n, -1.0).takeIf { it in 0.2..5.0 }
            StationFuel(
                "u$key", o.optString("n"), o.optString("a"), LatLon(o.getDouble("la"), o.getDouble("lo")),
                p("p"), p("d"), p("l"), o.optLong("ts"), official = false, reportedBy = o.optString("by").ifBlank { null },
            )
        } catch (_: Exception) {
            null
        }
    }
}
