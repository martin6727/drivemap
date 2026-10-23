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

    /** Download in progress (shown on the Update button). */
    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading

    /**
     * Download the new APK and install it through [AutoSetup], so the update stays visible in
     * Android Auto. Falls back to the browser download if that fails.
     */
    suspend fun download(context: Context) {
        if (_downloading.value) return
        _downloading.value = true
        try {
            val bytes = Http.getBytes(DOWNLOAD_URL, timeoutSec = 300)
            val dir = java.io.File(context.cacheDir, "apk").apply { mkdirs() }
            val apk = java.io.File(dir, "DriveMap-update.apk")
            if (bytes.size < 1_000_000 || bytes[0] != 'P'.code.toByte()) throw java.io.IOException("not an APK")
            apk.writeBytes(bytes)
            _available.value = null
            AutoSetup.install(context, apk)
        } catch (_: Exception) {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(DOWNLOAD_URL))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } finally {
            _downloading.value = false
        }
    }

    fun dismiss(context: Context) {
        val v = _available.value ?: return
        prefs(context).edit().putString("skipped", v).apply()
        _available.value = null
    }

    /** The daily background check. */
    suspend fun check(context: Context) {
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("checked", 0) < 24 * 3600_000L) return
        try { checkNow(context, manual = false) } catch (_: Exception) {}
    }

    /**
     * Ask GitHub for the latest release. Returns true when a newer version is out (the Update
     * prompt then shows). [manual]: the user tapped "Check for updates", so a version they
     * skipped with "Later" is offered again. Throws when GitHub can't be reached.
     */
    suspend fun checkNow(context: Context, manual: Boolean = true): Boolean {
        val p = prefs(context)
        val json = JSONObject(Http.get(API, mapOf("Accept" to "application/vnd.github+json"), timeoutSec = 15))
        p.edit().putLong("checked", System.currentTimeMillis()).apply()
        val latest = json.optString("tag_name").removePrefix("v").trim()
        val mine = currentVersion(context)
        val newer = latest.isNotEmpty() && newer(latest, mine)
        if (newer && (manual || p.getString("skipped", null) != latest)) _available.value = latest
        return newer
    }

    fun currentVersion(context: Context): String =
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

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
