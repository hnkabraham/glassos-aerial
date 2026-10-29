package io.github.abhishekkaisar.glassos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/**
 * Follows the media sessions and reports what plays (music, a podcast, Screen Mirroring's audio): at [start], when a
 * session comes or goes, when a track changes and when one starts or stops playing (not on every position update).
 * Needs Notification access, as Control Center's Now Playing does; without it nothing is reported and [following]
 * stays false, so the owner can start again once access is granted. A pause reports nothing only after
 * [PAUSE_GRACE_MS], counted from the pause, so the gap between two tracks or a quick pause does not flash the card away.
 * The same track playing on is not reported again. Main thread; [stop] before the owner goes.
 */
class NowPlayingWatch(private val ctx: Context, private val onChange: (NowPlaying?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var followed: List<Pair<MediaController, Follow>> = emptyList()
    private var on = false
    /** Registered for session changes (Notification access was there when it started). */
    var following = false
        private set
    private var shown: NowPlaying? = null
    private var clearing = false
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        follow(list ?: emptyList())
        changed()
    }

    /** One session's callback: its track, and whether it plays, not every position or buffer update. */
    private inner class Follow(c: MediaController) : MediaController.Callback() {
        private var playing = playing(c.playbackState)
        override fun onMetadataChanged(metadata: MediaMetadata?) { changed() }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val p = playing(state)
            if (p != playing) { playing = p; changed() }
        }
        override fun onSessionDestroyed() { changed() }
    }

    private val begin = Runnable {
        if (!on) return@Runnable
        try {
            (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .addOnActiveSessionsChangedListener(sessions, MediaListener.component(ctx), main)
            following = true
        } catch (e: Throwable) { } // No Notification access: a SecurityException, and nothing to follow.
        follow(NowPlaying.sessions(ctx))
        report.run()
    }
    private val report = Runnable {
        if (!on) return@Runnable
        val now = NowPlaying.current(ctx, playingOnly = true)
        if (now != null) {
            main.removeCallbacks(clear)
            clearing = false
            val same = shown?.let { it.pkg == now.pkg && it.title == now.title && it.subtitle == now.subtitle &&
                it.art?.width == now.art?.width && it.art?.height == now.art?.height } == true
            shown = now
            if (!same) onChange(now)
        } else if (shown != null && !clearing) {
            clearing = true
            main.postDelayed(clear, PAUSE_GRACE_MS)
        }
    }
    private val clear = Runnable { clearing = false; if (on) { shown = null; onChange(null) } }

    /** Starts following after [delayMs] (a home coming on screen draws its first frame first). */
    fun start(delayMs: Long = 0) {
        if (on) return
        on = true
        live.add(this)
        main.postDelayed(begin, delayMs)
    }

    /**
     * What the owner already shows (taken over from the other home): the same track is not shown again, and if it has
     * stopped meanwhile, the pause grace takes it away.
     */
    fun seed(np: NowPlaying?) { if (shown == null) shown = np }

    /** Notification access arrived while following nothing: follow now. */
    private fun rearm() {
        if (!on || following) return
        main.removeCallbacks(begin)
        begin.run()
    }

    /** Stops following; the owner hides what it showed. */
    fun stop() {
        if (!on) return
        on = false
        live.remove(this)
        shown = null
        clearing = false
        main.removeCallbacks(begin)
        main.removeCallbacks(report)
        main.removeCallbacks(clear)
        if (following) try {
            (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).removeOnActiveSessionsChangedListener(sessions)
        } catch (e: Throwable) { }
        following = false
        follow(emptyList())
    }

    private fun follow(list: List<MediaController>) {
        for ((c, f) in followed) try { c.unregisterCallback(f) } catch (e: Throwable) { }
        followed = if (on) list.map { it to Follow(it) } else emptyList()
        for ((c, f) in followed) try { c.registerCallback(f, main) } catch (e: Throwable) { }
    }

    /** A new track sends its metadata and its state separately: one report after both. */
    private fun changed() {
        main.removeCallbacks(report)
        main.postDelayed(report, 150)
    }

    companion object {
        const val PAUSE_GRACE_MS = 8000L
        private val live = HashSet<NowPlayingWatch>()
        private fun playing(s: PlaybackState?) = s?.state == PlaybackState.STATE_PLAYING || s?.state == PlaybackState.STATE_BUFFERING

        /** The notification listener connected (access granted): every watch that could not follow, follows now. */
        fun accessGranted() = Handler(Looper.getMainLooper()).post { live.toList().forEach { it.rearm() } }
    }
}

