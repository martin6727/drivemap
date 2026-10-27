package com.drivemap.app.data

import com.drivemap.app.i18n.t
import android.content.Context
import com.drivemap.app.geo.LatLon
import com.drivemap.app.route.AvoidOptions
import com.drivemap.app.route.Place
import com.drivemap.app.route.RouteMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class FuelType(private val en: String, val unit: String, val defaultPrice: Double, val defaultConsumption: Double) {
    PETROL("Petrol", "L", 1.55, 6.5),
    DIESEL("Diesel", "L", 1.50, 5.5),
    LPG("LPG", "L", 0.80, 8.5),
    ELECTRIC("Electric", "kWh", 0.35, 17.0);

    val label: String get() = t(en)
}

enum class MapTheme(private val en: String) {
    AUTO("Auto (day/night)"), DAY("Day"), NIGHT("Night");

    val label: String get() = t(en)
}

/** How much the voice says while navigating. Radar, police and hazard warnings are spoken at every level. */
enum class VoiceLevel(private val en: String, private val hintEn: String) {
    FULL("Full", "Every turn with street names, exits and ‘continue for 5 km’"),
    NORMAL("Normal", "Short turn alerts without street names"),
    ALERTS("Alerts only", "No turn instructions, only warnings");

    val label: String get() = t(en)
    val hint: String get() = t(hintEn)
}

