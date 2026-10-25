package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneNamesTest {
    @Test fun humanizesNumberedSlugs() {
        assertEquals(SceneName("Golden Gate Bridge"), SceneNames.humanize("03_golden-gate_bridge 4K.mp4"))
        assertEquals(SceneName("Golden Gate Bridge"), SceneNames.humanize("03-golden-gate-bridge.mp4"))
        assertEquals(SceneName("Hong Kong Harbour at Night"), SceneNames.humanize("12 hong_kong_harbour_at_night_hdr.mov"))
        assertEquals(SceneName("Victoria Harbour", "Hong Kong"), SceneNames.humanize("07 - Victoria Harbour - Hong Kong.mov"))
        assertEquals(SceneName("Grand Canyon", "Arizona"), SceneNames.humanize("01 - Grand Canyon - Arizona.mp4"))
    }

    /** The README's examples, exactly (mixed underscores, slug hyphens and a quality tag). */
    @Test fun documentedExamples() {
        assertEquals(SceneName("Golden Gate Bridge"), SceneNames.humanize("03_golden-gate_bridge 4K.mp4"))
        assertEquals(SceneName("Victoria Harbour", "Hong Kong"), SceneNames.humanize("07 - Victoria Harbour - Hong Kong.mov"))
        assertEquals(SceneName("Golden Gate Bridge", "San Francisco"), SceneNames.humanize("03 - Golden Gate Bridge - San Francisco.mp4"))
    }

    @Test fun mixedCaseNamesKeepTheirHyphens() {
        assertEquals(SceneName("Saint-Tropez", "Côte d'Azur"), SceneNames.humanize("08 - Saint-Tropez - Côte d'Azur.mp4"))
        assertEquals(SceneName("Aix-en-Provence at Dawn"), SceneNames.humanize("09_Aix-en-Provence_at_Dawn_4K.mp4"))
        assertEquals(SceneName("Mont Saint Michel"), SceneNames.humanize("10-mont-saint-michel.mp4"))
        assertEquals(SceneName("Wilkes-Barre", "PA"), SceneNames.humanize("11 - Wilkes-Barre - PA.mp4"))
    }

    @Test fun keepsMixedCaseAndNumbersThatAreTheName() {
        assertEquals(SceneName("iceland Fjords"), SceneNames.humanize("05 iceland Fjords.mp4"))
        assertEquals(SceneName("1984"), SceneNames.humanize("1984.mp4"))
        assertEquals(SceneName("Route 66"), SceneNames.humanize("02 Route 66.m4v"))
    }

    @Test fun metadataOverridesAndIsOptional() {
        val meta = SceneMetadata.parse("""{"03-gg.mp4": {"title": "Golden Gate", "location": "San Francisco"}, "04-x": "Coast", "bad/name": "x", "n": 5}""")
        assertEquals(SceneName("Golden Gate", "San Francisco"), SceneMetadata.nameFor("03-gg.mp4", meta))
        assertEquals(SceneName("Coast"), SceneMetadata.nameFor("04-x.mp4", meta))
        assertEquals(SceneName("Desert"), SceneMetadata.nameFor("05-desert.mp4", meta))
        assertEquals(2, meta.size)
        assertTrue(SceneMetadata.parse("not json").isEmpty())
        assertTrue(SceneMetadata.parse("x".repeat(SceneMetadata.MAX_BYTES + 1)).isEmpty())
    }

    @Test fun ids() {
        assertTrue(SceneNames.isValidId("01 - A, b.mp4"))
        assertFalse(SceneNames.isValidId("../a.mp4"))
        assertFalse(SceneNames.isValidId("a\nb"))
        assertFalse(SceneNames.isValidId(""))
    }

    @Test fun filterFallbacks() {
        val lib = listOf("a", "b", "c")
        assertEquals(listOf("b"), SceneFilter.eligible(lib, setOf("b"), emptySet(), true).eligible)
        // Favourites only, but every favourite is hidden: all visible clips play rather than nothing.
        val r = SceneFilter.eligible(lib, setOf("b"), setOf("b"), true)
        assertEquals(SceneFilter.Reason.FAVORITES_EMPTY, r.reason)
        assertEquals(listOf("a", "c"), r.eligible)
        assertEquals(SceneFilter.Reason.ALL_HIDDEN, SceneFilter.eligible(lib, emptySet(), lib.toSet(), false).reason)
        assertEquals(SceneFilter.Reason.ALL_FAILED, SceneFilter.eligible(lib, emptySet(), setOf("a"), false, setOf("b", "c")).reason)
        assertEquals(SceneFilter.Reason.EMPTY_LIBRARY, SceneFilter.eligible(emptyList(), emptySet(), emptySet(), false).reason)
        assertEquals(listOf("a", "c"), SceneFilter.eligible(lib, emptySet(), emptySet(), false, setOf("b")).eligible)
    }
}