/**
 * Where the screensaver's and cinema mode's Now Playing card goes: a corner neither the clock nor the scene name uses,
 * nor will use when the clock moves on.
 */
object NowPlayingPosition {
    /**
     * [clock]: the cinema clock's corner, null when it is not shown; [label]: the scene name's corner while it shows (it
     * can stay where it was when the clock moved). Bottom left first, then top right.
     */
    fun corner(clock: Int?, label: Int? = null): Int {
        val taken = setOf(clock, CinemaPosition.labelCorner(clock), label)
        return listOf(3, 1, 2, 0).first { it !in taken }
    }
}

/** A track's art, downscaled once to the size it is drawn at (a session's art can be a full-size cover). */
private class ArtCache {
    private var source: Bitmap? = null
    var art: Bitmap? = null
        private set

    fun set(b: Bitmap?, px: Int) {
        if (b === source) return
        source = b
        art = if (b == null || b.isRecycled || b.width <= 0 || b.height <= 0) null else try {
            val s = px.toFloat() / minOf(b.width, b.height)
            if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
        } catch (e: Throwable) { null }
    }
}

/** Draws [art] centre-cropped into [r] with corner [radius], or a music note on a dark tile when there is none. */
private class ArtPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val m = Matrix()
    private var shaderOf: Bitmap? = null

    fun draw(c: Canvas, art: Bitmap?, r: RectF, radius: Float) {
        if (art == null || art.isRecycled) {
            fill.color = 0x59000000
            c.drawRoundRect(r, radius, radius, fill)
            Glyphs.draw(c, Glyph.NOTE, r.centerX(), r.centerY(), r.height() * 0.5f, 0xD9FFFFFF.toInt(), glyph)
            return
        }
        if (art !== shaderOf) { shaderOf = art; paint.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val s = maxOf(r.width() / art.width, r.height() / art.height)
        m.setScale(s, s)
        m.postTranslate(r.centerX() - art.width * s / 2, r.centerY() - art.height * s / 2)
        paint.shader.setLocalMatrix(m)
        c.drawRoundRect(r, radius, radius, paint)
    }
}

/**
 * Now Playing beside the user pill, as tvOS shows it: a glass capsule with the cover, the title and the artist. Not
 * focusable (Control Center has the controls). Hidden until [show] is given a session.
 */
