package io.github.abhishekkaisar.glassos

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** One timed caption: where the scene is from [ms] into the clip. */
class Caption(val ms: Long, val text: String)

/**
 * Place captions: short notes that change as a scene plays ("Over the coast heading north"), like the places Apple TV
 * names during its aerials. A clip's captions come from a file beside it, "<clip name without extension>.captions.json"
 * (GlassOS Manager copies it with the clip):
 *
 *     {"version": 1, "captions": [{"t": 0, "text": "..."}, {"t": 42.5, "text": "..."}]}
 *
 * where t is seconds from the clip's start at normal speed. Optional; a clip without one shows none.
 */
object Captions {
    const val SUFFIX = ".captions.json"
    const val MAX_BYTES = 32 * 1024
    private const val MAX_ENTRIES = 300
    private const val MAX_TEXT = 140
    /** How long each caption stays once it appears (the next one replaces it sooner). */
    const val SHOW_MS = 10_000L

    fun fileFor(video: File): File = File(video.parentFile, video.name.substringBeforeLast('.') + SUFFIX)

    /** What [clip]'s captions are now (clip and file version), to reload them only when this changes. */
    fun keyFor(clip: AerialClip): String = clip.id + ":" + (clip.captions?.let { "${it.length()}:${it.lastModified()}" } ?: "none")

    /**
     * How far above the bottom edge the caption must sit to clear [views] in a bottom corner (the cinema clock, the
     * scene name, the Now Playing card), or [base] when none is there. Each is (view, its bottom margin) while shown.
     */
    fun clearance(base: Int, gap: Int, views: List<Pair<View, Int>>): Int =
        views.fold(base) { m, (v, margin) -> maxOf(m, margin + heightOf(v) + gap) }

    /** [v]'s height, measured now if it has never been laid out (a clock or name that was GONE until a moment ago). */
    private fun heightOf(v: View): Int {
        if (v.height > 0) return v.height
        if (v.measuredHeight == 0) {
            val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            v.measure(any, any)
        }
        return v.measuredHeight
    }

    /** The captions in time order; empty for a missing, oversized or malformed file. Off the main thread. */
    fun read(f: File?): List<Caption> = try {
        if (f == null || !f.isFile || f.length() !in 1..MAX_BYTES.toLong()) emptyList() else parse(f.readText())
    } catch (e: Exception) { emptyList() }

    fun parse(text: String?): List<Caption> {
        if (text == null || text.length > MAX_BYTES) return emptyList()
        val a = try { JSONObject(text).optJSONArray("captions") } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<Caption>()
        for (i in 0 until minOf(a.length(), MAX_ENTRIES)) {
            val o = a.optJSONObject(i) ?: continue
            val t = o.optDouble("t", Double.NaN)
            // Collapse whitespace: one line on screen.
            val s = (o.opt("text") as? String)?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_TEXT) ?: continue
            if (t.isNaN() || t < 0 || t > 24 * 3600 || s.isEmpty()) continue
            out.add(Caption((t * 1000).toLong(), s))
        }
        return out.sortedBy { it.ms }
    }

    /** The index of the caption in force at [ms] (the last one at or before it), or -1 before the first. */
    fun indexAt(list: List<Caption>, ms: Long): Int {
        var lo = 0
        var hi = list.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].ms <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/**
 * The place caption over the scenery in cinema mode and the screensaver: a line or two, centred near the bottom (clear of
 * the clock, the scene name and the Now Playing card, which keep to the corners). Each caption fades in when the
 * playing clip reaches it and out after [Captions.SHOW_MS]; [follow] is called about once a second by its holder.
 */
class CaptionLine(ctx: Context) : TextView(ctx) {
    private var list: List<Caption> = emptyList()
    private var clip: String? = null
    private var shownIndex = -1
    private val hideLater = Runnable { fadeOut() }

    init {
        textSize = 20f
        typeface = Fonts.regular
        setTextColor(0xF2FFFFFF.toInt())
        setShadowLayer(ctx.dp(8f), 0f, ctx.dp(1f), 0xB3000000.toInt())
        // Two lines at most: Apple's longest English caption is 87 characters.
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = android.view.Gravity.CENTER
        setLineSpacing(0f, 1.1f)
        alpha = 0f
        visibility = View.GONE
        isFocusable = false
        letterSpacing = 0.01f
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** The captions of [clipId] (empty: none). A new clip starts over; the same clip keeps what shows. */
    fun setCaptions(clipId: String?, captions: List<Caption>) {
        if (clipId == clip && captions == list) return
        clip = clipId
        list = captions
        shownIndex = -1
        hide(instant = true)
    }

    /** The clip is at [ms] (null: not playing). Shows the caption in force when it changed since the last call. */
    fun follow(ms: Long?) {
        if (ms == null || list.isEmpty()) return
        val i = Captions.indexAt(list, ms)
        if (i == shownIndex) return
        shownIndex = i
        if (i < 0) { fadeOut(); return }
        // A caption reached late (the clip resumed mid-way) shows only for what is left of its time.
        val left = Captions.SHOW_MS - (ms - list[i].ms)
        if (left < 1500) { fadeOut(); return }
        text = list[i].text
        removeCallbacks(hideLater)
        visibility = View.VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(Effects.fade(700)).start()
        postDelayed(hideLater, left)
    }

    /** Gone at once or with a fade; the next [follow] that reaches a new caption shows it again. */
    fun hide(instant: Boolean = false) {
        removeCallbacks(hideLater)
        animate().cancel()
        if (instant || visibility != View.VISIBLE) { alpha = 0f; visibility = View.GONE; return }
        fadeOut()
    }

    /** Leaves cinema mode or the screensaver: the caption in force shows again on return. */
    fun reset() {
        shownIndex = -1
        hide(instant = true)
    }

    private fun fadeOut() {
        removeCallbacks(hideLater)
        if (visibility != View.VISIBLE) return
        animate().cancel()
        animate().alpha(0f).setDuration(Effects.fade(900)).withEndAction { visibility = View.GONE }.start()
    }
}
