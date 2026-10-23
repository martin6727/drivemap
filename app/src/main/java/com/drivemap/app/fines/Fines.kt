package com.drivemap.app.fines

import com.drivemap.app.i18n.t
import com.drivemap.app.data.Countries
import kotlin.math.floor

/**
 * One row of a fine table. [minOver]..[maxOver] are km/h over the limit (after the measurement
 * tolerance is deducted), inclusive. [amount] is the typical/fixed fine, [min]..[max] the range a
 * police officer or authority can impose.
 */
data class FineBand(
    val minOver: Int,
    val maxOver: Int,
    val amount: Double,
    val min: Double = amount,
    val max: Double = amount,
    val points: String? = null,
    val ban: String? = null,
)

data class FineEstimate(
    val country: String,
    val limit: Int,
    val speed: Int,
    /** km/h over the limit after the tolerance deduction. */
    val overAfterTolerance: Int,
    val toleranceKmh: Int,
    val amountEur: Double,
    val minEur: Double,
    val maxEur: Double,
    val points: String?,
    val ban: String?,
    val basis: String,
    val currency: String,
    val amountLocal: Double,
    val roughEstimate: Boolean,
) {
    val isSpeeding get() = speed > limit
    val fined get() = amountEur > 0.0 || maxEur > 0.0
}

private typealias Tol = (Double) -> Double

private val tol3: Tol = { v -> if (v <= 100) 3.0 else v * 0.03 }
private val tol5: Tol = { v -> if (v < 100) 5.0 else v * 0.05 }
private val tolNone: Tol = { 0.0 }

private class Schedule(
    val country: String,
    val currency: String,
    val basis: String,
    val tolerance: Tol,
    val rough: Boolean = false,
    val bands: (limit: Int, urban: Boolean, motorway: Boolean) -> List<FineBand>,
)

private fun b(min: Int, max: Int, amount: Double, lo: Double = amount, hi: Double = amount, pts: String? = null, ban: String? = null) =
    FineBand(min, max, amount, lo, hi, pts, ban)

private const val OPEN = 999

/**
 * Speeding fine schedules. Values researched in October 2026; they are estimates, not legal advice.
 *
 * - SK: fixed camera fines for the vehicle holder (§ 139a z. 8/2009, aksamec.sk 2026 table); police
 *   on-the-spot ranges incl. the 1 Sep 2026 amendment (top band +250/+200 €).
 * - CZ: 2024 points reform (Cebia / Policie ČR 2026).
 * - AT: ÖAMTC guide values + Raserpaket minimums (uniform catalogue only from Jan 2027).
 * - HU: 410/2007 Korm. rendelet, Annex 1 (as of Oct 2026).
 * - PL: 2022 taryfikator (unchanged in 2026). DE: BKatV (2021 catalogue).
 * - CH: Ordnungsbussen. IT: Codice della strada. FR: forfaits.
 */
