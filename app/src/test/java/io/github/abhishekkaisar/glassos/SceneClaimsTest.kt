package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClaimsTest {
    private val screensaver = Any()
    private val mirroring = Any()

    @Test fun theScreensaverClaimsAndYieldsAsBefore() {
        val c = SceneClaims()
        assertNull(c.owner)
        assertTrue(c.claim(screensaver, outside = false))
        assertSame(screensaver, c.owner)
        assertFalse("claiming again changes nothing", c.claim(screensaver, outside = false))
        assertFalse("a stranger's yield changes nothing", c.yield(Any()))
        assertTrue(c.yield(screensaver))
        assertNull(c.owner)
    }

    @Test fun aMirrorHoldOutranksTheScreensaverWhicheverCameFirst() {
        val c = SceneClaims()
        assertTrue(c.claim(mirroring, outside = true))
        // The dream starting while the receiver is hidden and decoding does not get the decoder.
        assertFalse(c.claim(screensaver, outside = false))
        assertSame(mirroring, c.owner)
        // The session ends: the screensaver, still on, plays.
        assertTrue(c.yield(mirroring))
        assertSame(screensaver, c.owner)

        val d = SceneClaims()
        d.claim(screensaver, outside = false)
        assertTrue(d.claim(mirroring, outside = true))
        assertSame(mirroring, d.owner)
        assertFalse("the screensaver stopping behind the hold changes nothing", d.yield(screensaver))
        assertTrue(d.yield(mirroring))
        assertNull(d.owner)
    }
}
