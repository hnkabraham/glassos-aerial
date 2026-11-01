package io.github.abhishekkaisar.glassos

import org.junit.Assert.*
import org.junit.Test

class PlatformTest {
    @Test fun ambientExperienceIsOnUnlessSetToZero() {
        // As GlassOS Manager reads it: a setting that is not there counts as on where the TV has Ambient Experience.
        assertFalse(Platform.ambientOn("0") { true })
        assertFalse(Platform.ambientOn("0") { false })
        assertTrue(Platform.ambientOn("1") { false })
        assertTrue(Platform.ambientOn("-1") { true })
        assertTrue(Platform.ambientOn(null) { true })
        assertTrue(Platform.ambientOn("") { true })
        assertFalse(Platform.ambientOn(null) { false })
    }
}