private val schedules: Map<String, Schedule> = listOf(
    Schedule("SK", "EUR", "Camera fine (fixed, vehicle holder); police on the spot shown as range", tol3) { _, urban, _ ->
        if (urban) listOf(
            b(1, 5, 0.0, 0.0, 50.0),
            b(6, 10, 15.0, 0.0, 50.0),
            b(11, 15, 39.0, 0.0, 50.0),
            b(16, 20, 66.0, 0.0, 50.0),
            b(21, 25, 96.0, 30.0, 400.0),
            b(26, 30, 141.0, 30.0, 400.0),
            b(31, 35, 201.0, 30.0, 400.0),
            b(36, 40, 270.0, 30.0, 400.0),
            b(41, 45, 342.0, 30.0, 400.0),
            b(46, 50, 402.0, 30.0, 400.0),
            b(51, 55, 471.0, 500.0, 1000.0, ban = "Possible ban 6 months – 3 years"),
            b(56, 60, 540.0, 500.0, 1000.0, ban = "Possible ban 6 months – 3 years"),
            b(61, 65, 600.0, 500.0, 1000.0, ban = "Possible ban 6 months – 3 years"),
            b(66, 70, 699.0, 500.0, 1000.0, ban = "Possible ban 6 months – 3 years"),
            b(71, OPEN, 798.0, 500.0, 1300.0, ban = "Possible ban 6 months – 3 years"),
        ) else listOf(
            b(1, 10, 0.0, 0.0, 50.0),
            b(11, 15, 30.0, 0.0, 50.0),
            b(16, 20, 42.0, 0.0, 50.0),
            b(21, 25, 66.0, 0.0, 50.0),
            b(26, 30, 99.0, 0.0, 50.0),
            b(31, 35, 162.0, 30.0, 400.0),
            b(36, 40, 201.0, 30.0, 400.0),
            b(41, 45, 270.0, 30.0, 400.0),
            b(46, 50, 342.0, 30.0, 400.0),
            b(51, 55, 402.0, 30.0, 400.0),
            b(56, 60, 471.0, 30.0, 400.0),
            b(61, 65, 540.0, 500.0, 1000.0, ban = "Possible ban 6 months – 3 years"),
            b(66, 70, 600.0, 500.0, 1000.0, ban = "Possible ban 6 months – 3 years"),
            b(71, 75, 699.0, 500.0, 1000.0, ban = "Possible ban 6 months – 3 years"),
            b(76, OPEN, 798.0, 500.0, 1300.0, ban = "Possible ban 6 months – 3 years"),
        )
    },
    Schedule("CZ", "CZK", "On-the-spot fine (police); higher in administrative proceedings", tol3) { _, urban, _ ->
        if (urban) listOf(
            b(1, 9, 1000.0, 0.0, 1500.0),
            b(10, 19, 2000.0, 1500.0, 2000.0, pts = "2 points"),
            b(20, 39, 3000.0, 2500.0, 3500.0, pts = "4 points"),
            b(40, OPEN, 10000.0, 7000.0, 25000.0, pts = "6 points", ban = "Ban 6–18 months"),
        ) else listOf(
            b(1, 9, 1000.0, 0.0, 1500.0),
            b(10, 29, 2000.0, 1500.0, 2000.0, pts = "2 points"),
            b(30, 49, 3000.0, 2500.0, 3500.0, pts = "4 points"),
            b(50, OPEN, 10000.0, 7000.0, 25000.0, pts = "6 points", ban = "Ban 6–18 months"),
        )
    },
    Schedule("AT", "EUR", "Typical Anonymverfügung / Organmandat (varies by state until 2027)", tol3) { _, urban, _ ->
        val susp1 = "Licence withdrawn ≥ 1 month"
        val susp3 = "Licence withdrawn ≥ 3 months, car can be seized for 2 weeks"
        val seize = "Licence ≥ 3 months + retraining; car can be confiscated"
        if (urban) listOf(
            b(1, 10, 30.0, 20.0, 40.0),
            b(11, 20, 60.0, 45.0, 100.0),
            b(21, 30, 100.0, 60.0, 190.0),
            b(31, 40, 180.0, 150.0, 300.0),
            b(41, 60, 400.0, 300.0, 5000.0, ban = susp1),
            b(61, 80, 700.0, 500.0, 7500.0, ban = susp3),
            b(81, OPEN, 1000.0, 500.0, 7500.0, ban = seize),
        ) else listOf(
            b(1, 10, 30.0, 20.0, 40.0),
            b(11, 20, 60.0, 45.0, 100.0),
            b(21, 30, 100.0, 60.0, 190.0),
            b(31, 50, 180.0, 150.0, 300.0),
            b(51, 70, 400.0, 300.0, 5000.0, ban = susp1),
            b(71, 90, 700.0, 500.0, 7500.0, ban = susp3),
            b(91, OPEN, 1000.0, 500.0, 7500.0, ban = seize),
        )
    },
    Schedule("HU", "HUF", "Fixed administrative fine (cameras, 410/2007 Annex 1)", tol3) { limit, _, _ ->
        when {
            limit <= 50 -> listOf(
                b(16, 25, 50000.0), b(26, 35, 70000.0), b(36, 45, 100000.0), b(46, 55, 140000.0),
                b(56, 65, 210000.0), b(66, 75, 312000.0), b(76, OPEN, 468000.0),
            )
            limit <= 100 -> listOf(
                b(16, 30, 50000.0), b(31, 45, 70000.0), b(46, 60, 100000.0), b(61, 75, 140000.0),
                b(76, 90, 210000.0), b(91, 105, 312000.0), b(106, OPEN, 468000.0),
            )
            else -> listOf(
                b(21, 35, 50000.0), b(36, 50, 70000.0), b(51, 65, 100000.0), b(66, 80, 140000.0),
                b(81, 95, 210000.0), b(96, 110, 315000.0), b(111, OPEN, 468000.0),
            )
        }
    },
    Schedule("PL", "PLN", "Mandate (taryfikator); doubled for repeat offences over 30 km/h", tolNone) { _, _, _ ->
        val ban = "Licence suspended 3 months (built-up area or single-carriageway road)"
        listOf(
            b(1, 10, 50.0, pts = "1 point"),
            b(11, 15, 100.0, pts = "2 points"),
            b(16, 20, 200.0, pts = "3 points"),
            b(21, 25, 300.0, pts = "5 points"),
            b(26, 30, 400.0, pts = "7 points"),
            b(31, 40, 800.0, 800.0, 1600.0, pts = "9 points"),
            b(41, 50, 1000.0, 1000.0, 2000.0, pts = "11 points"),
            b(51, 60, 1500.0, 1500.0, 3000.0, pts = "13 points", ban = ban),
            b(61, 70, 2000.0, 2000.0, 4000.0, pts = "14 points", ban = ban),
            b(71, OPEN, 2500.0, 2500.0, 5000.0, pts = "15 points", ban = ban),
        )
    },
    Schedule("DE", "EUR", "Bußgeldkatalog (BKatV)", tol3) { _, urban, _ ->
        if (urban) listOf(
            b(1, 10, 30.0), b(11, 15, 50.0), b(16, 20, 70.0),
            b(21, 25, 115.0, pts = "1 point"),
            b(26, 30, 180.0, pts = "1 point", ban = "1 month if repeated within a year"),
            b(31, 40, 260.0, pts = "2 points", ban = "1 month"),
            b(41, 50, 400.0, pts = "2 points", ban = "1 month"),
            b(51, 60, 560.0, pts = "2 points", ban = "2 months"),
            b(61, 70, 700.0, pts = "2 points", ban = "3 months"),
            b(71, OPEN, 800.0, pts = "2 points", ban = "3 months"),
        ) else listOf(
            b(1, 10, 20.0), b(11, 15, 40.0), b(16, 20, 60.0),
            b(21, 25, 100.0, pts = "1 point"),
            b(26, 30, 150.0, pts = "1 point"),
            b(31, 40, 200.0, pts = "1 point", ban = "1 month if repeated within a year"),
            b(41, 50, 320.0, pts = "2 points", ban = "1 month"),
            b(51, 60, 480.0, pts = "2 points", ban = "1 month"),
            b(61, 70, 600.0, pts = "2 points", ban = "2 months"),
            b(71, OPEN, 700.0, pts = "2 points", ban = "3 months"),
        )
    },
    Schedule("CH", "CHF", "Ordnungsbusse; above the table a court sets a fine based on income", { v ->
        when {
            v <= 100 -> 5.0
            v <= 150 -> 6.0
            else -> 7.0
        }
    }) { _, urban, motorway ->
        when {
            urban -> listOf(
                b(1, 5, 40.0), b(6, 10, 120.0), b(11, 15, 250.0),
                b(16, 20, 600.0, 400.0, 2000.0, ban = "Court case, warning or licence withdrawal"),
                b(21, 24, 1200.0, 600.0, 4000.0, ban = "Licence ≥ 1 month"),
                b(25, 49, 3000.0, 1000.0, 10000.0, ban = "Licence ≥ 3 months"),
                b(50, OPEN, 10000.0, 5000.0, 50000.0, ban = "Speeding offender ('Raser'): licence ≥ 2 years, prison possible"),
            )
            motorway -> listOf(
                b(1, 5, 20.0), b(6, 10, 60.0), b(11, 15, 120.0), b(16, 20, 180.0), b(21, 25, 260.0),
                b(26, 30, 600.0, 400.0, 2000.0, ban = "Court case, licence withdrawal possible"),
                b(31, 34, 1200.0, 600.0, 4000.0, ban = "Licence ≥ 1 month"),
                b(35, 79, 3000.0, 1000.0, 10000.0, ban = "Licence ≥ 3 months"),
                b(80, OPEN, 10000.0, 5000.0, 50000.0, ban = "Speeding offender ('Raser'): licence ≥ 2 years, prison possible"),
            )
            else -> listOf(
                b(1, 5, 40.0), b(6, 10, 100.0), b(11, 15, 160.0), b(16, 20, 240.0),
                b(21, 25, 600.0, 400.0, 2000.0, ban = "Court case, licence withdrawal possible"),
                b(26, 29, 1200.0, 600.0, 4000.0, ban = "Licence ≥ 1 month"),
                b(30, 59, 3000.0, 1000.0, 10000.0, ban = "Licence ≥ 3 months"),
                b(60, OPEN, 10000.0, 5000.0, 50000.0, ban = "Speeding offender ('Raser'): licence ≥ 2 years, prison possible"),
            )
        }
    },
    Schedule("IT", "EUR", "Codice della strada (−30% if paid within 5 days; +1/3 at night)", tol5) { _, _, _ ->
        listOf(
            b(1, 10, 42.0, 42.0, 173.0),
            b(11, 40, 173.0, 173.0, 695.0, pts = "3 points"),
            b(41, 60, 543.0, 543.0, 2170.0, pts = "6 points", ban = "Licence 1–3 months"),
            b(61, OPEN, 845.0, 845.0, 3382.0, pts = "10 points", ban = "Licence 6–12 months"),
        )
    },
    Schedule("FR", "EUR", "Amende forfaitaire", tol5) { limit, _, _ ->
        val small = if (limit <= 50) 135.0 else 68.0
        listOf(
            b(1, 19, small, pts = "1 point"),
            b(20, 29, 135.0, pts = "2 points"),
            b(30, 39, 135.0, pts = "3 points", ban = "Suspension up to 3 years possible"),
            b(40, 49, 135.0, pts = "4 points", ban = "Licence suspended up to 3 years"),
            b(50, OPEN, 1500.0, 1500.0, 3750.0, pts = "6 points", ban = "Licence suspended, car can be seized"),
        )
    },
).associateBy { it.country }

