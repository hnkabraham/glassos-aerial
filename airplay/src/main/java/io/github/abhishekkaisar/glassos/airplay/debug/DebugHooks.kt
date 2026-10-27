package io.github.abhishekkaisar.glassos.airplay.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks for debug builds only (FLAG_DEBUGGABLE), run from GlassOS Manager's `airplay-debug`. The Mac cannot see the
 * emulator's mDNS (its NAT keeps multicast in), so the emulator checks its own adverts from the main process.
 */
object DebugHooks {
    fun enabled(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private var strict = false

    /**
     * Debug builds: StrictMode for the :airplay process (its main thread and the VM), logging every violation under
     * the StrictMode tag. Called by the service and the receiver screen, whichever starts first; main thread.
     */
    fun strictMode(ctx: Context) {
        if (strict || !enabled(ctx)) return
        strict = true
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }

    /** One resolved service: its instance name, type, address, port and TXT record. */
    class Found(val type: String, val name: String, val host: String, val port: Int, val txt: Map<String, String>)

    /**
     * Browses [types] for [browseMs], then resolves every service found, one at a time (NsdManager before API 34
     * resolves one at a time). Blocks a background thread; never the main thread.
     */
    fun discover(ctx: Context, types: List<String>, browseMs: Long, resolveMs: Long = 3000): List<Found> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "discover on the main thread" }
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val seen = LinkedHashMap<String, NsdServiceInfo>()
        val browsers = types.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "browse $serviceType failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    val key = type + "/" + info.serviceName
                    synchronized(seen) { if (key !in seen) seen[key] = info }
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
            }.also { l ->
                try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: RuntimeException) {
                    Log.w(TAG, "browse $type not started", e)
                }
            }
        }
        try { Thread.sleep(browseMs) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        for (l in browsers) try { nsd.stopServiceDiscovery(l) } catch (e: RuntimeException) { }

        val found = ArrayList<Found>()
        val candidates = synchronized(seen) { seen.entries.toList() }
        for ((key, info) in candidates) {
            val type = key.substringBefore('/')
            val latch = CountDownLatch(1)
            var resolved: NsdServiceInfo? = null
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve ${si.serviceName} failed: $errorCode")
                        latch.countDown()
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        resolved = si
                        latch.countDown()
                    }
                })
                latch.await(resolveMs, TimeUnit.MILLISECONDS)
            } catch (e: RuntimeException) {
                Log.w(TAG, "resolve ${info.serviceName} not started", e)
            }
            val r = resolved ?: continue
            val txt = LinkedHashMap<String, String>()
            for ((k, v) in r.attributes) txt[k] = v?.toString(Charsets.UTF_8) ?: ""
            @Suppress("DEPRECATION")
            val host = r.host?.hostAddress ?: ""
            found += Found(type, r.serviceName, host, r.port, txt)
        }
        return found
    }

    private const val TAG = "AirPlayDebug"
}
