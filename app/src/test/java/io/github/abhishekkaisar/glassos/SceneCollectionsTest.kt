package io.github.abhishekkaisar.glassos

import org.junit.Assert.*
import org.junit.Test

class SceneCollectionsTest {
    @Test fun metadataPreservesCategoriesAndLegacyTitles() {
        val m = SceneMetadata.parse("""{"coast.mp4":{"title":"Waves","collections":["coast","unknown","coast"]},"old.mp4":"Old scene"}""")
        assertEquals(setOf("coast"), SceneMetadata.nameFor("coast.mp4", m).collections)
        assertEquals("Old scene", SceneMetadata.nameFor("old.mp4", m).title)
        assertEquals(emptySet<String>(), SceneMetadata.nameFor("old.mp4", m).collections)
    }
    @Test fun scheduleWrapsMidnightAndChangesAtBoundary() {
        val p = SceneSchedule.parse("""{"version":1,"schedule":[{"start":1200,"collection":"space"},{"start":420,"collection":"coast"}]}""")
        assertEquals("space", SceneSchedule.at(p, 0))
        assertEquals("space", SceneSchedule.at(p, 419))
        assertEquals("coast", SceneSchedule.at(p, 420))
        assertEquals("coast", SceneSchedule.at(p, 1199))
        assertEquals("space", SceneSchedule.at(p, 1200))
    }
    @Test fun malformedScheduleCannotPartlyApply() {
        for (text in listOf("no", "{}", """{"version":2,"schedule":[]}""",
            """{"version":1,"schedule":[{"start":0,"collection":"coast"},{"start":0,"collection":"space"}]}""",
            """{"version":1,"schedule":[{"start":1440,"collection":"coast"}]}""",
            """{"version":1,"schedule":[{"start":true,"collection":"coast"}]}""",
            """{"version":1,"schedule":[{"start":0,"collection":"scheduled"}]}""")) {
            assertTrue(text, SceneSchedule.parse(text).isEmpty())
        }
        assertEquals("all", SceneSchedule.at(emptyList(), 10))
    }
    // ---------- Daily rhythm entries that follow the sun ----------

    private val la = java.util.TimeZone.getTimeZone("America/Los_Angeles")
    private fun noon(y: Int, m: Int, d: Int) = java.util.Calendar.getInstance(la).apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis
    /** Seattle on the June solstice: sunrise 05:12, sunset 21:11. */
    private val seattle = SunTimes.day(noon(2026, 6, 21), la, 47.6062, -122.3321)
    private val sunFile = """{"version":1,"schedule":[{"start":420,"anchor":"sunrise","offset":-30,"collection":"coast"},""" +
        """{"start":1080,"anchor":"sunset","offset":0,"collection":"space"},{"start":720,"collection":"mountains"}]}"""

    /** A day whose sunrise is at [rise] and sunset at [set] (minutes, UTC). */
    private fun sunAt(rise: Int, set: Int) = SunDay(rise * 60_000L, set * 60_000L, null, java.util.TimeZone.getTimeZone("UTC"))

    @Test fun sunEntriesResolveToTodaysTimes() {
        val p = SceneSchedule.parse(sunFile)
        assertEquals(listOf(ScenePeriod(420, "coast", "sunrise", -30), ScenePeriod(720, "mountains"), ScenePeriod(1080, "space", "sunset", 0)), p)
        val today = SceneSchedule.resolve(p, seattle)
        assertEquals(listOf("coast", "mountains", "space"), today.map { it.collection })
        assertEquals(5 * 60 + 12 - 30, today[0].start)
        assertEquals(21 * 60 + 11, today[2].start)
        assertEquals("space", SceneSchedule.at(today, 0))
        assertEquals("coast", SceneSchedule.at(today, 5 * 60))
        assertEquals("mountains", SceneSchedule.at(today, 21 * 60))   // the fixed 18:00 would already be space
        assertEquals("space", SceneSchedule.at(today, 21 * 60 + 11))
    }

    @Test fun withoutSunTimesEntriesKeepTheirStart() {
        val p = SceneSchedule.parse(sunFile)
        assertEquals(p, SceneSchedule.resolve(p, null))
        val polar = SunTimes.day(noon(2026, 6, 21), java.util.TimeZone.getTimeZone("Arctic/Longyearbyen"), 78.2232, 15.6267)
        assertEquals("up", polar.polar)
        assertEquals(listOf(420, 720, 1080), SceneSchedule.resolve(p, polar).map { it.start })
    }

