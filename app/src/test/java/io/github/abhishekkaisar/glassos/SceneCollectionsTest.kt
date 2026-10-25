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
