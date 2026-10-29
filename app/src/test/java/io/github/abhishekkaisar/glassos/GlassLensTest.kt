package io.github.abhishekkaisar.glassos

import org.junit.Assert.*
import org.junit.Test

class GlassLensTest {
    // The shelf at 1080p (corner 48 px, bevel about 31 px, bending 74 px) and a time pill (fully round ends).
    private val shelf = GlassPainter.lensBands(1890f, 232f, 48f, 31f, 74f)
    private val pill = GlassPainter.lensBands(200f, 58f, 29f, 14.5f, 29f)

    @Test fun bandsTileTheBevelFromTheEdge() {
        for (bands in listOf(shelf, pill)) {
            assertEquals(0f, bands.first().t0, 0f)
            for (k in 1 until bands.size) assertEquals(bands[k - 1].t1, bands[k].t0, 1e-4f)
        }
        assertEquals(31f, shelf.last().t1, 1e-4f)
        assertEquals(14.5f, pill.last().t1, 1e-4f)
    }

    @Test fun theBendingGrowsTowardsTheEdge() {
        for (bands in listOf(shelf, pill)) for (k in 1 until bands.size) assertTrue(bands[k - 1].d >= bands[k].d)
        assertTrue(shelf.first().d > 20f)
    }

    @Test fun runsAndCornersMoveTheBackdropTheSameDistanceWhereTheyMeet() {
        // At the band's middle: a run moves it (half the side - tc)(1 - scale), a corner (radius - tc)(1 - s).
        for ((bands, w, h, radius) in listOf(Quad(shelf, 1890f, 232f, 48f), Quad(pill, 200f, 58f, 29f))) {
            for (b in bands) {
                val tc = (b.t0 + b.t1) / 2f
                assertEquals(b.d, (w / 2f - tc) * (1f - b.sx), 1e-3f)
                assertEquals(b.d, (h / 2f - tc) * (1f - b.sy), 1e-3f)
                assertEquals(b.d, (radius - tc) * (1f - b.s), 1e-3f)
            }
        }
    }

    @Test fun aCornerNeverGathersIntoAPoint() {
        for ((bands, radius) in listOf(shelf to 48f, pill to 29f)) for (b in bands) {
            val rho = radius - (b.t0 + b.t1) / 2f
            assertTrue(b.d <= 0.7f * rho + 1e-4f)
            assertTrue(b.s >= 0.3f - 1e-4f)
        }
        // Uncapped, the shelf's outer band would move the backdrop 56 px, more than its 47 px corner.
        assertEquals(0.7f * (48f - 0.93f), shelf.first().d, 0.01f)
    }

    @Test fun aBandDeeperThanItsCornerHasNoCornerScale() {
        val square = GlassPainter.lensBands(400f, 200f, 4f, 40f, 80f)
        assertEquals(0f, square.last().s, 0f)
        assertTrue(square.last().sx in 0.2f..1f)
    }

    private data class Quad(val bands: Array<LensBand>, val w: Float, val h: Float, val radius: Float)
}
