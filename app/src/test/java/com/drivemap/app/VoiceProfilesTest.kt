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
    fun rallyMakesNoClicksAndCallsBends() {
        assertNull(RallyCoDriverVoice.sound(a(Kind.NOW)))
        assertTrue(RallyCoDriverVoice.paceNotes)
        assertTrue(!StandardVoice.paceNotes)
    }

    private fun bend(left: Boolean, grade: Int, long: Boolean = false, tightens: Boolean = false) =
        com.drivemap.app.nav.Bend(0.0, 80.0, left, 90.0, 40.0, grade, tightens, false, long)

    @Test
    fun paceNoteWording() {
        fun say(call: com.drivemap.app.nav.BendCall, lang: String = "sk-SK") =
            RallyCoDriverVoice.phrase(Announcement(Kind.BEND, lang, true, bend = call))!!
        assertEquals("Pravá 4", say(com.drivemap.app.nav.BendCall(bend(false, 4))))
        assertEquals("Dlhá ľavá 3 zatvára", say(com.drivemap.app.nav.BendCall(bend(true, 3, long = true, tightens = true))))
        assertEquals("Ľavá 4 do pravá 3, 100", say(com.drivemap.app.nav.BendCall(bend(true, 4), bend(false, 3), 110.0)))
        assertEquals("Pozor, vracák vpravo", say(com.drivemap.app.nav.BendCall(bend(false, 0))))
        // the same way twice: one bend that stays, tightens or opens
        assertEquals("Dlhá pravá 2", say(com.drivemap.app.nav.BendCall(bend(false, 2), bend(false, 2))))
        assertEquals("Pravá 3 zatvára do 2", say(com.drivemap.app.nav.BendCall(bend(false, 3), bend(false, 2))))
        assertEquals("Ľavá 2 otvára do 4, 150", say(com.drivemap.app.nav.BendCall(bend(true, 2), bend(true, 4), 160.0)))
        assertEquals("Caution, left 1", say(com.drivemap.app.nav.BendCall(bend(true, 1)), "en-US"))
        // standard voice and unsupported languages say nothing for bends
        assertNull(StandardVoice.phrase(Announcement(Kind.BEND, "sk-SK", true, bend = com.drivemap.app.nav.BendCall(bend(true, 4)))))
        assertNull(RallyCoDriverVoice.phrase(Announcement(Kind.BEND, "de-DE", true, bend = com.drivemap.app.nav.BendCall(bend(true, 4)))))
    }

    @Test
    fun startAndContinueKeepTheirInformation() {
        assertTrue(RallyCoDriverVoice.phrase(a(Kind.START))!!.endsWith("STANDARD"))
        val c = RallyCoDriverVoice.phrase(a(Kind.CONTINUE, dist = 12000.0))!!
        assertTrue(c, c.contains(Phrases.distance("sk-SK", 12000.0)))
    }
}
