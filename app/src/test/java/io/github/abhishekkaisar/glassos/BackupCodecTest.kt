package io.github.abhishekkaisar.glassos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private val folders = listOf(
        AppFolder("folder:kids1", "Kids", listOf("com.pbs.kids", "com.disney.disneyplus")),
        AppFolder("folder:music1", "Music", listOf("com.spotify.tv.android")))
    private val full = BackupData(
        dock = listOf("com.netflix.ninja", "folder:kids1"),
        order = listOf("folder:music1", "com.plexapp.android"),
        hidden = listOf("com.amazon.weather"),
        folders = folders,
        display = DisplayBackup(sound = false, topShelf = "aerial", scene = "scene:ocean", cinemaDelaySec = 120, cinemaClock = true,
            nightEnabled = true, nightStart = 22 * 60, nightEnd = 7 * 60, nightLevel = 2, nightSun = true, nightStartOffset = 30,
            nightEndOffset = -15, reduceTransparency = true, reduceMotion = false, mostUsed = true),
        scenery = SceneryBackup(shuffle = true, favoritesOnly = false, labels = true,
            favorites = listOf("01 - Golden Gate, San Francisco.mp4"), hidden = listOf("09-night.mov")),
        mirroring = MirroringBackup(on = true, name = "Living Room ’TV’", pin = true, resolution = "720p", syncVolume = false))

    private fun ok(text: String): BackupData = when (val r = BackupCodec.decode(text)) {
        is BackupResult.Ok -> r.data
        is BackupResult.Error -> { fail("rejected: ${r.message}"); throw AssertionError() }
    }

    private fun rejects(text: String, vararg words: String) {
        val r = BackupCodec.decode(text)
        assertTrue("should be rejected: $text", r is BackupResult.Error)
        val msg = (r as BackupResult.Error).message
        for (w in words) assertTrue("'$msg' should mention '$w'", msg.contains(w))
    }

    private fun doc(edit: JSONObject.() -> Unit): String =
        JSONObject(BackupCodec.encode(full, "test", 0L)).apply(edit).toString()

    @Test fun roundTrip() {
        val text = BackupCodec.encode(full, "1.1.0-aerial.1", 1_700_000_000_000L)
        assertEquals(full, ok(text))
        val r = BackupCodec.decode(text) as BackupResult.Ok
        assertEquals("2023-11-14T22:13:20Z", r.exportedAt)
    }

    @Test fun neverExportsPrivateOrDeviceSettings() {
        val text = BackupCodec.encode(full, "x", 0L)
        for (k in listOf("tmdbKey", "country", "setupDone", "askedStorage", "replaceHome", "sleepAt",
                "position", "clip\"", "file:", "/storage", "/sdcard", "password", "serial", "foregroundMillis", "usageHistory",
                "sunLat", "sunLon", "sunLabel", "latitude", "longitude", "lockCode", "locked",
                // Screen Mirroring: its pairing identity, private prefs and advanced settings stay on the TV.
                "airplay", "pem", "mac", "h265", "max_fps", "advertise", "benchmark", "allow_new_conn")) {
            assertFalse("must not contain $k", text.contains(k))
        }
        val o = JSONObject(text)
        assertEquals(setOf("format", "version", "app", "exportedAt", "layout", "folders", "display", "scenery", "screenMirroring"), o.keySet())
        assertEquals(setOf("on", "name", "pin", "resolution", "syncVolume"), o.getJSONObject("screenMirroring").keySet())
    }

    @Test fun screenMirroringRoundTripsAndOlderBackupsKeepIt() {
        val m = ok(BackupCodec.encode(full, "x", 0L)).mirroring!!
        assertEquals(MirroringBackup(on = true, name = "Living Room ’TV’", pin = true, resolution = "720p", syncVolume = false), m)
        // Empty is the TV's own name; every resolution choice is valid.
        for (res in listOf("auto", "1080p", "720p")) {
            val d = ok(doc { getJSONObject("screenMirroring").put("name", "").put("resolution", res) }).mirroring!!
            assertEquals("", d.name)
            assertEquals(res, d.resolution)
        }
        // A 63-byte name (one DNS-SD label) is the longest allowed.
        assertEquals(21, ok(doc { getJSONObject("screenMirroring").put("name", "€".repeat(21)) }).mirroring!!.name!!.length)
        // A backup from before Screen Mirroring (or with a partial section) leaves the rest as it is.
        assertEquals(null, ok(doc { remove("screenMirroring") }).mirroring)
        val partial = ok(doc { put("screenMirroring", JSONObject().put("pin", false)) }).mirroring!!
        assertEquals(MirroringBackup(pin = false), partial)
        assertTrue(full.summary().endsWith("Screen Mirroring settings"))
    }

    @Test fun rejectsBadScreenMirroring() {
        rejects(doc { put("screenMirroring", "on") }, "screenMirroring", "object")
        rejects(doc { getJSONObject("screenMirroring").put("on", "true") }, "screenMirroring.on", "true or false")
        rejects(doc { getJSONObject("screenMirroring").put("pin", 1) }, "screenMirroring.pin", "true or false")
        rejects(doc { getJSONObject("screenMirroring").put("syncVolume", JSONObject()) }, "screenMirroring.syncVolume")
        rejects(doc { getJSONObject("screenMirroring").put("resolution", "4k") }, "screenMirroring.resolution", "auto, 1080p, 720p")
        rejects(doc { getJSONObject("screenMirroring").put("resolution", "1080P") }, "screenMirroring.resolution")
        rejects(doc { getJSONObject("screenMirroring").put("resolution", 1080) }, "screenMirroring.resolution", "text")
        rejects(doc { getJSONObject("screenMirroring").put("name", 7) }, "screenMirroring.name", "text")
        rejects(doc { getJSONObject("screenMirroring").put("name", "Den\nTV") }, "screenMirroring.name", "63 bytes")
        rejects(doc { getJSONObject("screenMirroring").put("name", "Den\u0007") }, "screenMirroring.name")
        rejects(doc { getJSONObject("screenMirroring").put("name", " Den") }, "screenMirroring.name")
        rejects(doc { getJSONObject("screenMirroring").put("name", "x".repeat(64)) }, "screenMirroring.name", "63 bytes")
        rejects(doc { getJSONObject("screenMirroring").put("name", "\u20ac".repeat(22)) }, "screenMirroring.name")
    }

    @Test fun exportDropsAnInvalidMirroringValue() {
        val messy = full.copy(mirroring = MirroringBackup(on = false, name = "Den\tTV", resolution = "4k"))
        assertEquals(MirroringBackup(on = false), ok(BackupCodec.encode(messy, "x", 0L)).mirroring)
    }

    @Test fun olderBackupsLeaveAppOrderUnspecified() {
        val d = ok(doc { getJSONObject("display").remove("mostUsed") })
        assertEquals(null, d.display!!.mostUsed)
    }

    @Test fun nightTravelsAsOne() {
        val night = JSONObject(BackupCodec.encode(full, "x", 0L)).getJSONObject("display").getJSONObject("night")
        assertEquals(setOf("enabled", "start", "end", "level", "sun", "startOffset", "endOffset"), night.keySet())
        // A backup from before sun mode restores clock times, even onto a TV that follows the sun.
        val old = ok(doc { getJSONObject("display").getJSONObject("night").apply { remove("sun"); remove("startOffset"); remove("endOffset") } })
        assertEquals(false, old.display!!.nightSun)
        assertEquals(null, old.display!!.nightStartOffset)
        assertEquals(22 * 60, old.display!!.nightStart)
        // No night section at all keeps the current night settings.
        assertEquals(null, ok(doc { getJSONObject("display").remove("night") }).display!!.nightSun)
    }

    @Test fun rejectsBadNightSunFields() {
        rejects(doc { getJSONObject("display").getJSONObject("night").put("sun", "yes") }, "display.night.sun", "true or false")
        rejects(doc { getJSONObject("display").getJSONObject("night").put("startOffset", 241) }, "display.night.startOffset", "240")
        rejects(doc { getJSONObject("display").getJSONObject("night").put("endOffset", -241) }, "display.night.endOffset")
        rejects(doc { getJSONObject("display").getJSONObject("night").put("endOffset", 1.5) }, "display.night.endOffset", "whole number")
        val d = ok(doc { getJSONObject("display").getJSONObject("night").put("startOffset", -240).put("endOffset", 240) })
        assertEquals(-240, d.display!!.nightStartOffset)
        assertEquals(240, d.display!!.nightEndOffset)
    }

    @Test fun automaticOrderPreferenceIsStrictlyBoolean() {
        rejects(doc { getJSONObject("display").put("mostUsed", "yes") }, "display.mostUsed", "true or false")
        val d = ok(doc { getJSONObject("display").put("mostUsed", false) })
        assertEquals(false, d.display!!.mostUsed)
    }

    @Test fun exportDropsWhatImportWouldRefuse() {
        val messy = full.copy(dock = listOf("folder:gone", "com.pbs.kids", "com.netflix.ninja", "com.netflix.ninja"),
            display = full.display!!.copy(scene = "file:/storage/emulated/0/Pictures/Glass/me.jpg"))
        val back = ok(BackupCodec.encode(messy, "x", 0L))
        assertEquals(listOf("com.netflix.ninja"), back.dock)
        assertEquals(null, back.display!!.scene)
    }

    @Test fun missingSectionsAndUnknownKeysAreFine() {
        val d = ok("""{"format":"glassos-aerial-backup","version":1,"future":{"x":1}}""")
        assertEquals(BackupData(), d)
        assertEquals("no settings", d.summary())
        val p = ok(doc { remove("display"); remove("scenery"); put("extra", 3) })
        assertEquals(null, p.display)
        assertEquals(full.folders, p.folders)
    }

    @Test fun rejectsWrongFileOrVersion() {
        rejects("hello", "not valid JSON")
        rejects("[1,2]", "not valid JSON")
        rejects("""{"format":"something-else","version":1}""", "not a GlassOS Aerial backup")
        rejects("""{"format":"glassos-aerial-backup"}""", "version")
        rejects("""{"format":"glassos-aerial-backup","version":2}""", "newer", "Update")
        rejects("""{"format":"glassos-aerial-backup","version":0}""", "not valid")
        rejects("""{"format":"glassos-aerial-backup","version":"1"}""", "version")
        rejects(" ".repeat(BackupCodec.MAX_BYTES + 1), "too large")
    }

    @Test fun rejectsBadLayout() {
        rejects(doc { put("layout", "x") }, "layout")
        rejects(doc { getJSONObject("layout").put("dock", org.json.JSONArray((1..7).map { "com.app$it.x" })) }, "layout.dock", "limit is 6")
        rejects(doc { getJSONObject("layout").put("dock", org.json.JSONArray(listOf("com.a.b", "com.a.b"))) }, "more than once")
        rejects(doc { getJSONObject("layout").put("order", org.json.JSONArray(listOf("com.a.b", 5))) }, "layout.order[1]", "text")
        rejects(doc { getJSONObject("layout").put("hidden", org.json.JSONArray(listOf("not a package"))) }, "layout.hidden[0]")
        rejects(doc { getJSONObject("layout").put("order", org.json.JSONArray(listOf("folder:nothere"))) }, "folder that is not in the backup")
        rejects(doc { getJSONObject("layout").put("dock", org.json.JSONArray(listOf("com.pbs.kids"))) }, "com.pbs.kids", "Kids")
        rejects(doc { getJSONObject("layout").put("order", org.json.JSONArray(listOf("../../etc"))) }, "layout.order[0]")
    }

    @Test fun rejectsBadFolders() {
        fun withFolders(json: String) = doc { put("folders", org.json.JSONArray(json)); put("layout", JSONObject()) }
        rejects(withFolders("""[{"id":"folder:a","name":"A","members":["com.x.y"]},{"id":"folder:b","name":"B","members":["com.x.y"]}]"""), "more than one folder")
        rejects(withFolders("""[{"id":"folder:a","name":"A","members":["com.x.y","com.x.y"]}]"""), "more than one folder")
        rejects(withFolders("""[{"id":"folder:A!","name":"A","members":[]}]"""), "invalid id")
        rejects(withFolders("""[{"id":"com.x.y","name":"A","members":[]}]"""), "invalid id")
        rejects(withFolders("""[{"id":"folder:a","name":"A"},{"id":"folder:a","name":"B"}]"""), "repeats")
        rejects(withFolders("""[{"id":"folder:a","name":"   "}]"""), "empty name")
        rejects(withFolders("""[{"id":"folder:a","name":"${"x".repeat(41)}"}]"""), "longer than 40")
        rejects(withFolders("""[{"id":"folder:a"}]"""), "no name")
        rejects(withFolders("""[{"id":"folder:a","name":"A","members":"com.x.y"}]"""), "must be a list")
        rejects(withFolders("[" + (1..51).joinToString(",") { """{"id":"folder:f$it","name":"F"}""" } + "]"), "limit is 50")
        rejects(doc { put("folders", JSONObject()) }, "folders")
        // A folder without members is valid.
        ok(withFolders("""[{"id":"folder:a","name":"A"}]"""))
    }

    @Test fun rejectsBadDisplayAndScenery() {
        rejects(doc { getJSONObject("display").put("topShelf", "youtube") }, "display.topShelf")
        rejects(doc { getJSONObject("display").put("background", "file:/sdcard/x.jpg") }, "display.background")
        rejects(doc { getJSONObject("display").put("cinemaDelaySec", 45) }, "display.cinemaDelaySec")
        rejects(doc { getJSONObject("display").put("cinemaDelaySec", 1.5) }, "whole number")
        rejects(doc { getJSONObject("display").put("sound", "yes") }, "display.sound", "true or false")
        rejects(doc { getJSONObject("display").getJSONObject("night").put("start", 1440) }, "display.night.start")
        rejects(doc { getJSONObject("display").getJSONObject("night").put("level", 3) }, "display.night.level")
        rejects(doc { getJSONObject("scenery").put("favorites", org.json.JSONArray(listOf("Movies/x.mp4"))) }, "scenery.favorites[0]", "file name")
        rejects(doc { getJSONObject("scenery").put("hidden", org.json.JSONArray((0..2000).map { "c$it.mp4" })) }, "limit is 2000")
    }

    @Test fun sizeLimitIsInUtf8Bytes() {
        val max = BackupCodec.MAX_BYTES
        assertEquals(max, BackupCodec.fileBytes("a".repeat(max)).size)
        assertTooLarge { BackupCodec.fileBytes("a".repeat(max + 1)) }
        // Two bytes each: exactly at the limit, then one character (two bytes) over it while far fewer characters.
        assertEquals(max, BackupCodec.fileBytes("\u00e9".repeat(max / 2)).size)
        assertTooLarge { BackupCodec.fileBytes("\u00e9".repeat(max / 2 + 1)) }
        assertTooLarge { BackupCodec.fileBytes("\u20ac".repeat(max / 3 + 1)) }
        rejects("\u00e9".repeat(max / 2 + 1), "too large")
    }

    @Test fun exportsAtTheLimitStillRestore() {
        // A real export padded with a long folder list stays readable as long as it is within the limit.
        val many = (1..50).map { i -> AppFolder("folder:f$i", "Földer $i", (1..40).map { "com.example.app$i.n$it" }) }
        val text = BackupCodec.encode(BackupData(folders = many, hidden = emptyList()), "x", 0L)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes <= BackupCodec.MAX_BYTES) { BackupCodec.fileBytes(text); ok(text) } else assertTooLarge { BackupCodec.fileBytes(text) }
    }

    @Test fun writeAtomicRefusesOversizeBeforeCreatingAFile() {
        val dir = java.nio.file.Files.createTempDirectory("glass-backup-test").toFile()
        try {
            val f = Backup.writeAtomic(dir, "b.json", "{\"x\":\"\u00e9\"}".toByteArray(Charsets.UTF_8))
            assertEquals("{\"x\":\"\u00e9\"}", f.readText(Charsets.UTF_8))
            assertEquals(listOf("b.json"), dir.list()!!.toList())
            assertTooLarge { Backup.writeAtomic(dir, "big.json", ByteArray(BackupCodec.MAX_BYTES + 1)) }
            assertEquals(listOf("b.json"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    private fun assertTooLarge(block: () -> Unit) {
        try { block(); fail("expected TooLarge") } catch (e: BackupCodec.TooLarge) {
            assertTrue(e.message!!.contains("256 KB"))
        }
    }

    @Test fun summary() {
        assertEquals("2 top row items, 2 folders, 1 hidden app, 1 favourite scene, 1 hidden scene, display settings, " +
            "Screen Mirroring settings", full.summary())
    }

    @Test fun aTvWithoutScreenMirroringNeitherNamesNorRestoresIt() {
        val here = full.forThisTv(mirroring = false)
        assertEquals(null, here.mirroring)
        assertFalse(here.summary().contains("Screen Mirroring"))
        // Everything else, and whether the layout replaces the current one, stays.
        assertEquals(full.copy(mirroring = null), here)
        assertTrue(here.hasLayout)
        assertEquals(full, full.forThisTv(mirroring = true))
        assertEquals(null, here.mirroringWarning(on = false, pin = false))
    }

    @Test fun restoringSaysPlainlyWhenItTurnsScreenMirroringOn() {
        val on = full.mirroring!!
        assertTrue(full.mirroringWarning(on = false, pin = false)!!.contains("with the code it shows"))
        val noPin = full.copy(mirroring = on.copy(pin = false)).mirroringWarning(on = false, pin = true)!!
        assertTrue(noPin, noPin.contains("any device on your network") && noPin.contains("Require PIN is off"))
        // A backup without the PIN setting keeps this TV's.
        assertTrue(full.copy(mirroring = on.copy(pin = null)).mirroringWarning(on = false, pin = true)!!.contains("code"))
        assertTrue(full.copy(mirroring = on.copy(pin = null)).mirroringWarning(on = false, pin = false)!!.contains("no code"))
        // Already on, or the backup leaves it off: nothing to warn about.
        assertEquals(null, full.mirroringWarning(on = true, pin = false))
        assertEquals(null, full.copy(mirroring = on.copy(on = false)).mirroringWarning(on = false, pin = false))
        assertEquals(null, full.copy(mirroring = on.copy(on = null)).mirroringWarning(on = false, pin = false))
        assertEquals(null, BackupData().mirroringWarning(on = false, pin = false))
        assertFalse(full.mirroringWarning(on = false, pin = false)!!.contains("AirPlay"))
    }
}
