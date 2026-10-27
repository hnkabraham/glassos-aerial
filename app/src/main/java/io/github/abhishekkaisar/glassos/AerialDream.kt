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
        super.onWakeUp()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
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
    private val sw get() = Backdrop.screenW
    private val sh get() = Backdrop.screenH

    init {
        setBackgroundColor(Color.BLACK)
        addView(still, LayoutParams(-1, -1))
        addView(aerial, LayoutParams(-1, -1))
        // As on the home: the scene's name top left (bottom left while the clock is there), the clock in a safe corner,
        // night mode's dark layer over both.
        addView(label, LayoutParams(sw * 50 / 100, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = sw * 60 / 1920; topMargin = sh * 44 / 1080
        })
        addView(clock, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
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
        tick()
        if (prefs.cinemaClock) {
            clock.visibility = View.VISIBLE
            clock.animate().cancel()
            clock.animate().alpha(1f).setDuration(Effects.fade(900)).start()
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
        }
    }

    /** Lets go of the decoder (saving the playhead for the home) and of the minute tick. Main thread. */
    fun stop() {
        if (!started) return
        started = false
        scan++
        aerial.setRunning(false)
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
        clock.update()
        clock.place(prefs.movingClock)
        // The clock moved into the scene name's corner: the name goes at once, as the clock moved (see
        // CinemaPosition.labelCorner).
        if (prefs.cinemaClock && label.corner == clock.corner) label.hide(instant = true)
        val a = HomeScreen.nightAlpha(prefs)
        dim.setBackgroundColor(Color.argb((a * 255).toInt(), 0, 0, 0))
        dim.visibility = if (a > 0f) View.VISIBLE else View.GONE
        if (started && clips.isNotEmpty() && prefs.sceneCollection == "scheduled") aerial.setPlaylist(clips, true, HomeScreen.sceneChoice(prefs))
    }

    private fun clipChanged(clip: AerialClip) {
        clip.poster?.let { poster -> showStill("aerial:${poster.path}:${clip.revision}") { WallpaperStore.decodeCrop(poster, sw, sh) } }
        // Shown once per clip, as on the home: waking and sleeping again does not repeat it.
        if (clip.id != SceneSession.labeled) {
            SceneSession.labeled = clip.id
            if (prefs.sceneLabels) label.show(clip.name, null, HomeScreen.SCENE_LABEL_MS,
                CinemaPosition.labelCorner(if (prefs.cinemaClock) clock.corner else null))
        }
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
