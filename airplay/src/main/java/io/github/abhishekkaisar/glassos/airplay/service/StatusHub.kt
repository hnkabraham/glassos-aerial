package io.github.abhishekkaisar.glassos.airplay.service

import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.Protocol

/**
 * The receiver's end of the launcher protocol ([Protocol]): the Messenger the service returns from onBind, the
 * registered clients, and the status pushed to them when one registers and on every change. Main thread only; the
 * service is not exported, so only GlassOS's own processes reach it.
 */
internal class StatusHub(private val commands: Commands) {
    interface Commands {
        fun onApply(config: AirPlayConfig)
        fun onStop()
        fun onDisconnect()
        fun onShow()
        /** Debug builds: what is registered over DNS-SD, or null. */
        fun adverts(): Bundle?
        /** Debug builds: a [Protocol.MSG_DEBUG] request; ignored otherwise. */
        fun onDebug(request: Bundle)
    }

    private val clients = ArrayList<Messenger>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> receive(msg); true })

    var status = AirPlayStatus()
        private set

    val binder: IBinder get() = messenger.binder

    /** Sends [s] to every client when it differs from the last one (or always, with [force]). */
    fun push(s: AirPlayStatus, force: Boolean = false) {
        if (s == status && !force) return
        status = s
        for (c in clients.toList()) send(c, Protocol.MSG_STATUS, Protocol.statusToBundle(s))
    }

    private fun receive(msg: Message) {
        when (msg.what) {
            Protocol.MSG_REGISTER -> msg.replyTo?.let { c ->
                if (c !in clients) clients += c
                send(c, Protocol.MSG_STATUS, Protocol.statusToBundle(status))
            }
            Protocol.MSG_UNREGISTER -> msg.replyTo?.let { clients -= it }
            Protocol.MSG_APPLY -> commands.onApply(Protocol.configFromBundle(msg.data))
            Protocol.MSG_STOP -> commands.onStop()
            Protocol.MSG_DISCONNECT -> commands.onDisconnect()
            Protocol.MSG_SHOW -> commands.onShow()
            Protocol.MSG_ADVERTS -> msg.replyTo?.let { send(it, Protocol.MSG_ADVERTS, commands.adverts() ?: Bundle()) }
            Protocol.MSG_DEBUG -> commands.onDebug(msg.data)
        }
    }

    private fun send(to: Messenger, what: Int, data: Bundle) {
        val m = Message.obtain(null, what)
        m.data = data
        try {
            to.send(m)
        } catch (e: DeadObjectException) {
            clients -= to
        } catch (e: RemoteException) {
            Log.w(TAG, "status not delivered", e)
        }
    }

    private companion object {
        const val TAG = "AirPlayStatusHub"
    }
}