data class AppSettings(
    val fuelType: FuelType = FuelType.PETROL,
    val consumption: Double = 6.5,
    val fuelPrice: Double = 1.55,
    /** Use the weekly EU average of the country you are in (falls back to [fuelPrice]). */
    val autoFuelPrice: Boolean = true,
    val defaultMode: RouteMode = RouteMode.FASTEST,
    val avoid: AvoidOptions = AvoidOptions(),
    val radarAlerts: Boolean = true,
    val radarSound: Boolean = true,
    val mobileReports: Boolean = true,
    val hotspotAlerts: Boolean = true,
    val respectCameraBans: Boolean = true,
    val fineMeter: Boolean = true,
    val toleranceInFine: Boolean = true,
    val voice: Boolean = true,
    val voiceLang: String = "en-US",
    val voiceLevel: VoiceLevel = VoiceLevel.FULL,
    /** Voice profile id (see nav/VoiceProfiles); unknown ids fall back to the standard voice. */
    val voiceProfile: String = "standard",
    /** App language: "auto" (phone language), "en" or "sk". */
    val appLanguage: String = "auto",
    /** "Liquid glass" look: see-through frosted panels. */
    val glass: Boolean = false,
    /** With [glass]: bright frosted panels, light even at night. */
    val lightGlass: Boolean = false,
    /** With [glass]: clear, untinted glass (like iOS). */
    val clearGlass: Boolean = false,
    /** Route through "destination only" roads (dopravná obsluha) as if they were normal roads. */
    val throughDestOnly: Boolean = false,
    /** Your car while navigating (Car3D skin id). */
    val carSkin: String = "arrow",
    /** Navigation camera tilted (3D) or flat (2D). */
    val view3d: Boolean = true,
    val mapTheme: MapTheme = MapTheme.AUTO,
    val valhallaUrl: String = DEFAULT_VALHALLA,
    val mqttUrl: String = DEFAULT_MQTT,
    val userName: String = "",
    val memberId: String = "",
    val autoAddConvoyStops: Boolean = true,
    val shareLocation: Boolean = true,
    /** Colour of the route line (ARGB). */
    val routeColor: Int = ROUTE_COLORS.first().first,
) {
    /**
     * The language actually spoken: a voice profile can speak its own language (e.g. the English
     * rally co-driver); otherwise the voice language chosen in settings. Used for everything said
     * and for the route's spoken instructions, so nothing is mixed.
     */
    val spokenLang: String get() = com.drivemap.app.nav.VoiceProfiles.byId(voiceProfile).language ?: voiceLang

    companion object {
        const val DEFAULT_VALHALLA = "https://valhalla1.openstreetmap.de"
        const val DEFAULT_MQTT = "ssl://broker.hivemq.com:8883"

        /** Route line colours offered in Settings (Google blue first). */
        val ROUTE_COLORS: List<Pair<Int, String>> = listOf(
            0xFF1A73E8.toInt() to "Blue",
            0xFF9334E6.toInt() to "Purple",
            0xFF0F9D58.toInt() to "Green",
            0xFF00897B.toInt() to "Teal",
            0xFFF57C00.toInt() to "Orange",
            0xFFD93025.toInt() to "Red",
            0xFFE91E63.toInt() to "Pink",
            0xFFF9AB00.toInt() to "Yellow",
        )
    }
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value get() = _state.value

    private fun load(): AppSettings {
        val d = AppSettings()
        var memberId = p.getString("memberId", "") ?: ""
        if (memberId.isBlank()) {
            memberId = UUID.randomUUID().toString().replace("-", "").take(12)
            p.edit().putString("memberId", memberId).apply()
        }
        val lang = p.getString("voiceLang", null) ?: defaultVoiceLang()
        return AppSettings(
            fuelType = enumOr(p.getString("fuelType", null), d.fuelType),
            consumption = p.getFloat("consumption", d.consumption.toFloat()).toDouble(),
            fuelPrice = p.getFloat("fuelPrice", d.fuelPrice.toFloat()).toDouble(),
            autoFuelPrice = p.getBoolean("autoFuelPrice", true),
            defaultMode = enumOr(p.getString("defaultMode", null), d.defaultMode),
            avoid = AvoidOptions(
                tolls = p.getBoolean("avoidTolls", false),
                motorways = p.getBoolean("avoidMotorways", false),
                ferries = p.getBoolean("avoidFerries", false),
                unpaidVignettes = p.getBoolean("avoidUnpaidVignettes", true),
            ),
            radarAlerts = p.getBoolean("radarAlerts", true),
            radarSound = p.getBoolean("radarSound", true),
            mobileReports = p.getBoolean("mobileReports", true),
            hotspotAlerts = p.getBoolean("hotspotAlerts", true),
            respectCameraBans = p.getBoolean("respectCameraBans", true),
            fineMeter = p.getBoolean("fineMeter", true),
            toleranceInFine = p.getBoolean("toleranceInFine", true),
            voice = p.getBoolean("voice", true),
            voiceLang = lang,
            voiceLevel = enumOr(p.getString("voiceLevel", null), d.voiceLevel),
            voiceProfile = p.getString("voiceProfile", null) ?: "standard",
            appLanguage = p.getString("appLanguage", "auto") ?: "auto",
            glass = p.getBoolean("glass", false),
            lightGlass = p.getBoolean("lightGlass", false),
            clearGlass = p.getBoolean("clearGlass", false),
            throughDestOnly = p.getBoolean("throughDestOnly", false),
            carSkin = p.getString("carSkin", "arrow") ?: "arrow",
            view3d = p.getBoolean("view3d", true),
            mapTheme = enumOr(p.getString("mapTheme", null), d.mapTheme),
            valhallaUrl = p.getString("valhallaUrl", d.valhallaUrl) ?: d.valhallaUrl,
            mqttUrl = p.getString("mqttUrl", d.mqttUrl) ?: d.mqttUrl,
            userName = p.getString("userName", "") ?: "",
            memberId = memberId,
            autoAddConvoyStops = p.getBoolean("autoAddConvoyStops", true),
            shareLocation = p.getBoolean("shareLocation", true),
            routeColor = p.getInt("routeColor", d.routeColor),
        )
    }

    private fun defaultVoiceLang(): String = when (Locale.getDefault().language) {
        "sk" -> "sk-SK"
        "cs" -> "cs-CZ"
        "de" -> "de-DE"
        "hu" -> "hu-HU"
        "pl" -> "pl-PL"
        else -> "en-US"
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, d: T): T =
        try { if (name == null) d else enumValueOf<T>(name) } catch (_: Exception) { d }

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_state.value)
        p.edit()
            .putString("fuelType", s.fuelType.name)
            .putFloat("consumption", s.consumption.toFloat())
            .putFloat("fuelPrice", s.fuelPrice.toFloat())
            .putBoolean("autoFuelPrice", s.autoFuelPrice)
            .putString("defaultMode", s.defaultMode.name)
            .putBoolean("avoidTolls", s.avoid.tolls)
            .putBoolean("avoidMotorways", s.avoid.motorways)
            .putBoolean("avoidFerries", s.avoid.ferries)
            .putBoolean("avoidUnpaidVignettes", s.avoid.unpaidVignettes)
            .putBoolean("radarAlerts", s.radarAlerts)
            .putBoolean("radarSound", s.radarSound)
            .putBoolean("mobileReports", s.mobileReports)
            .putBoolean("hotspotAlerts", s.hotspotAlerts)
            .putBoolean("respectCameraBans", s.respectCameraBans)
            .putBoolean("fineMeter", s.fineMeter)
            .putBoolean("toleranceInFine", s.toleranceInFine)
            .putBoolean("voice", s.voice)
            .putString("voiceLang", s.voiceLang)
            .putString("voiceLevel", s.voiceLevel.name)
            .putString("voiceProfile", s.voiceProfile)
            .putString("appLanguage", s.appLanguage)
            .putBoolean("glass", s.glass)
            .putBoolean("lightGlass", s.lightGlass)
            .putBoolean("clearGlass", s.clearGlass)
            .putBoolean("throughDestOnly", s.throughDestOnly)
            .putString("carSkin", s.carSkin)
            .putBoolean("view3d", s.view3d)

            .putString("mapTheme", s.mapTheme.name)
            .putString("valhallaUrl", s.valhallaUrl)
            .putString("mqttUrl", s.mqttUrl)
            .putString("userName", s.userName)
            .putBoolean("autoAddConvoyStops", s.autoAddConvoyStops)
            .putBoolean("shareLocation", s.shareLocation)
            .putInt("routeColor", s.routeColor)
            .apply()
        _state.value = s
    }
}

