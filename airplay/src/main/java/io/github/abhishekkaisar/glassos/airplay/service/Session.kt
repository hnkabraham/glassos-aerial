/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay.service

import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.Mode
import io.github.abhishekkaisar.glassos.airplay.audio.TrackInfo

// Pure (no android.* imports).

/**
 * The state of the senders connected to one receiver run, which jqssun kept in about 20 StateFlows
 * (AirPlayService.kt:95-160). Plain fields, main thread only: native callbacks post their changes there.
 * [clock] is a monotonic millisecond clock (SystemClock.elapsedRealtime in the service).
 */
internal class Session(private val clock: () -> Long) {
    /** Open sender connections (every TCP connection counts, as in UxPlay's conn_init). */
    var connections = 0
    /** A mirror stream is running (mirror_video_running), reported before its first frame. */
    var mirrorRunning = false
    /** The mirror stream reported a real size. */
    var mirrorSized = false
    var videoWidth = 0
    var videoHeight = 0
    /** Music streaming, not the audio of a mirrored screen. */
    var audioOnly = false
    var pin: String? = null
    var track = TrackInfo()
    var positionMs = 0L
    var durationMs = 0L
    var playing = true
        private set
    private var progressBaseMs = 0L
    private var progressBaseTime = 0L

    val mode: Mode
        get() = when {
            mirrorRunning -> Mode.MIRROR
            audioOnly && connections > 0 -> Mode.AUDIO
            else -> Mode.IDLE
        }

    /** The track position, extrapolated from the last progress report while playing. */
    fun currentPositionMs(): Long {
        if (progressBaseTime == 0L || !playing) return positionMs
        val elapsed = clock() - progressBaseTime
        return (progressBaseMs + elapsed).coerceIn(0, durationMs.coerceAtLeast(0))
    }

    fun setPlaying(p: Boolean) {
        if (p) {
            // resume extrapolation from the current position
            progressBaseMs = positionMs
            progressBaseTime = clock()
        } else {
            // freeze the position
            positionMs = currentPositionMs()
            progressBaseTime = 0
        }
        playing = p
    }

    /** A RAOP progress report, in samples at 44.1 kHz; false when it was degenerate (pause/resume transitions). */
    fun progress(start: Long, curr: Long, end: Long): Boolean {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        if (durMs <= 0) return false
        positionMs = posMs
        durationMs = durMs
        progressBaseMs = posMs
        progressBaseTime = clock()
        playing = true
        return true
    }

    fun clearTrack() {
        track = TrackInfo()
        positionMs = 0
        durationMs = 0
        progressBaseTime = 0
    }

    /** The last sender left. */
    fun sendersGone() {
        connections = 0
        audioOnly = false
        clearTrack()
    }

    fun reset() {
        sendersGone()
        mirrorRunning = false
        mirrorSized = false
        videoWidth = 0
        videoHeight = 0
        pin = null
        playing = true
    }
}