class NowPlayingPill(ctx: Context) : View(ctx) {
    private val glass = GlassPainter().apply {
        tint = 0.20f; tintColor = 0xFFFFF4E8.toInt(); sheen = 0.10f; highlight = 0.40f; border = 0.14f; stroke = ctx.dp(0.6f); clear = true
    }
    private val gp = Paint()
    private val rect = RectF()
    private val artRect = RectF()
    private val arts = ArtCache()
    private val artPainter = ArtPainter()
    private val titleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.medium; textSize = ctx.dp(13f); color = Color.WHITE }
    private val subText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.regular; textSize = ctx.dp(13f); color = 0xBFFFFFFF.toInt() }
    private val h = ctx.dpi(29f)
    private val inset = ctx.dp(3.5f)
    private val gap = ctx.dp(8f)
    private val padR = ctx.dp(12f)
    private val maxText = ctx.dp(300f)
    private var title = ""
    private var sub = ""
    private var shadowStep = -1

    init {
        isFocusable = false
        alpha = 0f
        visibility = GONE
    }

    /** Shows [np] (a new track swaps in place), at once with [instant], or fades out with null. */
    fun show(np: NowPlaying?, instant: Boolean = false) {
        animate().cancel()
        if (np == null) {
            if (visibility != VISIBLE) return
            animate().alpha(0f).setDuration(Effects.fade(400)).withEndAction { visibility = GONE }.start()
            return
        }
        arts.set(np.art, (h * 2))
        title = np.title
        sub = np.subtitle
        requestLayout()
        invalidate()
        if (instant) { visibility = VISIBLE; alpha = 1f; return }
        if (visibility != VISIBLE) { visibility = VISIBLE; alpha = 0f }
        animate().alpha(1f).setDuration(Effects.fade(400)).start()
    }

    /** Hidden at once (the home left the screen). */
    fun hideNow() { animate().cancel(); alpha = 0f; visibility = GONE }

    private fun texts(): Pair<String, String> {
        val t = TextUtils.ellipsize(title, titleText, maxText, TextUtils.TruncateAt.END).toString()
        if (sub.isEmpty()) return t to ""
        val room = maxText - titleText.measureText(t)
        val s = if (room > subText.measureText(" · …")) TextUtils.ellipsize(" · $sub", subText, room, TextUtils.TruncateAt.END).toString() else ""
        return t to s
    }

    override fun onMeasure(w: Int, hs: Int) {
        val (t, s) = texts()
        val width = inset + (h - 2 * inset) + gap + titleText.measureText(t) + subText.measureText(s) + padR
        setMeasuredDimension(Math.ceil(width.toDouble()).toInt(), h)
    }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        GlowCache.draw(c, rect.width(), rect.height(), r, dp(8f), Color.BLACK, 0.22f, dp(3f), gp)
        // The same adaptive glass as the user pill beside it.
        val g = Tone.pillGlass(Tone.pill, clear = true)
        glass.tint = g.tint
        glass.dark = g.dark
        glass.highlight = 0.40f - 0.18f * g.t
        glass.border = 0.14f - 0.06f * g.t
        glass.draw(c, this, rect, r)
        val side = height - 2 * inset
        artRect.set(inset, inset, inset + side, inset + side)
        artPainter.draw(c, arts.art, artRect, side / 2f)
        val q = (g.shadow * 8).toInt()
        if (q != shadowStep) {
            shadowStep = q
            for (p in listOf(titleText, subText)) {
                if (q > 0) p.setShadowLayer(dp(3f), 0f, dp(0.5f), alphaOf(Color.BLACK, 0.35f * q / 8f)) else p.clearShadowLayer()
            }
        }
        val (t, s) = texts()
        val x = artRect.right + gap
        val y = height / 2f - (titleText.ascent() + titleText.descent()) / 2
        c.drawText(t, x, y, titleText)
        if (s.isNotEmpty()) c.drawText(s, x + titleText.measureText(t), y, subText)
    }
}

/**
 * Now Playing over the scenery in the screensaver and cinema mode: the cover, the title and the artist on a glass card
 * in a free corner ([place]; see [NowPlayingPosition]). With [sampleBackdrop] off (the screensaver, which has no
 * blurred backdrop of its own) it is a clear dark card instead of frosted glass.
 */
