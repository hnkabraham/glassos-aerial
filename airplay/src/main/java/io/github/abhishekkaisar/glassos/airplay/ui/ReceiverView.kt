package io.github.abhishekkaisar.glassos.airplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.R
import io.github.abhishekkaisar.glassos.airplay.ui.ReceiverScreen.Content
import kotlin.math.roundToInt

/**
 * The receiver screen's views: the mirrored picture (a SurfaceView the service's VideoRenderer draws into, sized to
 * the stream's aspect ratio and centred on black) and, over it, GlassOS's dark glass panels: the PIN, the track
 * playing, or the receiver's state, and a bar with Disconnect. GlassOS's text sizes and Roboto. Nothing here redraws
 * per frame: the picture is its own surface and the panels are drawn once (this process has no blurred backdrop, so
 * the glass is a flat tint with a sheen and a rim).
 */
internal class ReceiverView(ctx: Context, private val host: Host) : FrameLayout(ctx) {
    interface Host {
        fun surfaceReady(surface: android.view.Surface)
        fun surfaceGone(surface: android.view.Surface)
        fun disconnect()
    }

    private val video = SurfaceView(ctx)
    private var videoW = 0
    private var videoH = 0

    private val panel = GlassPanel(ctx, dp(30f))
    private val title = text(24f, Fonts.medium, 0xFFFFFFFF.toInt())
    private val pin = text(64f, Fonts.light, 0xFFFFFFFF.toInt()).apply { letterSpacing = 0.35f }
    private val line = text(15f, Fonts.regular, 0xD9FFFFFF.toInt())
    private val hint = text(14f, Fonts.medium, 0x99FFFFFF.toInt())

    private val playing = GlassPanel(ctx, dp(30f)).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val cover = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF1A1E29.toInt())
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14f))
        }
        clipToOutline = true
    }
    private val trackTitle = text(24f, Fonts.medium, 0xFFFFFFFF.toInt(), Gravity.START)
    private val trackArtist = text(15f, Fonts.regular, 0xD9FFFFFF.toInt(), Gravity.START)
    private val trackLine = text(14f, Fonts.medium, 0x99FFFFFF.toInt(), Gravity.START)

    private val bar = GlassPanel(ctx, dp(26f)).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val disconnect = GlassButton(ctx, "Disconnect").apply { setOnClickListener { host.disconnect() } }
    private val barHint = text(14f, Fonts.medium, 0x99FFFFFF.toInt(), Gravity.START)

    private var content = Content.READY
    private var offerDisconnect = false
    /** The bar shows over a mirrored picture only after a key, and hides again on its own. */
    private var controlsUntil = 0L
    private val hideControls = Runnable { applyControls() }

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(video, LayoutParams(-1, -1, Gravity.CENTER))
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = host.surfaceReady(h.surface)
            // A new size (the stream's aspect ratio changed): the renderer binds its window again.
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, ht: Int) = host.surfaceReady(h.surface)
            override fun surfaceDestroyed(h: SurfaceHolder) = host.surfaceGone(h.surface)
        })

        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        val pad = dpi(26f)
        panel.setPadding(pad, dpi(26f), pad, dpi(24f))
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        panel.addView(pin, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(14f); bottomMargin = dpi(6f) })
        panel.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
        panel.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        addView(panel, LayoutParams(dpi(480f), -2, Gravity.CENTER))

        playing.setPadding(dpi(22f), dpi(22f), dpi(30f), dpi(22f))
        playing.addView(cover, LinearLayout.LayoutParams(dpi(150f), dpi(150f)))
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        words.addView(trackLine, LinearLayout.LayoutParams(-1, -2))
        words.addView(trackTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        words.addView(trackArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        playing.addView(words, LinearLayout.LayoutParams(dpi(330f), -2).apply { leftMargin = dpi(24f) })
        addView(playing, LayoutParams(-2, -2, Gravity.CENTER))

        bar.setPadding(dpi(10f), dpi(10f), dpi(22f), dpi(10f))
        bar.addView(disconnect, LinearLayout.LayoutParams(-2, dpi(48f)))
        bar.addView(barHint, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(18f) })
        addView(bar, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(40f) })
        show(AirPlayStatus(), null)
    }

    /** Shows [s] ([cover]: the track's art while music plays). Main thread. */
    fun show(s: AirPlayStatus, cover: Bitmap?) {
        content = ReceiverScreen.content(s)
        if (s.videoWidth != videoW || s.videoHeight != videoH) {
            videoW = s.videoWidth
            videoH = s.videoHeight
            requestLayout()
        }
        video.visibility = if (content == Content.VIDEO) View.VISIBLE else View.INVISIBLE

        title.setText(R.string.mirroring_title)
        line.text = ReceiverScreen.statusLine(s)
        hint.text = ReceiverScreen.hint(s)
        hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
        pin.visibility = if (content == Content.PIN) View.VISIBLE else View.GONE
        // The PIN goes only to the screen: never into a log or a description.
        pin.text = s.pin ?: ""
        panel.visibility = if (content == Content.VIDEO || content == Content.NOW_PLAYING) View.GONE else View.VISIBLE

        playing.visibility = if (content == Content.NOW_PLAYING) View.VISIBLE else View.GONE
        if (content == Content.NOW_PLAYING) {
            trackLine.text = ReceiverScreen.statusLine(s)
            trackTitle.text = s.title.ifEmpty { "Music" }
            trackArtist.text = s.artist
            trackArtist.visibility = if (s.artist.isEmpty()) View.GONE else View.VISIBLE
            this.cover.setImageBitmap(cover)
        } else this.cover.setImageDrawable(null)

        barHint.text = if (content == Content.VIDEO || content == Content.NOW_PLAYING) ReceiverScreen.hint(s) else ""
        barHint.visibility = if (barHint.text.isEmpty()) View.GONE else View.VISIBLE
        // Without its hint the bar holds the button alone: the same margin on both sides.
        bar.setPadding(dpi(10f), dpi(10f), dpi(if (barHint.visibility == View.VISIBLE) 22f else 10f), dpi(10f))
        offerDisconnect = ReceiverScreen.offersDisconnect(s)
        applyControls()
    }

    /** A remote key over a mirrored picture: the bar shows for a few seconds. */
    fun showControls(): Boolean {
        if (content != Content.VIDEO || !offerDisconnect) return false
        val wasShown = bar.visibility == View.VISIBLE
        controlsUntil = android.os.SystemClock.uptimeMillis() + CONTROLS_MS
        applyControls()
        return !wasShown
    }

    /** Keeps a shown bar up while the user moves around it. */
    fun touchControls() {
        if (content == Content.VIDEO && bar.visibility == View.VISIBLE) {
            controlsUntil = android.os.SystemClock.uptimeMillis() + CONTROLS_MS
            removeCallbacks(hideControls)
            postDelayed(hideControls, CONTROLS_MS)
        }
    }

    private fun applyControls() {
        removeCallbacks(hideControls)
        val left = controlsUntil - android.os.SystemClock.uptimeMillis()
        val shown = offerDisconnect && (content != Content.VIDEO || left > 0)
        bar.visibility = if (shown) View.VISIBLE else View.GONE
        if (shown) {
            if (!disconnect.isFocused) disconnect.requestFocus()
            if (content == Content.VIDEO) postDelayed(hideControls, left)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // The picture: the largest box with the stream's aspect ratio, centred (bars stay black).
        val fit = AspectFit.fit(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec), videoW, videoH)
        val lp = video.layoutParams as LayoutParams
        if (lp.width != fit.width || lp.height != fit.height) {
            lp.width = fit.width
            lp.height = fit.height
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideControls)
        super.onDetachedFromWindow()
    }

    private fun text(sp: Float, face: Typeface, color: Int, g: Int = Gravity.CENTER) = TextView(context).apply {
        textSize = sp
        typeface = face
        setTextColor(color)
        gravity = g
        setLineSpacing(0f, 1.15f)
        // Keeps the window's focus on Disconnect, the only control.
        isFocusable = false
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).roundToInt()

    companion object {
        private const val CONTROLS_MS = 6_000L
    }
}