/** Home / work / favourites / recent searches. */
class PlacesStore(context: Context) {
    private val p = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val _recents = MutableStateFlow(read("recents"))
    private val _favorites = MutableStateFlow(read("favorites"))
    private val _home = MutableStateFlow(read("home").firstOrNull())
    private val _work = MutableStateFlow(read("work").firstOrNull())
    val recents: StateFlow<List<Place>> = _recents
    val favorites: StateFlow<List<Place>> = _favorites
    val home: StateFlow<Place?> = _home
    val work: StateFlow<Place?> = _work

    private fun read(key: String): List<Place> = try {
        val arr = JSONArray(p.getString(key, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Place(o.getString("n"), LatLon(o.getDouble("la"), o.getDouble("lo")), o.optString("a"), o.optString("c"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(key: String, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("la", it.pos.lat).put("lo", it.pos.lon).put("a", it.address).put("c", it.category)) }
        p.edit().putString(key, arr.toString()).apply()
    }

    private fun same(a: Place, b: Place) = a.name == b.name && Math.abs(a.pos.lat - b.pos.lat) < 1e-4 && Math.abs(a.pos.lon - b.pos.lon) < 1e-4

    fun addRecent(place: Place) {
        val list = (listOf(place) + _recents.value.filterNot { same(it, place) }).take(15)
        write("recents", list); _recents.value = list
    }

    fun toggleFavorite(place: Place) {
        val cur = _favorites.value
        val list = if (cur.any { same(it, place) }) cur.filterNot { same(it, place) } else cur + place
        write("favorites", list); _favorites.value = list
    }

    fun isFavorite(place: Place) = _favorites.value.any { same(it, place) }

    fun setHome(place: Place?) { write("home", listOfNotNull(place)); _home.value = place }
    fun setWork(place: Place?) { write("work", listOfNotNull(place)); _work.value = place }
}