class NowPlayingCard(ctx: Context, private val sampleBackdrop: Boolean) : View(ctx) {
    private val glass = GlassPainter().apply { tint = 0.10f; dark = 0.28f; sheen = 0.08f; highlight = 0.36f; border = 0.16f; stroke = ctx.dp(0.8f); textGuard = true }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(0.8f) }
    private val gp = Paint()
    private val rect = RectF()
    private val artRect = RectF()
    private val arts = ArtCache()
    private val artPainter = ArtPainter()
    private val titleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium; textSize = ctx.dp(17f); color = Color.WHITE; setShadowLayer(ctx.dp(4f), 0f, ctx.dp(0.5f), 0x59000000)
    }
    private val subText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.regular; textSize = ctx.dp(14f); color = 0xCCFFFFFF.toInt(); setShadowLayer(ctx.dp(4f), 0f, ctx.dp(0.5f), 0x59000000)
    }
    private val pad = ctx.dp(12f)
    private val side = ctx.dp(60f)
    private val gap = ctx.dp(14f)
    private val maxText = ctx.dp(280f)
    private val radius = ctx.dp(20f)
    private var title = ""
    private var sub = ""
    private var sheenH = -1
    private var rimShader: Shader? = null
    /** True from [show] with a session until it is hidden. */
    var shown = false
        private set
    var corner = -1
        private set

    init {
        isFocusable = false
        alpha = 0f
        visibility = GONE
    }

    fun show(np: NowPlaying?) {
        animate().cancel()
        if (np == null) { hide(); return }
        shown = true
        arts.set(np.art, (side * 2).toInt())
        title = np.title
        sub = np.subtitle
        requestLayout()
        invalidate()
        if (visibility != VISIBLE) { visibility = VISIBLE; alpha = 0f }
        animate().alpha(1f).setDuration(Effects.fade(600)).start()
    }

    /** Gone at once, or quickly (as the clock leaves when cinema mode wakes: it may sit where the user pill returns). */
    fun hide(instant: Boolean = false) {
        shown = false
        animate().cancel()
        if (instant || visibility != VISIBLE) { alpha = 0f; visibility = GONE; return }
        animate().alpha(0f).setDuration(Effects.fade(200)).withEndAction { if (!shown) visibility = GONE }.start()
    }

    /** How far above the bottom edge something centred must sit to clear this card in a bottom corner (0 otherwise). */
    fun bottomClearance(): Int {
        if (!shown || corner < 2) return 0
        return (layoutParams as? FrameLayout.LayoutParams)?.bottomMargin?.plus(Math.ceil((pad * 2 + side).toDouble()).toInt()) ?: 0
    }

    /** Moves to corner [c] (0 top left, then clockwise), inside the TV's safe margins like the clock. */
    // Screen corners, as the clock's (CinemaClock.place): the two must not trade sides in right-to-left languages.
    @android.annotation.SuppressLint("RtlHardcoded")
    fun place(c: Int) {
        if (c == corner && layoutParams != null) return
        corner = c
        val vertical = if (c < 2) Gravity.TOP else Gravity.BOTTOM
        val horizontal = if (c == 0 || c == 3) Gravity.LEFT else Gravity.RIGHT
        val sw = Backdrop.screenW
        val sh = Backdrop.screenH
        layoutParams = FrameLayout.LayoutParams(-2, -2, vertical or horizontal).apply {
            setMargins(sw * 64 / 1920, sh * 52 / 1080, sw * 64 / 1920, sh * 52 / 1080)
        }
    }

    override fun onMeasure(w: Int, hs: Int) {
        val text = maxOf(titleText.measureText(title), subText.measureText(sub)).coerceAtMost(maxText)
        setMeasuredDimension(Math.ceil((pad + side + gap + text + pad * 1.5f).toDouble()).toInt(), Math.ceil((pad * 2 + side).toDouble()).toInt())
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        // The shadow reaches past the card: its parent does not clip children.
        GlowCache.draw(c, rect.width(), rect.height(), radius, dp(18f), Color.BLACK, 0.20f, dp(4f), gp)
        if (sampleBackdrop && Backdrop.a.bmp != null) glass.draw(c, this, rect, radius)
        else {
            // Clear smoked glass over the moving scenery: a light dark tint, a sheen at the top and a bright top rim.
            val solid = Effects.reduceTransparency
            fill.color = if (solid) GlassPainter.SOLID else 0x5C0C101C
            c.drawRoundRect(rect, radius, radius, fill)
            if (sheenH != height) {
                sheenH = height
                sheenPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, height * 0.6f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                rimShader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(0x8CFFFFFF.toInt(), 0x24FFFFFF, 0x38FFFFFF),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            }
            if (!solid) c.drawRoundRect(rect, radius, radius, sheenPaint)
            rim.shader = if (solid) null else rimShader
            rim.color = 0x47FFFFFF
            val h = rim.strokeWidth / 2
            c.drawRoundRect(h, h, width - h, height - h, radius - h, radius - h, rim)
        }
        artRect.set(pad, pad, pad + side, pad + side)
        artPainter.draw(c, arts.art, artRect, dp(9f))
        val x = artRect.right + gap
        val room = width - x - pad
        val t = TextUtils.ellipsize(title, titleText, room, TextUtils.TruncateAt.END).toString()
        val s = TextUtils.ellipsize(sub, subText, room, TextUtils.TruncateAt.END).toString()
        val cy = height / 2f
        if (s.isEmpty()) c.drawText(t, x, cy - (titleText.ascent() + titleText.descent()) / 2, titleText)
        else {
            c.drawText(t, x, cy - dp(3f), titleText)
            c.drawText(s, x, cy + dp(3f) - subText.ascent(), subText)
        }
    }
}
