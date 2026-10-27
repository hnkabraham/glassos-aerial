package io.github.abhishekkaisar.glassos.airplay.ui

import io.github.abhishekkaisar.glassos.airplay.ui.AspectFit.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class AspectFitTest {
    @Test fun sixteenByNineFillsA1080pScreen() {
        assertEquals(Box(0, 0, 1920, 1080), AspectFit.fit(1920, 1080, 1280, 720))
        assertEquals(Box(0, 0, 1920, 1080), AspectFit.fit(1920, 1080, 1920, 1080))
    }

    @Test fun portraitIsPillarboxedAndCentred() {
        // 1080x1920 in 1920x1080: 607.5 wide, rounded, centred.
        assertEquals(Box(656, 0, 608, 1080), AspectFit.fit(1920, 1080, 1080, 1920))
        assertEquals(Box(400, 0, 1120, 1080), AspectFit.fit(1920, 1080, 1120, 1080))
    }

    @Test fun widerThanTheScreenIsLetterboxed() {
        // 2.39:1 cinema
        assertEquals(Box(0, 138, 1920, 803), AspectFit.fit(1920, 1080, 2390, 1000))
        // A 4:3 picture on a 4:3-ish view stays whole
        assertEquals(Box(0, 0, 1024, 768), AspectFit.fit(1024, 768, 640, 480))
    }

    @Test fun anUnknownSizeFillsTheView() {
        assertEquals(Box(0, 0, 1920, 1080), AspectFit.fit(1920, 1080, 0, 0))
        assertEquals(Box(0, 0, 1920, 1080), AspectFit.fit(1920, 1080, 1280, 0))
        assertEquals(Box(0, 0, 0, 0), AspectFit.fit(0, 0, 1280, 720))
    }

    @Test fun neverCollapsesToNothing() {
        val b = AspectFit.fit(1920, 1080, 1, 100_000)
        assertEquals(1, b.width)
        assertEquals(1080, b.height)
    }
}