    @Test fun unknownAnchorsAndOffsetsMeanTheFixedTime() {
        fun row(extra: String) = SceneSchedule.parse("""{"version":1,"schedule":[{"start":420,"collection":"coast"$extra}]}""").single()
        assertEquals(ScenePeriod(420, "coast", "sunset", 0), row(""","anchor":"sunset""""))
        assertEquals(ScenePeriod(420, "coast", "sunrise", 240), row(""","anchor":"sunrise","offset":240"""))
        for (extra in listOf(""","anchor":"dusk","offset":10""", ""","anchor":5""", ""","anchor":"sunset","offset":241""",
                ""","anchor":"sunset","offset":-30.0""", ""","anchor":"sunset","offset":"30"""", ""","anchor":"sunset","offset":null""",
                ""","offset":30""")) {
            assertEquals(extra, ScenePeriod(420, "coast"), row(extra))
        }
        // start stays required, as in every older build.
        assertTrue(SceneSchedule.parse("""{"version":1,"schedule":[{"anchor":"sunset","collection":"coast"}]}""").isEmpty())
    }

    @Test fun tiesGoToTheLaterEntry() {
        // A sunrise entry landing on a fixed entry's minute: the later entry in the file (by fixed start) wins.
        val p = SceneSchedule.parse("""{"version":1,"schedule":[{"start":300,"anchor":"sunrise","collection":"coast"},{"start":720,"collection":"mountains"}]}""")
        val today = SceneSchedule.resolve(p, sunAt(720, 1200))
        assertEquals(listOf(720, 720), today.map { it.start })
        assertEquals("mountains", SceneSchedule.at(today, 720))
        val q = SceneSchedule.parse("""{"version":1,"schedule":[{"start":720,"collection":"mountains"},{"start":900,"anchor":"sunrise","collection":"coast"}]}""")
        assertEquals("coast", SceneSchedule.at(SceneSchedule.resolve(q, sunAt(720, 1200)), 720))
        // Sunrise moving past a fixed entry swaps their order.
        assertEquals(listOf("mountains", "coast"), SceneSchedule.resolve(p, sunAt(800, 1200)).map { it.collection })
    }

    @Test fun dstDaysFollowTheClockChange() {
        val p = SceneSchedule.parse("""{"version":1,"schedule":[{"start":360,"anchor":"sunrise","collection":"coast"},{"start":1200,"collection":"space"}]}""")
        fun start(d: Int) = SceneSchedule.resolve(p, SunTimes.day(noon(2026, 3, d), la, 34.0522, -118.2428)).first().start
        assertTrue(start(7) in 6 * 60 + 13..6 * 60 + 15)   // 06:14 PST
        assertTrue(start(8) in 7 * 60 + 11..7 * 60 + 13)   // 07:12 PDT
    }

    @Test fun olderBuildsRunTheFixedTimesOfASunFile() {
        // The parser of 1.5.x, unchanged: only version, start and collection.
        fun oldParse(text: String): List<Pair<Int, String>> = try {
            val o = org.json.JSONObject(text)
            if (o.opt("version") != 1) emptyList() else {
                val a = o.getJSONArray("schedule")
                if (a.length() !in 1..24) emptyList() else {
                    val result = (0 until a.length()).map { i ->
                        val row = a.getJSONObject(i)
                        val start = row.opt("start") as? Int ?: return emptyList()
                        val collection = row.opt("collection") as? String ?: return emptyList()
                        if (start !in 0..1439 || collection !in SceneCollections.labels) return emptyList()
                        start to collection
                    }
                    if (result.map { it.first }.distinct().size != result.size) emptyList() else result.sortedBy { it.first }
                }
            }
        } catch (_: Exception) { emptyList() }
        val old = oldParse(sunFile)
        assertEquals(listOf(420 to "coast", 720 to "mountains", 1080 to "space"), old)
        assertEquals(old, SceneSchedule.resolve(SceneSchedule.parse(sunFile), null).map { it.start to it.collection })
    }

    @Test fun sceneChoiceIsSharedAndFollowsTheSun() {
        val p = SceneSchedule.parse(sunFile)
        val fixed = SceneChoice.of(setOf("a"), setOf("b"), true, false, "desert", p, seattle, 0)
        assertEquals("desert", fixed.collection)
        assertEquals(setOf("a"), fixed.favorites); assertEquals(setOf("b"), fixed.hidden)
        assertTrue(fixed.favoritesOnly); assertFalse(fixed.shuffle)
        assertEquals("mountains", SceneChoice.of(emptySet(), emptySet(), false, true, "scheduled", p, seattle, 20 * 60).collection)
        assertEquals("space", SceneChoice.of(emptySet(), emptySet(), false, true, "scheduled", p, null, 20 * 60).collection)
        assertEquals("all", SceneChoice.of(emptySet(), emptySet(), false, true, "scheduled", emptyList(), seattle, 20 * 60).collection)
    }

    @Test fun collectionFallbackTreatsUntaggedAsOther() {
        assertTrue(SceneCollections.contains("all", emptySet()))
        assertTrue(SceneCollections.contains("other", emptySet()))
        assertFalse(SceneCollections.contains("coast", emptySet()))
        assertTrue(SceneCollections.contains("coast", setOf("coast", "mountains")))
    }
    @Test fun collectionTravelsInBackupAndInvalidModeFails() {
        val d=BackupData(scenery=SceneryBackup(collection="scheduled"))
        val r=BackupCodec.decode(BackupCodec.encode(d,"test",0)) as BackupResult.Ok
        assertEquals("scheduled",r.data.scenery!!.collection)
        assertTrue(BackupCodec.decode("""{"format":"glassos-aerial-backup","version":1,"scenery":{"collection":"unknown"}}""") is BackupResult.Error)
        val old=BackupCodec.decode("""{"format":"glassos-aerial-backup","version":1,"scenery":{"shuffle":true}}""") as BackupResult.Ok
        assertNull(old.data.scenery!!.collection)
    }
    @Test fun watchdogAllowsProgressAndResetsAfterResume() {
        val w=PlaybackProgress(); w.reset(0)
        assertFalse(w.stalled(0,0));assertFalse(w.stalled(0,19_999));assertTrue(w.stalled(0,20_000))
        assertFalse(w.stalled(20,20_001));assertFalse(w.stalled(40,40_000))
        w.reset(90_000);assertFalse(w.stalled(40,90_001));assertFalse(w.stalled(40,100_000))
    }
}
