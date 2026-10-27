package io.github.abhishekkaisar.glassos.airplay

import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorTest {
    @Test fun aReceiverRunningWhileOffIsStoppedUnlessAStopOrRestartIsUnderWay() {
        assertTrue(strayRun(wanted = false, pending = false, state = State.RUNNING))
        assertTrue(strayRun(wanted = false, pending = false, state = State.STARTING))
        // Already stopping (or a quick off and on waiting for STOPPED): nothing more to send.
        assertFalse(strayRun(wanted = false, pending = true, state = State.RUNNING))
        assertFalse(strayRun(wanted = true, pending = false, state = State.RUNNING))
        assertFalse(strayRun(wanted = false, pending = false, state = State.STOPPED))
        assertFalse(strayRun(wanted = false, pending = false, state = State.ERROR))
    }
}
