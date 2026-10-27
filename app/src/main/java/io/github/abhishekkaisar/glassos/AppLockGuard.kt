package io.github.abhishekkaisar.glassos

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The app lock as the home screens, [AppLockGuard] and [LockActivity] share it (one process). */
object AppLock {
    val session = LockSession()
    val tries = LockTries()
    /** Locked packages, read by tiles as they draw and by the guard as it polls. */
    @Volatile var locked: Set<String> = emptySet()
        private set

    // Changes are synchronized: GlassOS Manager's run on a binder thread, and must not undo a lock made meanwhile.
    @Synchronized fun load(p: Prefs) { locked = p.locked }

    @Synchronized fun set(ctx: Context, p: Prefs, pkgs: Set<String>) {
        p.locked = pkgs
        locked = pkgs
        AppLockGuard.sync(ctx)
    }

    /**
     * A new [code] (checked with [LockCode.valid]), every locked app kept. Settings and GlassOS Manager both change
     * the code here; any thread. False when it could not be saved.
     */
    @Synchronized fun setCode(ctx: Context, p: Prefs, code: String): Boolean {
        val keep = p.locked
        if (!p.setLock(keep, LockCode.store(code))) return false
        locked = keep
        tries.pass()
        session.end()
        AppLockGuard.sync(ctx)
        return true
    }

    /** No code and no locked apps: every app opens freely. Settings and GlassOS Manager; any thread. */
    @Synchronized fun turnOff(ctx: Context, p: Prefs): Boolean {
        if (!p.setLock(emptySet(), "")) return false
        locked = emptySet()
        session.end()
        AppLockGuard.sync(ctx)
        return true
    }

    /** Ordinary apps only: never GlassOS, a home app or a screensaver (Back from the lock screen goes Home). */
    fun canLock(ctx: Context, pkg: String): Boolean = pkg !in Platform.usageHelpers(ctx)

    fun waitText(ms: Long) = "Too many wrong codes. Try again in ${(ms + 999) / 1000} seconds."
}

/**
 * Locks apps however they open: a cast from a phone, Alexa, a remote's app button, recent apps. Watches which app
 * comes to the front the way [HomeWatcher] does (only the usage events since the last poll, every 120 ms with the
 * screen on) and covers a locked one with [LockActivity]. Runs only while an app is locked and it has Usage access
 * and, from Android 10, Display over other apps: without that a background service may not open the lock screen.
 */
class AppLockGuard : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var usm: UsageStatsManager
    private lateinit var power: PowerManager
    private var lastTs = 0L
    private var screenOn = true
    private var fire = false
    // The TV's screensaver package, read at most once per poll and only when an event needs it.
    private var saver: String? = null
    private var saverRead = false
    private val saverOnce: () -> String? = {
        if (!saverRead) {
            saverRead = true
            // The framework plays the default screensaver while none is chosen.
            saver = ScreensaverFront.saverPackage(AerialDream.setting(this)) ?: ScreensaverFront.saverPackage(
                try { Settings.Secure.getString(contentResolver, "screensaver_default_component") } catch (e: Exception) { null })
        }
        saver
    }
    /**
     * Whether a package has an activity people open (launcher, leanback launcher or home), kept per package until a
     * package is installed, updated, changed or removed.
     */
    private val opens = HashMap<String, Boolean>()
    private val opensOnce: (String) -> Boolean = { pkg ->
        opens.getOrPut(pkg) {
            listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_HOME).any { cat ->
                // Unknown counts as an ordinary app: it ends an unlock.
                try { packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg), 0).isNotEmpty() }
                catch (e: RuntimeException) { true }
            }
        }
    }
    // Runs on the guard's thread, as the polls that read the answers do.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { opens.clear() }
    }

    private val poll = object : Runnable {
        override fun run() {
            try { check() } catch (e: Throwable) { }
            handler.postDelayed(this, if (power.isInteractive) POLL_MS else IDLE_POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        WLog.init(this)
        startForeground(2, notification())
        AppLock.load(Prefs(this))
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        thread = HandlerThread("glass-lock", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        handler = Handler(thread.looper)
        fire = Platform.isFireTv(this)
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, null, handler)
        lastTs = System.currentTimeMillis()
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!wanted(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try { unregisterReceiver(packages) } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun check() {
        val on = power.isInteractive
        // The TV going to sleep locks the open app again.
        if (screenOn && !on) AppLock.session.end()
        screenOn = on
        val now = System.currentTimeMillis()
        saverRead = false
        val ev = usm.queryEvents(lastTs - 300, now + 1000)
        val e = UsageEvents.Event()
        var cover: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.timeStamp <= lastTs || e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            lastTs = e.timeStamp
            val pkg = e.packageName ?: continue
            // The lock screen changes nothing; Glass's home ends an unlock like any other app.
            if (pkg == packageName && e.className == LockActivity::class.java.name) continue
            // Nor does a screensaver starting or showing (see ScreensaverFront): the app it covered stays unlocked when
            // it ends. The TV going to sleep still locks it again. A locked app never passes for one.
            if (ScreensaverFront.matches(pkg, e.className, AppLock.locked, fire, saverOnce, opensOnce)) continue
            // Only the app left in front matters: one that came and went within a poll is already covered by another.
            cover = if (AppLock.session.front(pkg, e.timeStamp, AppLock.locked)) pkg else null
        }
        if (cover != null) {
            WLog.i("app lock: covering $cover")
            val i = Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_PKG, cover)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            try { startActivity(i) } catch (e: Throwable) { }
        }
    }

    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App lock", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null); setShowBadge(false)
            })
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN).setSound(null)
        }
        return b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("GlassOS").setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL = "lock"
        private const val POLL_MS = 120L
        private const val IDLE_POLL_MS = 2000L

        /** True while the guard runs in this process (GlassOS Manager's status shows it). */
        @Volatile var running = false
            private set

        /** True when locked apps can be covered however they open, not only from Glass's home. */
        fun available(ctx: Context): Boolean =
            HomeWatcher.hasAccess(ctx) && (Build.VERSION.SDK_INT < 29 || Grants.canDrawOverlays(ctx))

        fun wanted(ctx: Context): Boolean = Prefs(ctx).locked.isNotEmpty() && available(ctx)

        /** Starts the guard while an app is locked and it may run, else makes sure it is stopped. */
        fun sync(ctx: Context) {
            val i = Intent(ctx, AppLockGuard::class.java)
            try {
                if (wanted(ctx)) {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                } else ctx.stopService(i)
            } catch (e: Throwable) { }
        }
    }
}

