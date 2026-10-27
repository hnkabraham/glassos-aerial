package io.github.abhishekkaisar.glassos.airplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.State
import io.github.abhishekkaisar.glassos.airplay.service.AirPlayService

/**
 * The launcher's side of the receiver, in the main process: starts the service in :airplay, keeps a flags=0 binding
 * to it (which never keeps it alive by itself), relays commands and status, and restarts it when it dies without
 * having said STOPPED or ERROR first ([SupervisorBackoff]: 2 s, 10 s, 60 s, then gives up). Main thread only.
 */
internal class Supervisor(private val app: Context, private val onChange: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val backoff = SupervisorBackoff()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg -> receive(msg); true })
    private var service: Messenger? = null
    private var bindRequested = false
    private var config: AirPlayConfig? = null
    private var debug = DebugOptions.NONE
    /** STOP was sent: the next loss of the binding is intentional. */
    private var stopping = false
    /** STOP was asked for before a binding was up: send it once one is. */
    private var pendingStop = false
    /** STOP went to the bound receiver, which has not said STOPPED (or ERROR) yet. */
    private var awaitingStop = false
    /** A start that followed a stop at once (a quick off and on): START goes once the receiver has said STOPPED. */
    private var startAfterStop = false
    /** START went to a receiver that had stopped and is about to exit: if it exits first, a new one gets START. */
    private var relaunching = false
    private val advertReplies = ArrayList<(Bundle?) -> Unit>()

    /** The receiver should run: started and not stopped since (it follows the launcher's airplay_on). */
    var wanted = false
        private set
    /** The receiver's last status, or what the supervisor knows (STARTING while it starts or restarts). */
    var status = AirPlayStatus()
        private set
    /** Restarts after unexpected deaths since the last start by the user. */
    var restarts = 0
        private set
    val connected: Boolean get() = service != null
    val gaveUp: Boolean get() = backoff.gaveUp

    fun start(cfg: AirPlayConfig, dbg: DebugOptions) {
        wanted = true
        pendingStop = false
        config = cfg
        debug = dbg
        main.removeCallbacks(restart)
        main.removeCallbacks(lostCheck)
        backoff.reset()
        restarts = 0
        if (awaitingStop && service != null) {
            // STOP went over the binding and START would go through the system, so either could reach the receiver
            // first. START waits for its STOPPED; the receiver then cancels its exit and starts again in its process.
            if (!startAfterStop) Log.i(TAG, "start waits for the receiver to finish stopping")
            startAfterStop = true
            status = AirPlayStatus(state = State.STARTING)
            onChange()
            return
        }
        stopping = false
        launch()
    }

    fun apply(cfg: AirPlayConfig) {
        config = cfg
        if (wanted) send(Protocol.MSG_APPLY, Protocol.configToBundle(cfg))
    }

    /**
     * Follows the launcher's airplay_on without starting or stopping anything: a receiver found running while [on]
     * is watched (and restarted after a crash) as if this process had started it. For a process that started after
     * the receiver did.
     */
    fun attach(on: Boolean, cfg: AirPlayConfig) {
        config = cfg
        if (on != wanted) {
            wanted = on
            if (!on) main.removeCallbacks(restart)
            onChange()
        }
        bind()
    }

    fun stop() {
        wanted = false
        startAfterStop = false
        relaunching = false
        main.removeCallbacks(restart)
        backoff.reset()
        if (service != null) {
            stopping = true
            awaitingStop = send(Protocol.MSG_STOP)
            onChange()
        } else {
            // A receiver may still run (this process restarted since it was started): a flags=0 binding connects
            // only to one that does, and STOP goes out then.
            pendingStop = true
            bind()
            if (status.state != State.ERROR) status = AirPlayStatus()
            onChange()
        }
    }

    fun disconnect() = send(Protocol.MSG_DISCONNECT)

    fun show() = send(Protocol.MSG_SHOW)

    /** Debug builds: a [Protocol.MSG_DEBUG] request for the running receiver; false when none is connected. */
    fun debug(request: Bundle): Boolean = send(Protocol.MSG_DEBUG, request)

    /** Binds (flags=0) to a receiver that may already run, to learn its status. Never starts one. */
    fun refresh() = bind()

    /** Debug builds: asks the receiver what it advertises; [reply] gets null when no receiver is connected. */
    fun adverts(reply: (Bundle?) -> Unit) {
        if (service == null) return reply(null)
        advertReplies += reply
        if (!send(Protocol.MSG_ADVERTS)) {
            advertReplies -= reply
            reply(null)
        }
    }

    private fun launch() {
        val cfg = config ?: return
        val i = Intent(app, AirPlayService::class.java)
            .setAction(Protocol.ACTION_START)
            .putExtra(Protocol.EXTRA_CONFIG, Protocol.configToBundle(cfg))
            .putExtra(Protocol.EXTRA_DEBUG, debug.toBundle())
        // A forced init failure is for one run only; fixed ports stay for restarts.
        debug = DebugOptions(false, debug.ports)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: RuntimeException) {
            // IllegalStateException (background start refused) or SecurityException.
            Log.w(TAG, "could not start the receiver", e)
            status = AirPlayStatus(state = State.ERROR, error = AirPlayStatus.ERROR_START)
            onChange()
            return
        }
        // Stopped (or failed) and exiting: it cancels its exit when START arrives, unless its process goes first.
        if (service != null && (status.state == State.STOPPED || status.state == State.ERROR)) relaunching = true
        // Not running yet, or stopped: it reports STARTING itself soon.
        if (service == null || status.state != State.RUNNING) status = AirPlayStatus(state = State.STARTING)
        bind()
        onChange()
    }

    private fun bind() {
        if (bindRequested) return
        bindRequested = try {
            app.bindService(Intent(app, AirPlayService::class.java), conn, 0)
        } catch (e: SecurityException) {
            false
        }
    }

    private fun unbind() {
        service = null
        if (!bindRequested) return
        bindRequested = false
        try { app.unbindService(conn) } catch (e: IllegalArgumentException) { }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return
            main.removeCallbacks(lostCheck)
            service = Messenger(binder)
            backoff.up(SystemClock.elapsedRealtime())
            send(Protocol.MSG_REGISTER)
            val cfg = config
            if (pendingStop && !wanted) {
                pendingStop = false
                stopping = true
                awaitingStop = send(Protocol.MSG_STOP)
            } else if (wanted && cfg != null) {
                // Settings saved while no binding was up reach the receiver now; unchanged ones restart nothing.
                send(Protocol.MSG_APPLY, Protocol.configToBundle(cfg))
            }
            onChange()
        }

        // The receiver's process died, or the service was stopped.
        override fun onServiceDisconnected(name: ComponentName?) = lost()

        // API 26+: also after onServiceDisconnected when the service was stopped; the binding is dead.
        override fun onBindingDied(name: ComponentName?) = lost()
    }

    private fun lost() {
        if (!bindRequested) return
        // A dead binding never connects again. While the receiver is wanted, a fresh flags=0 binding (which never
        // starts it) picks up the next instance: a START sent just before this one exited, or a restart.
        unbind()
        if (wanted) bind()
        // The process is gone: a final STOPPED or ERROR stays, without the dead pid or its senders.
        if (status.state == State.STOPPED || status.state == State.ERROR) status = status.copy(connections = 0, pid = 0)
        for (r in advertReplies) r(null)
        advertReplies.clear()
        awaitingStop = false
        main.removeCallbacks(lostCheck)
        if (startAfterStop || relaunching) {
            // The stopped receiver's process exited before it took the START meant for it: a new one gets it.
            Log.i(TAG, "the stopped receiver exited first: starting a new one")
            startAfterStop = false
            relaunching = false
            stopping = false
            launch()
            return
        }
        // The receiver's last status (STOPPED or ERROR when it exits on purpose) may still be on its way: decide once
        // it had time to arrive.
        main.postDelayed(lostCheck, GRACE_MS)
        onChange()
    }

    private val lostCheck = Runnable {
        val intentional = stopping || !wanted || status.state == State.STOPPED || status.state == State.ERROR
        stopping = false
        if (intentional) {
            status = if (status.state == State.ERROR) status.copy(connections = 0, pid = 0) else AirPlayStatus()
            onChange()
            return@Runnable
        }
        val delay = backoff.died(SystemClock.elapsedRealtime())
        if (delay == null) {
            Log.w(TAG, "receiver died ${backoff.deaths + 1} times: not restarting it")
            status = AirPlayStatus(state = State.ERROR, error = AirPlayStatus.ERROR_CRASHED)
        } else {
            restarts++
            Log.w(TAG, "receiver died unexpectedly: restarting it in $delay ms")
            status = AirPlayStatus(state = State.STARTING)
            main.postDelayed(restart, delay)
        }
        onChange()
    }

    private val restart = Runnable { if (wanted) launch() }

    private fun receive(msg: Message) {
        when (msg.what) {
            Protocol.MSG_STATUS -> {
                val s = Protocol.statusFromBundle(msg.data)
                if (s.state == State.STOPPED || s.state == State.ERROR) awaitingStop = false
                // It took the START: an exit it had planned is cancelled.
                else relaunching = false
                if (startAfterStop && !awaitingStop) {
                    // It has stopped: now START reaches it after the STOP.
                    Log.i(TAG, "the receiver stopped: starting it again")
                    startAfterStop = false
                    stopping = false
                    status = s
                    launch()
                    return
                }
                // While a START waits for the STOP to finish, the launcher shows it starting.
                status = if (startAfterStop) AirPlayStatus(state = State.STARTING) else s
                if (strayRun(wanted, startAfterStop || awaitingStop, s.state)) {
                    // Running while Screen Mirroring is off (a START that overtook a STOP, or a receiver this process
                    // attached to with airplay_on false): it would listen on the network where every screen says
                    // Off. Once per such status: awaitingStop holds until it says STOPPED.
                    Log.w(TAG, "the receiver runs while Screen Mirroring is off: stopping it")
                    stopping = true
                    awaitingStop = send(Protocol.MSG_STOP)
                }
                onChange()
            }
            Protocol.MSG_ADVERTS -> {
                val replies = advertReplies.toList()
                advertReplies.clear()
                for (r in replies) r(msg.data)
            }
        }
    }

    private fun send(what: Int, data: Bundle? = null): Boolean {
        val s = service ?: return false
        val m = Message.obtain(null, what)
        m.replyTo = incoming
        if (data != null) m.data = data
        return try {
            s.send(m)
            true
        } catch (e: RemoteException) {
            false
        }
    }

    companion object {
        private const val TAG = "AirPlaySupervisor"
        /** How long a lost binding waits for the receiver's final STOPPED or ERROR status. */
        private const val GRACE_MS = 500L
    }
}

/**
 * A receiver reports itself starting or running while it is not [wanted] and no stop or restart is under way
 * ([pending]): it has to be stopped. Pure, for the JVM tests.
 */
internal fun strayRun(wanted: Boolean, pending: Boolean, state: State): Boolean =
    !wanted && !pending && (state == State.STARTING || state == State.RUNNING)
