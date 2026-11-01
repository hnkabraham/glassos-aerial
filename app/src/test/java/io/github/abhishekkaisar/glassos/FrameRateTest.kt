package io.github.abhishekkaisar.glassos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrameRateTest {
    // Modes like a Fire TV's, with its inexact rates plus a 50 Hz mode for 25 fps scenes; current 2160p at 59.94.
    private val modes = listOf(
        ModeSpec(1, 1920, 1080, 60.000004f), ModeSpec(2, 1920, 1080, 59.939999f), ModeSpec(3, 3840, 2160, 59.939999f),
        ModeSpec(5, 3840, 2160, 50f), ModeSpec(6, 3840, 2160, 60.000004f), ModeSpec(7, 3840, 2160, 30f),
        ModeSpec(8, 3840, 2160, 29.969999f), ModeSpec(9, 3840, 2160, 25f), ModeSpec(10, 3840, 2160, 24f),
        ModeSpec(11, 3840, 2160, 23.976f), ModeSpec(13, 1920, 1080, 23.976f))
    private val current = modes.first { it.id == 3 }

    @Test fun ratesFitWholeMultiplesOnly() {
        assertTrue(FrameRate.fits(59.939999f, 29.97f))
        assertTrue(FrameRate.fits(59.939999f, 29.97003f))
        assertTrue(FrameRate.fits(23.976f, 23.976f))
        assertTrue(FrameRate.fits(50f, 25f))
        assertFalse(FrameRate.fits(60.000004f, 29.97f))
        assertFalse(FrameRate.fits(59.939999f, 30f))
        assertFalse(FrameRate.fits(59.939999f, 23.976f))
        assertFalse(FrameRate.fits(59.939999f, 25f))
        assertFalse(FrameRate.fits(0f, 30f))
        assertFalse(FrameRate.fits(60f, 0f))
    }

    @Test fun nothingChangesForScenesThatAlreadyFit() {
        // The Apple aerials: 29.97 fps on the usual 59.94 Hz.
        assertNull(FrameRate.pick(modes, current, listOf(29.97003f)))
        assertNull(FrameRate.pick(modes, current, listOf(59.94f)))
    }

    @Test fun otherRatesGetTheBestModeAtTheSameSize() {
        assertEquals(6, FrameRate.pick(modes, current, listOf(30f)))      // 60 Hz, not 30 Hz
        assertEquals(5, FrameRate.pick(modes, current, listOf(25f)))      // 50 Hz
        assertEquals(11, FrameRate.pick(modes, current, listOf(23.976f))) // 23.976 Hz at 2160p, never the 1080p one
        assertEquals(10, FrameRate.pick(modes, current, listOf(24f)))
        // Nothing at this size fits 12.5 fps... except 25 and 50 Hz: the highest wins.
        assertEquals(5, FrameRate.pick(modes, current, listOf(12.5f)))
        // A rate no mode fits: stay.
        assertNull(FrameRate.pick(modes, current, listOf(17f)))
        // At 1080p the choice stays at 1080p.
        assertEquals(1, FrameRate.pick(modes, modes.first { it.id == 2 }, listOf(30f)))
        // Every scene that may play must fit: 25 and 50 fps share 50 Hz; 29.97 and 30 fps share no mode, so no switch.
        assertEquals(5, FrameRate.pick(modes, current, listOf(25f, 50f)))
        assertNull(FrameRate.pick(modes, current, listOf(29.97003f, 30f)))
        assertNull(FrameRate.pick(modes, current, emptyList()))
    }

    @Test fun frameRateComesFromFrameTimesNotTheContainerField() {
        // 29.97 fps, in decode order with B-frames out of order.
        val ntsc = (0 until 60).map { Math.round(it * 1_000_000.0 * 1001 / 30000) }
        val decodeOrder = ntsc.chunked(3).flatMap { listOf(it[0]) + it.drop(1).reversed() }
        assertEquals(29.97f, FrameRate.fpsFromTimes(decodeOrder)!!, 0.01f)
        // Cut off in decode order: the last frames' neighbours are missing, which must not stretch the frame length.
        val cut = decodeOrder.take(59) - decodeOrder[57] + listOf(ntsc[59] + 66_733L, ntsc[59] + 100_100L)
        assertEquals(29.97f, FrameRate.fpsFromTimes(cut)!!, 0.01f)
        assertEquals(25f, FrameRate.fpsFromTimes((0 until 30).map { it * 40_000L })!!, 0.001f)
        // Times rounded to milliseconds (33 and 34 ms in turn) still read as 29.97.
        assertEquals(29.97f, FrameRate.fpsFromTimes((0 until 60).map { Math.round(it * 1001.0 / 30) * 1000L })!!, 0.02f)
        assertNull(FrameRate.fpsFromTimes(listOf(0L, 33_366L)))
        assertNull(FrameRate.fpsFromTimes(List(20) { 5L }))
        assertNull(FrameRate.fpsFromTimes((0 until 20).map { it * 1_000_000L }))
    }

    @Test fun backupsCarryTheChoice() {
        val text = BackupCodec.encode(BackupData(display = DisplayBackup(matchFrameRate = true)), "test", 0)
        assertEquals(true, (BackupCodec.decode(text) as BackupResult.Ok).data.display!!.matchFrameRate)
        val doc = JSONObject(text).apply { getJSONObject("display").put("matchFrameRate", "yes") }
        assertTrue(BackupCodec.decode(doc.toString()) is BackupResult.Error)
    }
}
