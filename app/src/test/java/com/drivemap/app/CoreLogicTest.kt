package com.drivemap.app

import com.drivemap.app.convoy.ConvoyCrypto
import com.drivemap.app.convoy.MqttHub
import com.drivemap.app.data.Countries
import com.drivemap.app.fines.FineEstimator
import com.drivemap.app.geo.Geo
import com.drivemap.app.geo.LatLon
import com.drivemap.app.geo.PolylinePath
import com.drivemap.app.vignette.Validity
import com.drivemap.app.vignette.VignetteCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoreLogicTest {

    @Test
    fun polylineRoundTrip() {
        val pts = listOf(LatLon(48.148600, 17.107700), LatLon(48.208200, 16.373800), LatLon(-33.1, 151.2))
        val enc = Geo.encodePolyline(pts, 6)
        val dec = Geo.decodePolyline(enc, 6)
        assertEquals(pts.size, dec.size)
        pts.zip(dec).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lon, b.lon, 1e-6)
        }
    }

    @Test
    fun distanceAndBearing() {
        val ba = LatLon(48.1486, 17.1077)
        val vie = LatLon(48.2082, 16.3738)
        val d = Geo.dist(ba, vie)
        assertTrue("BA–Vienna ≈ 55 km, got $d", d in 54_000.0..56_000.0)
        val b = Geo.bearing(ba, vie)
        assertTrue("west-ish bearing, got $b", b in 270.0..290.0)
        assertEquals(-20.0, Geo.angleDiff(10.0, 350.0), 1e-9)
    }

    @Test
    fun pathProjection() {
        val path = PolylinePath(listOf(LatLon(48.0, 17.0), LatLon(48.0, 17.01), LatLon(48.01, 17.01)))
        val hit = path.project(LatLon(48.0002, 17.005))!!
        assertEquals(0, hit.segment)
        assertTrue(hit.crossTrack in 15.0..30.0)
        assertTrue(hit.along in 300.0..450.0)
        val p = path.pointAt(path.length)
        assertEquals(48.01, p.lat, 1e-9)
    }

    @Test
    fun geohashNeighbours() {
        val h = Geo.geohash(48.1486, 17.1077, 4)
        assertEquals("u2s1", h)
        val n = Geo.geohashWithNeighbours(h)
        assertEquals(9, n.size)
        assertTrue(n.contains(h))
    }

    @Test
    fun slovakCameraFines() {
        // 50 zone, 72 km/h measured: 72 - 3 tolerance = 69 -> 19 over -> 16..20 band = 66 €
        val f = FineEstimator.estimate("SK", 72.0, 50, urban = true, motorway = false)!!
        assertEquals(19, f.overAfterTolerance)
        assertEquals(66.0, f.amountEur, 0.01)
        // 90 rural, 130 km/h: tolerance 3% of 130 = 3.9 -> 126.1 -> 36 over -> 201 €
        val r = FineEstimator.estimate("SK", 130.0, 90, urban = false, motorway = false)!!
        assertEquals(36, r.overAfterTolerance)
        assertEquals(201.0, r.amountEur, 0.01)
        // within tolerance -> nothing
        val ok = FineEstimator.estimate("SK", 52.0, 50, urban = true, motorway = false)!!
        assertEquals(0.0, ok.amountEur, 0.0)
        // top band carries a possible ban
        val top = FineEstimator.estimate("SK", 110.0, 50, urban = true, motorway = false)!!
        assertNotNull(top.ban)
    }

    @Test
    fun hungarianTableUsesLimitCategories() {
        // limit 130: first band starts above +20 km/h (after tolerance)
        val none = FineEstimator.estimate("HU", 150.0, 130, urban = false, motorway = true)!!
        assertEquals(0.0, none.amountEur, 0.0)
        val some = FineEstimator.estimate("HU", 160.0, 130, urban = false, motorway = true)!!
        assertEquals(Countries.toEur(50000.0, "HUF"), some.amountEur, 0.01)
    }

    @Test
    fun germanCatalogue() {
        val f = FineEstimator.estimate("DE", 78.0, 50, urban = true, motorway = false)!!
        // 78 - 3 = 75 -> 25 over -> 115 €, 1 point
        assertEquals(115.0, f.amountEur, 0.01)
        assertEquals("1 point", f.points)
    }

    @Test
    fun unknownCountryFallsBackToGeneric() {
        val f = FineEstimator.estimate("XX", 100.0, 80, urban = false, motorway = false)!!
        assertTrue(f.roughEstimate)
        assertTrue(f.amountEur > 0)
        assertNull(FineEstimator.estimate("SK", 100.0, -1, urban = false, motorway = true))
    }

    @Test
    fun vignetteValidity() {
        val d = LocalDate.of(2026, 10, 21)
        assertEquals(LocalDate.of(2026, 10, 30), Validity.Days(10).endDate(d))
        assertEquals(LocalDate.of(2026, 10, 21), Validity.Days(1).endDate(d))
        assertEquals(LocalDate.of(2027, 1, 31), Validity.CalendarYear14.endDate(d))
        assertEquals(LocalDate.of(2028, 1, 31), Validity.CalendarYear14.endDate(LocalDate.of(2026, 12, 5)))
        assertEquals(LocalDate.of(2026, 11, 21), Validity.Months(1).endDate(d))
        assertEquals(LocalDate.of(2026, 10, 25), Validity.Weekend.endDate(d)) // Wed -> Sun
        val cheapest = VignetteCatalog.cheapestCovering("AT", d, d)!!
        assertEquals("at1", cheapest.id)
        val week = VignetteCatalog.cheapestCovering("AT", d, d.plusDays(5))!!
        assertEquals("at10", week.id)
    }

    @Test
    fun convoyCrypto() {
        val code = ConvoyCrypto.newCode()
        assertEquals(12, code.length)
        assertTrue(ConvoyCrypto.isValid(ConvoyCrypto.pretty(code).lowercase()))
        val key = ConvoyCrypto.deriveKey(code)
        val msg = "{\"la\":48.1,\"lo\":17.1}".toByteArray()
        val enc = ConvoyCrypto.encrypt(key, msg)
        assertTrue(ConvoyCrypto.decrypt(key, enc)!!.contentEquals(msg))
        val other = ConvoyCrypto.deriveKey(ConvoyCrypto.newCode())
        assertNull(ConvoyCrypto.decrypt(other, enc))
        assertEquals(ConvoyCrypto.topicId(code), ConvoyCrypto.topicId(ConvoyCrypto.pretty(code)))
    }

    @Test
    fun mqttTopicMatching() {
        assertTrue(MqttHub.matches("dm1/c/abc/#", "dm1/c/abc/m/123"))
        assertTrue(MqttHub.matches("dm1/r/u2s1/+", "dm1/r/u2s1/xyz"))
        assertFalse(MqttHub.matches("dm1/r/u2s1/+", "dm1/r/u2s1/xyz/extra"))
        assertFalse(MqttHub.matches("dm1/r/u2s1/+", "dm1/r/u2s4/xyz"))
    }

    @Test
    fun defaultLimits() {
        assertEquals(130, Countries.defaultLimit("SK", "motorway", urban = false, tollOrExpress = true))
        assertEquals(50, Countries.defaultLimit("SK", "secondary", urban = true, tollOrExpress = false))
        assertEquals(100, Countries.defaultLimit("AT", "primary", urban = false, tollOrExpress = false))
        assertEquals(-1, Countries.defaultLimit("DE", "motorway", urban = false, tollOrExpress = false))
    }
}
