package io.github.abhishekkaisar.glassos.airplay.debug

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.abhishekkaisar.glassos.airplay.service.AirPlayService
import java.nio.ByteBuffer

/**
 * Debug builds only (`airplay-debug feed-mp4 PATH`): plays an MP4's H.264 track into the receiver the way a sender's
 * mirror stream arrives, through the service's own native callbacks on this thread, as the native threads call them:
 * a connection, mirror_video_running(true), a short pause (a sender connects its mirror stream after SETUP), the
 * stream's size, then Annex-B frames with SPS and PPS before each keyframe, at the clip's pace and looped for
 * [seconds]. Then mirror_video_running(false) and the connection's end, as when a sender stops, unless the receiver
 * itself stopped ([cancel]). Logs the session's counters every two seconds.
 */
internal class DebugFeed(
    private val path: String,
    private val seconds: Int,
    private val service: AirPlayService,
    private val stats: () -> String,
) : Thread("AirPlayDebugFeed") {
    @Volatile private var finishing = false
    private var cancelled = false

    /** Ends the session as a sender would (feed-stop, Disconnect). Never interrupts a callback in progress. */
    fun finish() {
        finishing = true
        synchronized(this) { interrupt() }
    }

    /** The receiver stops: no further callback at all. May wait for the frame being fed. */
    fun cancel() {
        synchronized(this) { cancelled = true }
        interrupt()
    }

    /** Runs [block] (one native callback) unless the receiver stopped; false if it did. */
    private inline fun deliver(block: () -> Unit): Boolean = synchronized(this) {
        if (cancelled) return false
        block()
        true
    }

    override fun run() {
        val ex = MediaExtractor()
        var started = false
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC
            }
            if (track == null) {
                Log.w(TAG, "feed: no H.264 track in that file")
                return
            }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val config = AnnexB.config(format.bytes("csd-0"), format.bytes("csd-1"))
            val limitMs = (if (seconds > 0) seconds else DEFAULT_SECONDS) * 1000L
            Log.i(TAG, "feed: ${w}x$h H.264 for ${limitMs / 1000} s")

            if (!deliver { service.onConnectionInit(); service.onMirrorRunning(true) }) return
            started = true
            Thread.sleep(SETUP_GAP_MS)
            if (!deliver { service.onVideoSize(w.toFloat(), h.toFloat(), w.toFloat(), h.toFloat()) }) return

            val buf = ByteBuffer.allocate(maxOf(w * h, 1 shl 20))
            val begin = SystemClock.elapsedRealtime()
            var loopBaseUs = 0L
            var lastUs = 0L
            var frames = 0
            var nextLog = begin + LOG_MS
            while (!finishing && SystemClock.elapsedRealtime() - begin < limitMs) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    // Loop: the next pass continues the timeline one frame after the last sample.
                    loopBaseUs += lastUs + FRAME_US
                    lastUs = 0
                    ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    continue
                }
                val ptsUs = ex.sampleTime.coerceAtLeast(0)
                val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                ex.advance()
                lastUs = ptsUs
                val sample = ByteArray(n).also { buf.get(it, 0, n) }
                val nal = AnnexB.toAnnexB(sample) ?: continue
                // At the clip's pace: a sender delivers frames in real time.
                val due = begin + (loopBaseUs + ptsUs) / 1000
                val wait = due - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                if (!deliver { service.onVideoData(AnnexB.frame(nal, key, config), (loopBaseUs + ptsUs) * 1000, false) }) return
                frames++
                val now = SystemClock.elapsedRealtime()
                if (now >= nextLog) {
                    nextLog = now + LOG_MS
                    Log.i(TAG, "feed: ${stats()}")
                }
            }
            Log.i(TAG, "feed: done after $frames frames; ${stats()}")
        } catch (e: InterruptedException) {
            Log.i(TAG, "feed: stopped; ${stats()}")
        } catch (e: Exception) {
            Log.w(TAG, "feed: failed", e)
        } finally {
            ex.release()
            if (started) deliver { service.onMirrorRunning(false); service.onConnectionDestroy() }
            val self = this
            Handler(Looper.getMainLooper()).post { service.feedEnded(self) }
        }
    }

    private fun MediaFormat.bytes(key: String): ByteArray? {
        if (!containsKey(key)) return null
        val b = getByteBuffer(key) ?: return null
        val d = b.duplicate()
        d.position(0)
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    private companion object {
        const val TAG = "AirPlayDebug"
        const val DEFAULT_SECONDS = 60
        /** What a sender takes between SETUP (mirror_video_running) and its first frame, at the least. */
        const val SETUP_GAP_MS = 200L
        const val LOG_MS = 2_000L
        const val FRAME_US = 33_333L
    }
}
