package com.drivemap.app.vignette

import com.drivemap.app.i18n.t
import com.drivemap.app.data.Countries
import java.time.DayOfWeek
import java.time.LocalDate

/** How long a vignette product is valid, counted from its start date (start day included). */
sealed class Validity {
    data class Days(val days: Int) : Validity()
    data class Months(val months: Int) : Validity()
    /** Annual vignette tied to a calendar year: 1 Dec of the previous year to 31 Jan of the next. */
    object CalendarYear14 : Validity()
    /** Annual vignette valid until 31 Jan of the following year (Hungary). */
    object UntilJan31 : Validity()
    /** Friday–Sunday (Bulgaria). */
    object Weekend : Validity()

    /** Last valid day (inclusive) for a vignette starting on [start]. */
    fun endDate(start: LocalDate): LocalDate = when (this) {
        is Days -> start.plusDays((days - 1).toLong())
        is Months -> start.plusMonths(months.toLong())
        CalendarYear14 -> {
            // A vignette bought in December counts for the next year.
            val year = if (start.monthValue == 12) start.year + 1 else start.year
            LocalDate.of(year + 1, 1, 31)
        }
        UntilJan31 -> LocalDate.of(start.year + 1, 1, 31)
        Weekend -> {
            var d = start
            while (d.dayOfWeek != DayOfWeek.SUNDAY) d = d.plusDays(1)
            d
        }
    }
}

data class VignetteProduct(
    val id: String,
    private val labelEn: String,
    val price: Double,
    val currency: String,
    val validity: Validity,
) {
    val label: String get() = t(labelEn)
    val priceEur get() = Countries.toEur(price, currency)
    fun priceText(): String = if (currency == "EUR") String.format("€%.2f", price)
    else String.format("%,.0f %s (≈ €%.2f)", price, currency, priceEur)
}

data class VignetteCountry(
    val code: String,
    val products: List<VignetteProduct>,
    private val noteEn: String,
    val buyUrl: String,
) {
    val note: String get() = t(noteEn)
}


/**
 * Car (≤ 3.5 t) vignette prices for 2026. Sources: autotrip.cz 2026 overview (Apr–Oct 2026),
 * Hungarian/Slovenian/Bulgarian operator figures as quoted there. Romania/Moldova are approximate.
 */
