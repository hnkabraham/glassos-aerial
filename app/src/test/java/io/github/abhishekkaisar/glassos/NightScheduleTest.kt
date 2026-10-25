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

    @Test fun outOfRangeIsClamped() {
        val s = NightSchedule(-5, 5000)
        assertEquals(0, s.start); assertEquals(1439, s.end)
    }
}
