package io.github.abhishekkaisar.glassos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NowPlayingTest {
    @Test fun cardKeepsClearOfTheClockAndTheSceneName() {
        for (clock in listOf<Int?>(null, 0, 1, 2, 3)) {
            val card = NowPlayingPosition.corner(clock)
            assertNotEquals(clock, card)
            assertNotEquals(CinemaPosition.labelCorner(clock), card)
        }
        // Bottom left unless the scene name has moved there (the clock in the top left), then top right.
        assertEquals(3, NowPlayingPosition.corner(null))
        assertEquals(3, NowPlayingPosition.corner(2))
        assertEquals(1, NowPlayingPosition.corner(0))
        assertEquals(1, NowPlayingPosition.corner(3))
        // A scene name held where it was when the clock moved on (bottom left, clock now bottom right): top right.
        assertEquals(1, NowPlayingPosition.corner(2, label = 3))
        assertEquals(2, NowPlayingPosition.corner(0, label = 1))
    }

    @Test fun movingClockNeverLandsOnTheCard() {
        // The card follows the clock's corner each minute, so the two never share one.
        for (minute in 0L until 8L) {
            val clock = CinemaPosition.corner(minute, true)
            assertNotEquals(clock, NowPlayingPosition.corner(clock))
        }
    }

    @Test fun nowPlayingRoundTripsAndOlderBackupsLeaveItAlone() {
        val text = BackupCodec.encode(BackupData(display = DisplayBackup(nowPlaying = false)), "test", 0)
        assertEquals(false, (BackupCodec.decode(text) as BackupResult.Ok).data.display!!.nowPlaying)
        val older = BackupCodec.encode(BackupData(display = DisplayBackup(sound = true)), "test", 0)
        assertNull((BackupCodec.decode(older) as BackupResult.Ok).data.display!!.nowPlaying)
        val doc = JSONObject(text).apply { getJSONObject("display").put("nowPlaying", "on") }
        assertTrue(BackupCodec.decode(doc.toString()) is BackupResult.Error)
    }
}
