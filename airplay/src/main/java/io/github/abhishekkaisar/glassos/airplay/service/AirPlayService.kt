/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Surface
import io.github.abhishekkaisar.glassos.airplay.AirPlay
import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.State
import io.github.abhishekkaisar.glassos.airplay.DebugOptions
import io.github.abhishekkaisar.glassos.airplay.Protocol
import io.github.abhishekkaisar.glassos.airplay.R
import io.github.abhishekkaisar.glassos.airplay.audio.DacpController
import io.github.abhishekkaisar.glassos.airplay.audio.DmapParser
import io.github.abhishekkaisar.glassos.airplay.audio.TrackInfo
import io.github.abhishekkaisar.glassos.airplay.audio.VolumeBroadcast
import io.github.abhishekkaisar.glassos.airplay.debug.DebugFeed
import io.github.abhishekkaisar.glassos.airplay.debug.DebugHooks
import io.github.abhishekkaisar.glassos.airplay.discovery.MdnsService
import io.github.abhishekkaisar.glassos.airplay.discovery.NsdServiceManager
import io.github.abhishekkaisar.glassos.airplay.render.AudioConfig
import io.github.abhishekkaisar.glassos.airplay.render.AudioRenderer
import io.github.abhishekkaisar.glassos.airplay.render.DisplayInfo
import io.github.abhishekkaisar.glassos.airplay.render.VideoRenderer
import io.github.abhishekkaisar.glassos.airplay.ui.AirPlayActivity
import io.github.abhishekkaisar.glassos.airplay.ui.ReceiverScreen
import io.github.abhishekkaisar.glassos.airplay.ui.ReceiverScreen.Trigger
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import java.io.File
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Screen Mirroring receiver, alone in the :airplay process with all native code, so a native crash never takes
 * the launcher down. A foreground service started by the launcher ([Protocol.ACTION_START] with the whole config)
 * and driven over the Messenger in [StatusHub]; it pushes its status on every change and always sends STOPPED or
 * ERROR before its process exits.
 *
 * Ported from jqssun's AirPlayService: a plain Service with a Handler and plain fields instead of LifecycleService,
 * coroutines and StateFlows; framework Notification and MediaSession instead of the AndroidX compat classes; no
 * DacpPlayer (Media3), USB advertising, video download or HLS player. The partial wake lock is held only while a
 * sender is connected, the multicast lock while the receiver runs. GlassOS: the native library's loading, the
 * receiver's start (key file, sockets) and stop (it waits for its threads, a second or two) run in order on one
 * worker thread ([nativeWork]), never on the main thread, which the receiver screen shares.
 */
class AirPlayService : Service(), RaopCallbackHandler, LogListener, StatusHub.Commands {

    /** In-process observers (the receiver screen); main thread. Also told when the cover art changes. */
    fun interface Listener {
        fun onStatus(s: AirPlayStatus)
    }

    /** What an in-process client gets from binding with [ACTION_LOCAL]. */
    inner class LocalBinder : Binder() {
        val service: AirPlayService get() = this@AirPlayService
    }

    private val main = Handler(Looper.getMainLooper())
    private val session = Session { SystemClock.elapsedRealtime() }
    private val hub = StatusHub(this)
    private val listeners = LinkedHashSet<Listener>()

    private var config = AirPlayConfig.DEFAULTS
    private var debug = DebugOptions.NONE
    private var state = State.STOPPED
    private var error: String? = null
    private var port = 0
    private var serverName = ""
    /** Bumped at every start and shutdown: changes that native callbacks of an older run posted are dropped. */
    @Volatile private var generation = 0
    /** The running receiver's handle, once its start finished (main thread; [workerHandle] is the worker's own). */
    @Volatile private var nativeHandle = 0L
    /** The receiver open on [nativeWork], touched only there: a stop closes whichever is open, in order. */
    private var workerHandle = 0L
    /**
     * The run (generation) whose native threads are up, or -1 while none is or one is closing: native callbacks
     * belong to it, so those of a run being closed (its teardown) never reach the next one.
     */
    @Volatile private var nativeRun = -1
    /** Sender connections as the native threads see them (session.connections follows on the main thread). */
    private val liveConnections = AtomicInteger()
    private var nsd: NsdServiceManager? = null
    private var adverts: List<MdnsService> = emptyList()
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var exitPending = false
    /** Bumped by every scheduled or cancelled exit: an exit waiting on the worker belongs to one of them only. */
    private var exitToken = 0
    private var activityVisible = false
    /** Back hid the receiver screen during this session: the session does not bring it back by itself. */
    private var userHid = false
    /** Debug builds: an MP4 played in as a mirror session (airplay-debug feed-mp4). */
    private var feed: DebugFeed? = null

    lateinit var videoRenderer: VideoRenderer
        private set
    val audioRenderer = AudioRenderer()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var dacp: DacpController? = null
    private var mediaSession: MediaSession? = null
    private var volumeReceiver: BroadcastReceiver? = null
    /** The track's cover while music plays (for the receiver screen); main thread. */
    var coverArt: Bitmap? = null
        private set

