package io.github.abhishekkaisar.glassos.airplay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screen Mirroring as the launcher sees it, in the main process: start, apply, stop, show and disconnect the
 * receiver that runs in the :airplay process, and its status. Never loads the native library: everything native
 * happens in :airplay. Callable from any thread; work and listeners run on the main thread.
 */
object AirPlay {
    /** Fire OS 6 (Android 7.1) and later; Fire OS 5 is untested, so the feature stays hidden there. */
    const val MIN_SDK = 25
    private const val LIBRARY = "libairplay_native.so"

    /** What the launcher knows: the receiver's last status plus the supervisor's view of it. */
    class Snapshot(
        val status: AirPlayStatus,
        /** A binding to a running receiver is up. */
        val connected: Boolean,
        /** Started and not stopped since. */
        val wanted: Boolean,
        /** Restarts after crashes since the last start. */
        val restarts: Int,
        /** It kept crashing and is not restarted again (status is ERROR "crashed"). */
        val gaveUp: Boolean,
    )

    fun interface Listener {
        fun onAirPlay(s: Snapshot)
    }

    private val main = Handler(Looper.getMainLooper())
    // Holds the application context only, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    private var supervisor: Supervisor? = null
    private val listeners = LinkedHashSet<Listener>()

    @Volatile
    var snapshot = Snapshot(AirPlayStatus(), connected = false, wanted = false, restarts = 0, gaveUp = false)
        private set

    /**
     * Whether this TV and this build can run the receiver: API [MIN_SDK]+ and the native library installed (a
     * `-Pglassos.airplay.native=false` build has none). A file check, never a dlopen.
     */
    fun isSupported(ctx: Context): Boolean = Build.VERSION.SDK_INT >= MIN_SDK && isBundled(ctx)

    /** This build carries the receiver's native code (its licences then apply), on any Android version. */
    fun isBundled(ctx: Context): Boolean {
        val dir = ctx.applicationInfo.nativeLibraryDir ?: return false
        return File(dir, LIBRARY).isFile
    }

    /** The TV's own name (Settings > Device name on API 25+), the receiver's name unless the user set one. */
    fun defaultName(ctx: Context): String {
        val named = if (Build.VERSION.SDK_INT >= 25) {
            try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (e: Exception) { null }
        } else null
        return named?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: AirPlayConfig.FALLBACK_NAME
    }

    /** Starts the receiver with [config] (or applies it to the running one), and keeps it running until [stop]. */
    fun start(ctx: Context, config: AirPlayConfig, debug: DebugOptions = DebugOptions.NONE) {
        val cfg = config.normalise(defaultName(ctx))
        onMain(ctx) { it.start(cfg, debug) }
    }

    /** Sends [config] to the running receiver; it restarts only if a restart key changed. */
    fun apply(ctx: Context, config: AirPlayConfig) {
        val cfg = config.normalise(defaultName(ctx))
        onMain(ctx) { it.apply(cfg) }
    }

    fun stop(ctx: Context) = onMain(ctx) { it.stop() }

    /**
     * Tells the launcher's side whether Screen Mirroring is on (airplay_on) and with which settings, without starting
     * or stopping the receiver: one already running is then watched, restarted after a crash and sent [config].
     * For when this process started after the receiver did.
     */
    fun attach(ctx: Context, on: Boolean, config: AirPlayConfig) {
        val cfg = config.normalise(defaultName(ctx))
        onMain(ctx) { it.attach(on, cfg) }
    }

    /** Drops the connected senders; the receiver keeps running. */
    fun disconnect(ctx: Context) = onMain(ctx) { it.disconnect() }

    /** Brings the receiver screen to the front. */
    fun show(ctx: Context) = onMain(ctx) { it.show() }

    /**
     * Debug builds: sends [request] (a [Protocol.MSG_DEBUG] Bundle) to the running receiver, which ignores it in a
     * release build. False when no receiver is connected.
     */
    fun debug(ctx: Context, request: Bundle): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "AirPlay.debug on the main thread" }
        val latch = CountDownLatch(1)
        var sent = false
        main.post {
            sent = supervisor(ctx).debug(request)
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        return sent
    }

    /** Main thread. [l] gets every change, starting with the current snapshot. */
    fun addListener(l: Listener) {
        listeners += l
        l.onAirPlay(snapshot)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /**
     * Blocks a background thread (never the main thread) until [done] holds or [timeoutMs] passes, after binding to a
     * receiver that may already run (which never starts one). Returns the snapshot at that point.
     */
    fun await(ctx: Context, timeoutMs: Long, done: (Snapshot) -> Boolean): Snapshot {
        check(Looper.myLooper() != Looper.getMainLooper()) { "AirPlay.await on the main thread" }
        val latch = CountDownLatch(1)
        val l = Listener { s -> if (done(s)) latch.countDown() }
        main.post {
            supervisor(ctx).refresh()
            addListener(l)
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        main.post { removeListener(l) }
        return snapshot
    }

    /**
     * Debug builds, background thread: what the running receiver registered over DNS-SD (name, type, port and TXT
     * record of each service), or null when none answers within [timeoutMs].
     */
    fun adverts(ctx: Context, timeoutMs: Long): Bundle? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "AirPlay.adverts on the main thread" }
        val latch = CountDownLatch(1)
        var result: Bundle? = null
        main.post {
            supervisor(ctx).adverts { b ->
                result = b
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    private fun onMain(ctx: Context, block: (Supervisor) -> Unit) {
        val app = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) block(supervisor(app)) else main.post { block(supervisor(app)) }
    }

    private fun supervisor(ctx: Context): Supervisor =
        supervisor ?: Supervisor(ctx.applicationContext) { changed() }.also { supervisor = it }

    private fun changed() {
        val s = supervisor ?: return
        snapshot = Snapshot(s.status, s.connected, s.wanted, s.restarts, s.gaveUp)
        for (l in listeners.toList()) l.onAirPlay(snapshot)
    }
}
