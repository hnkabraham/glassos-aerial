/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    /** GlassOS: the names the system registered, by service type (it renames on a conflict). */
    val registered: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun register(services: List<MdnsService>) = services.forEach(::_register)

    private fun _register(s: MdnsService) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registered[s.type] = info.serviceName
                Log.i(TAG, "${s.type} registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registered.remove(s.type)
                Log.i(TAG, "${s.type} unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} unregister failed: $code")
            }
        }
        // GlassOS: a bad name or TXT record fails this service only, not the receiver.
        try {
            val info = NsdServiceInfo().apply {
                // One DNS label: the RAOP name ("<MAC>@<name>") can exceed it where the name alone does not.
                serviceName = AirPlayConfig.truncateUtf8(s.name, AirPlayConfig.MAX_NAME_BYTES)
                serviceType = s.type
                port = s.port
                s.txt.forEach { (k, v) -> setAttribute(k, v) }
            }
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrations += listener
        } catch (e: RuntimeException) {
            Log.e(TAG, "${s.type} not registered", e)
        }
    }

    fun release() {
        registrations.forEach {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrations.clear()
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
    }
}
