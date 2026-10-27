/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Pure (no android.* imports), unit tested on the JVM.

/** Parses DMAP (iTunes metadata) binary TLV format into a tag->value map. */
object DmapParser {

    // tags whose payload is an integer (1/2/4/8 bytes)
    private val INT_TAGS = setOf(
        "astm", // duration ms
        "astn", // track number
        "asdk", // data kind
        "asts", // ???
        "miid", // item id
        "mcti", // container item id
        "mper", // persistent id
        "asai", // album id
        "asri", // artist id
        "asci", // composer id
        "asgi", // genre id
    )

    // tags that are containers (contain nested dmap items)
    private val CONTAINER_TAGS = setOf("mlit", "mcon", "mlcl")

    fun parse(data: ByteArray): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() >= 8) {
            val tagBytes = ByteArray(4)
            buf.get(tagBytes)
            val tag = String(tagBytes, Charsets.US_ASCII)
            val len = buf.int
            if (len < 0 || len > buf.remaining()) break
            val payload = ByteArray(len)
            buf.get(payload)
            result[tag] = when {
                tag in CONTAINER_TAGS -> parse(payload)
                tag in INT_TAGS -> _readInt(payload)
                else -> String(payload, Charsets.UTF_8)
            }
        }
        return result
    }

    private fun _readInt(b: ByteArray): Long {
        var v = 0L
        for (byte in b) v = (v shl 8) or (byte.toLong() and 0xFF)
        return v
    }
}
