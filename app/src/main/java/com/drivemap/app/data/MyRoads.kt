package com.drivemap.app.data

import android.content.Context
import com.drivemap.app.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A road you can drive although the map marks it private, closed or as a bad track. */
data class MyRoad(val id: String, val name: String, val pos: LatLon)

/**
 * "Your roads": the route planner also tries a route through each of these when one lies on the
 * way, and takes it when it's clearly shorter (see RoutePlanner). Fixes wrong map data right away
 * instead of waiting for OpenStreetMap to be corrected.
 */
class MyRoadsStore(context: Context) {
    private val prefs = context.getSharedPreferences("myroads", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<MyRoad>> = _items

    val value get() = _items.value

    fun add(name: String, pos: LatLon) {
        val r = MyRoad("r" + System.currentTimeMillis(), name.ifBlank { String.format("%.5f, %.5f", pos.lat, pos.lon) }, pos)
        _items.value = _items.value + r
        save()
    }

    fun remove(id: String) {
        _items.value = _items.value.filter { it.id != id }
        save()
    }

    private fun load(): List<MyRoad> {
        val raw = prefs.getString("roads", null) ?: return DEFAULTS
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                MyRoad(o.getString("id"), o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")))
            }
        } catch (_: Exception) {
            DEFAULTS
        }
    }

    private fun save() {
        val a = JSONArray()
        _items.value.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.pos.lat).put("lon", it.pos.lon)) }
        prefs.edit().putString("roads", a.toString()).apply()
    }

    companion object {
        /**
         * Straight on along Vodná past SUNOB into the industrial area on Bratislavská, Nitra
         * (OSM marks its start "destination only", so routes turned left onto the gravel instead).
         */
        val DEFAULTS = listOf(MyRoad("nitra-sunob", "Vodná – rovno popri SUNOB, Nitra", LatLon(48.31700, 18.06920)))
    }
}
