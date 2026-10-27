package com.drivemap.app

import com.drivemap.app.nav.Announcement
import com.drivemap.app.nav.Announcement.Kind
import com.drivemap.app.nav.Phrases
import com.drivemap.app.nav.RallyCoDriverVoice
import com.drivemap.app.nav.StandardVoice
import com.drivemap.app.nav.VoiceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice profiles reword announcements; the facts (distance, street, exit) must survive. */
class VoiceProfilesTest {
    private fun a(kind: Kind, lang: String = "sk-SK", full: Boolean = true, type: Int = 10, dist: Double? = 300.0,
                  street: String? = "Štúrova", exit: String? = null, toward: String? = null, roundabout: Int = 0) =
        Announcement(kind, lang, full, type, roundabout, dist, street, exit, toward, standardText = "STANDARD")

    @Test
    fun standardVoiceAlwaysFallsBackToStandardWords() {
        assertNull(StandardVoice.phrase(a(Kind.APPROACH)))
        assertNull(StandardVoice.sound(a(Kind.NOW)))
    }

    @Test
    fun unknownProfileIdFallsBackToStandard() {
        assertEquals(StandardVoice, VoiceProfiles.byId("pirate"))
        assertEquals(StandardVoice, VoiceProfiles.byId(null))
        assertEquals(RallyCoDriverVoice, VoiceProfiles.byId("rally"))
    }

    @Test
    fun rallyKeepsDistanceDirectionAndStreet() {
        val s = RallyCoDriverVoice.phrase(a(Kind.APPROACH))!!
        assertTrue(s, s.contains(Phrases.distance("sk-SK", 300.0)))
        assertTrue(s, s.contains("doprava"))
        assertTrue(s, s.contains("Štúrova"))
    }

    @Test
    fun rallyKeepsExitNumberAndDirectionSign() {
        val s = RallyCoDriverVoice.phrase(a(Kind.APPROACH, type = 20, dist = 1500.0, street = null, exit = "4", toward = "Nitra"))!!
        assertTrue(s, s.contains("4") && s.contains("Nitra") && s.contains(Phrases.distance("sk-SK", 1500.0)))
    }

    @Test
    fun normalLevelStaysShortButKeepsExit() {
        val s = RallyCoDriverVoice.phrase(a(Kind.APPROACH, full = false, type = 20, exit = "4", toward = "Nitra"))!!
        assertTrue(s, s.contains("4"))
        assertTrue(s, !s.contains("Štúrova") && !s.contains("Nitra"))
    }

    @Test
    fun rallyNowCallAndRoundabout() {
        assertTrue(RallyCoDriverVoice.phrase(a(Kind.NOW))!!.contains("teraz"))
        val r = RallyCoDriverVoice.phrase(a(Kind.APPROACH, type = 26, roundabout = 3, street = null))!!
        assertTrue(r, r.contains("tretí"))
        assertTrue(RallyCoDriverVoice.phrase(a(Kind.APPROACH, lang = "en-US"))!!.startsWith("300 meters, right"))
    }

    @Test
    fun everyTurnKeepsItsDistance() {
        for (type in listOf(9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24, 25, 26, 28)) {
            for (lang in listOf("sk-SK", "cs-CZ", "en-US")) {
                val s = RallyCoDriverVoice.phrase(a(Kind.APPROACH, lang = lang, type = type, dist = 750.0, roundabout = 2))
                assertNotNull("type $type $lang", s)
                assertTrue("$type $lang: $s", s!!.contains(Phrases.distance(lang, 750.0)))
            }
        }
    }

    @Test
    fun languagesWithoutRallyPhrasesUseStandardWords() {
        assertNull(RallyCoDriverVoice.phrase(a(Kind.APPROACH, lang = "de-DE")))
        assertNull(RallyCoDriverVoice.sound(a(Kind.NOW, lang = "de-DE")))
    }

    @Test
    fun soundOnlyRightBeforeATurn() {
        assertNotNull(RallyCoDriverVoice.sound(a(Kind.NOW)))
        assertNull(RallyCoDriverVoice.sound(a(Kind.APPROACH)))
        assertNull(RallyCoDriverVoice.sound(a(Kind.NOW, type = 4)))   // arrival: no click
    }

    @Test
    fun startAndContinueKeepTheirInformation() {
        assertTrue(RallyCoDriverVoice.phrase(a(Kind.START))!!.endsWith("STANDARD"))
        val c = RallyCoDriverVoice.phrase(a(Kind.CONTINUE, dist = 12000.0))!!
        assertTrue(c, c.contains(Phrases.distance("sk-SK", 12000.0)))
    }
}