object VignetteCatalog {
    val countries: List<VignetteCountry> = listOf(
        VignetteCountry(
            "SK", listOf(
                VignetteProduct("sk1", "1-day", 8.10, "EUR", Validity.Days(1)),
                VignetteProduct("sk10", "10-day", 10.80, "EUR", Validity.Days(10)),
                VignetteProduct("sk30", "30-day", 17.10, "EUR", Validity.Days(30)),
                VignetteProduct("sk365", "365-day", 90.00, "EUR", Validity.Days(365)),
            ),
            "Electronic, tied to the number plate. Motorways and expressways.", "https://eznamka.sk",
        ),
        VignetteCountry(
            "CZ", listOf(
                VignetteProduct("cz1", "1-day", 230.0, "CZK", Validity.Days(1)),
                VignetteProduct("cz10", "10-day", 300.0, "CZK", Validity.Days(10)),
                VignetteProduct("cz30", "30-day", 480.0, "CZK", Validity.Days(30)),
                VignetteProduct("cz365", "365-day", 2570.0, "CZK", Validity.Days(365)),
            ),
            "Electronic. Electric and hydrogen cars are exempt.", "https://edalnice.cz",
        ),
        VignetteCountry(
            "AT", listOf(
                VignetteProduct("at1", "1-day", 9.60, "EUR", Validity.Days(1)),
                VignetteProduct("at10", "10-day", 12.80, "EUR", Validity.Days(10)),
                VignetteProduct("at2m", "2-month", 32.00, "EUR", Validity.Months(2)),
                VignetteProduct("atyear", "Annual 2026 (1 Dec 2025 – 31 Jan 2027)", 106.80, "EUR", Validity.CalendarYear14),
            ),
            "Online 2-month/annual vignettes start 18 days after purchase. Tunnels and passes (Tauern, Brenner, Karawanken…) cost extra.",
            "https://shop.asfinag.at",
        ),
        VignetteCountry(
            "HU", listOf(
                VignetteProduct("hu1", "1-day (D1)", 5550.0, "HUF", Validity.Days(1)),
                VignetteProduct("hu10", "10-day (D1)", 6900.0, "HUF", Validity.Days(10)),
                VignetteProduct("hum", "Monthly (D1)", 11170.0, "HUF", Validity.Months(1)),
                VignetteProduct("huy", "Annual national (until 31 Jan next year)", 61760.0, "HUF", Validity.UntilJan31),
                VignetteProduct("hucounty", "Annual county (one county)", 7190.0, "HUF", Validity.UntilJan31),
            ),
            "Electronic. A county vignette covers only that county's tolled sections.", "https://nemzetiutdij.hu",
        ),
        VignetteCountry(
            "SI", listOf(
                VignetteProduct("si7", "Weekly (2A)", 16.00, "EUR", Validity.Days(7)),
                VignetteProduct("sim", "Monthly (2A)", 32.00, "EUR", Validity.Months(1)),
                VignetteProduct("siy", "Annual (2A)", 117.50, "EUR", Validity.Days(366)),
            ),
            "Class 2A = up to 1.3 m above the front axle (most cars). Karawanken tunnel €9 extra.",
            "https://evinjeta.dars.si",
        ),
        VignetteCountry(
            "CH", listOf(
                VignetteProduct("chy", "Annual 2026 (1 Dec 2025 – 31 Jan 2027)", 40.0, "CHF", Validity.CalendarYear14),
            ),
            "Only an annual vignette exists.", "https://via.admin.ch",
        ),
        VignetteCountry(
            "BG", listOf(
                VignetteProduct("bgw", "Weekend", 5.11, "EUR", Validity.Weekend),
                VignetteProduct("bg7", "Weekly", 7.67, "EUR", Validity.Days(7)),
                VignetteProduct("bgm", "Monthly", 15.34, "EUR", Validity.Days(30)),
                VignetteProduct("bgq", "Quarterly", 27.61, "EUR", Validity.Days(90)),
                VignetteProduct("bgy", "Annual", 49.60, "EUR", Validity.Days(365)),
            ),
            "Bulgaria uses the euro since 1 Jan 2026. Danube bridges cost extra.", "https://bgtoll.bg",
        ),
        VignetteCountry(
            "RO", listOf(
                VignetteProduct("ro1", "1-day", 3.50, "EUR", Validity.Days(1)),
                VignetteProduct("ro10", "10-day", 6.00, "EUR", Validity.Days(10)),
                VignetteProduct("ro30", "30-day", 9.50, "EUR", Validity.Days(30)),
                VignetteProduct("ro60", "60-day", 15.00, "EUR", Validity.Days(60)),
                VignetteProduct("roy", "12-month", 50.00, "EUR", Validity.Months(12)),
            ),
            "Rovinieta; needed on all national roads, not only motorways. Prices approximate (paid in RON).",
            "https://www.roviniete.ro",
        ),
        VignetteCountry(
            "MD", listOf(
                VignetteProduct("md7", "7-day", 4.0, "EUR", Validity.Days(7)),
                VignetteProduct("md15", "15-day", 7.0, "EUR", Validity.Days(15)),
                VignetteProduct("md30", "30-day", 10.0, "EUR", Validity.Days(30)),
                VignetteProduct("md90", "90-day", 20.0, "EUR", Validity.Days(90)),
                VignetteProduct("md365", "365-day", 60.0, "EUR", Validity.Days(365)),
            ),
            "Needed on all roads for foreign cars. Prices approximate.", "https://evinieta.gov.md",
        ),
    )

    private val byCode = countries.associateBy { it.code }

    fun country(code: String): VignetteCountry? = byCode[code]

    fun product(id: String): VignetteProduct? = countries.firstNotNullOfOrNull { c -> c.products.firstOrNull { it.id == id } }

    fun countryOfProduct(id: String): String? = countries.firstOrNull { c -> c.products.any { it.id == id } }?.code

    /** Cheapest product that covers [from]..[to] when started on [from]. */
    fun cheapestCovering(code: String, from: LocalDate, to: LocalDate): VignetteProduct? =
        byCode[code]?.products
            ?.filter { it.id != "hucounty" && !it.validity.endDate(from).isBefore(to) }
            ?.minByOrNull { it.priceEur }

    /** In Romania and Moldova the vignette is needed on all roads, not just tolled motorways. */
    fun coversAllRoads(code: String) = code == "RO" || code == "MD"
}
