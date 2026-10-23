package com.drivemap.app.data

import com.drivemap.app.i18n.t

/**
 * Static per-country data: names, currencies (approximate 2026 rates), default speed limits
 * for cars and rough per-km motorway toll rates for countries without vignettes.
 */
object Countries {

    private val names = mapOf(
        "SK" to "Slovakia", "CZ" to "Czechia", "AT" to "Austria", "HU" to "Hungary", "PL" to "Poland",
        "DE" to "Germany", "SI" to "Slovenia", "HR" to "Croatia", "IT" to "Italy", "CH" to "Switzerland",
        "FR" to "France", "RO" to "Romania", "BG" to "Bulgaria", "MD" to "Moldova", "RS" to "Serbia",
        "UA" to "Ukraine", "BA" to "Bosnia and Herzegovina", "ME" to "Montenegro", "MK" to "North Macedonia",
        "AL" to "Albania", "GR" to "Greece", "ES" to "Spain", "PT" to "Portugal", "NL" to "Netherlands",
        "BE" to "Belgium", "LU" to "Luxembourg", "DK" to "Denmark", "SE" to "Sweden", "NO" to "Norway",
        "LT" to "Lithuania", "LV" to "Latvia", "EE" to "Estonia", "GB" to "United Kingdom", "IE" to "Ireland",
        "TR" to "Türkiye", "LI" to "Liechtenstein", "FI" to "Finland",
    )

    fun name(code: String?): String = if (code == null) t("Unknown") else names[code]?.let { t(it) } ?: code


    /** Flag emoji from ISO alpha-2 code. */
    fun flag(code: String?): String {
        if (code == null || code.length != 2) return "🏳"
        val a = Character.toChars(0x1F1E6 + (code[0].uppercaseChar() - 'A'))
        val b = Character.toChars(0x1F1E6 + (code[1].uppercaseChar() - 'A'))
        return String(a) + String(b)
    }

    /** Units of currency per 1 EUR (approximate, autumn 2026). */
    private val perEur = mapOf(
        "EUR" to 1.0, "CZK" to 24.4, "HUF" to 380.0, "PLN" to 4.25, "CHF" to 0.94, "RON" to 5.09,
        "MDL" to 19.6, "BGN" to 1.95583, "RSD" to 117.0, "SEK" to 11.2, "NOK" to 11.7, "DKK" to 7.46,
        "GBP" to 0.86, "BAM" to 1.95583, "MKD" to 61.5, "ALL" to 98.0, "UAH" to 47.0, "TRY" to 45.0,
    )

    fun toEur(amount: Double, currency: String): Double = amount / (perEur[currency] ?: 1.0)

    // ---------------------------------------------------------------- speed limits

    /** Default limits (km/h) for cars: urban, rural, expressway, motorway. -1 = no general limit. */
    private data class Limits(val urban: Int, val rural: Int, val expressway: Int, val motorway: Int)

    private val limits = mapOf(
        "SK" to Limits(50, 90, 130, 130),
        "CZ" to Limits(50, 90, 110, 130),
        "AT" to Limits(50, 100, 100, 130),
        "HU" to Limits(50, 90, 110, 130),
        "PL" to Limits(50, 90, 120, 140),
        "DE" to Limits(50, 100, 100, -1),
        "SI" to Limits(50, 90, 110, 130),
        "HR" to Limits(50, 90, 110, 130),
        "IT" to Limits(50, 90, 110, 130),
        "CH" to Limits(50, 80, 100, 120),
        "FR" to Limits(50, 80, 110, 130),
        "RO" to Limits(50, 90, 100, 130),
        "BG" to Limits(50, 90, 120, 140),
        "RS" to Limits(50, 80, 100, 130),
        "MD" to Limits(50, 90, 90, 110),
        "UA" to Limits(50, 90, 110, 130),
        "GR" to Limits(50, 90, 110, 130),
        "ES" to Limits(50, 90, 120, 120),
        "PT" to Limits(50, 90, 100, 120),
        "NL" to Limits(50, 80, 100, 100),
        "BE" to Limits(50, 70, 120, 120),
        "DK" to Limits(50, 80, 90, 130),
        "SE" to Limits(50, 70, 90, 110),
        "NO" to Limits(50, 80, 90, 110),
        "GB" to Limits(48, 96, 112, 112),
    )
    private val defaultLimits = Limits(50, 90, 110, 130)

    /**
     * Implied limit for a road with no posted maxspeed.
     * [roadClass] uses Valhalla classes (motorway, trunk, primary, ...).
     */
    fun defaultLimit(country: String?, roadClass: String, urban: Boolean, tollOrExpress: Boolean): Int {
        val l = limits[country] ?: defaultLimits
        return when {
            roadClass == "motorway" -> if (urban && l.motorway > 0) minOf(l.motorway, 90) else l.motorway
            roadClass == "trunk" && tollOrExpress -> if (urban) minOf(l.expressway, 80) else l.expressway
            urban -> l.urban
            roadClass == "residential" || roadClass == "service_other" -> l.urban
            else -> l.rural
        }
    }

    // ---------------------------------------------------------------- tolls

    /** Countries where motorways need a time-based vignette instead of per-km tolls. */
    val vignetteCountries = setOf("SK", "CZ", "AT", "HU", "SI", "CH", "BG", "RO", "MD")

    /** Rough average car toll per km on tolled motorways (EUR). Estimates only. */
    private val tollPerKm = mapOf(
        "HR" to 0.07, "IT" to 0.075, "FR" to 0.10, "ES" to 0.10, "PT" to 0.09, "PL" to 0.07,
        "RS" to 0.045, "GR" to 0.07, "MK" to 0.03, "BA" to 0.05, "ME" to 0.05, "AL" to 0.04,
        "TR" to 0.03, "IE" to 0.06, "NO" to 0.06, "GB" to 0.10, "DK" to 0.05, "SE" to 0.05,
    )

    fun tollRate(country: String?): Double = tollPerKm[country] ?: 0.06

    /**
     * Countries where warning about speed camera locations while driving is illegal.
     * Germany (StVO §23 1c), Switzerland (SVG Art. 98a), France (radar warning ban; only "danger zones"
     * are allowed) and Cyprus.
     */
    val cameraWarningBanned = setOf("DE", "CH", "FR", "CY")
}
