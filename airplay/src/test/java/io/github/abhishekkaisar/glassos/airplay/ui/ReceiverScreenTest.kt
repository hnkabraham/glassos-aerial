package io.github.abhishekkaisar.glassos.airplay.ui

import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.Mode
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.State
import io.github.abhishekkaisar.glassos.airplay.ui.ReceiverScreen.Content
import io.github.abhishekkaisar.glassos.airplay.ui.ReceiverScreen.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverScreenTest {
    private val ready = AirPlayStatus(state = State.RUNNING, name = "Den", port = 7000)
    private val mirror = ready.copy(connections = 1, mode = Mode.MIRROR, videoWidth = 1280, videoHeight = 720)
    private val music = ready.copy(connections = 1, mode = Mode.AUDIO, title = "Song", artist = "Band")
    private val pin = ready.copy(connections = 1, pin = "1234")

    @Test fun mirrorVideoAndPinOpenTheScreenMusicNever() {
        for (t in listOf(Trigger.MIRROR_START, Trigger.VIDEO_PLAY, Trigger.PIN, Trigger.SHOW)) {
            assertTrue(t.name, ReceiverScreen.opens(t, visible = false, userHid = false))
            assertFalse("already showing: $t", ReceiverScreen.opens(t, visible = true, userHid = false))
        }
        // There is no music trigger at all: an audio-only session leaves the scenery and Now Playing.
        assertEquals(setOf("MIRROR_START", "VIDEO_PLAY", "PIN", "SHOW"), Trigger.values().map { it.name }.toSet())
    }

    @Test fun afterBackOnlyAPinOrShowBringsTheScreenBack() {
        assertFalse(ReceiverScreen.opens(Trigger.MIRROR_START, visible = false, userHid = true))
        assertFalse(ReceiverScreen.opens(Trigger.VIDEO_PLAY, visible = false, userHid = true))
        assertTrue(ReceiverScreen.opens(Trigger.PIN, visible = false, userHid = true))
        assertTrue(ReceiverScreen.opens(Trigger.SHOW, visible = false, userHid = true))
    }

    @Test fun contentFollowsTheStatus() {
        assertEquals(Content.OFF, ReceiverScreen.content(AirPlayStatus()))
        assertEquals(Content.OFF, ReceiverScreen.content(ready.copy(state = State.STARTING)))
        assertEquals(Content.OFF, ReceiverScreen.content(ready.copy(state = State.ERROR, error = "port")))
        assertEquals(Content.READY, ReceiverScreen.content(ready))
        assertEquals(Content.CONNECTING, ReceiverScreen.content(ready.copy(connections = 1)))
        assertEquals(Content.VIDEO, ReceiverScreen.content(mirror))
        assertEquals(Content.VIDEO, ReceiverScreen.content(ready.copy(mode = Mode.VIDEO)))
        assertEquals(Content.NOW_PLAYING, ReceiverScreen.content(music))
        // A PIN comes first, even while the mirror stream is being set up.
        assertEquals(Content.PIN, ReceiverScreen.content(mirror.copy(pin = "1234")))
    }

    @Test fun theScreenClosesWhenItsSessionEndsOrTheReceiverStops() {
        assertTrue(ReceiverScreen.ended(mirror, ready))
        assertTrue("sender left", ReceiverScreen.ended(mirror, ready.copy(connections = 0)))
        assertTrue("PIN dismissed", ReceiverScreen.ended(pin, ready.copy(connections = 1)))
        assertTrue(ReceiverScreen.ended(mirror, AirPlayStatus(state = State.STOPPED)))
        assertTrue("restart for new settings", ReceiverScreen.ended(ready, ready.copy(state = State.STARTING)))
        assertFalse("PIN entered, mirroring follows", ReceiverScreen.ended(mirror.copy(pin = "1234"), mirror))
        assertFalse("mirroring turns into music", ReceiverScreen.ended(mirror, music))
        assertTrue("mirroring stopped, the sender lingers", ReceiverScreen.ended(mirror, music.copy(title = "", artist = "")))
        assertTrue(ReceiverScreen.ended(music, ready.copy(connections = 1)))
        assertFalse("opened by SHOW with nothing on", ReceiverScreen.ended(ready, ready))
        assertFalse(ReceiverScreen.ended(ready, ready.copy(connections = 1)))
        assertFalse(ReceiverScreen.ended(mirror, mirror.copy(activityVisible = true)))
    }

    @Test fun aScreenOpenedForASessionThatAlreadyEndedCloses() {
        assertTrue(ReceiverScreen.stale(Trigger.MIRROR_START, ready))
        assertTrue(ReceiverScreen.stale(Trigger.PIN, ready))
        assertFalse(ReceiverScreen.stale(Trigger.MIRROR_START, mirror))
        assertFalse(ReceiverScreen.stale(Trigger.PIN, pin))
        assertFalse(ReceiverScreen.stale(Trigger.SHOW, ready))
    }

    @Test fun keepsTheTvAwakeOnlyWithASender() {
        assertFalse(ReceiverScreen.keepsScreenOn(ready))
        assertFalse(ReceiverScreen.keepsScreenOn(AirPlayStatus()))
        assertTrue(ReceiverScreen.keepsScreenOn(ready.copy(connections = 1)))
        assertTrue(ReceiverScreen.keepsScreenOn(mirror))
        assertTrue(ReceiverScreen.keepsScreenOn(ready.copy(mode = Mode.MIRROR)))
        assertTrue(ReceiverScreen.keepsScreenOn(ready.copy(pin = "1234")))
        assertFalse(ReceiverScreen.offersDisconnect(ready))
        assertTrue(ReceiverScreen.offersDisconnect(pin))
    }

    @Test fun textNamesTheFeatureNotTheProtocol() {
        val all = listOf(AirPlayStatus(), ready, ready.copy(connections = 1), mirror, music, pin).flatMap {
            listOf(ReceiverScreen.statusLine(it), ReceiverScreen.hint(it))
        }
        for (t in all) {
            assertFalse(t, t.contains("AirPlay"))
            assertFalse("never shows the PIN: $t", t.contains("1234"))
        }
        assertEquals("Ready as “Den”", ReceiverScreen.statusLine(ready))
        assertEquals("Mirroring", ReceiverScreen.statusLine(mirror))
        assertEquals("Screen Mirroring is off", ReceiverScreen.statusLine(AirPlayStatus()))
    }

    @Test fun theLauncherOffersTheScreenWhileASessionRunsWithoutIt() {
        assertTrue(mirror.offersView)
        assertTrue(pin.offersView)
        assertFalse(mirror.copy(activityVisible = true).offersView)
        assertFalse("music never needs the screen", music.offersView)
        assertFalse(ready.offersView)
        assertFalse("off reads as off", mirror.copy(state = State.ERROR).offersView)
        assertTrue(mirror.decodesVideo)
        assertFalse(music.decodesVideo)
        assertFalse(mirror.copy(state = State.STOPPED).decodesVideo)
    }

    @Test fun aStatusInALogNeverShowsThePin() {
        val s = pin.toString()
        assertFalse(s, s.contains("1234"))
        assertTrue(s, s.contains("pin=shown"))
    }
}
