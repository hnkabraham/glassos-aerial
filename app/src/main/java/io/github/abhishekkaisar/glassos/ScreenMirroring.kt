package io.github.abhishekkaisar.glassos

import android.content.Context
import io.github.abhishekkaisar.glassos.airplay.AirPlay
import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig

/**
 * Screen Mirroring on the launcher's side, past the facade ([AirPlay]): turning it on and off, its settings, starting
 * the receiver when the launcher comes up (boot, update, the home resuming) and following the receiver's status
 * pushes. While the receiver decodes a mirrored screen or a video it holds the scenery decoder ([SceneSession.hold]).
 * The receiver pushes that mode as soon as a sender sets mirroring up, so the aerial player closes before the mirror's
 * first frame and stays closed while the session lasts, its screen hidden or not, screensaver included; scenery plays
 * again when the session ends or the receiver goes. Music alone leaves the scenery playing. Main thread.
 */
object ScreenMirroring {
    private var watching = false
    private var holding = false

    /** Screen Mirroring can run here: Fire OS 6 (Android 7.1) or later, in a build with the receiver. */
    fun available(ctx: Context) = AirPlay.isSupported(ctx)

    /**
     * Follows the receiver from now on (once per process, from each home and the screensaver) and binds to a receiver
     * that may already run (one started before this process was). Never starts one; one found running while Screen
     * Mirroring is off is stopped (the supervisor does that when it hears from it).
     */
    fun watch(ctx: Context, prefs: Prefs) {
        if (!available(ctx)) return
        listen()
        AirPlay.attach(ctx, prefs.airplayOn, prefs.airplayConfig)
    }

    /**
     * Starts the receiver when Screen Mirroring is on and this process knows of none running or starting: after a
     * boot or an update ([why] says which), and when the home resumes (a launcher process that started after the
     * receiver died). Once per process in practice: the start makes it known. A receiver that failed or kept crashing
     * is left alone until the user turns Screen Mirroring off and on.
     */
    fun sync(ctx: Context, prefs: Prefs, why: String) {
        if (!available(ctx)) return
        listen()
        val s = AirPlay.snapshot
        if (!MirrorState.shouldStart(prefs.airplayOn, s.connected, s.status)) return
        WLog.i("screen mirroring: starting the receiver ($why)")
        AirPlay.start(ctx, prefs.airplayConfig)
    }

    /** Settings or Control Center turned Screen Mirroring on or off ([from] says where). Never waits for the receiver. */
    fun turn(ctx: Context, prefs: Prefs, on: Boolean, from: String) {
        prefs.airplayOn = on
        WLog.i("screen mirroring: turned ${if (on) "on" else "off"} in $from")
        if (on) AirPlay.start(ctx, prefs.airplayConfig) else AirPlay.stop(ctx)
    }

    /** One setting from Settings; a running receiver gets it at once and restarts only for a restart key. */
    fun <T : Any> set(ctx: Context, prefs: Prefs, key: AirPlayConfig.Key<T>, value: T?) {
        prefs.setAirplay(key, value)
        WLog.i("screen mirroring: ${key.name} changed in Settings" + if (key.restart) " (the receiver restarts)" else "")
        if (prefs.airplayOn) AirPlay.apply(ctx, prefs.airplayConfig)
    }

    /** A restored backup set Screen Mirroring's settings: the receiver follows them (started, restarted or stopped). */
    fun restored(ctx: Context, prefs: Prefs) {
        if (!available(ctx)) return
        WLog.i("screen mirroring: settings restored from a backup (${if (prefs.airplayOn) "on" else "off"})")
        if (prefs.airplayOn) AirPlay.start(ctx, prefs.airplayConfig) else AirPlay.stop(ctx)
    }

    /** Brings the receiver screen to the front (a session hidden by Back, or one that started over another app). */
    fun show(ctx: Context) {
        WLog.i("screen mirroring: receiver screen requested")
        AirPlay.show(ctx)
    }

    private fun listen() {
        if (watching) return
        watching = true
        AirPlay.addListener(listener)
    }

    private val listener = AirPlay.Listener { s ->
        // A receiver whose binding dropped (it died, or exits) decodes nothing any more.
        val busy = s.connected && s.status.decodesVideo
        if (busy == holding) return@Listener
        holding = busy
        if (busy) {
            WLog.i("screen mirroring: ${s.status.mode.name.lowercase()} session, scenery stops")
            SceneSession.hold(this)
        } else {
            SceneSession.yield(this)
            WLog.i("screen mirroring: session over, scenery may play")
        }
    }
}