private val generic = Schedule("??", "EUR", "Rough European average (no table for this country)", tol3, rough = true) { _, urban, _ ->
    val extra = if (urban) 1.3 else 1.0
    listOf(
        b(1, 10, 30.0 * extra), b(11, 20, 80.0 * extra), b(21, 30, 150.0 * extra),
        b(31, 40, 300.0 * extra), b(41, 50, 500.0 * extra, ban = "Licence suspension likely"),
        b(51, OPEN, 800.0 * extra, ban = "Licence suspension likely"),
    )
}

object FineEstimator {

    fun hasTable(country: String?) = country != null && schedules.containsKey(country)

    fun estimate(country: String?, speedKmh: Double, limit: Int, urban: Boolean, motorway: Boolean, applyTolerance: Boolean = true): FineEstimate? {
        if (limit <= 0) return null
        val s = schedules[country] ?: generic
        val tol = if (applyTolerance) s.tolerance(speedKmh) else 0.0
        val tolerance = floor(tol + 0.5).toInt()
        val speed = speedKmh.toInt()
        val over = floor(speedKmh - tol - limit).toInt()
        val bands = s.bands(limit, urban, motorway)
        val band = if (over >= 1) bands.firstOrNull { over in it.minOver..it.maxOver }
            ?: bands.lastOrNull { over > it.maxOver }?.takeIf { it.maxOver == OPEN } else null
        val amount = band?.amount ?: 0.0
        return FineEstimate(
            country = country ?: "??",
            limit = limit,
            speed = speed,
            overAfterTolerance = over.coerceAtLeast(0),
            toleranceKmh = tolerance,
            amountEur = Countries.toEur(amount, s.currency),
            minEur = Countries.toEur(band?.min ?: 0.0, s.currency),
            maxEur = Countries.toEur(band?.max ?: 0.0, s.currency),
            points = band?.points?.let { t(it) },
            ban = band?.ban?.let { t(it) },
            basis = t(s.basis),
            currency = s.currency,
            amountLocal = amount,
            roughEstimate = s.rough,
        )
    }

    /** Full table for the details sheet: (label, fine text) rows. */
    fun table(country: String?, limit: Int, urban: Boolean, motorway: Boolean): List<Pair<String, String>> {
        val s = schedules[country] ?: generic
        return s.bands(limit, urban, motorway).map { band ->
            val range = if (band.maxOver >= OPEN) "${band.minOver}+ km/h" else "${band.minOver}–${band.maxOver} km/h"
            val eur = Countries.toEur(band.amount, s.currency)
            val txt = buildString {
                append(if (eur > 0) String.format("€%.0f", eur) else "€0")
                if (band.max > band.amount) append(" (").append(t("up to {0}", String.format("€%.0f", Countries.toEur(band.max, s.currency)))).append(")")
                band.points?.let { append(" · ").append(t(it)) }
                band.ban?.let { append(" · ").append(t(it)) }
            }
            range to txt
        }
    }

    fun basis(country: String?) = t((schedules[country] ?: generic).basis)

}
