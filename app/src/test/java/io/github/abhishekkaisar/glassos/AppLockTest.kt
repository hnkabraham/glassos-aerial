package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private val yt = "com.amazon.firetv.youtube"
    private val netflix = "com.netflix.ninja"

    @Test fun codesUseArrowsAndMediaKeysOnly() {
        assertEquals('U', LockCode.letter(19))
        assertEquals('D', LockCode.letter(20))
        assertEquals('L', LockCode.letter(21))
        assertEquals('R', LockCode.letter(22))
        assertEquals('W', LockCode.letter(89))
        assertEquals('F', LockCode.letter(90))
        // Play/Pause, Play and Pause are one button.
        assertEquals('P', LockCode.letter(85))
        assertEquals('P', LockCode.letter(126))
        assertEquals('P', LockCode.letter(127))
        // OK, Enter, Back, Home, Menu and volume never count.
        for (k in listOf(23, 66, 4, 3, 82, 24, 25, 164)) assertNull(LockCode.letter(k))
    }

    @Test fun storedCodeMatchesOnlyItself() {
        val stored = LockCode.store("UUDP")
        assertTrue(LockCode.matches(stored, "UUDP"))
        assertFalse(LockCode.matches(stored, "UUDD"))
        assertFalse(LockCode.matches(stored, "PDUU"))
        assertFalse(LockCode.matches(stored, "UUD"))
        assertFalse(LockCode.matches(stored, "UUDPP"))
        assertFalse(stored.contains("UUDP"))
    }

    @Test fun eachStoreIsSalted() {
        val a = LockCode.store("LRLR")
        val b = LockCode.store("LRLR")
        assertNotEquals(a, b)
        assertTrue(LockCode.matches(a, "LRLR"))
        assertTrue(LockCode.matches(b, "LRLR"))
        assertEquals(LockCode.store("LRLR", "fixed"), LockCode.store("LRLR", "fixed"))
    }

    @Test fun validCodesAreFourRemoteLetters() {
        for (good in listOf("UDLR", "WPFP", "UUUU", "FFWW")) assertTrue(good, LockCode.valid(good))
        for (bad in listOf("", "UDL", "UDLRR", "udlr", "UDLX", "UD R", "UDL\n", "UDLR\n", "ＵＤＬＲ", "1234", "UD;R"))
            assertFalse(bad, LockCode.valid(bad))
        // Every letter a remote button gives is one a code may hold.
        for (k in listOf(19, 20, 21, 22, 89, 90, 85, 126, 127)) {
            val c = LockCode.letter(k)!!
            assertTrue(LockCode.valid("$c$c$c$c"))
        }
    }

    @Test fun codeLettersMatchGlassOSManager() {
        // manager/core.py accepts ^[UDLRWPF]{4}$; manager/tests/test_core.py checks the same seven letters.
        assertEquals(setOf('U', 'D', 'L', 'R', 'W', 'P', 'F'), LockCode.LETTERS)
        assertEquals("DFLPRUW", ('A'..'Z').filter { LockCode.valid("$it$it$it$it") }.joinToString(""))
    }

    @Test fun onlyValidCodesAreStored() {
        for (bad in listOf("", "UDLX", "udlr", "UDLRR")) {
            assertThrows(IllegalArgumentException::class.java) { LockCode.store(bad) }
            assertThrows(IllegalArgumentException::class.java) { LockCode.store(bad, "fixed") }
        }
        assertTrue(LockCode.matches(LockCode.store("WPFR"), "WPFR"))
    }

    @Test fun missingOrDamagedCodeNeverMatches() {
        for (s in listOf("", ":", "salt:", ":hash", "nocolon")) assertFalse(LockCode.matches(s, "UUDP"))
    }

    @Test fun waitFollowsTooManyWrongCodes() {
        val t = LockTries()
        for (i in 1 until LockCode.TRIES) assertFalse(t.fail(1000L))
        assertEquals(0L, t.waitMs(1000L))
        assertTrue(t.fail(1000L))
        assertEquals(LockCode.WAIT_MS, t.waitMs(1000L))
        assertEquals(LockCode.WAIT_MS - 10_000, t.waitMs(11_000L))
        assertEquals(0L, t.waitMs(1000L + LockCode.WAIT_MS))
        // After the wait, a full set of tries again.
        for (i in 1 until LockCode.TRIES) assertFalse(t.fail(40_000L))
    }

    @Test fun rightCodeResetsTries() {
        val t = LockTries()
        repeat(LockCode.TRIES - 1) { t.fail(0L) }
        t.pass()
        assertFalse(t.fail(0L))
        assertEquals(0L, t.waitMs(0L))
    }

    @Test fun lockedAppNeedsCodeWhenItComesToTheFront() {
        val s = LockSession()
        assertTrue(s.front(yt, 100L, setOf(yt)))
        assertFalse(s.front(netflix, 200L, setOf(yt)))
    }

    @Test fun unlockedAppStaysOpenWhileInFront() {
        val s = LockSession()
        s.unlock(yt, 1000L)
        assertFalse(s.front(yt, 1050L, setOf(yt)))
        // A second activity of the same app, or a cast into it while it is open.
        assertFalse(s.front(yt, 5000L, setOf(yt)))
    }

    @Test fun anotherAppLocksItAgain() {
        val s = LockSession()
        s.unlock(yt, 1000L)
        assertFalse(s.front(netflix, 2000L, setOf(yt)))
        assertNull(s.pkg)
        assertTrue(s.front(yt, 3000L, setOf(yt)))
    }

    @Test fun eventsFromBeforeTheUnlockDoNotEndIt() {
        val s = LockSession()
        s.unlock(yt, 1000L)
        // The home that asked for the code, read by a poll after the unlock.
        assertFalse(s.front("com.wolf.tn", 900L, setOf(yt)))
        assertEquals(yt, s.pkg)
        assertFalse(s.front(yt, 1100L, setOf(yt)))
    }

    @Test fun endLocksAgain() {
        val s = LockSession()
        s.unlock(yt, 1000L)
        s.end()
        assertTrue(s.front(yt, 1100L, setOf(yt)))
    }

    private val photos = "com.amazon.bueller.photos"
    private val noEntry: (String) -> Boolean = { false }
    private val hasEntry: (String) -> Boolean = { true }

    private fun screensaver(pkg: String, cls: String?, locked: Set<String> = setOf(yt), saver: String? = photos,
                            opens: (String) -> Boolean = noEntry, fire: Boolean = false) =
        ScreensaverFront.matches(pkg, cls, locked, fire, { saver }, opens)

    @Test fun dreamHostIsAScreensaver() {
        assertTrue(screensaver("com.google.android.backdrop", ScreensaverFront.DREAM_ACTIVITY))
        assertTrue(screensaver("io.github.abhishekkaisar.glassos.aerial", ScreensaverFront.DREAM_ACTIVITY, saver = null))
    }

    @Test fun startScreensaverTrampolineIsAScreensaver() {
        assertTrue(screensaver(ScreensaverFront.SYSTEM_UI, ScreensaverFront.SOMNAMBULATOR, saver = null))
        // Only that activity: System UI's others are not.
        assertFalse(screensaver(ScreensaverFront.SYSTEM_UI, "com.android.systemui.tv.TvNotificationPanelActivity", saver = null))
        assertFalse(screensaver("com.example.fake", ScreensaverFront.SOMNAMBULATOR, saver = null))
    }

    @Test fun chosenScreensaverPackageWithoutAnEntryIsAScreensaver() {
        assertTrue(screensaver(photos, "com.amazon.bueller.photos.ScreensaverActivity"))
        assertTrue(screensaver(photos, null))
    }

    @Test fun chosenScreensaverPackageThatPeopleOpenIsNot() {
        // An app or a home that is also the screensaver ends an unlock like any other.
        assertFalse(screensaver(photos, "com.amazon.bueller.photos.MainActivity", opens = hasEntry))
        assertFalse(screensaver("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity", saver = "com.amazon.tv.launcher",
            opens = { it == "com.amazon.tv.launcher" }))
    }

    @Test fun fireTvAmbientExperienceIsAScreensaver() {
        val ambient = Platform.FIRE_AMBIENT
        // Whatever the screensaver setting names, and even if people can open it.
        assertTrue(screensaver(ambient, "$ambient.AmbientActivity", fire = true))
        assertTrue(screensaver(ambient, "$ambient.AmbientActivity", saver = null, opens = hasEntry, fire = true))
        // Only on a Fire TV.
        assertFalse(screensaver(ambient, "$ambient.AmbientActivity", saver = null, fire = false))
        assertFalse(screensaver(netflix, "com.netflix.ninja.MainActivity", fire = true))
        assertFalse(screensaver(Platform.FIRE_HOME, "com.amazon.tv.launcher.ui.HomeActivity", fire = true))
    }

    @Test fun ordinaryAppIsNotAScreensaver() {
        assertFalse(screensaver(netflix, "com.netflix.ninja.MainActivity"))
        assertFalse(screensaver(netflix, "com.netflix.ninja.MainActivity", saver = null))
        assertFalse(screensaver("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity"))
    }

    @Test fun lockedAppNeverPassesForAScreensaver() {
        assertFalse(screensaver(yt, ScreensaverFront.DREAM_ACTIVITY))
        assertFalse(screensaver(yt, "com.amazon.firetv.youtube.Main", saver = yt))
        assertFalse(screensaver(ScreensaverFront.SYSTEM_UI, ScreensaverFront.SOMNAMBULATOR, locked = setOf(ScreensaverFront.SYSTEM_UI)))
        assertFalse(screensaver(photos, null, locked = setOf(photos)))
        assertFalse(screensaver(Platform.FIRE_AMBIENT, null, locked = setOf(Platform.FIRE_AMBIENT), fire = true))
    }

    @Test fun lookupsHappenOnlyWhenNeeded() {
        var reads = 0
        var asks = 0
        val saver = { reads++; photos }
        val opens: (String) -> Boolean = { asks++; false }
        assertTrue(ScreensaverFront.matches(yt, ScreensaverFront.DREAM_ACTIVITY, emptySet(), false, saver, opens))
        assertFalse(ScreensaverFront.matches(yt, ScreensaverFront.DREAM_ACTIVITY, setOf(yt), false, saver, opens))
        assertTrue(ScreensaverFront.matches(Platform.FIRE_AMBIENT, null, emptySet(), true, saver, opens))
        assertEquals(0 to 0, reads to asks)
        // An app that is not the screensaver's package is never looked up in the package manager.
        assertFalse(ScreensaverFront.matches(netflix, "x", emptySet(), true, saver, opens))
        assertEquals(1 to 0, reads to asks)
    }

    @Test fun screensaverPackageIsTheFirstComponents() {
        assertEquals(photos, ScreensaverFront.saverPackage("$photos/.daydream.ScreenSaverService"))
        assertEquals("io.github.abhishekkaisar.glassos.aerial", ScreensaverFront.saverPackage(
            "io.github.abhishekkaisar.glassos.aerial/io.github.abhishekkaisar.glassos.AerialDream,$photos/.Saver"))
        assertEquals(photos, ScreensaverFront.saverPackage(" $photos/.Saver "))
        for (none in listOf(null, "", " ", "/.Saver", ",$photos/.Saver")) assertNull(none, ScreensaverFront.saverPackage(none))
    }
}
