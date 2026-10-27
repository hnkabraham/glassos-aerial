package io.github.abhishekkaisar.glassos.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorBackoffTest {
    private val min = 60_000L

    @Test fun threeQuickDeathsGoUpTo60SecondsThenGiveUp() {
        val b = SupervisorBackoff()
        var t = 0L
        b.up(t)
        assertEquals(2_000L, b.died(t + 5_000))
        t += 7_000
        b.up(t)
        assertEquals(10_000L, b.died(t + 5_000))
        t += 15_000
        b.up(t)
        assertEquals(60_000L, b.died(t + 5_000))
        assertFalse(b.gaveUp)
        t += 65_000
        b.up(t)
        assertNull(b.died(t + 5_000))
        assertTrue(b.gaveUp)
        // Stays given up, even after a long run, until a reset.
        b.up(t + 10_000)
        assertNull(b.died(t + 60 * min))
    }

    @Test fun aDeathBeforeComingUpCounts() {
        // A restart that crashes before the launcher connects to it.
        val b = SupervisorBackoff()
        assertEquals(2_000L, b.died(0))
        assertEquals(10_000L, b.died(2_100))
        assertEquals(60_000L, b.died(12_200))
        assertNull(b.died(72_300))
    }

    @Test fun fiveStableMinutesStartTheCountOver() {
        val b = SupervisorBackoff()
        b.up(0)
        assertEquals(2_000L, b.died(1_000))
        b.up(3_000)
        assertEquals(10_000L, b.died(4_000))
        b.up(14_000)
        // Up exactly five minutes: the next death is a first one again.
        assertEquals(2_000L, b.died(14_000 + 5 * min))
        assertEquals(1, b.deaths)
    }

    @Test fun lessThanFiveMinutesKeepsCounting() {
        val b = SupervisorBackoff()
        b.up(0)
        assertEquals(2_000L, b.died(1_000))
        b.up(3_000)
        assertEquals(10_000L, b.died(3_000 + 5 * min - 1))
    }

    @Test fun upKeepsTheFirstTime() {
        val b = SupervisorBackoff()
        b.up(0)
        assertEquals(2_000L, b.died(1_000))
        b.up(10_000)
        // Repeated status pushes call up() again; stability counts from the first.
        b.up(10_000 + 4 * min)
        assertEquals(2_000L, b.died(10_000 + 5 * min))
    }

    @Test fun resetAfterAUserStart() {
        val b = SupervisorBackoff()
        repeat(3) { b.died(it * 1_000L) }
        assertNull(b.died(10_000))
        assertTrue(b.gaveUp)
        b.reset()
        assertFalse(b.gaveUp)
        assertEquals(0, b.deaths)
        assertEquals(2_000L, b.died(20_000))
    }

    @Test fun customDelays() {
        val b = SupervisorBackoff(longArrayOf(1L, 2L), stableMs = 100L)
        assertEquals(1L, b.died(0))
        assertEquals(2L, b.died(1))
        assertNull(b.died(2))
        assertEquals(listOf(2_000L, 10_000L, 60_000L), SupervisorBackoff.DELAYS_MS.toList())
        assertEquals(5 * min, SupervisorBackoff.STABLE_MS)
    }
}
