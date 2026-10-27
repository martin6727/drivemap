package com.drivemap.app

import com.drivemap.app.geo.LatLon
import com.drivemap.app.net.Categories
import com.drivemap.app.net.SearchRank
import com.drivemap.app.route.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankTest {
    private val nitra = LatLon(48.3069, 18.0864)

    @Test
    fun spacingCaseAndAccentsDontMatter() {
        assertEquals("espressofix", SearchRank.compact("Espresso Fix"))
        assertEquals("espressofix", SearchRank.compact("ESPRESSO-FIX"))
        assertEquals("kaviaren u jozefa", SearchRank.normalize("Kaviareň „U Jozefa“"))
    }

    @Test
    fun matchTiers() {
        assertEquals(0, SearchRank.tier("espressofix", "Espresso Fix"))
        assertEquals(0, SearchRank.tier("espresso fix", "EspressoFix"))
        assertEquals(1, SearchRank.tier("kauf", "Kaufland"))
        assertEquals(2, SearchRank.tier("fix", "Espresso Fix"))
        assertEquals(3, SearchRank.tier("pressof", "Espresso Fix"))
        assertEquals(4, SearchRank.tier("bratislavska 4", "Bratislavská 254/4", "Nitra"))
        assertEquals(5, SearchRank.tier("espressofix", "Espresso Max"))
    }

    @Test
    fun exactNameBeatsCloserPartialMatch() {
        val partial = Place("Espresso Max", LatLon(48.307, 18.087))          // ~100 m away
        val exact = Place("Espresso Fix", LatLon(48.318, 18.060))            // ~2.3 km away
        val ranked = SearchRank.rank("espressofix", nitra, listOf(partial, exact))
        assertEquals("Espresso Fix", ranked.first().name)
    }

    @Test
    fun farAwayExactMatchGoesLast() {
        val melbourne = Place("Espresso Fix", LatLon(-37.81, 144.96))
        val local = Place("Espresso Max", LatLon(48.307, 18.087))
        val ranked = SearchRank.rank("espressofix", nitra, listOf(melbourne, local))
        assertEquals(listOf("Espresso Max", "Espresso Fix"), ranked.map { it.name })
    }

    @Test
    fun nearestFirstAmongEqualMatches() {
        val far = Place("Kaufland", LatLon(48.15, 17.11))
        val near = Place("Kaufland", LatLon(48.313, 18.054))
        assertEquals(near, SearchRank.rank("kaufland", nitra, listOf(far, near)).first())
    }

    @Test
    fun dedupeKeepsFirstSourceOnly() {
        val a = Place("Kaufland", LatLon(48.3130, 18.0540), "Hviezdoslavova trieda")
        val b = Place("KAUFLAND", LatLon(48.3135, 18.0545))                   // same shop, ~65 m
        val other = Place("Kaufland", LatLon(48.30, 18.10))                   // another branch
        val out = SearchRank.dedupe(listOf(a, b, other))
        assertEquals(listOf(a, other), out)
    }

    @Test
    fun strongMatchHidesAddPlaceHint() {
        assertTrue(SearchRank.isStrong("kauf", Place("Kaufland", nitra)))
        assertTrue(!SearchRank.isStrong("espressofix", Place("Espresso Max", nitra)))
    }

    @Test
    fun nearbyGoodMatchBeatsFarExactName() {
        val farExact = Place("Lekáreň", LatLon(48.60, 17.80))                 // ~38 km
        val nearPrefix = Place("Lekáreň Hypericum", LatLon(48.312, 18.084))   // ~0.6 km
        assertEquals(nearPrefix, SearchRank.rank("lekaren", nitra, listOf(farExact, nearPrefix)).first())
    }

    @Test
    fun kindOfPlaceIsOrderedByDistanceOnly() {
        val farNamed = Place("Lekáreň", LatLon(48.60, 17.80))
        val nearOther = Place("Anemapharm", LatLon(48.31, 18.09))
        assertEquals(nearOther, SearchRank.rank("lekaren", nitra, listOf(farNamed, nearOther), byDistance = true).first())
    }

    @Test
    fun strongMatchOnlyCountsNearby() {
        val melbourne = Place("Espresso Fix", LatLon(-37.81, 144.96))
        assertTrue(!SearchRank.hasStrongNear("espressofix", nitra, listOf(melbourne)))
        assertTrue(SearchRank.hasStrongNear("espressofix", nitra, listOf(Place("EspressoFix", LatLon(48.318, 18.060)))))
    }

    @Test
    fun townBeatsItsBusStopsAndRiver() {
        val stop = Place("Banská Štiavnica, Banky", LatLon(48.47, 18.90), "", "highway:bus_stop")
        val platform = Place("Banská Štiavnica", LatLon(48.46, 18.91), "", "public_transport:platform")
        val cadastre = Place("Banská Štiavnica", LatLon(48.455, 18.90), "", "place:cadastral_community")
        val town = Place("Banská Štiavnica", LatLon(48.449, 18.910), "", "place:town")
        val near = LatLon(48.30, 18.10)
        assertEquals(town, SearchRank.rank("banska stiavnica", near, listOf(stop, platform, cadastre, town)).first())
        // Nitra: the town before the river and the railway platforms of the same name
        val river = Place("Nitra", LatLon(48.30, 18.07), "", "waterway:river")
        val plat = Place("Nitra", LatLon(48.315, 18.07), "", "railway:platform")
        val nitra = Place("Nitra", LatLon(48.31, 18.09), "", "place:town")
        assertEquals(nitra, SearchRank.rank("nitra", near, listOf(river, plat, nitra)).first())
        // but a station is a normal place, nearest first among shops etc.
        assertEquals(1, SearchRank.kind("railway:station"))
        assertEquals(1, SearchRank.kind("shop:supermarket"))
    }

    @Test
    fun categoryWordsStillRecognised() {
        assertEquals(listOf("pharmacy"), Categories.match("lekáreň"))
        assertEquals(listOf("car repair"), Categories.match("autoservis blízko"))
        assertNull(Categories.match("espressofix"))
    }
}
