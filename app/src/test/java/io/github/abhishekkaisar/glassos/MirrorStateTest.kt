package io.github.abhishekkaisar.glassos

import io.github.abhishekkaisar.glassos.MirrorState.Action
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.Mode
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorStateTest {
    private val ready = AirPlayStatus(state = State.RUNNING, name = "Den", port = 7000)
    private val mirroring = ready.copy(mode = Mode.MIRROR, connections = 1, activityVisible = true)
    private val pin = ready.copy(pin = "1234", connections = 1)
    private val music = ready.copy(mode = Mode.AUDIO, connections = 1, title = "Song")
    private val failed = AirPlayStatus(state = State.ERROR, error = AirPlayStatus.ERROR_NATIVE_INIT)

    @Test fun offReadsOffWhateverTheReceiverLastSaid() {
        for (s in listOf(AirPlayStatus(), failed, ready, mirroring, AirPlayStatus(state = State.ERROR, error = AirPlayStatus.ERROR_CRASHED))) {
            assertEquals("Off", MirrorState.statusLine(false, s, gaveUp = false))
            assertEquals("Off", MirrorState.statusLine(false, s, gaveUp = true))
            assertEquals("Screen Mirroring", MirrorState.tileLabel(false, s))
            assertEquals(Action.START, MirrorState.tileAction(false, s))
        }
    }

    @Test fun statusLineFollowsTheReceiver() {
        assertEquals("Starting…", MirrorState.statusLine(true, AirPlayStatus(), false))
        assertEquals("Starting…", MirrorState.statusLine(true, AirPlayStatus(state = State.STARTING), false))
        assertEquals("Ready as “Den”", MirrorState.statusLine(true, ready, false))
        assertEquals("Mirroring", MirrorState.statusLine(true, mirroring, false))
        assertEquals("Mirroring · hidden", MirrorState.statusLine(true, mirroring.copy(activityVisible = false), false))
        assertEquals("Playing", MirrorState.statusLine(true, music, false))
        assertEquals("Showing a code", MirrorState.statusLine(true, pin.copy(activityVisible = true), false))
        assertEquals("Showing a code · hidden", MirrorState.statusLine(true, pin, false))
        assertEquals("Connecting…", MirrorState.statusLine(true, ready.copy(connections = 1), false))
        assertEquals("Stopped unexpectedly", MirrorState.statusLine(true, AirPlayStatus(state = State.ERROR, error = AirPlayStatus.ERROR_CRASHED), true))
        assertEquals("Stopped unexpectedly", MirrorState.statusLine(true, AirPlayStatus(state = State.STARTING), gaveUp = true))
        assertEquals("Could not start", MirrorState.statusLine(true, failed, false))
        assertEquals("No free network port", MirrorState.statusLine(true, failed.copy(error = AirPlayStatus.ERROR_PORT), false))
        assertEquals("Not in this build", MirrorState.statusLine(true, failed.copy(error = AirPlayStatus.ERROR_NATIVE_MISSING), false))
    }

    @Test fun neverShowsThePinOrAppleProductNames() {
        val all = listOf(AirPlayStatus(), failed, ready, mirroring, pin, pin.copy(activityVisible = true), music)
        for (s in all) for (on in listOf(true, false)) {
            val texts = listOf(MirrorState.statusLine(on, s, false), MirrorState.tileLabel(on, s))
            for (t in texts) {
                assertFalse(t, t.contains("1234"))
                assertFalse(t, t.contains("AirPlay"))
            }
        }
    }

    @Test fun tileLabels() {
        assertEquals("Screen Mirroring", MirrorState.tileLabel(true, ready))
        assertEquals("Screen Mirroring", MirrorState.tileLabel(true, AirPlayStatus(state = State.STARTING)))
        assertEquals("Screen Mirroring", MirrorState.tileLabel(true, failed))
        assertEquals("Mirroring", MirrorState.tileLabel(true, mirroring))
        assertEquals("Mirroring", MirrorState.tileLabel(true, ready.copy(mode = Mode.VIDEO)))
        assertEquals("Playing", MirrorState.tileLabel(true, music))
        assertEquals("PIN shown", MirrorState.tileLabel(true, pin))
        assertEquals("PIN shown", MirrorState.tileLabel(true, mirroring.copy(pin = "9876")))
    }

    @Test fun tileShowsDuringASessionAndTogglesOtherwise() {
        assertEquals(Action.SHOW, MirrorState.tileAction(true, mirroring))
        assertEquals(Action.SHOW, MirrorState.tileAction(true, pin))
        assertEquals(Action.SHOW, MirrorState.tileAction(true, music))
        assertEquals(Action.STOP, MirrorState.tileAction(true, ready))
        assertEquals(Action.STOP, MirrorState.tileAction(true, ready.copy(connections = 1)))
        assertEquals(Action.STOP, MirrorState.tileAction(true, AirPlayStatus(state = State.STARTING)))
        // A failed or crashed receiver: the tile turns it off, and pressing it again retries.
        assertEquals(Action.STOP, MirrorState.tileAction(true, failed))
        assertEquals(Action.START, MirrorState.tileAction(false, failed))
    }

    @Test fun launcherStartsOnlyAReceiverItDoesNotKnow() {
        assertTrue(MirrorState.shouldStart(on = true, connected = false, s = AirPlayStatus()))
        assertFalse(MirrorState.shouldStart(on = false, connected = false, s = AirPlayStatus()))
        assertFalse(MirrorState.shouldStart(on = true, connected = true, s = ready))
        assertFalse(MirrorState.shouldStart(on = true, connected = false, s = AirPlayStatus(state = State.STARTING)))
        // Never a retry loop on every resume after a failure or giving up.
        assertFalse(MirrorState.shouldStart(on = true, connected = false, s = failed))
        assertFalse(MirrorState.shouldStart(on = true, connected = false, s = AirPlayStatus(state = State.ERROR, error = AirPlayStatus.ERROR_CRASHED)))
    }

    @Test fun resolutionSteps() {
        assertEquals("720p", MirrorState.stepResolution("1080p", 1))
        assertEquals("auto", MirrorState.stepResolution("720p", 1))
        assertEquals("720p", MirrorState.stepResolution("auto", -1))
        assertEquals("auto", MirrorState.stepResolution("bogus", 0))
        assertEquals("Auto", MirrorState.resolutionLabel("auto"))
        assertEquals("1080p", MirrorState.resolutionLabel("1080p"))
        assertEquals("720p", MirrorState.resolutionLabel("720p"))
    }
}
