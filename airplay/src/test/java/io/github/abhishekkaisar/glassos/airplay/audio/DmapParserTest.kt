package io.github.abhishekkaisar.glassos.airplay.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class DmapParserTest {
    private fun item(tag: String, payload: ByteArray): ByteArray {
        require(tag.length == 4)
        val out = ByteArrayOutputStream()
        out.write(tag.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        out.write(payload)
        return out.toByteArray()
    }

    private fun text(tag: String, s: String) = item(tag, s.toByteArray(Charsets.UTF_8))
    private fun int32(tag: String, v: Int) = item(tag, ByteBuffer.allocate(4).putInt(v).array())
    private fun join(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    @Test fun stringsAndIntegers() {
        val m = DmapParser.parse(join(
            text("minm", "Clair de Lune"), text("asar", "Debussy é"), int32("astm", 315_000),
            item("astn", byteArrayOf(7)), item("asdk", byteArrayOf(0x01, 0x02)),
            item("mper", ByteBuffer.allocate(8).putLong(0x0102030405060708L).array()),
        ))
        assertEquals("Clair de Lune", m["minm"])
        assertEquals("Debussy é", m["asar"])
        assertEquals(315_000L, m["astm"])
        assertEquals(7L, m["astn"])
        assertEquals(0x0102L, m["asdk"])
        assertEquals(0x0102030405060708L, m["mper"])
    }

    @Test fun integersAreUnsigned() {
        assertEquals(0xFFFFFFFFL, DmapParser.parse(int32("astm", -1))["astm"])
        assertEquals(0xFFL, DmapParser.parse(item("astn", byteArrayOf(-1)))["astn"])
    }

    @Test fun containersNestAndTrackInfoFlattensThem() {
        val data = item("mlit", join(text("minm", "Title"), text("asar", "Artist"), text("asal", "Album"),
            text("asgn", "Genre"), int32("astm", 200_000)))
        val m = DmapParser.parse(data)
        @Suppress("UNCHECKED_CAST")
        val inner = m["mlit"] as Map<String, Any>
        assertEquals("Title", inner["minm"])
        val t = TrackInfo.fromDmap(m)
        assertEquals(TrackInfo("Title", "Artist", "Album", "Genre", 200_000L), t)
    }

    @Test fun truncatedInputKeepsWhatCameBefore() {
        val good = text("minm", "Kept")
        // Claims 100 bytes, has 3.
        val bad = "asar".toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).putInt(100).array() + byteArrayOf(1, 2, 3)
        val m = DmapParser.parse(good + bad)
        assertEquals(mapOf<String, Any>("minm" to "Kept"), m)
        // A negative length stops parsing too.
        val negative = "asal".toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).putInt(-1).array()
        assertEquals(mapOf<String, Any>("minm" to "Kept"), DmapParser.parse(good + negative))
        // Fewer than 8 bytes left: ignored.
        assertEquals(mapOf<String, Any>("minm" to "Kept"), DmapParser.parse(good + byteArrayOf(1, 2, 3)))
    }

    @Test fun emptyInput() {
        assertTrue(DmapParser.parse(ByteArray(0)).isEmpty())
        assertEquals(TrackInfo(), TrackInfo.fromDmap(emptyMap()))
    }

    @Test fun anEmptyStringIsKept() {
        assertEquals("", DmapParser.parse(text("minm", ""))["minm"])
    }
}
