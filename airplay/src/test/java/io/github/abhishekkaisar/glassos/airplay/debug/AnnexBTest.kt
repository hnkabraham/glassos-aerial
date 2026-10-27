package io.github.abhishekkaisar.glassos.airplay.debug

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val sps = b(0x67, 0x42, 0x00, 0x1F)
    private val pps = b(0x68, 0xCE, 0x3C, 0x80)
    private val idr = b(0x65, 0x88, 0x84, 0x00, 0x33)
    private val slice = b(0x41, 0x9A, 0x02)

    @Test fun lengthPrefixedSamplesGetStartCodes() {
        val avcc = b(0, 0, 0, 5, *idr.map { it.toInt() and 0xFF }.toIntArray(), 0, 0, 0, 3, *slice.map { it.toInt() and 0xFF }.toIntArray())
        val out = AnnexB.toAnnexB(avcc)!!
        assertArrayEquals(b(0, 0, 0, 1) + idr + b(0, 0, 0, 1) + slice, out)
        assertEquals(listOf(5, 1), AnnexB.nalTypes(out))
    }

    @Test fun startCodeSamplesPassThrough() {
        val annexB = b(0, 0, 0, 1) + idr
        assertSame(annexB, AnnexB.toAnnexB(annexB))
        val short = b(0, 0, 1) + slice
        assertSame(short, AnnexB.toAnnexB(short))
    }

    @Test fun badLengthsAreRefused() {
        assertNull(AnnexB.toAnnexB(b(0, 0, 0, 9, 0x65, 0x88)))
        assertNull(AnnexB.toAnnexB(b(0, 0, 0)))
        assertNull(AnnexB.toAnnexB(ByteArray(0)))
        assertNull(AnnexB.toAnnexB(b(0, 0, 0, 0, 0x65)))
    }

    @Test fun keyframesCarryTheConfigLikeAMirrorStream() {
        // csd-0 and csd-1 as MediaExtractor gives them (start codes included), or bare.
        val config = AnnexB.config(b(0, 0, 0, 1) + sps, pps, null)
        assertArrayEquals(b(0, 0, 0, 1) + sps + b(0, 0, 0, 1) + pps, config)
        val key = AnnexB.frame(b(0, 0, 0, 1) + idr, keyframe = true, config = config)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(key))
        val other = AnnexB.frame(b(0, 0, 0, 1) + slice, keyframe = false, config = config)
        assertEquals(listOf(1), AnnexB.nalTypes(other))
        assertTrue(AnnexB.startsWithStartCode(key))
    }
}
