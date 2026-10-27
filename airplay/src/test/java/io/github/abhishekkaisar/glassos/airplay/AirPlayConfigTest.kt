package io.github.abhishekkaisar.glassos.airplay

import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig.Companion.DEFAULTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayConfigTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** A valid value of [k] that differs from its default. */
    private fun other(k: AirPlayConfig.Key<*>): Any = when (k) {
        is AirPlayConfig.BoolKey -> !k.def
        is AirPlayConfig.IntKey -> if (k.def != k.range.first) k.range.first else k.range.last
        is AirPlayConfig.ChoiceKey -> k.choices.first { it != k.def }
        is AirPlayConfig.NameKey -> "Living Room"
    }

    @Suppress("UNCHECKED_CAST")
    private fun AirPlayConfig.set(k: AirPlayConfig.Key<*>, v: Any) = with(k as AirPlayConfig.Key<Any>, v)

    @Test fun defaults() {
        val c = DEFAULTS
        assertEquals("", c.name)
        assertFalse(c.pin)
        assertEquals("1080p", c.res)
        assertTrue(c.syncVolume)
        assertEquals(7000, c.port)
        assertNull(c.h265)
        assertEquals(60, c.maxFps)
        assertFalse(c.overscanned)
        assertTrue(c.allowNewConnection)
        assertEquals(-1, c.audioLatencyMs)
        assertTrue(c.enforceSdr)
        assertTrue(c.allowFrameDrop)
        assertNull(c.operatingRate)
        assertFalse(c.scheduledRelease)
        assertTrue(c.realtimePriority)
        assertFalse(c.lowLatency)
        assertTrue(c.audioAutoBuffer)
        assertEquals(40, c.audioCushionMs)
        assertEquals(3, c.audioAdaptiveStep)
        assertEquals(95, c.audioPercentile)
        assertEquals(0, c.oboeBufferFrames)
        assertTrue(c.aac)
        assertTrue(c.advertiseMirroring)
        assertTrue(c.advertiseAudio)
        assertFalse(c.benchmarkLog)
        for (k in AirPlayConfig.KEYS) assertEquals(k.name, k.def, DEFAULTS.toMap()[k.name])
    }

    @Test fun keysAreUniqueAndPrefixedAndOnIsNotOne() {
        val names = AirPlayConfig.KEYS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(listOf("name", "pin", "res", "sync_volume"), names.take(4))
        for (k in AirPlayConfig.KEYS) assertEquals("airplay_" + k.name, k.prefKey)
        // airplay_on belongs to the launcher (start/stop), not to the receiver's config.
        assertNull(AirPlayConfig.key("on"))
        // Fixed in this build: no video handover, no ALAC, no forced software decoders.
        for (fixed in listOf("advertise_video", "alac_enabled", "force_sw_aac", "force_sw_alac", "resolution")) {
            assertNull(fixed, AirPlayConfig.key(fixed))
        }
    }

    @Test fun nameIsAtMost63Utf8Bytes() {
        val ascii = DEFAULTS.with(AirPlayConfig.NAME, "").normalise("x".repeat(100))
        assertEquals("x".repeat(63), ascii.name)

        // Two-byte characters: 31 of them (62 bytes) fit, a 32nd would not.
        val accents = DEFAULTS.normalise("é".repeat(40))
        assertEquals("é".repeat(31), accents.name)

        // Four-byte characters (surrogate pairs) are never split.
        val emoji = DEFAULTS.normalise("😀".repeat(20))
        assertEquals(60, bytes(emoji.name))
        assertEquals("😀".repeat(15), emoji.name)

        // Mixed: 1 + 20 x 3 = 61 bytes; one more euro sign would make 64.
        assertEquals("a" + "€".repeat(20), DEFAULTS.normalise("a" + "€".repeat(30)).name)

        // Exactly 63 bytes is kept whole.
        val exact = "€".repeat(21)
        assertEquals(63, bytes(exact))
        assertEquals(exact, DEFAULTS.normalise(exact).name)

        for (s in listOf("x".repeat(500), "é".repeat(200), "😀".repeat(64), "中文".repeat(40))) {
            val n = DEFAULTS.normalise(s).name
            assertTrue(s, bytes(n) <= AirPlayConfig.MAX_NAME_BYTES)
            assertTrue(s, AirPlayConfig.NAME.valid(n))
        }
    }

    @Test fun nameParsingRejectsWhatCannotBeAdvertised() {
        assertEquals("Living Room", AirPlayConfig.NAME.parse("  Living Room "))
        assertEquals("x".repeat(63), AirPlayConfig.NAME.parse("x".repeat(63)))
        assertNull(AirPlayConfig.NAME.parse("x".repeat(64)))
        assertNull(AirPlayConfig.NAME.parse("é".repeat(32)))
        assertNull(AirPlayConfig.NAME.parse("Living\nRoom"))
        assertTrue(AirPlayConfig.NAME.valid(""))
        assertFalse(AirPlayConfig.NAME.valid(" padded "))
    }

    @Test fun normaliseFallsBackToTheDeviceName() {
        assertEquals("Sam's Fire TV", DEFAULTS.normalise("Sam's Fire TV").name)
        assertEquals("Den", DEFAULTS.with(AirPlayConfig.NAME, "Den").normalise("Sam's Fire TV").name)
        // Control characters go, runs of white space become one space.
        assertEquals("Fire TV Stick", DEFAULTS.normalise(" Fire\tTV \u0007 Stick\n").name)
        // Neither name usable: a fixed one, never an empty service name.
        assertEquals(AirPlayConfig.FALLBACK_NAME, DEFAULTS.normalise("   ").name)
        // Only the name changes.
        val n = DEFAULTS.normalise("TV")
        for (k in AirPlayConfig.KEYS) if (k != AirPlayConfig.NAME) assertEquals(DEFAULTS.toMap()[k.name], n.toMap()[k.name])
    }

    @Test fun restartNeededMatrix() {
        val restartKeys = AirPlayConfig.KEYS.filter { it.restart }.map { it.name }.toSet()
        assertEquals(
            setOf("name", "pin", "res", "port", "h265", "max_fps", "overscanned", "allow_new_conn", "audio_latency_ms",
                "enforce_sdr", "allow_frame_drop", "operating_rate", "scheduled_output_buffer_release", "aac_enabled",
                "advertise_mirroring", "advertise_audio", "benchmark_log"),
            restartKeys,
        )
        for (k in AirPlayConfig.KEYS) {
            val changed = DEFAULTS.set(k, other(k))
            assertNotEquals(k.name, DEFAULTS, changed)
            assertEquals(k.name, k.restart, AirPlayConfig.restartNeeded(DEFAULTS, changed))
            assertEquals(k.name, k.restart, AirPlayConfig.restartNeeded(changed, DEFAULTS))
        }
        // Not running yet: a start always (re)starts. Nothing changed: never.
        assertTrue(AirPlayConfig.restartNeeded(null, DEFAULTS))
        assertFalse(AirPlayConfig.restartNeeded(DEFAULTS, DEFAULTS))
        assertFalse(AirPlayConfig.restartNeeded(DEFAULTS, AirPlayConfig.from(DEFAULTS.toMap())))
        // Live keys together stay live; one restart key among them restarts.
        val live = DEFAULTS.with(AirPlayConfig.SYNC_VOLUME, false).with(AirPlayConfig.AUDIO_CUSHION_MS, 80)
            .with(AirPlayConfig.LOW_LATENCY, true)
        assertFalse(AirPlayConfig.restartNeeded(DEFAULTS, live))
        assertTrue(AirPlayConfig.restartNeeded(DEFAULTS, live.with(AirPlayConfig.PIN, true)))
    }

    @Test fun invalidValuesReadAsDefaults() {
        val c = AirPlayConfig.from(mapOf(
            "port" to 0, "res" to "4k", "h265" to "maybe", "max_fps" to 120, "pin" to "true",
            "audio_adaptive_step" to 5, "oboe_buffer_frames" to -1, "name" to "x".repeat(64), "unknown" to 1,
        ))
        assertEquals(DEFAULTS, c)
        val good = AirPlayConfig.from(mapOf("port" to 7100, "res" to "720p", "h265" to "off", "pin" to true, "name" to "Den"))
        assertEquals(7100, good.port)
        assertEquals("720p", good.res)
        assertEquals(false, good.h265)
        assertTrue(good.pin)
        assertEquals("Den", good.name)
    }

    @Test fun prefsUseThePrefixedKeysOnly() {
        val c = AirPlayConfig.fromPrefs(mapOf("airplay_port" to 7100, "port" to 7200, "airplay_on" to true, "name" to "x"))
        assertEquals(7100, c.port)
        assertEquals("", c.name)
        assertEquals(DEFAULTS.with(AirPlayConfig.PORT, 7100), c)
    }

    @Test fun settingTextIsParsedStrictly() {
        assertEquals(true, AirPlayConfig.PIN.parse("true"))
        assertEquals(false, AirPlayConfig.PIN.parse(" FALSE "))
        assertNull(AirPlayConfig.PIN.parse("yes"))
        assertNull(AirPlayConfig.PIN.parse("1"))
        assertEquals(8000, AirPlayConfig.PORT.parse(" 8000 "))
        assertNull(AirPlayConfig.PORT.parse("0"))
        assertNull(AirPlayConfig.PORT.parse("65536"))
        assertNull(AirPlayConfig.PORT.parse("7000x"))
        assertEquals(-1, AirPlayConfig.AUDIO_LATENCY_MS.parse("-1"))
        assertEquals("720p", AirPlayConfig.RES.parse("720P"))
        assertNull(AirPlayConfig.RES.parse("2160p"))
        assertEquals("off", AirPlayConfig.H265.parse("Off"))
        assertNull(AirPlayConfig.MAX_FPS.parse("61"))
        // with() ignores an invalid value.
        assertEquals(DEFAULTS, DEFAULTS.with(AirPlayConfig.PORT, 70000))
        assertEquals(DEFAULTS, DEFAULTS.with(AirPlayConfig.RES, "4k"))
    }

    @Test fun truncateNeverSplitsACharacter() {
        assertEquals("", AirPlayConfig.truncateUtf8("é", 1))
        assertEquals("a", AirPlayConfig.truncateUtf8("aé", 2))
        assertEquals("aé", AirPlayConfig.truncateUtf8("aé", 3))
        assertEquals("", AirPlayConfig.truncateUtf8("😀", 3))
        assertEquals("😀", AirPlayConfig.truncateUtf8("😀", 4))
        // The RAOP service name ("<MAC>@<name>") is cut to one DNS label too.
        val raop = "AABBCCDDEEFF@" + "x".repeat(63)
        assertEquals(63, bytes(AirPlayConfig.truncateUtf8(raop, AirPlayConfig.MAX_NAME_BYTES)))
    }
}
