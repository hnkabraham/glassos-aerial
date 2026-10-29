package io.github.abhishekkaisar.glassos

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.dreams.DreamService
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * GlassOS's scenery as the TV's screensaver. The TV starts it when idle once it is the chosen screensaver (secure
 * `screensaver_components`), which GlassOS Manager sets over ADB: GlassOS cannot set it itself. Same process as the
 * home, so both share the scenery's play order and playhead. Not interactive: the first key wakes the TV.
 */
class AerialDream : DreamService() {
    private var view: ScreensaverView? = null
    /** Settings > Match frame rate asked the TV for a mode this session (once at most). */
    private var asked = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        // TextureView draws only in a hardware-accelerated window. A dream's window is; said explicitly, as HomeOverlay does.
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        WLog.init(this)
        // Possibly a fresh process: the effects and the screen size the home's views read.
        Effects.load(Prefs(this))
        MainActivity.screenSize(this)
        val v = ScreensaverView(ContextThemeWrapper(this, R.style.GlassTheme))
        view = v
        // The frame rates of the scenes that may play, once per session: a mode that suits them all if the TV's does
        // not (see FrameRate).
        v.onSessionFps = { rates ->
            @Suppress("DEPRECATION")
            if (!asked && Prefs(this).matchFrameRate) asked = FrameRate.request(window, window.windowManager.defaultDisplay, rates)
        }
        setContentView(v)
        // The view paints every pixel (black or a still): no window background under it.
        window.setBackgroundDrawable(null)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    /** Waking: the decoder goes before the home, which can resume while this is still on screen, asks for it. */
    override fun onWakeUp() {
        view?.stop()
        letGoOfMode()
        super.onWakeUp()
    }

    override fun onDreamingStopped() {
        view?.stop()
        letGoOfMode()
        super.onDreamingStopped()
    }

    private fun letGoOfMode() {
        if (asked) FrameRate.release(window)
        asked = false
    }

    override fun onDetachedFromWindow() {
        view?.release()
        view = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** True when the TV's screensaver is this one (Settings > Screensaver, and GlassOS Manager's status). */
        fun chosen(ctx: Context): Boolean = isThis(ctx, setting(ctx))

        /** The TV's `screensaver_components` setting, read only; null when it is not set. */
        fun setting(ctx: Context): String? =
            try { Settings.Secure.getString(ctx.contentResolver, "screensaver_components") } catch (e: Exception) { null }

        /** The setting can list several components; the first is the one that plays. */
        fun isThis(ctx: Context, components: String?): Boolean {
            val first = components?.split(',')?.firstOrNull()?.trim()?.let { ComponentName.unflattenFromString(it) }
            return first == ComponentName(ctx, AerialDream::class.java)
        }
    }
}

/**
 * Settings > Screensaver > Preview: the screensaver as a plain full-screen activity, to see it without waiting for the
 * TV to go idle (and before it is the TV's screensaver). Any key or touch ends it.
 */
class ScreensaverPreview : Activity() {
    private lateinit var view: ScreensaverView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        Effects.load(Prefs(this))
        MainActivity.screenSize(this)
        view = ScreensaverView(this)
        setContentView(view)
        window.setBackgroundDrawable(null)
    }

    override fun onResume() {
        super.onResume()
        view.start()
    }

    override fun onPause() {
        view.stop()
        super.onPause()
    }

    override fun onDestroy() {
        view.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) finish()
        return true
    }
}

/**
 * What the screensaver shows (the dream and its preview): the scenery the home would play now, with the same clips,
 * choice, play order and playhead; each clip's still while it loads; the scene's name, the cinema clock and night
 * mode, all as the home has them. While started it alone holds the decoder ([SceneSession.claim]). Without clips, or
 * without storage access (a screensaver cannot ask for it), it shows the home's fallback background and the clock:
 * it never ends on its own, which the TV could take as a reason to go to sleep.
 */
