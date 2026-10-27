package io.github.abhishekkaisar.glassos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun olderBackupsKeepAppearanceUnspecified() {
        val text = BackupCodec.encode(BackupData(display = DisplayBackup(sound = true)), "test", 0)
        val result = (BackupCodec.decode(text) as BackupResult.Ok).data.display!!
        assertNull(result.theme)
        assertNull(result.movingClock)
        assertNull(result.customArt)
    }

    @Test fun appearanceRoundTripsAndUnknownThemesAreRejected() {
        val settings = DisplayBackup(theme = "forest", movingClock = false, customArt = true)
        val text = BackupCodec.encode(BackupData(display = settings), "test", 0)
        assertEquals(settings, (BackupCodec.decode(text) as BackupResult.Ok).data.display)
        for (bad in listOf("future-theme", "../../outside")) {
            val doc = JSONObject(text).apply { getJSONObject("display").put("theme", bad) }
            assertTrue(BackupCodec.decode(doc.toString()) is BackupResult.Error)
        }
        val doc = JSONObject(text).apply { getJSONObject("display").put("movingClock", "yes") }
        assertTrue(BackupCodec.decode(doc.toString()) is BackupResult.Error)
    }

    @Test fun clockVisitsAllCornersAndCanBeFixed() {
        for (start in listOf(0L, 100L, Long.MAX_VALUE - 4L)) {
            assertEquals(setOf(0, 1, 2, 3), (start until start + 4).map { CinemaPosition.corner(it, true) }.toSet())
            assertEquals(setOf(2), (start until start + 4).map { CinemaPosition.corner(it, false) }.toSet())
        }
    }

    @Test fun sceneNameKeepsClearOfTheClock() {
        assertEquals(0, CinemaPosition.labelCorner(null))
        for (clock in 0..3) assertNotEquals(clock, CinemaPosition.labelCorner(clock))
        // Moved out of the top left, the name is also clear of the clock's next corner (top right).
        assertEquals(3, CinemaPosition.labelCorner(0))
        assertNotEquals(CinemaPosition.corner(1L, true), CinemaPosition.labelCorner(CinemaPosition.corner(0L, true)))
    }
}