/** GlassOS's faces (Roboto on Android TV and Fire TV). */
internal object Fonts {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/**
 * GlassOS's dark glass without a backdrop: a dark tint, a sheen fading down from the top and a thin light rim, as
 * GlassOS draws its panels under Reduce transparency. Drawn when laid out, never per frame.
 */
internal class GlassPanel(ctx: Context, private val radius: Float) : LinearLayout(ctx) {
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9141824.toInt() }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * ctx.resources.displayMetrics.density
        color = 0x42FFFFFF
    }

    init {
        setWillNotDraw(false)
        // A focused button grows by 3 % into the padding: drawn whole, not cut flat at the padding's edge.
        clipToPadding = false
        clipChildren = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sheen.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, radius, radius, fill)
        c.drawRoundRect(rect, radius, radius, sheen)
        val half = rim.strokeWidth / 2
        rect.inset(half, half)
        c.drawRoundRect(rect, radius - half, radius - half, rim)
    }
}

/** GlassOS's settings row as a button: a faint fill that turns white, with dark text, when focused. */
internal class GlassButton(ctx: Context, label: String) : TextView(ctx) {
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = 16f * ctx.resources.displayMetrics.density

    init {
        text = label
        textSize = 19f
        typeface = Fonts.regular
        gravity = Gravity.CENTER
        val pad = (20f * ctx.resources.displayMetrics.density).roundToInt()
        setPadding(pad, 0, pad, 0)
        isFocusable = true
        isClickable = true
        applyFocus(false)
    }

    private fun applyFocus(f: Boolean) {
        setTextColor(if (f) 0xFF101218.toInt() else 0xF2FFFFFF.toInt())
        fill.color = if (f) 0xEDFFFFFF.toInt() else 0x12FFFFFF
        animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(if (f) 220 else 180).start()
        invalidate()
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        applyFocus(gained)
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, radius, radius, fill)
        super.onDraw(c)
    }
}
