package io.github.abhishekkaisar.glassos.airplay.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.debug.DebugHooks
import io.github.abhishekkaisar.glassos.airplay.service.AirPlayService

/**
 * The receiver screen, in the :airplay process next to the receiver: the mirrored picture, a PIN, the track playing
 * or the receiver's state ([ReceiverView]). Only the service starts it ([ReceiverScreen.opens]), and it follows the
 * service's status through the in-process binding ([AirPlayService.ACTION_LOCAL]; flags 0, so it never keeps the
 * receiver alive by itself). Back hides it while the session goes on (a mirrored screen keeps decoding, headless) and
 * SHOW brings it back; Disconnect drops the sender; it closes by itself when the session ends or the receiver stops.
 * Keeps the TV awake while a sender is there.
 */
class AirPlayActivity : Activity(), AirPlayService.Listener, ReceiverView.Host {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var view: ReceiverView
    private var service: AirPlayService? = null
    private var bound = false
    private var started = false
    private var surface: Surface? = null
    private var last: AirPlayStatus? = null
    private var keepOn = false
    /** What the receiver opened the screen for ([ReceiverScreen.Trigger]). */
    private var trigger = ReceiverScreen.Trigger.SHOW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugHooks.strictMode(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        trigger = triggerOf(intent)
        view = ReceiverView(this, this)
        setContentView(view)
        bound = bindService(Intent(this, AirPlayService::class.java).setAction(AirPlayService.ACTION_LOCAL), conn, 0)
        // Started by the receiver, so it runs; if it stopped meanwhile nothing would ever connect.
        main.postDelayed(noReceiver, NO_RECEIVER_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        trigger = triggerOf(intent)
    }

    private fun triggerOf(i: Intent?) =
        ReceiverScreen.Trigger.values().firstOrNull { it.name == i?.getStringExtra(EXTRA_TRIGGER) } ?: ReceiverScreen.Trigger.SHOW

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            main.removeCallbacks(noReceiver)
            service = svc
            surface?.let { svc.setVideoSurface(it) }
            if (started) svc.setActivityVisible(true)
            svc.addListener(this@AirPlayActivity)
        }

        // The receiver stopped or its process is going: nothing left to show.
        override fun onServiceDisconnected(name: ComponentName?) = gone()

        override fun onBindingDied(name: ComponentName?) = gone()
    }

    private fun gone() {
        service?.removeListener(this)
        service = null
        if (!isFinishing) finish()
    }

    private val noReceiver = Runnable {
        if (service == null) {
            Log.w(TAG, "no receiver to show")
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        service?.setActivityVisible(true)
    }

    override fun onStop() {
        super.onStop()
        started = false
        service?.setActivityVisible(false)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        service?.let { svc ->
            svc.removeListener(this)
            surface?.let { svc.clearVideoSurface(it) }
        }
        service = null
        if (bound) {
            bound = false
            try { unbindService(conn) } catch (e: IllegalArgumentException) { }
        }
        super.onDestroy()
    }

    override fun onStatus(s: AirPlayStatus) {
        view.show(s, service?.coverArt)
        val keep = ReceiverScreen.keepsScreenOn(s)
        if (keep != keepOn) {
            keepOn = keep
            if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val before = last
        last = s
        val ended = if (before == null) ReceiverScreen.stale(trigger, s) else ReceiverScreen.ended(before, s)
        if (ended && !isFinishing) {
            // "Screen Mirroring is off" stays a moment; a session that ended returns to what was there at once.
            main.removeCallbacks(close)
            if (ReceiverScreen.content(s) == ReceiverScreen.Content.OFF) main.postDelayed(close, OFF_MS) else close.run()
        }
    }

    private val close = Runnable { if (!isFinishing) finish() }

    // ---------- ReceiverView.Host ----------

    override fun surfaceReady(surface: Surface) {
        this.surface = surface
        service?.setVideoSurface(surface)
    }

    override fun surfaceGone(surface: Surface) {
        service?.clearVideoSurface(surface)
        if (this.surface === surface) this.surface = null
    }

    override fun disconnect() {
        service?.onDisconnect()
    }

    // ---------- Keys ----------

    /** Back hides the screen; a mirrored screen goes on decoding without it, and SHOW brings it back. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        service?.hide()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_STOP -> { disconnect(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                // Over a mirrored picture the first key brings up the bar (and does nothing else).
                if (view.showControls()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Its press disconnected: the release stays here too (unhandled, Android passes it on to a media app's session).
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_STOP || super.onKeyUp(keyCode, event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) view.touchControls()
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** The [ReceiverScreen.Trigger] the receiver opened the screen for. */
        const val EXTRA_TRIGGER = "trigger"
        private const val TAG = "AirPlayActivity"
        private const val NO_RECEIVER_MS = 3_000L
        private const val OFF_MS = 1_500L
    }
}
