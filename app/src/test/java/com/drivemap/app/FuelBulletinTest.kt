package com.drivemap.app

import com.drivemap.app.fuel.FuelPrices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Parses a downloaded EU Weekly Oil Bulletin file (set WOB_FILE to its path; skipped otherwise). */
class FuelBulletinTest {
    @Test
    fun parsesBulletin() {
        val path = System.getenv("WOB_FILE")
        assumeTrue("WOB_FILE not set", !path.isNullOrBlank())
        val (date, map) = FuelPrices.parseBulletin(File(path!!).readBytes())
        println("bulletin $date: " + map.toSortedMap().entries.joinToString { "${it.key}=${it.value.petrol}/${it.value.diesel}/${it.value.lpg}" })
        assertTrue("date $date", date.startsWith("20"))
        assertEquals(27, map.size)
        val sk = map["SK"]
        assertNotNull(sk)
        assertTrue("SK petrol ${sk!!.petrol}", sk.petrol!! in 1.0..3.0)
        assertTrue("SK diesel ${sk.diesel}", sk.diesel!! in 1.0..3.0)
        assertEquals(1.817, map["AT"]!!.petrol!!, 0.0001)
    }
}