/**
 * The lock screen over a locked app that opened outside Glass's home. Opaque, so the app beneath stops playing. The
 * right code lets the app carry on until another app comes to the front or the TV sleeps; Back goes Home.
 */
class LockActivity : Activity() {
    private var pkg = ""
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var dots: CodeDots

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val prefs = Prefs(this)
        Effects.load(prefs)
        AppLock.load(prefs)
        val root = Scrim(this)
        val panel = GlassBox(this, dp(30f)).apply {
            glass.tint = 0.10f
            glass.dark = 0.30f
            glass.sheen = 0.10f
            shadowAlpha = 0.5f
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dpi(26f)
            setPadding(pad, dpi(26f), pad, dpi(24f))
        }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        title = TextView(this).apply {
            textSize = 24f
            typeface = Fonts.medium
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        message = TextView(this).apply {
            textSize = 15f
            typeface = Fonts.regular
            setTextColor(0xD9FFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        dots = CodeDots(this).apply { onCode = { entered(it) } }
        list.addView(art, LinearLayout.LayoutParams(dpi(160f), dpi(90f)).apply { bottomMargin = dpi(16f) })
        list.addView(title, LinearLayout.LayoutParams(-1, -2))
        list.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(18f); bottomMargin = dpi(16f) })
        list.addView(message, LinearLayout.LayoutParams(-1, -2))
        panel.addView(list, FrameLayout.LayoutParams(-1, -2))
        root.addView(panel, FrameLayout.LayoutParams(dpi(480f), -2, Gravity.CENTER))
        setContentView(root)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    override fun onStart() {
        super.onStart()
        // Kept stopped through sleep (noHistory does not finish it then) while the lock went off: nothing left to ask.
        if (pkg.isNotEmpty() && pkg !in AppLock.locked) { finish(); return }
        shown = java.lang.ref.WeakReference(this)
    }

    override fun onStop() {
        super.onStop()
        if (shown?.get() === this) shown = null
    }

    private fun show(i: Intent) {
        pkg = i.getStringExtra(EXTRA_PKG) ?: ""
        if (pkg !in AppLock.locked) { finish(); return }
        val pm = packageManager
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { null }
        val label = info?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        art.setImageDrawable(info?.let { pm.getApplicationBanner(it) ?: pm.getApplicationIcon(it) })
        title.text = "$label is locked"
        val wait = AppLock.tries.waitMs(SystemClock.elapsedRealtime())
        message.text = if (wait > 0) AppLock.waitText(wait) else HINT
        dots.clear()
        dots.requestFocus()
    }

    private fun entered(code: String) {
        val now = SystemClock.elapsedRealtime()
        val wait = AppLock.tries.waitMs(now)
        when {
            wait > 0 -> { message.text = AppLock.waitText(wait); dots.reject() }
            LockCode.matches(Prefs(this).lockCode, code) -> {
                AppLock.tries.pass()
                AppLock.session.unlock(pkg, System.currentTimeMillis())
                WLog.i("app lock: $pkg unlocked")
                finish()
                overridePendingTransition(0, 0)
            }
            AppLock.tries.fail(now) -> { message.text = AppLock.waitText(LockCode.WAIT_MS); dots.reject() }
            else -> { message.text = "Wrong code. Try again."; dots.reject() }
        }
    }

    /** Back goes Home, past the locked app. */
    override fun onBackPressed() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) { }
        finish()
    }

    /** The home's blurred scenery, darkened; plain night when the home has not drawn one in this process. */
    private class Scrim(ctx: Context) : FrameLayout(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        init {
            setWillNotDraw(false)
            // The panel's shadow reaches past its bounds.
            clipChildren = false
        }

        override fun onDraw(c: Canvas) {
            val bmp: Bitmap? = Backdrop.a.bmp
            if (bmp != null && !bmp.isRecycled) {
                c.drawBitmap(bmp, Backdrop.a.m, paint)
                c.drawColor(0x8C000000.toInt())
            } else c.drawColor(0xFF0E1118.toInt())
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        private const val HINT = "Enter the code with your remote. Back goes Home."
        /** The lock screen while it is on screen (main thread). */
        private var shown: java.lang.ref.WeakReference<LockActivity>? = null

        /** The lock was turned off elsewhere (GlassOS Manager): the app beneath carries on. Main thread. */
        fun finishShown() {
            shown?.get()?.finish()
            shown = null
        }
    }
}
