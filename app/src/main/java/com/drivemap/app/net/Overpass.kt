package com.drivemap.app.net

import kotlinx.coroutines.delay

/** Overpass API with mirror fallback (the main instance is often busy). */
object Overpass {
    private val mirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    @Volatile private var preferred = 0

    suspend fun query(ql: String, attempts: Int = 4, timeoutSec: Long = 90): String {
        var lastError: Exception? = null
        for (i in 0 until attempts) {
            val idx = (preferred + i) % mirrors.size
            try {
                val text = Http.postForm(mirrors[idx], mapOf("data" to ql), timeoutSec = timeoutSec)
                if (text.contains("\"elements\"") && !text.contains("runtime error")) {
                    preferred = idx
                    return text
                }
                lastError = java.io.IOException("Overpass returned no data")
            } catch (e: Exception) {
                lastError = e
            }
            delay(1500L * (i + 1))
        }
        throw lastError ?: java.io.IOException("Overpass unavailable")
    }
}