    // Sender volume sync (jqssun): the TV's volume keys drive the sender's volume over DACP and the sender's slider
    // drives STREAM_MUSIC; main thread unless marked volatile.
    @Volatile private var syncClientVol = true
    private var pendingServerVolEchoes = 0
    // senders park volume at -30 as route closes
    private var serverPreZeroIdx = -1
    @Volatile private var clientVol: Float? = null
    private var clientVolTarget = -1f
    private var clientVolHint = 0
    private var clientVolDir = 0
    private var clientVolSteps = 0
    private val clientVolTimeout = Runnable { clientVolDriveEnd() }

    // ---------- Service ----------

    override fun onCreate() {
        super.onCreate()
        DebugHooks.strictMode(this)
        videoRenderer = VideoRenderer(this)
        createNotificationChannel()
        dacp = DacpController(this)
        mediaSession = MediaSession(this, "GlassOS Screen Mirroring").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    setPlaying(true)
                    dacp?.play()
                }
                override fun onPause() {
                    setPlaying(false)
                    dacp?.pause()
                }
                override fun onSkipToNext() {
                    dacp?.nextItem()
                }
                override fun onSkipToPrevious() {
                    dacp?.prevItem()
                }
            })
            // Needed before API 26 for media buttons and transport controls to reach the session.
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(VolumeBroadcast.EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val idx = intent.getIntExtra(VolumeBroadcast.EXTRA_VALUE, -1)
                val prev = intent.getIntExtra(VolumeBroadcast.EXTRA_PREV_VALUE, -1)
                if (idx < 0 || prev < 0 || idx == prev) return
                if (pendingServerVolEchoes > 0) { pendingServerVolEchoes--; return }
                if (!syncClientVol || session.connections == 0) return
                val target = idx.toFloat() / serverVolMax()
                Log.d(TAG, "receiver volume $prev -> $idx, sync sender to $target")
                serverPreZeroIdx = -1
                driveClientVol(target, if (idx > prev) 1 else -1)
            }
        }
        // The system sends this broadcast, which reaches a not-exported receiver too.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(VolumeBroadcast.ACTION), RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, IntentFilter(VolumeBroadcast.ACTION))
        volumeReceiver = receiver
    }

    override fun onBind(intent: Intent): IBinder = if (intent.action == ACTION_LOCAL) LocalBinder() else hub.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Protocol.ACTION_START -> {
                // startForegroundService needs startForeground within 5 s, whatever happens next.
                promoteToForeground()
                cancelExit()
                val cfg = Protocol.configFromBundle(intent.getBundleExtra(Protocol.EXTRA_CONFIG))
                val dbg = if (debuggable()) DebugOptions.from(intent.getBundleExtra(Protocol.EXTRA_DEBUG)) else DebugOptions.NONE
                if (state == State.RUNNING || state == State.STARTING) {
                    // While it starts, the settings are compared once it runs (started).
                    val portsChanged = !(dbg.ports contentEquals debug.ports)
                    debug = dbg
                    applyConfig(cfg, forceRestart = portsChanged || dbg.failInit)
                } else {
                    config = cfg
                    debug = dbg
                    startServer()
                }
            }
            Protocol.ACTION_STOP -> onStop()
            Protocol.ACTION_PLAY_PAUSE -> if (state == State.RUNNING) togglePlayPause()
            Protocol.ACTION_NEXT -> if (state == State.RUNNING) dacp?.nextItem()
            Protocol.ACTION_PREV -> if (state == State.RUNNING) dacp?.prevItem()
        }
        // A stray intent (a transport button after the receiver stopped) must not leave an idle service behind.
        if (state == State.STOPPED && !exitPending) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(exitRunnable)
        exitToken++
        main.removeCallbacks(openCheck)
        if (nativeHandle != 0L || state == State.RUNNING || state == State.STARTING) shutdownRun()
        if (state != State.ERROR) state = State.STOPPED
        pushStatus(force = true)
        volumeReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        volumeReceiver = null
        dacp?.release()
        dacp = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // ---------- Commands from the launcher (StatusHub, main thread) ----------

    override fun onApply(config: AirPlayConfig) = applyConfig(config, forceRestart = false)

    /** STOP: native stop and destroy, NSD and locks released, STOPPED pushed, then the process exits. */
    override fun onStop() {
        main.removeCallbacks(restartRunnable)
        if (nativeHandle != 0L || state == State.RUNNING || state == State.STARTING) shutdownRun()
        state = State.STOPPED
        error = null
        pushStatus(force = true)
        Log.i(TAG, "receiver stopped")
        scheduleExit()
    }

    /** Drops every sender (the receiver keeps listening); a PIN waiting for one goes too. */
    override fun onDisconnect() {
        Log.i(TAG, "disconnect")
        if (nativeHandle != 0L) nativeWork.post { workerHandle.takeIf { it != 0L }?.let { NativeBridge.nativeDisconnect(it) } }
        // The debug feed stands for a sender: it ends as one would.
        feed?.finish()
        dismissPin()
    }

    /** SHOW from the launcher: the receiver screen, even after Back hid it. */
    override fun onShow() {
        if (state != State.RUNNING) return
        userHid = false
        openScreen(Trigger.SHOW)
    }

    override fun onDebug(request: Bundle) {
        if (!debuggable()) return
        when (request.getString(Protocol.DEBUG_OP)) {
            Protocol.OP_FEED -> {
                val path = request.getString(Protocol.DEBUG_PATH) ?: return
                // One session at a time, as with a sender (GlassOS Manager answers "busy" first).
                if (state != State.RUNNING || feed != null) return
                val seconds = request.getInt(Protocol.DEBUG_SECONDS)
                feed = DebugFeed(path, seconds, this) { videoRenderer.stats() }.also { it.start() }
            }
            Protocol.OP_FEED_STOP -> feed?.finish()
            // As display_pin would (the PIN itself is never logged).
            Protocol.OP_PIN -> {
                val pin = request.getString(Protocol.DEBUG_PIN)
                if (pin != null && state == State.RUNNING) onDisplayPin(pin) else dismissPin()
            }
        }
    }

    /** The debug feed ended (its time was up, feed-stop, Disconnect): forget it. Main thread. */
    internal fun feedEnded(f: DebugFeed) {
        if (feed === f) feed = null
    }

    override fun adverts(): Bundle? {
        if (!debuggable()) return null
        val list = ArrayList<Bundle>()
        for (a in adverts) list += Bundle().apply {
            putString(Protocol.ADVERT_NAME, nsd?.registered?.get(a.type) ?: a.name)
            putString(Protocol.ADVERT_TYPE, a.type)
            putInt(Protocol.ADVERT_PORT, a.port)
            putBundle(Protocol.ADVERT_TXT, Bundle().apply { for ((k, v) in a.txt) putString(k, v) })
        }
        return Bundle().apply { putParcelableArrayList(Protocol.ADVERTS, list) }
    }

    // ---------- In-process API (the receiver screen) ----------

    fun addListener(l: Listener) {
        listeners += l
        l.onStatus(hub.status)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun setVideoSurface(surface: Surface) = videoRenderer.setSurface(surface)

    fun clearVideoSurface(surface: Surface) = videoRenderer.clearSurface(surface)

    /** The receiver screen shows (or not): the process stays when STOP comes while it shows. */
    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
        if (visible) main.removeCallbacks(openCheck)
        pushStatus()
    }

    /**
     * Back on the receiver screen: it goes, the session goes on (a mirrored screen keeps decoding, headless) and does
     * not bring the screen back by itself; SHOW does. A PIN is dismissed: the sender asks again if it still wants it.
     */
    fun hide() {
        if (session.mode != AirPlayStatus.Mode.IDLE) userHid = true
        dismissPin()
    }

    /** Brings the receiver screen up for [t], if the launch policy says so. Main thread. */
    private fun openScreen(t: Trigger) {
        if (state != State.RUNNING || !ReceiverScreen.opens(t, activityVisible, userHid)) return
        // Allowed while GlassOS has a window on screen (the home, this screen) or holds Display over other apps;
        // otherwise Android 10 and later drop the start without an error, and the session goes on headless.
        try {
            startActivity(Intent(this, AirPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AirPlayActivity.EXTRA_TRIGGER, t.name))
        } catch (e: RuntimeException) {
            Log.w(TAG, "receiver screen not started", e)
        }
        main.removeCallbacks(openCheck)
        main.postDelayed(openCheck, OPEN_CHECK_MS)
    }

    private val openCheck = Runnable {
        if (activityVisible || state != State.RUNNING || !(hub.status.decodesVideo || session.pin != null)) return@Runnable
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        Log.i(TAG, "the receiver screen did not open (background start refused" +
            (if (overlay) "" else ", no Display over other apps") + "): the session goes on without it")
    }

    private fun dismissPin() {
        if (session.pin == null) return
        clearPin()
        pushStatus()
    }

    private fun notifyCover() {
        for (l in listeners.toList()) l.onStatus(hub.status)
    }

    // ---------- Receiver lifecycle ----------

    private fun startServer() {
        val run = ++generation
        state = State.STARTING
        error = null
        port = 0
        serverName = config.name
        pushStatus()
        // The launcher only offers Screen Mirroring where AirPlay.isSupported; the receiver checks again for itself.
        if (Build.VERSION.SDK_INT < AirPlay.MIN_SDK) return failStart(AirPlayStatus.ERROR_UNSUPPORTED)
        if (debug.failInit) {
            Log.w(TAG, "debug: failing the native init on purpose")
            return failStart(AirPlayStatus.ERROR_NATIVE_INIT)
        }
        nsd = NsdServiceManager(this).apply { acquireMulticastLock() }
        val cfg = config
        val fixed = debug.ports
        nativeWork.post {
            // A stop or restart queued before this ran closes it right after (the worker keeps the order).
            val r = openNative(run, cfg, fixed)
            main.post { started(run, cfg, fixed, r) }
        }
    }

    /** What one native start produced on the worker; [error] is set when it failed. */
    private class NativeRun(val port: Int = 0, val name: String = "", val adverts: List<MdnsService> = emptyList(),
                            val handle: Long = 0L, val error: String? = null)

    /** Worker thread: loads the library, inits, configures and starts a receiver for run [run] with [cfg]. */
    private fun openNative(run: Int, cfg: AirPlayConfig, fixed: IntArray?): NativeRun {
        closeNative()
        if (!nativeReady()) return NativeRun(error = AirPlayStatus.ERROR_NATIVE_MISSING)
        val keyFile = File(filesDir, "airplay.pem").absolutePath
        val h = NativeBridge.nativeInit(this, hwAddr(), cfg.name, keyFile, cfg.allowNewConnection, cfg.pin)
        if (h == 0L) return NativeRun(error = AirPlayStatus.ERROR_NATIVE_INIT)
        workerHandle = h
        nativeRun = run
        audioRenderer.attachEngine(h)
        audioRenderer.updateConfig(audioConfig(cfg))

        val maxFps = cfg.maxFps
        val (reqW, reqH) = displaySize(cfg, clamp = false)
        val h265 = videoRenderer.selectDecoders(reqW, reqH, maxFps, cfg.h265)
        videoRenderer.enforceSdr = cfg.enforceSdr
        videoRenderer.keyAllowFrameDrop = cfg.allowFrameDrop
        videoRenderer.selector.maxOperatingRate = cfg.operatingRate
        videoRenderer.benchmarkLog = cfg.benchmarkLog
        videoRenderer.scheduledOutputBufferRelease = cfg.scheduledRelease
        NativeBridge.nativeSetH265Enabled(h, h265)
        // No ALAC: this build has no software decoder for it (AIRPLAY_FFMPEG off).
        NativeBridge.nativeSetCodecs(h, false, cfg.aac)
        // No video (HLS) handover until phase 2.
        NativeBridge.nativeSetHlsEnabled(h, false)
        NativeBridge.nativeSetLang(h, languageTags())
        NativeBridge.nativeSetFeature(h, NativeBridge.FT_MIRROR, cfg.advertiseMirroring)
        NativeBridge.nativeSetFeature(h, NativeBridge.FT_AUDIO, cfg.advertiseAudio)
        NativeBridge.nativeSetPlist(h, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(h, "overscanned", if (cfg.overscanned) 1 else 0)
        if (cfg.audioLatencyMs >= 0) NativeBridge.nativeSetPlist(h, "audio_delay_micros", cfg.audioLatencyMs * 1000)

        val (w, ht) = displaySize(cfg)
        videoRenderer.setResolution(w, ht)
        NativeBridge.nativeSetDisplaySize(h, w, ht, maxFps)

        if (fixed != null) {
            val ok = NativeBridge.nativeSetPorts(h, intArrayOf(fixed[0], fixed[1]), intArrayOf(fixed[2], fixed[3], fixed[4]))
            Log.w(TAG, "debug: fixed ports ${fixed.joinToString()} ${if (ok) "set" else "refused"}")
        }
        val requested = fixed?.get(1) ?: cfg.port
        var bound = NativeBridge.nativeStart(h, requested)
        if (bound < 0 && fixed == null && requested != 0) {
            // Another receiver app (or the TV's own) holds the port: take any free one and advertise that.
            Log.w(TAG, "port $requested is taken: using a dynamic port")
            bound = NativeBridge.nativeStart(h, 0)
        }
        if (bound < 0) {
            closeNative()
            return NativeRun(error = AirPlayStatus.ERROR_PORT)
        }

        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(h) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(h) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(h) ?: cfg.name
        val name = NativeBridge.nativeGetServerName(h) ?: cfg.name
        val list = buildList {
            if (cfg.advertiseAudio) add(MdnsService(raopName, "_raop._tcp", bound, raopTxt))
            add(MdnsService(name, "_airplay._tcp", bound, airplayTxt))
        }
        return NativeRun(bound, name, list, h)
    }

    /**
     * Worker thread: stops and destroys the receiver open there, if any (the native stop waits for its threads),
     * then the video renderer, so no late frame restarts a decoder. What its threads report while they end is
     * dropped ([nativeRun]).
     */
    private fun closeNative() {
        val h = workerHandle
        workerHandle = 0L
        nativeRun = -1
        if (h != 0L) {
            audioRenderer.detachEngine()
            NativeBridge.nativeStop(h)
            NativeBridge.nativeDestroy(h)
        }
        videoRenderer.release()
    }

    /** Main thread: the worker's start for run [run] finished. */
    private fun started(run: Int, cfg: AirPlayConfig, fixed: IntArray?, r: NativeRun) {
        // Stopped or restarted meanwhile: that shutdown queued the close behind this start.
        if (run != generation) return
        if (r.error != null) return failStart(r.error)
        nativeHandle = r.handle
        port = r.port
        serverName = r.name
        adverts = r.adverts
        syncClientVol = effectiveSync(config)
        nsd?.register(adverts)

        state = State.RUNNING
        pushStatus()
        updateNotification()
        Log.i(TAG, "receiver started on port ${r.port} as \"${r.name}\"")
        // Settings sent while it started: a restart key (or new debug ports) restarts it now.
        if (AirPlayConfig.restartNeeded(cfg, config) || !(fixed contentEquals debug.ports)) {
            main.removeCallbacks(restartRunnable)
            main.postDelayed(restartRunnable, RESTART_DEBOUNCE_MS)
        } else if (cfg != config) applyConfig(config, forceRestart = false)
    }

    /** A start that failed: everything released, ERROR pushed, then the process exits (the launcher won't retry). */
    private fun failStart(code: String) {
        Log.e(TAG, "receiver did not start: $code")
        shutdownRun()
        state = State.ERROR
        error = code
        pushStatus(force = true)
        scheduleExit()
    }

    /** Ends one receiver run: native stop and destroy, then NSD and the locks, then the renderers and the session. */
    private fun shutdownRun() {
        generation++
        // The debug feed stops without a word: the session it stood for is reset below.
        feed?.cancel()
        feed = null
        // The mirror's decoder goes now, before any status: the launcher opens the scenery's decoder as soon as it
        // hears STOPPED or STARTING, and the native stop below may take a second. Waits for a frame being decoded.
        videoRenderer.stopSession()
        main.removeCallbacks(openCheck)
        userHid = false
        audioRenderer.detachEngine()
        nativeHandle = 0L
        // On the worker, after any start still in progress there.
        nativeWork.post { closeNative() }
        nsd?.release()
        nsd = null
        adverts = emptyList()
        releaseWakeLock()
        liveConnections.set(0)
        dacp?.reset()
        mediaSession?.isActive = false
        coverArt = null
        session.reset()
        clientVol = null
        clientVolDriveEnd()
        port = 0
    }

    private fun applyConfig(cfg: AirPlayConfig, forceRestart: Boolean) {
        val old = config
        config = cfg
        if (state != State.RUNNING) return
        if (forceRestart || AirPlayConfig.restartNeeded(old, cfg)) {
            main.removeCallbacks(restartRunnable)
            main.postDelayed(restartRunnable, RESTART_DEBOUNCE_MS)
        } else {
            // GlassOS: logged, so a settings change that needs no restart can be told from one that does.
            if (cfg != old) Log.i(TAG, "settings applied without a restart")
            audioRenderer.updateConfig(audioConfig(cfg))
            syncClientVol = effectiveSync(cfg)
            clientVolDriveEnd()
            applyClientVol()
        }
    }

    private val restartRunnable = Runnable {
        if (state == State.RUNNING) {
            Log.i(TAG, "restarting the receiver for new settings")
            shutdownRun()
            startServer()
        }
    }

    private val exitRunnable = Runnable {
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            foreground = false
        }
        // The receiver screen, while it shows, keeps the process: only the service stops.
        if (activityVisible) {
            exitPending = false
            Log.i(TAG, "receiver screen shows: the service stops, the process stays")
            stopSelf()
            return@Runnable
        }
        // Once the native stop queued on the worker is done (it waits for the receiver's threads, and a first start
        // may still be writing the key file): the main thread does not wait for it, the process goes after it.
        val token = exitToken
        nativeWork.post { main.post { if (token == exitToken && exitPending) exitProcess() } }
    }

    private fun exitProcess() {
        exitPending = false
        // Frees the native heap and threads. No stopSelf first: the launcher would see the service gone before the
        // process is, and a START it sent then (airplay-stop then airplay-start) would reach this dying process and
        // come back only through the system's crash restart, 1 s or more later. A START_NOT_STICKY service killed
        // with its own process is not restarted.
        Log.i(TAG, "receiver process exits")
        Process.killProcess(Process.myPid())
    }

    /** Gives the final status time to reach the launcher before the process goes. */
    private fun scheduleExit() {
        exitPending = true
        exitToken++
        main.removeCallbacks(exitRunnable)
        main.postDelayed(exitRunnable, EXIT_DELAY_MS)
    }

    private fun cancelExit() {
        exitToken++
        if (!exitPending) return
        exitPending = false
        main.removeCallbacks(exitRunnable)
    }

    private fun nativeReady(): Boolean = try {
        // oboe's OpenSL ES backend (pre-AAudio devices, API < 27) can't discover native
        // rate / burst size itself; feed it AudioManager values so low-latency buffer
        // sizing works there
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )
        Log.i(TAG, "native library loaded from ${applicationInfo.nativeLibraryDir} (${Build.SUPPORTED_ABIS.firstOrNull()})")
        true
    } catch (e: LinkageError) {
        Log.e(TAG, "native library not loaded", e)
        false
    }

    private fun pushStatus(force: Boolean = false) {
        val s = AirPlayStatus(
            state = state, error = error, name = serverName, port = port, connections = session.connections,
            mode = session.mode, pin = session.pin, title = session.track.title, artist = session.track.artist,
            activityVisible = activityVisible, pid = Process.myPid(),
            videoWidth = if (session.mirrorSized) session.videoWidth else 0,
            videoHeight = if (session.mirrorSized) session.videoHeight else 0,
        )
        val changed = s != hub.status
        hub.push(s, force)
        if (changed || force) for (l in listeners.toList()) l.onStatus(s)
    }

    /**
     * Runs [block] on the main thread unless the receiver run that posted it has ended by then. A callback made while
     * its run closes (after a restart started the next one) is dropped too.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        val g = nativeRun
        main.post { if (g == generation) block() }
    }

    private fun debuggable() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun audioConfig(c: AirPlayConfig) = AudioConfig(
        cushionMs = if (c.audioAutoBuffer) 0 else c.audioCushionMs,
        percentilePct = c.audioPercentile,
        oboeBufferFrames = c.oboeBufferFrames,
        // No software decoders in this build (AIRPLAY_FFMPEG off).
        forceSwAlac = false,
        forceSwAac = false,
        realtimePriority = c.realtimePriority,
        lowLatency = c.lowLatency,
        benchmarkLog = c.benchmarkLog,
    )

    /** HDMI devices often have a fixed volume: the sender's volume then stays a gain applied by the receiver. */
    private fun effectiveSync(c: AirPlayConfig) = c.syncVolume && !audioManager.isVolumeFixed

    /**
     * The size advertised to senders: 720p, 1080p, or (auto) the panel's size capped at 1920x1080; never more than
     * the selected decoders take ([clamp]). A TV is landscape, so the orientation is not followed.
     */
    private fun displaySize(cfg: AirPlayConfig, clamp: Boolean = true): Pair<Int, Int> {
        val (w, h) = when (cfg.res) {
            AirPlayConfig.RES_720 -> 1280 to 720
            AirPlayConfig.RES_1080 -> 1920 to 1080
            else -> {
                val (rw, rh) = DisplayInfo.realSize(this)
                val lw = maxOf(rw, rh).coerceAtLeast(2)
                val lh = minOf(rw, rh).coerceAtLeast(2)
                val s = minOf(1.0, 1920.0 / lw, 1080.0 / lh)
                ((lw * s).toInt() and 1.inv()) to ((lh * s).toInt() and 1.inv())
            }
        }
        if (!clamp) return w to h
        // strict decoders black-screen past their limits; advertised size is only upper bound for senders
        val (maxW, maxH) = videoRenderer.maxResolution()
        return w.coerceAtMost(maxW) to h.coerceAtMost(maxH)
    }

    private fun languageTags(): String {
        val c = resources.configuration
        val tags = if (Build.VERSION.SDK_INT >= 24) c.locales.toLanguageTags() else @Suppress("DEPRECATION") c.locale.toLanguageTag()
        return tags.replace(',', ':')
    }

    // ---------- RaopCallbackHandler (native threads) ----------

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) =
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        onMain {
            clearPin()
            if (!usingScreen) {
                setPlaying(true)
                // pure music streaming (not screen mirroring audio)
                setAudioOnly(true)
            }
            pushStatus()
        }
        Log.i(TAG, "audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        if (w > 0 && h > 0) videoRenderer.setResolution(w.toInt(), h.toInt())
        onMain {
            clearPin()
            if (w > 0 && h > 0) {
                session.videoWidth = w.toInt()
                session.videoHeight = h.toInt()
                session.mirrorSized = true
            }
            pushStatus()
        }
        Log.i(TAG, "video size: ${srcW}x$srcH -> ${w}x$h")
    }

    override fun onClientVolume(db: Float) {
        val frac = dbToFrac(db)
        clientVol = frac
        Log.d(TAG, "sender volume ${db}dB, frac $frac")
        onMain {
            val driving = clientVolTarget >= 0f
            if (driving) clientVolStep()
            if (!driving || !syncClientVol) applyClientVol()
        }
    }

    override fun onClientVolumeQuery(): Float {
        val db = fracToDb(clientVolExpected())
        Log.d(TAG, "sender volume query -> ${db}dB")
        return db
    }

    override fun onAudioTeardown() = onMain { setPlaying(false) }

    override fun onConnectionInit() {
        liveConnections.incrementAndGet()
        onMain {
            session.connections++
            if (session.connections == 1) acquireWakeLock()
            pushStatus()
            Log.i(TAG, "sender connected (${session.connections})")
        }
    }

    override fun onConnectionDestroy() {
        if (liveConnections.decrementAndGet() <= 0) {
            liveConnections.set(0)
            // last client gone: release audio output devices to save power
            audioRenderer.stop()
        }
        onMain {
            session.connections = (session.connections - 1).coerceAtLeast(0)
            if (session.connections == 0) sendersGone()
            pushStatus()
            Log.i(TAG, "sender disconnected (${session.connections})")
        }
    }

    override fun onConnectionReset(reason: Int) {
        Log.i(TAG, "connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) = onMain {
        if (session.pin != pin) {
            session.pin = pin
            pushStatus()
            updateNotification()
        }
        // The PIN is only on the TV: the screen comes up for it even after Back hid the session.
        userHid = false
        openScreen(Trigger.PIN)
    }

    override fun onMetadata(data: ByteArray) {
        val info = TrackInfo.fromDmap(DmapParser.parse(data))
        onMain {
            session.track = info
            if (info.durationMs > 0) session.durationMs = info.durationMs
            updateMediaMetadata()
            pushStatus()
        }
        Log.i(TAG, "track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        onMain {
            coverArt = bmp
            updateMediaMetadata()
            notifyCover()
        }
    }

    override fun onProgress(start: Long, curr: Long, end: Long) = onMain {
        if (session.progress(start, curr, end)) updatePlaybackState()
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        onMain { dacp?.update(dacpId, activeRemote) }
        Log.i(TAG, "DACP: $dacpId")
    }

    override fun onMirrorRunning(running: Boolean) {
        // A run being shut down starts no decoder again (shutdownRun has stopped it).
        if (running && nativeRun != generation) return
        if (running) videoRenderer.startSession() else videoRenderer.stopSession()
        // Pushed at once: the launcher stops the scenery's video decoder before the mirror decoder starts.
        onMain {
            session.mirrorRunning = running
            if (!running) session.mirrorSized = false
            setAudioOnly(!running)
            pushStatus()
            Log.i(TAG, if (running) "mirroring starts" else "mirroring ends")
            if (running) openScreen(Trigger.MIRROR_START)
        }
    }

    // Video (HLS) handover is phase 2: it is not advertised, so senders do not use these. When it comes, onVideoPlay
    // opens the receiver screen with Trigger.VIDEO_PLAY and the session reports Mode.VIDEO.
    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        Log.w(TAG, "video handover is not supported in this build")
    }

    override fun onVideoScrub(positionSeconds: Float) {}

    override fun onVideoRate(rate: Float) {}

    override fun onVideoStop() {}

    override fun onVideoSessionPoll() {}

    // LogSink already writes every native line to logcat; there is no log screen to forward it to.
    override fun onLog(msg: String) {}

    // ---------- Session (main thread) ----------

    private fun sendersGone() {
        session.sendersGone()
        userHid = false
        releaseWakeLock()
        coverArt = null
        dacp?.reset()
        clientVol = null
        clientVolDriveEnd()
        if (serverPreZeroIdx >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, serverPreZeroIdx, 0)
            serverPreZeroIdx = -1
        }
        applyClientVol()
        mediaSession?.isActive = false
        updateNotification()
    }

    private fun clearPin() {
        if (session.pin == null) return
        session.pin = null
        updateNotification()
    }

    private fun setAudioOnly(audioOnly: Boolean) {
        val prev = session.audioOnly
        session.audioOnly = audioOnly
        if (audioOnly && !prev) {
            mediaSession?.isActive = true
            Log.i(TAG, "audio mode")
        } else if (!audioOnly && prev) {
            mediaSession?.isActive = false
            coverArt = null
            session.clearTrack()
            updateNotification()
            Log.i(TAG, "mirror mode")
        }
    }

    private fun togglePlayPause() {
        val nowPlaying = !session.playing
        setPlaying(nowPlaying)
        dacp?.let { if (nowPlaying) it.play() else it.pause() }
    }

    private fun setPlaying(playing: Boolean) {
        session.setPlaying(playing)
        updatePlaybackState()
    }

    private fun updateMediaMetadata() {
        val t = session.track
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, session.durationMs)
        coverArt?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(b.build())
        updateNotification()
    }

    private fun updatePlaybackState() {
        val playing = session.playing
        val st = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                session.positionMs, if (playing) 1f else 0f, SystemClock.elapsedRealtime()
            )
            .build()
        mediaSession?.setPlaybackState(st)
        updateNotification()
    }

    // ---------- Volume (jqssun) ----------

    private fun clientVolExpected(): Float = if (syncClientVol) serverVolFrac() else clientVol ?: 1f

    private fun applyClientVol() {
        val frac = clientVol
        if (!syncClientVol) {
            setClientGain(clientGain(frac ?: 1f))
            return
        }
        setClientGain(1f)
        if (frac == null) return
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val idx = serverVolIdx(frac)
        serverPreZeroIdx = if (idx == 0) (if (serverPreZeroIdx < 0) cur else serverPreZeroIdx) else -1
        if (idx != cur) {
            pendingServerVolEchoes++
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        }
    }

    private fun setClientGain(gain: Float) {
        if (gain == audioRenderer.clientGain) return
        audioRenderer.setClientGain(gain)
    }

    private fun serverVolMax() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private fun serverVolIdx(frac: Float) = (frac * serverVolMax()).roundToInt()
    private fun serverVolFrac() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / serverVolMax()

    private fun driveClientVol(target: Float, hint: Int) {
        val active = clientVolTarget >= 0f
        clientVolTarget = target
        clientVolHint = hint
        clientVolDir = 0
        clientVolSteps = 0
        if (!active) clientVolStep()
    }

    private fun clientVolStep() {
        main.removeCallbacks(clientVolTimeout)
        val target = clientVolTarget
        if (target < 0f) return
        val frac = clientVol
        val want = when {
            frac == null -> clientVolHint
            abs(target - frac) <= CLIENT_VOL_EPS -> 0
            target > frac -> 1
            else -> -1
        }
        if (want == 0 || (clientVolDir != 0 && want != clientVolDir) || clientVolSteps >= CLIENT_VOL_MAX_STEPS) {
            clientVolDriveEnd()
            return
        }
        clientVolDir = want
        clientVolSteps++
        if (want > 0) dacp?.volumeUp() else dacp?.volumeDown()
        main.postDelayed(clientVolTimeout, CLIENT_VOL_TIMEOUT_MS)
    }

    private fun clientVolDriveEnd() {
        main.removeCallbacks(clientVolTimeout)
        if (clientVolTarget >= 0f) Log.d(TAG, "sender volume drive done: sender $clientVol, target $clientVolTarget")
        clientVolTarget = -1f
        clientVolDir = 0
    }

    // ---------- Locks ----------

    // Released when the last sender leaves or the receiver stops.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glassos:airplay").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.mirroring_channel), NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun promoteToForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
        foreground = true
    }

    private fun updateNotification() {
        if (!foreground || state != State.RUNNING) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.mirroring_title))
            .setOngoing(true)
            .setShowWhen(false)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            b.setContentIntent(PendingIntent.getActivity(this, 0, it, immutable()))
        }
        val t = session.track
        val pin = session.pin
        val token = mediaSession?.sessionToken
        when {
            // The code only on the TV screen, never in the notification (listeners and dumpsys read it).
            pin != null -> b.setContentText(getString(R.string.mirroring_code_asked))
            session.audioOnly && t.title.isNotEmpty() && token != null -> {
                b.setContentTitle(t.title).setContentText(t.artist).setSubText(t.album)
                coverArt?.let { b.setLargeIcon(it) }
                @Suppress("DEPRECATION")
                b.addAction(android.R.drawable.ic_media_previous, getString(R.string.mirroring_previous), mediaAction(Protocol.ACTION_PREV))
                    .addAction(android.R.drawable.ic_media_pause, getString(R.string.mirroring_play_pause), mediaAction(Protocol.ACTION_PLAY_PAUSE))
                    .addAction(android.R.drawable.ic_media_next, getString(R.string.mirroring_next), mediaAction(Protocol.ACTION_NEXT))
                b.setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
            }
            state == State.RUNNING -> b.setContentText(getString(R.string.mirroring_ready, serverName))
            else -> b.setContentText(getString(R.string.mirroring_starting))
        }
        return b.build()
    }

    private fun mediaAction(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, AirPlayService::class.java).setAction(action),
            immutable() or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun immutable() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    // ---------- Hardware address (jqssun) ----------

    private fun hwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac!!
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }
        // fall back to stable per-install random address
        return persistedRandomMac()
            ?: byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    // GlassOS: in the receiver's own "airplay" prefs, which only the :airplay process writes.
    private fun persistedRandomMac(): ByteArray? {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        macFromString(prefs.getString(FALLBACK_MAC, null))?.takeIf { isUsableMac(it) }?.let { return it }
        repeat(10) {
            val mac = randomAaiMac()
            if (isUsableMac(mac)) {
                prefs.edit().putString(FALLBACK_MAC, macToString(mac)).apply()
                return mac
            }
        }
        return null
    }

    // random locally-administered unicast MAC in AAI SLAP quadrant
    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun macToString(mac: ByteArray): String = mac.joinToString(":") { String.format(Locale.ROOT, "%02x", it) }

    private fun macFromString(s: String?): ByteArray? {
        if (s == null) return null
        return try {
            s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    companion object {
        private const val TAG = "AirPlayService"
        /**
         * Native starts and stops, in order, for every service instance in the process: a receiver stopped while its
         * screen kept the process closes there before the next one opens (port 7000 free again).
         */
        private val nativeWork: Handler by lazy { Handler(HandlerThread("AirPlayNative").apply { start() }.looper) }
        /** Bind with this action from the :airplay process to get a [LocalBinder]. */
        const val ACTION_LOCAL = "io.github.abhishekkaisar.glassos.airplay.LOCAL"
        private const val CHANNEL_ID = "screen_mirroring"
        private const val NOTIFICATION_ID = 7000
        private const val PREFS = "airplay"
        private const val FALLBACK_MAC = "fallback_mac_address"
        private const val RESTART_DEBOUNCE_MS = 500L
        private const val EXIT_DELAY_MS = 300L
        /** How long a started receiver screen may take to report itself visible before it counts as refused. */
        private const val OPEN_CHECK_MS = 2_000L
        private const val CLIENT_VOL_EPS = 0.033f
        private const val CLIENT_VOL_MAX_STEPS = 32
        private const val CLIENT_VOL_TIMEOUT_MS = 800L

        // airplay volume is linear in dB along sender slider
        private fun dbToFrac(db: Float) = if (db <= -144f) 0f else ((db + 30f) / 30f).coerceIn(0f, 1f)
        private fun fracToDb(frac: Float) = if (frac <= 0f) -144f else -30f + 30f * frac
        private fun clientGain(frac: Float) = if (frac <= 0f) 0f else 10f.pow(fracToDb(frac) / 20f)
    }
}
