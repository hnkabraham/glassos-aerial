package io.github.abhishekkaisar.glassos.airplay.ui

import kotlin.math.roundToInt

// Pure (no android.* imports), unit tested on the JVM.

/** Where the mirrored picture goes: the largest rect with its aspect ratio inside the view, centred. */
object AspectFit {
    class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
        override fun equals(other: Any?) = other is Box && other.left == left && other.top == top &&
            other.width == width && other.height == height
        override fun hashCode() = ((left * 31 + top) * 31 + width) * 31 + height
        override fun toString() = "Box($left, $top, ${width}x$height)"
    }

    /**
     * Letterboxes (bars above and below) or pillarboxes (bars at the sides) a [videoW] x [videoH] picture in a
     * [viewW] x [viewH] view. An unknown video size (0 before the stream reports one) fills the view.
     */
    fun fit(viewW: Int, viewH: Int, videoW: Int, videoH: Int): Box {
        if (viewW <= 0 || viewH <= 0) return Box(0, 0, viewW.coerceAtLeast(0), viewH.coerceAtLeast(0))
        if (videoW <= 0 || videoH <= 0) return Box(0, 0, viewW, viewH)
        // Compare the two ratios without division: wider than the view means full width.
        val wider = videoW.toLong() * viewH >= videoH.toLong() * viewW
        val w = if (wider) viewW else (viewH.toDouble() * videoW / videoH).roundToInt().coerceIn(1, viewW)
        val h = if (wider) (viewW.toDouble() * videoH / videoW).roundToInt().coerceIn(1, viewH) else viewH
        return Box((viewW - w) / 2, (viewH - h) / 2, w, h)
    }
}
