package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Reference values: the USNO Astronomical Applications tables for 2026 (rounded to the nearest minute, as these are).
 * GlassOS Manager's test_sun.py checks the same vectors against sun.py.
 */
class SunTimesTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private val oslo = TimeZone.getTimeZone("Europe/Oslo")

    /** An instant on that local date (noon, or [h]:[min]). */
    private fun at(tz: TimeZone, y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private fun day(tz: TimeZone, y: Int, m: Int, d: Int, lat: Double, lon: Double) = SunTimes.day(at(tz, y, m, d), tz, lat, lon)

    /** Within a minute of "HH:mm", across midnight too. */
    private fun near(expected: String, actual: Int?, what: String) {
        assertNotNull(what, actual)
        val e = expected.substring(0, 2).toInt() * 60 + expected.substring(3).toInt()
        val diff = ((actual!! - e) % 1440 + 1440 + 720) % 1440 - 720
        assertTrue("$what: expected $expected, got ${actual / 60}:${actual % 60}", kotlin.math.abs(diff) <= 1)
    }

    private fun check(tz: TimeZone, date: Triple<Int, Int, Int>, lat: Double, lon: Double, rise: String, set: String) {
        val s = day(tz, date.first, date.second, date.third, lat, lon)
        assertNull(s.polar)
        near(rise, s.minute("sunrise", 0), "sunrise $date $lat,$lon")
        near(set, s.minute("sunset", 0), "sunset $date $lat,$lon")
    }

    @Test fun matchesUsnoTables() {
        check(la, Triple(2026, 6, 21), 47.6062, -122.3321, "05:12", "21:11")    // Seattle, PDT
        check(la, Triple(2026, 12, 21), 47.6062, -122.3321, "07:55", "16:20")   // Seattle, PST
        check(la, Triple(2026, 6, 21), 37.7749, -122.4194, "05:48", "20:35")    // San Francisco
        check(la, Triple(2026, 12, 21), 37.7749, -122.4194, "07:21", "16:54")
        check(TimeZone.getTimeZone("Australia/Sydney"), Triple(2026, 12, 21), -33.8688, 151.2093, "05:41", "20:05")
        check(oslo, Triple(2026, 6, 21), 59.9139, 10.7522, "03:54", "22:44")
        check(oslo, Triple(2026, 12, 21), 59.9139, 10.7522, "09:18", "15:12")
        check(oslo, Triple(2026, 11, 27), 69.6492, 18.9553, "11:20", "11:42")  // Tromsø, a day before polar night
    }

    @Test fun dstDaysUseTheOffsetAtTheEvent() {
        // Los Angeles (zone.tab point). Converting with the midnight offset would give 06:12 and 07:12.
        check(la, Triple(2026, 3, 8), 34.0522, -118.2428, "07:12", "18:55")
        check(la, Triple(2026, 11, 1), 34.0522, -118.2428, "06:13", "17:00")
        val before = day(la, 2026, 3, 7, 34.0522, -118.2428).minute("sunrise", 0)!!
        val after = day(la, 2026, 3, 8, 34.0522, -118.2428).minute("sunrise", 0)!!
        assertTrue("clocks went forward an hour: $before -> $after", after - before in 56..60)
    }

    @Test fun polarDayAndNight() {
        val svalbard = TimeZone.getTimeZone("Arctic/Longyearbyen")
        val summer = day(svalbard, 2026, 6, 21, 78.2232, 15.6267)
        assertEquals("up", summer.polar)
        assertNull(summer.sunrise); assertNull(summer.sunset)
        assertNull(summer.minute("sunset", 30))
        assertEquals("down", day(svalbard, 2026, 12, 21, 78.2232, 15.6267).polar)
        assertEquals("down", day(oslo, 2026, 11, 28, 69.6492, 18.9553).polar)   // Tromsø
    }

    @Test fun eventsAfterMidnightFoldIntoTheDay() {
        // Reykjavik's June sunset is at 00:04 the next calendar day.
        val r = day(TimeZone.getTimeZone("Atlantic/Reykjavik"), 2026, 6, 21, 64.1466, -21.9426)
        near("02:55", r.minute("sunrise", 0), "Reykjavik sunrise")
        assertEquals(4, r.minute("sunset", 0))
        // An offset is elapsed time: Seattle's sunset (21:10.9) + 3 h is 00:11.
        val s = day(la, 2026, 6, 21, 47.6062, -122.3321)
        assertEquals(11, s.minute("sunset", 180))
        assertEquals(s.minute("sunrise", 0)!! - 30, s.minute("sunrise", -30))
        assertNull(s.minute("noon", 0))
    }

    @Test fun anyTimeOfTheLocalDayGivesThatDay() {
        // Sydney is 11 hours ahead of UTC in December: early and late on the same local date give the same events.
        val tz = TimeZone.getTimeZone("Australia/Sydney")
        val early = SunTimes.day(at(tz, 2026, 12, 21, 0, 5), tz, -33.8688, 151.2093)
        val late = SunTimes.day(at(tz, 2026, 12, 21, 23, 55), tz, -33.8688, 151.2093)
        assertEquals(early.sunrise, late.sunrise)
        assertEquals(early.sunset, late.sunset)
        assertTrue(early.sunrise!! in at(tz, 2026, 12, 21, 0, 0)..at(tz, 2026, 12, 21, 23, 59))
    }

    @Test fun noPlaceGivesNoTimes() {
        val s = SunTimes.day(at(la, 2026, 6, 21), la, Double.NaN, 0.0)
        assertNull(s.sunrise); assertNull(s.polar)
        assertNull(SunTimes.day(at(la, 2026, 6, 21), la, 91.0, 0.0).sunset)
    }

    @Test fun civilDays() {
        assertEquals(0L, SunTimes.daysFromCivil(1970, 1, 1))
        assertEquals(-1L, SunTimes.daysFromCivil(1969, 12, 31))
        assertEquals(11017L, SunTimes.daysFromCivil(2000, 3, 1))
        assertEquals(19782L, SunTimes.daysFromCivil(2024, 2, 29))
        assertEquals(20751L, SunTimes.daysFromCivil(2026, 10, 25))
        assertEquals(-135140L, SunTimes.daysFromCivil(1600, 1, 1))
    }

    @Test fun localMinuteRoundsToTheNearestMinute() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(0, SunTimes.localMinute(29_999, utc))
        assertEquals(1, SunTimes.localMinute(30_000, utc))
        assertEquals(1439, SunTimes.localMinute(-30_001, utc))
        assertEquals(0, SunTimes.localMinute(-30_000, utc))
    }

    @Test fun managerPlacesAreRoundedAndCleaned() {
        val p = Place.parse("34.05,-118.25,Los Angeles")!!
        assertEquals(34.1, p.lat, 0.0); assertEquals(-118.2, p.lon, 0.0)
        assertEquals("Los Angeles", p.label); assertEquals("manager", p.source)
        assertEquals("St. John's, NL", Place.parse("47.56,-52.71,St. John's, NL")!!.label)
        assertEquals("Zürich", Place.parse("47.4,8.5,Zürich")!!.label)
        assertEquals("Home script", Place.parse("1,2,Home <script>")!!.label)
        assertEquals("", Place.parse("1,2")!!.label)
        assertEquals(40, Place.parse("1,2," + "a".repeat(50))!!.label.length)
        assertEquals("0.0", Place.parse("-0.04,0")!!.lat.toString())
        for (bad in listOf("", "1", "91,0,x", "0,181", "NaN,0", "0,Infinity", "a,b", "1;2,3")) assertNull(bad, Place.parse(bad))
    }

    @Test fun dayKeyChangesAtLocalMidnightAndWithTheZone() {
        // An open Settings panel re-reads its sun values only when this changes.
        val early = at(la, 2026, 10, 25, 0, 1)
        assertEquals(SunNow.dayKey(early, la), SunNow.dayKey(at(la, 2026, 10, 25, 23, 59), la))
        assertNotEquals(SunNow.dayKey(early, la), SunNow.dayKey(at(la, 2026, 10, 24, 23, 59), la))
        assertNotEquals(SunNow.dayKey(early, la), SunNow.dayKey(early, TimeZone.getTimeZone("UTC")))
    }
}
