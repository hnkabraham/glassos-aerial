package io.github.abhishekkaisar.glassos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Live glass (Settings > Live glass): while the scenery video plays behind the home, the glass follows the moving
 * picture instead of the clip's still. About four times a second the playing frame is copied at 480x270 (PixelCopy
 * on Android 7 and later, off the main thread; TextureView.getBitmap before that), averaged with the last copy so the
 * glass changes smoothly, blurred twice on a worker thread (the light blur for clear glass and the frosted copy, as
 * [Blur] makes them from stills) and handed to [Backdrop] in place of the still. Glass surfaces redraw through the
 * backdrop's frame listeners; nothing else does. Stops when the video stops (the home is covered or left, cinema mode
 * without a card, the screensaver) and the hero puts its still back.
 */
class LiveGlass(private val video: AerialVideoView, private val onFrame: () -> Unit, private val onStop: () -> Unit) {
    private val thread = HandlerThread("glass-live").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var released = false
    private val capture = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val now = IntArray(W * H)
    private val avg = IntArray(W * H)
    private var hasAvg = false
    /** Three of each, written in turn: one shown as the backdrop, one being drawn from, one being written. */
    private val lites = Array(3) { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }
    private val heavies = Array(3) { Bitmap.createBitmap(W / 2, H / 2, Bitmap.Config.ARGB_8888) }
    private val heavyCanvases = Array(3) { Canvas(heavies[it]) }
    private var slot = 0
    private val half = Bitmap.createBitmap(W / 2, H / 2, Bitmap.Config.ARGB_8888)
    private val halfCanvas = Canvas(half)
    private val halfRect = android.graphics.Rect(0, 0, W / 2, H / 2)
    /** A copy or its blurring is under way: the next tick waits (a slow TV never queues them up). */
    @Volatile private var inFlight = false
    private val scale = Paint(Paint.FILTER_BITMAP_FLAG)
    private val colour = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // The frosted copy's colour, as Blur.makeBackdrop gives stills.
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(1.35f)
            postConcat(ColorMatrix(floatArrayOf(1.04f, 0f, 0f, 0f, 6f, 0f, 1.04f, 0f, 0f, 6f, 0f, 0f, 1.04f, 0f, 8f, 0f, 0f, 0f, 1f, 0f)))
        })
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!inFlight) {
                inFlight = true
                video.captureFrame(capture, worker) { ok ->
                    try { if (ok && running) process() } finally { inFlight = false }
                }
            }
            worker.postDelayed(this, INTERVAL_MS)
        }
    }

    val isRunning: Boolean get() = running

    /** Starts sampling (main thread); the first frame reaches the glass about a quarter of a second later. */
    fun start() {
        if (running || released) return
        running = true
        inFlight = false
        hasAvg = false
        Backdrop.live = true
        worker.post(tick)
    }

    /** Stops sampling; the hero shows its still on the glass again. Main thread. */
    fun stop() {
        if (!running) return
        running = false
        worker.removeCallbacks(tick)
        Backdrop.live = false
        onStop()
    }

    fun release() {
        stop()
        released = true
        thread.quitSafely()
    }

    /** On the worker: average with the last frame, make both blurs into the next slot and hand them over. */
    private fun process() {
        capture.getPixels(now, 0, W, 0, 0, W, H)
        if (!hasAvg) { System.arraycopy(now, 0, avg, 0, now.size); hasAvg = true }
        else for (i in now.indices) {
            val a = avg[i]
            val b = now[i]
            avg[i] = (0xFF shl 24) or ((((a shr 16) and 255) + ((b shr 16) and 255)) / 2 shl 16) or
                ((((a shr 8) and 255) + ((b shr 8) and 255)) / 2 shl 8) or (((a and 255) + (b and 255)) / 2)
        }
        val s = slot
        slot = (slot + 1) % 3
        val lite = lites[s]
        lite.setPixels(avg, 0, W, 0, 0, W, H)
        // The frosted copy from the averaged frame: half size, colour, then the heavy blur.
        halfCanvas.drawBitmap(lite, null, halfRect, scale)
        val heavy = heavies[s]
        heavyCanvases[s].drawBitmap(half, 0f, 0f, colour)
        Blur.boxBlur(heavy, 3)
        Blur.boxBlur(lite, 1)
        val pill = Tone.measure(heavy, Tone.PILL)
        val chevron = Tone.measure(heavy, Tone.CHEVRON)
        main.post {
            if (!running) return@post
            Tone.pill = pill
            Tone.chevron = chevron
            Backdrop.place(Backdrop.a, heavy, null, 1f, lite)
            Backdrop.place(Backdrop.b, null, null, 0f)
            onFrame()
        }
    }

    companion object {
        const val W = 480
        const val H = 270
        const val INTERVAL_MS = 250L
    }
}
