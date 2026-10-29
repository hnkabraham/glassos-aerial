package io.github.abhishekkaisar.glassos

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.Window
import java.io.File

/** A display mode as [FrameRate] sees it: its id, physical size and refresh rate. */
data class ModeSpec(val id: Int, val width: Int, val height: Int, val rate: Float)

/**
 * Settings > Match frame rate: in the screensaver, the TV's refresh rate is changed to suit the scenery when the two
 * do not already fit (a 24 or 25 fps clip on a 59.94 Hz TV). Fire TV's documented way: the window's
 * preferredDisplayModeId (API 23). Only a mode at the same physical size is chosen, once per screensaver session, and
 * the window lets go of it when the screensaver ends. 29.97 fps scenery already fits the usual 59.94 Hz exactly, so
 * nothing changes for it. Reported rates are inexact (59.939999, 23.976), so rates are compared with a tolerance.
 */
object FrameRate {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 23

    /** True when [rate] Hz shows [fps] frames without judder: a whole multiple of it, within 0.01 Hz per multiple. */
    fun fits(rate: Float, fps: Float): Boolean {
        if (fps <= 0f || rate <= 0f) return false
        val k = Math.round(rate / fps)
        return k >= 1 && Math.abs(rate - k * fps) < 0.01f * k
    }

    /**
     * The mode to ask for, or null to stay as is: the highest-rate mode at [current]'s size that fits every rate in
     * [fps] (the scenes that may play). A library mixing rates no single mode fits (29.97 and 30 fps) stays as it is:
     * a switch would judder the other scenes.
     */
    fun pick(modes: List<ModeSpec>, current: ModeSpec, fps: Collection<Float>): Int? {
        if (fps.isEmpty() || fps.all { fits(current.rate, it) }) return null
        return modes.filter { m -> m.width == current.width && m.height == current.height && fps.all { fits(m.rate, it) } }
            .maxByOrNull { it.rate }?.id?.takeIf { it != current.id }
    }

    /**
     * Frames per second from presentation times in µs, or null with too few. They come in decode order, so B-frames
     * are out of order and the last few frames before the cut-off are missing: the median gap between neighbouring
     * times is the frame's length, where the whole span would come out too long. Containers often round 29.97 to 30 in
     * their frame-rate field, which would ask for the wrong mode.
     */
    fun fpsFromTimes(timesUs: List<Long>): Float? {
        val t = timesUs.distinct().sorted()
        if (t.size < 8) return null
        val gaps = (1 until t.size).map { t[it] - t[it - 1] }
        // Single-frame steps are the gaps near the median; their average is the frame length, also when the container
        // rounds times to milliseconds (33 and 34 ms in turn for 29.97 fps). Gaps of a missing frame are left out.
        val mid = gaps.sorted()[gaps.size / 2]
        if (mid <= 0) return null
        val steps = gaps.filter { it > mid / 2 && it < mid * 3 / 2 }
        val fps = 1_000_000.0 / steps.average()
        return if (fps in 5.0..300.0) fps.toFloat() else null
    }

    private val measured = HashMap<String, Float>()
    private val thread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "glass-framerate").apply { isDaemon = true } }

    /**
     * Measures [clips] one by one on this object's own thread (never in the way of the scenery's stills), stopping as
     * soon as [stillWanted] says no (the screensaver ended), then hands [done] the rates found.
     */
    fun measureAll(clips: List<AerialClip>, stillWanted: () -> Boolean, done: (List<Float>) -> Unit) {
        thread.execute {
            val rates = ArrayList<Float>()
            for (c in clips) {
                if (!stillWanted()) return@execute
                measureCached(c.video, c.revision)?.let { rates.add(it) }
            }
            done(rates)
        }
    }

    /** [measure], remembered per file and [revision] for the process's life. Off the main thread. */
    fun measureCached(video: File, revision: String): Float? {
        val k = video.path + ":" + revision
        synchronized(measured) { measured[k]?.let { return it } }
        val fps = measure(video) ?: return null
        synchronized(measured) { measured[k] = fps }
        return fps
    }

    /** [video]'s frame rate from its first frames' times (reads the container only, no decoding). Off the main thread. */
    fun measure(video: File): Float? {
        val x = MediaExtractor()
        return try {
            x.setDataSource(video.path)
            val track = (0 until x.trackCount).firstOrNull {
                x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            x.selectTrack(track)
            val times = ArrayList<Long>()
            while (times.size < 90) {
                val t = x.sampleTime
                if (t < 0) break
                times.add(t)
                if (!x.advance()) break
            }
            fpsFromTimes(times)
        } catch (e: Exception) { null } finally { x.release() }
    }

    /** What [display] runs at now, as text for Settings ("3840 × 2160 at 59.94 Hz"), or null below API 23. */
    @android.annotation.TargetApi(23)
    fun describe(display: Display?): String? {
        if (!supported || display == null) return null
        val m = display.mode
        return "${m.physicalWidth} × ${m.physicalHeight} at ${String.format(java.util.Locale.US, "%.2f", m.refreshRate)} Hz"
    }

    /**
     * Asks [window] for the mode that suits every rate in [fps] on [display]; returns true when it asked for one (a
     * switch may blank the TV for a second or two). Main thread.
     */
    @android.annotation.TargetApi(23)
    fun request(window: Window, display: Display?, fps: Collection<Float>): Boolean {
        if (!supported || display == null) return false
        val cur = display.mode
        val spec = { m: Display.Mode -> ModeSpec(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate) }
        val id = pick(display.supportedModes.map(spec), spec(cur), fps)
        WLog.i("frame rate: scenery ${fps.sorted().joinToString { "%.3f".format(java.util.Locale.US, it) }} fps, TV " +
            "%.3f".format(java.util.Locale.US, cur.refreshRate) + " Hz" + if (id == null) ", no change" else ", asking for mode $id")
        if (id == null) return false
        apply(window, id)
        return true
    }

    /** Lets go of a mode asked for with [request]: the TV returns to its own. */
    @android.annotation.TargetApi(23)
    fun release(window: Window?) {
        if (!supported || window == null) return
        if (window.attributes.preferredDisplayModeId == 0) return
        apply(window, 0)
    }

    /**
     * Sets the window's preferred mode and has the window manager take it. A dream's window does not pass attribute
     * changes on by itself (DreamService's onWindowAttributesChanged does nothing), so the layout is updated here.
     */
    @android.annotation.TargetApi(23)
    private fun apply(window: Window, id: Int) {
        val lp = window.attributes
        lp.preferredDisplayModeId = id
        window.attributes = lp
        try { window.windowManager.updateViewLayout(window.decorView, lp) } catch (e: Exception) { }
    }
}
