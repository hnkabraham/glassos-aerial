package io.github.abhishekkaisar.glassos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CaptionsTest {
    private fun doc(vararg c: Pair<Any, Any?>) = JSONObject().put("version", 1)
        .put("captions", JSONArray(c.map { (t, text) -> JSONObject().put("t", t).put("text", text) })).toString()

    @Test fun captionsAreReadInTimeOrderAndCleanedUp() {
        val list = Captions.parse(doc(60 to "Over the bay", 0 to "  Leaving   the\ncoast ", 22.5 to "Heading north"))
        assertEquals(listOf(0L, 22_500L, 60_000L), list.map { it.ms })
        assertEquals("Leaving the coast", list[0].text)
    }

    @Test fun badEntriesAreSkippedAndBadFilesGiveNone() {
        val list = Captions.parse(doc(-1 to "before the start", "soon" to "not a time", 5 to "", 7 to null, 9 to 42,
            90_000 to "after a day", 10 to "kept"))
        assertEquals(listOf("kept"), list.map { it.text })
        for (bad in listOf(null, "", "[]", "{}", "{\"captions\":5}", "not json", "x".repeat(Captions.MAX_BYTES + 1))) {
            assertTrue(Captions.parse(bad).isEmpty())
        }
        assertEquals(140, Captions.parse(doc(0 to "a".repeat(500)))[0].text.length)
    }

    @Test fun theCaptionInForceIsTheLastOneReached() {
        val list = Captions.parse(doc(0 to "a", 22 to "b", 60 to "c"))
        assertEquals(-1, Captions.indexAt(emptyList(), 5_000))
        assertEquals(0, Captions.indexAt(list, 0))
        assertEquals(0, Captions.indexAt(list, 21_999))
        assertEquals(1, Captions.indexAt(list, 22_000))
        assertEquals(2, Captions.indexAt(list, 3_600_000))
        val late = Captions.parse(doc(10 to "first"))
        assertEquals(-1, Captions.indexAt(late, 9_999))
    }

    @Test fun captionsFileSitsBesideItsClip() {
        assertEquals(File("/m/05-dusk.captions.json"), Captions.fileFor(File("/m/05-dusk.mp4")))
        assertEquals(File("/m/a.b.captions.json"), Captions.fileFor(File("/m/a.b.mov")))
    }

    @Test fun backupsCarryThePlaceCaptionsChoice() {
        val text = BackupCodec.encode(BackupData(scenery = SceneryBackup(captions = false)), "test", 0)
        assertEquals(false, (BackupCodec.decode(text) as BackupResult.Ok).data.scenery!!.captions)
        val older = BackupCodec.encode(BackupData(scenery = SceneryBackup(shuffle = true)), "test", 0)
        assertNull((BackupCodec.decode(older) as BackupResult.Ok).data.scenery!!.captions)
        val doc = JSONObject(text).apply { getJSONObject("scenery").put("captions", 1) }
        assertTrue(BackupCodec.decode(doc.toString()) is BackupResult.Error)
    }
}
