package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoneCoordsTest {
    private fun place(id: String) = ZoneCoords.lookup(id) ?: throw AssertionError("no place for $id")

    @Test fun zonesGiveTheirCityAtATenthOfADegree() {
        val la = place("America/Los_Angeles")
        assertEquals(34.1, la.lat, 0.0); assertEquals(-118.2, la.lon, 0.0)
        assertEquals("Los Angeles", la.label); assertEquals("timezone", la.source)
        assertEquals("Buenos Aires", place("America/Argentina/Buenos_Aires").label)
        assertEquals(-33.9, place("Australia/Sydney").lat, 0.0)
        // zone.tab, not zone1970.tab: Oslo keeps its own row instead of Berlin's.
        assertEquals(59.9, place("Europe/Oslo").lat, 0.0)
        assertEquals("2026e", ZoneCoords.TZDB)
    }

    @Test fun oldNamesFollowTheirLinks() {
        val pacific = place("US/Pacific")
        assertEquals("Los Angeles", pacific.label); assertEquals(34.1, pacific.lat, 0.0)
        val calcutta = place("Asia/Calcutta")
        assertEquals("Kolkata", calcutta.label); assertEquals(22.5, calcutta.lat, 0.0); assertEquals(88.4, calcutta.lon, 0.0)
        // Android's tzlookup still says Europe/Kiev.
        val kiev = place("Europe/Kiev")
        assertEquals("Kyiv", kiev.label); assertEquals(50.4, kiev.lat, 0.0); assertEquals(30.5, kiev.lon, 0.0)
    }

    @Test fun zonesWithoutAPlace() {
        for (id in listOf("UTC", "Etc/UTC", "GMT", "Etc/GMT+5", "Zulu", "", "america/los_angeles", "Mars/Olympus")) assertNull(id, ZoneCoords.lookup(id))
        assertNull(ZoneCoords.lookup(null))
    }
}
