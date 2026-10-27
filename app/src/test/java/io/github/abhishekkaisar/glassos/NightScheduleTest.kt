package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightScheduleTest {
    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    @Test fun windowAcrossMidnight() {
        val s = NightSchedule(hm(22), hm(7))
        assertFalse(s.isNight(hm(21, 59)))
        assertTrue(s.isNight(hm(22)))
        assertTrue(s.isNight(1439))
        assertTrue(s.isNight(0))
        assertTrue(s.isNight(hm(6, 59)))
        assertFalse(s.isNight(hm(7)))
        assertFalse(s.isNight(hm(12)))
    }

    @Test fun windowWithinOneDay() {
        val s = NightSchedule(hm(1), hm(5, 30))
        assertFalse(s.isNight(0))
        assertTrue(s.isNight(hm(1)))
        assertTrue(s.isNight(hm(5, 29)))
        assertFalse(s.isNight(hm(5, 30)))
        assertFalse(s.isNight(1439))
    }

    @Test fun equalStartAndEndNeverTurnsOn() {
        val s = NightSchedule(hm(8), hm(8))
        for (m in 0 until NightSchedule.DAY) assertFalse(s.isNight(m))
        assertEquals(NightSchedule.DAY, s.minutesToNextChange(0))
    }

    @Test fun nextChange() {
        val s = NightSchedule(hm(22), hm(7))
        assertEquals(60, s.minutesToNextChange(hm(21)))
        assertEquals(hm(9), s.minutesToNextChange(hm(22)))
        assertEquals(hm(7), s.minutesToNextChange(0))
        assertEquals(1, s.minutesToNextChange(1439 - 120))
    }

    @Test fun stepsWrapAndFormat() {
        assertEquals(0, NightSchedule.step(hm(23, 30), 1))
        assertEquals(hm(23, 30), NightSchedule.step(0, -1))
        assertEquals(hm(22, 30), NightSchedule.step(hm(22, 10), 1))
        assertEquals("22:00", NightSchedule.format(hm(22), true))
        assertEquals("10:00 PM", NightSchedule.format(hm(22), false))
        assertEquals("12:30 AM", NightSchedule.format(30, false))
        assertEquals("12:00 PM", NightSchedule.format(hm(12), false))
    }

    // ---------- Sunset to sunrise ----------

    private val la = java.util.TimeZone.getTimeZone("America/Los_Angeles")
    /** The June solstice, or [month] (0-based) and [day] of 2026. */
    private fun sun(lat: Double, lon: Double, tz: java.util.TimeZone = la, month: Int = 5, day: Int = 21) =
        SunTimes.day(java.util.Calendar.getInstance(tz).apply { clear(); set(2026, month, day, 12, 0) }.timeInMillis, tz, lat, lon)

    @Test fun sunModeFollowsSunsetAndSunrise() {
        val seattle = sun(47.6062, -122.3321)   // sunrise 05:12, sunset 21:11
        val s = NightSchedule.resolve(true, hm(22), hm(7), 30, -15, seattle)
        assertEquals(hm(21, 41), s.start)
        assertEquals(hm(4, 57), s.end)
        assertTrue(s.isNight(0)); assertFalse(s.isNight(hm(12)))
        // Clock mode ignores the sun.
        val c = NightSchedule.resolve(false, hm(22), hm(7), 30, -15, seattle)
        assertEquals(hm(22), c.start); assertEquals(hm(7), c.end)
    }

    @Test fun sunModeWithoutSunTimesUsesTheClock() {
        for (day in listOf(null, sun(78.2232, 15.6267, java.util.TimeZone.getTimeZone("Arctic/Longyearbyen")))) {
            val s = NightSchedule.resolve(true, hm(22), hm(7), 30, -15, day)
            assertEquals(hm(22), s.start); assertEquals(hm(7), s.end)
        }
    }

    @Test fun offsetsThatLeaveNoNightOrNoDayUseTheClock() {
        // Reykjavik at midsummer: sunset 00:03, sunrise 02:56. Sunset +90 to sunrise −90 would end before it starts.
        val rk = sun(64.1, -21.9, java.util.TimeZone.getTimeZone("Atlantic/Reykjavik"))
        val s = NightSchedule.resolve(true, hm(22), hm(7), 90, -90, rk)
        assertEquals(hm(22), s.start); assertEquals(hm(7), s.end)
        assertEquals(null, NightSchedule.sunWindow(90, -90, rk))
        // An hour either way still leaves a short night, from the sun.
        val short = NightSchedule.resolve(true, hm(22), hm(7), 60, -60, rk)
        assertEquals(rk.minute("sunset", 60), short.start); assertEquals(rk.minute("sunrise", -60), short.end)
        assertTrue(short.start in hm(1)..hm(1, 10) && short.end in hm(1, 50)..hm(2))
        assertFalse(short.isNight(hm(12)))
        // Fairbanks at midwinter: sunrise 10:58, sunset 14:40. Sunset −120 to sunrise +120 would last more than a day.
        val ak = java.util.TimeZone.getTimeZone("America/Anchorage")
        val fb = sun(64.8378, -147.7164, ak, 11, 21)
        val w = NightSchedule.resolve(true, hm(22), hm(7), -120, 120, fb)
        assertEquals(hm(22), w.start); assertEquals(hm(7), w.end)
        val plain = NightSchedule.resolve(true, hm(22), hm(7), 0, 0, fb)
        assertEquals(fb.minute("sunset", 0), plain.start); assertEquals(fb.minute("sunrise", 0), plain.end)
    }

    @Test fun dimAlphaIsZeroWhenOff() {
        val s = NightSchedule(hm(22), hm(7))
        assertEquals(0f, NightSchedule.dimAlpha(false, s, 1, hm(23)), 0f)
        assertEquals(0f, NightSchedule.dimAlpha(true, s, 1, hm(12)), 0f)
        assertEquals(0.28f, NightSchedule.dimAlpha(true, s, 0, hm(23)), 0f)
        assertEquals(0.42f, NightSchedule.dimAlpha(true, s, 1, hm(23)), 0f)
        assertEquals(0.56f, NightSchedule.dimAlpha(true, s, 9, 0), 0f)
    }

    @Test fun offsetsStepAndFormat() {
        assertEquals(15, NightSchedule.stepOffset(0, 1))
        assertEquals(-15, NightSchedule.stepOffset(0, -1))
        assertEquals(120, NightSchedule.stepOffset(120, 1))
        assertEquals(-120, NightSchedule.stepOffset(-120, -1))
        assertEquals(15, NightSchedule.stepOffset(7, 1))
        // A restored backup can hold more than the TV steps to.
        assertEquals(120, NightSchedule.stepOffset(240, -1))
        assertEquals("Sunset", NightSchedule.formatOffset("sunset", 0))
        assertEquals("Sunset +30", NightSchedule.formatOffset("sunset", 30))
        assertEquals("Sunrise \u221215", NightSchedule.formatOffset("sunrise", -15))
    }

    @Test fun outOfRangeIsClamped() {
        val s = NightSchedule(-5, 5000)
        assertEquals(0, s.start); assertEquals(1439, s.end)
    }
}
