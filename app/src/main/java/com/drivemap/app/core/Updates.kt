package com.drivemap.app.core

import android.content.Context
import com.drivemap.app.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * New-version check against the GitHub releases page (where friends download DriveMap).
 * At most once a day; a newer release shows a one-tap "Update" prompt.
 */
object Updates {
    const val DOWNLOAD_URL = "https://github.com/martin6727/drivemap/releases/latest/download/DriveMap.apk"
    private const val API = "https://api.github.com/repos/martin6727/drivemap/releases/latest"

    /** Version name of a newer release, while the prompt is waiting. */
    private val _available = MutableStateFlow<String?>(null)
    val available: StateFlow<String?> = _available

    fun dismiss(context: Context) {
        val v = _available.value ?: return
        prefs(context).edit().putString("skipped", v).apply()
        _available.value = null
    }

    suspend fun check(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong("checked", 0) < 24 * 3600_000L) return
        try {
            val json = JSONObject(Http.get(API, mapOf("Accept" to "application/vnd.github+json"), timeoutSec = 15))
            p.edit().putLong("checked", now).apply()
            val latest = json.optString("tag_name").removePrefix("v").trim()
            val mine = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return
            if (latest.isNotEmpty() && newer(latest, mine) && p.getString("skipped", null) != latest) _available.value = latest
        } catch (_: Exception) {
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** "1.20" > "1.19", "2.0" > "1.99" */
    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
