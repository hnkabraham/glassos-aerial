/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Sends DACP (Digital Audio Control Protocol) commands back to the AirPlay sender.
 * Resolves the sender's control port via mDNS, then sends HTTP GET requests.
 *
 * GlassOS: fire-and-forget (Guava's futures had no reader once DacpPlayer was dropped), and the GET is written on a
 * plain socket: GlassOS targets API 28, where the platform's HTTP stack refuses cleartext, and this one LAN request
 * should not open cleartext for the whole app.
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile var dacpId = ""
    @Volatile var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0

    val ready: Boolean get() = host.isNotEmpty() && activeRemote.isNotEmpty()

    fun update(dacpId: String, activeRemote: String) {
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        _resolve()
    }

    fun play() = _send("/ctrl-int/1/play")
    fun pause() = _send("/ctrl-int/1/pause")
    fun nextItem() = _send("/ctrl-int/1/nextitem")
    fun prevItem() = _send("/ctrl-int/1/previtem")
    fun volumeUp() = _send("/ctrl-int/1/volumeup")
    fun volumeDown() = _send("/ctrl-int/1/volumedown")

    fun reset() {
        dacpId = ""
        activeRemote = ""
        host = ""
        port = 0
    }

    fun release() {
        reset()
        exec.shutdownNow()
    }

    private fun _resolve() {
        if (dacpId.isEmpty()) return
        val serviceName = "iTunes_Ctrl_$dacpId"
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            this.serviceName = serviceName
        }
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "DACP resolve failed: $code")
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    host = si.host?.hostAddress ?: return
                    port = si.port
                    Log.i(TAG, "DACP resolved: $host:$port")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
        }
    }

    private fun _send(path: String) {
        if (!ready) return
        val h = host
        val p = port
        val remote = activeRemote
        try {
            exec.execute {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(h, p), TIMEOUT_MS)
                        s.soTimeout = TIMEOUT_MS
                        val hostHeader = if (':' in h) "[$h]:$p" else "$h:$p"
                        val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nActive-Remote: $remote\r\n" +
                            "Connection: close\r\n\r\n"
                        s.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
                        val status = s.getInputStream().bufferedReader(Charsets.US_ASCII).readLine() ?: ""
                        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                        if (code !in 200..299) Log.w(TAG, "DACP $path -> $status")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DACP send failed: $path", e)
                }
            }
        } catch (e: Exception) {
            // RejectedExecutionException after release()
            Log.w(TAG, "DACP send dropped: $path", e)
        }
    }

    companion object {
        private const val TAG = "DacpController"
        private const val TIMEOUT_MS = 2000
    }
}