class ScreensaverView(ctx: Context) : FrameLayout(ctx) {
    private val prefs = Prefs(ctx)
    private val still = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val aerial = AerialVideoView(ctx)
    private val label = SceneLabel(ctx)
    private val clock = CinemaClock(ctx)
    private val nowCard = NowPlayingCard(ctx, sampleBackdrop = false)
    private val captionLine = CaptionLine(ctx)
    private val captionTick = object : Runnable {
        override fun run() {
            if (!started) return
            captionLine.follow(aerial.positionMs())
            main.postDelayed(this, 1000)
        }
    }
    private val nowWatch = NowPlayingWatch(ctx) { showNowPlaying(it) }
    private val dim = View(ctx).apply { visibility = View.GONE }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var clips: List<AerialClip> = emptyList()
    private var stillBitmap: Bitmap? = null
    /** What the still shows or is decoding (a clip's poster or the fallback), so it is not decoded twice. */
    private var stillKey: String? = null
    /** Bumped by every start and stop: a scan that finishes after either is dropped. */
    private var scan = 0
    private var started = false
    private var released = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { tick() }
    }
    private val weatherListener: () -> Unit = { clock.weather = WeatherState.current(context, prefs) }
    private var captionKey = ""
    /** The frame rates of the scenes that may play, once per session (Settings > Match frame rate), off the main thread. */
    var onSessionFps: ((List<Float>) -> Unit)? = null
    private val sw get() = Backdrop.screenW
    private val sh get() = Backdrop.screenH

    init {
        setBackgroundColor(Color.BLACK)
        // The Now Playing card's shadow reaches past its bounds.
        clipChildren = false
        addView(still, LayoutParams(-1, -1))
        addView(aerial, LayoutParams(-1, -1))
        // As on the home: the scene's name top left (bottom left while the clock is there), the clock in a safe corner,
        // night mode's dark layer over both.
        addView(label, LayoutParams(sw * 50 / 100, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = sw * 60 / 1920; topMargin = sh * 44 / 1080
        })
        addView(clock, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        // The place caption, centred near the bottom, clear of the corners.
        addView(captionLine, LayoutParams(sw * 70 / 100, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = sh * 60 / 1080
        })
        // What a media app plays, in a corner clear of the clock and the name (see NowPlayingPosition).
        addView(nowCard)
        nowCard.place(NowPlayingPosition.corner(null))
        addView(dim, LayoutParams(-1, -1))
        aerial.onClipChanged = { clipChanged(it) }
        aerial.onUnavailable = { showFallback() }
    }

    /** Plays from where the home was. Main thread; again after [stop] is fine. */
    fun start() {
        if (started || released) return
        started = true
        WLog.i("screensaver on")
        // No scenery while the Screen Mirroring receiver decodes a mirrored screen (its screen hidden).
        ScreenMirroring.watch(context, prefs)
        // A home playing behind closes its player now, keeping its playhead for this view.
        SceneSession.claim(aerial)
        try {
            context.registerReceiver(timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            })
        } catch (e: Exception) { }
        WeatherState.addListener(weatherListener)
        tick()
        if (prefs.nowPlaying) nowWatch.start()
        if (prefs.placeCaptions) main.post(captionTick)
        if (prefs.cinemaClock) {
            clock.visibility = View.VISIBLE
            clock.animate().cancel()
            clock.animate().alpha(1f).setDuration(Effects.fade(900)).start()
            placeCaption()
        }
        val token = ++scan
        val ctx = context
        worker.execute {
            // AerialStore.clips reads no access as no clips; with access, it also loads the daily rhythm.
            val list = if (Grants.storage(ctx)) AerialStore.clips(ctx) else emptyList()
            main.post {
                if (token != scan || !started) return@post
                clips = list
                if (list.isNotEmpty()) aerial.setPlaylist(list, true, HomeScreen.sceneChoice(prefs))
                aerial.setRunning(true)
                if (!aerial.isActive) showFallback()
            }
            // The frame rates of the scenes that are not hidden, on their own thread (stills and captions go first), for
            // this session only: a wake stops the measuring.
            if (onSessionFps != null && prefs.matchFrameRate && FrameRate.supported && list.isNotEmpty()) {
                val hidden = prefs.sceneHidden
                FrameRate.measureAll(list.filter { it.id !in hidden }, { token == scan && started }) { rates ->
                    main.post { if (token == scan && started && rates.isNotEmpty()) onSessionFps?.invoke(rates) }
                }
            }
        }
    }

    /** Lets go of the decoder (saving the playhead for the home) and of the minute tick. Main thread. */
    fun stop() {
        if (!started) return
        started = false
        scan++
        aerial.setRunning(false)
        nowWatch.stop()
        nowCard.hide(instant = true)
        main.removeCallbacks(captionTick)
        captionLine.reset()
        placeCaption()
        WeatherState.removeListener(weatherListener)
        try { context.unregisterReceiver(timeReceiver) } catch (e: Exception) { }
        SceneSession.yield(aerial)
        WLog.i("screensaver off")
    }

    /** For good: the player, the worker and the still. */
    fun release() {
        if (released) return
        stop()
        released = true
        aerial.release()
        label.hide(instant = true)
        clock.animate().cancel()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        still.setImageDrawable(null)
        stillBitmap?.let { drop(it) }
        stillBitmap = null
    }

    /** The minute tick (and time or time zone changes): the clock, its corner, night mode and the daily rhythm. */
    private fun tick() {
        // The weather, as on the home: a stale reading goes, a due one is asked for (sets the clock's text too), but
        // only while the clock, the one place the screensaver shows it, is on.
        clock.weather = WeatherState.current(context, prefs)
        if (started && prefs.cinemaClock) WeatherState.refresh(context, prefs)
        clock.update()
        clock.place(prefs.movingClock)
        // The clock moved into the scene name's corner: the name goes at once, as the clock moved (see
        // CinemaPosition.labelCorner).
        if (prefs.cinemaClock && label.corner == clock.corner) label.hide(instant = true)
        if (nowCard.shown) placeNowCard() else placeCaption()
        val a = HomeScreen.nightAlpha(prefs)
        dim.setBackgroundColor(Color.argb((a * 255).toInt(), 0, 0, 0))
        dim.visibility = if (a > 0f) View.VISIBLE else View.GONE
        if (started && clips.isNotEmpty() && prefs.sceneCollection == "scheduled") aerial.setPlaylist(clips, true, HomeScreen.sceneChoice(prefs))
    }

    private fun clipChanged(clip: AerialClip) {
        clip.poster?.let { poster -> showStill("aerial:${poster.path}:${clip.revision}") { WallpaperStore.decodeCrop(poster, sw, sh) } }
        // Its place captions, read off the main thread; the last clip's go at once.
        val k = Captions.keyFor(clip)
        if (k != captionKey) {
            captionKey = k
            captionLine.setCaptions(clip.id, emptyList())
            val f = clip.captions
            if (f != null && !worker.isShutdown) worker.execute {
                val list = Captions.read(f)
                main.post { if (!released && aerial.current()?.id == clip.id && captionKey == k) captionLine.setCaptions(clip.id, list) }
            }
        }
        // Shown once per clip, as on the home: waking and sleeping again does not repeat it.
        if (clip.id != SceneSession.labeled) {
            SceneSession.labeled = clip.id
            if (prefs.sceneLabels) label.show(clip.name, null, HomeScreen.SCENE_LABEL_MS,
                CinemaPosition.labelCorner(if (prefs.cinemaClock) clock.corner else null))
            placeCaption()
        }
    }

    private fun showNowPlaying(np: NowPlaying?) {
        if (!started) return
        placeNowCard()
        nowCard.show(np)
        placeCaption()
    }

    private fun placeNowCard() {
        nowCard.place(NowPlayingPosition.corner(if (prefs.cinemaClock) clock.corner else null, label.corner.takeIf { label.shown }))
        placeCaption()
    }

    /** The place caption sits near the bottom, above whatever is in a bottom corner: the clock, the name or the card. */
    private fun placeCaption() {
        val bottom = ArrayList<Pair<View, Int>>()
        (clock.layoutParams as? LayoutParams)?.let { if (prefs.cinemaClock && clock.corner >= 2) bottom.add(clock to it.bottomMargin) }
        (label.layoutParams as? LayoutParams)?.let { if (label.shown && label.corner == 3) bottom.add(label to it.bottomMargin) }
        val m = maxOf(Captions.clearance(sh * 60 / 1080, context.dpi(12f), bottom), nowCard.bottomClearance() + context.dpi(12f))
        val lp = captionLine.layoutParams as LayoutParams
        if (lp.bottomMargin != m) { lp.bottomMargin = m; captionLine.layoutParams = lp }
    }

    /** Nothing can play (no clips, no storage access, or none decodes): the home's fallback background. */
    private fun showFallback() {
        val id = prefs.scene
        val ctx = context
        showStill("fallback:$id") { WallpaperStore.still(ctx, id, sw, sh) }
    }

    /** Decodes a still off the main thread, then shows it in place of the last one. */
    private fun showStill(key: String, decode: () -> Bitmap?) {
        if (key == stillKey || released) return
        stillKey = key
        worker.execute {
            val b = try { decode() } catch (e: Throwable) { null }
            main.post {
                if (released || key != stillKey) { b?.recycle(); return@post }
                val old = stillBitmap
                stillBitmap = b
                still.setImageBitmap(b)
                // An opaque still covers the black: no full-screen fill drawn under it.
                if (b != null) background = null else setBackgroundColor(Color.BLACK)
                old?.let { drop(it) }
            }
        }
    }

    companion object {
        private val later = Handler(Looper.getMainLooper())

        /** Frees a replaced still's 8 MB once the last frame that drew it is long gone (as HeroView does). */
        private fun drop(b: Bitmap) {
            later.postDelayed({ if (!b.isRecycled) b.recycle() }, 2000)
        }
    }
}
