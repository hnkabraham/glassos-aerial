package io.github.abhishekkaisar.glassos.airplay.debug

// Pure (no android.* imports), unit tested on the JVM.

/**
 * H.264 samples from an MP4, shaped like the frames a sender's mirror stream delivers (onVideoData): NAL units behind
 * 4-byte start codes, with the stream's SPS and PPS in front of every keyframe. Debug builds only (the MP4 feed).
 */
object AnnexB {
    private val START = byteArrayOf(0, 0, 0, 1)

    /** Whether [b] starts with a 3- or 4-byte start code. */
    fun startsWithStartCode(b: ByteArray): Boolean =
        (b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && b[3] == 1.toByte()) ||
            (b.size >= 3 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte())

    /**
     * A sample as start-code NAL units: one that already has start codes (what MediaExtractor gives for an MP4's
     * H.264 track) as it is, one in the MP4's own form (each NAL unit behind its big-endian [lengthSize]-byte length)
     * converted. Null when the lengths do not add up.
     */
    fun toAnnexB(sample: ByteArray, lengthSize: Int = 4): ByteArray? {
        if (sample.isEmpty()) return null
        if (startsWithStartCode(sample)) return sample
        if (lengthSize !in 1..4) return null
        val out = java.io.ByteArrayOutputStream(sample.size + 16)
        var i = 0
        while (i < sample.size) {
            if (i + lengthSize > sample.size) return null
            var len = 0L
            for (k in 0 until lengthSize) len = (len shl 8) or (sample[i + k].toLong() and 0xFF)
            i += lengthSize
            if (len <= 0 || i + len > sample.size) return null
            out.write(START)
            out.write(sample, i, len.toInt())
            i += len.toInt()
        }
        return out.toByteArray()
    }

    /** Joins the track's codec config (csd-0, csd-1: SPS and PPS, start codes included) into one block. */
    fun config(vararg csd: ByteArray?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in csd) if (c != null && c.isNotEmpty()) out.write(if (startsWithStartCode(c)) c else START + c)
        return out.toByteArray()
    }

    /** One frame as the mirror stream carries it: [config] in front of a keyframe, so a decoder can start on it. */
    fun frame(nal: ByteArray, keyframe: Boolean, config: ByteArray): ByteArray =
        if (keyframe && config.isNotEmpty()) config + nal else nal

    /** The type (5 IDR, 7 SPS, 8 PPS, 1 other slice...) of each NAL unit in a start-code frame. */
    fun nalTypes(frame: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var i = 0
        while (i + 3 < frame.size) {
            if (frame[i] == 0.toByte() && frame[i + 1] == 0.toByte()) {
                val head = when {
                    frame[i + 2] == 1.toByte() -> i + 3
                    frame[i + 2] == 0.toByte() && frame[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (head in frame.indices) {
                    types += frame[head].toInt() and 0x1F
                    i = head + 1
                    continue
                }
            }
            i++
        }
        return types
    }
}
