package io.github.abhishekkaisar.glassos.airplay

import android.os.Bundle

/**
 * The launcher <-> receiver protocol. START is an explicit Intent (startForegroundService on API 26+); everything
 * else goes over Messengers on a flags=0 binding, so it works on API 22 without AIDL. The service is not exported:
 * only GlassOS's own processes can bind it.
 */
object Protocol {
    private const val P = "io.github.abhishekkaisar.glassos.airplay."

    /** Start, or apply [EXTRA_CONFIG] to, the running receiver. */
    const val ACTION_START = P + "START"
    /** Fallback stop when no binding is up yet. */
    const val ACTION_STOP = P + "STOP"
    // Notification transport buttons.
    const val ACTION_PLAY_PAUSE = P + "PLAY_PAUSE"
    const val ACTION_NEXT = P + "NEXT"
    const val ACTION_PREV = P + "PREV"

    /** The full [AirPlayConfig] as a Bundle ([configToBundle]). */
    const val EXTRA_CONFIG = "config"
    /** [DebugOptions]; the service ignores it unless the app is debuggable. */
    const val EXTRA_DEBUG = "debug"

    // Launcher -> receiver.
    /** replyTo receives MSG_STATUS now and on every change. */
    const val MSG_REGISTER = 1
    const val MSG_UNREGISTER = 2
    /** data = config Bundle; restarts only when a restart key changed. */
    const val MSG_APPLY = 3
    const val MSG_STOP = 4
    const val MSG_DISCONNECT = 5
    /** Bring the receiver screen to the front. */
    const val MSG_SHOW = 6
    /** Debug builds: replyTo receives MSG_ADVERTS with what is registered over DNS-SD. */
    const val MSG_ADVERTS = 7
    /** Debug builds: data = a [DEBUG_OP] Bundle (feed an MP4 as a mirror session, show a PIN); ignored otherwise. */
    const val MSG_DEBUG = 8

    // Receiver -> launcher.
    /** data = status Bundle ([statusToBundle]). */
    const val MSG_STATUS = 100

    private const val S_STATE = "state"
    private const val S_ERROR = "error"
    private const val S_NAME = "name"
    private const val S_PORT = "port"
    private const val S_CONNECTIONS = "connections"
    private const val S_MODE = "mode"
    private const val S_PIN = "pin"
    private const val S_TITLE = "title"
    private const val S_ARTIST = "artist"
    private const val S_VISIBLE = "activityVisible"
    private const val S_PID = "pid"
    private const val S_VIDEO_W = "videoWidth"
    private const val S_VIDEO_H = "videoHeight"

    const val ADVERTS = "adverts"
    const val ADVERT_NAME = "name"
    const val ADVERT_TYPE = "type"
    const val ADVERT_PORT = "port"
    const val ADVERT_TXT = "txt"

    /** MSG_DEBUG: [OP_FEED] with [DEBUG_PATH] and [DEBUG_SECONDS], [OP_FEED_STOP], [OP_PIN] with [DEBUG_PIN] (null clears). */
    const val DEBUG_OP = "op"
    const val OP_FEED = "feed"
    const val OP_FEED_STOP = "feed-stop"
    const val OP_PIN = "pin"
    const val DEBUG_PATH = "path"
    const val DEBUG_SECONDS = "seconds"
    const val DEBUG_PIN = "pin"

    fun configToBundle(c: AirPlayConfig): Bundle = Bundle().apply {
        for ((k, v) in c.toMap()) when (v) {
            is Boolean -> putBoolean(k, v)
            is Int -> putInt(k, v)
            is String -> putString(k, v)
        }
    }

    fun configFromBundle(b: Bundle?): AirPlayConfig {
        if (b == null) return AirPlayConfig.DEFAULTS
        val raw = HashMap<String, Any?>()
        for (k in AirPlayConfig.KEYS) {
            if (!b.containsKey(k.name)) continue
            raw[k.name] = when (k.kind) {
                AirPlayConfig.Kind.BOOL -> b.getBoolean(k.name)
                AirPlayConfig.Kind.INT -> b.getInt(k.name)
                AirPlayConfig.Kind.TEXT -> b.getString(k.name)
            }
        }
        return AirPlayConfig.from(raw)
    }

    fun statusToBundle(s: AirPlayStatus): Bundle = Bundle().apply {
        putString(S_STATE, s.state.name)
        putString(S_ERROR, s.error)
        putString(S_NAME, s.name)
        putInt(S_PORT, s.port)
        putInt(S_CONNECTIONS, s.connections)
        putString(S_MODE, s.mode.name)
        putString(S_PIN, s.pin)
        putString(S_TITLE, s.title)
        putString(S_ARTIST, s.artist)
        putBoolean(S_VISIBLE, s.activityVisible)
        putInt(S_PID, s.pid)
        putInt(S_VIDEO_W, s.videoWidth)
        putInt(S_VIDEO_H, s.videoHeight)
    }

    fun statusFromBundle(b: Bundle?): AirPlayStatus {
        if (b == null) return AirPlayStatus()
        return AirPlayStatus(
            state = AirPlayStatus.state(b.getString(S_STATE)),
            error = b.getString(S_ERROR),
            name = b.getString(S_NAME) ?: "",
            port = b.getInt(S_PORT),
            connections = b.getInt(S_CONNECTIONS),
            mode = AirPlayStatus.mode(b.getString(S_MODE)),
            pin = b.getString(S_PIN),
            title = b.getString(S_TITLE) ?: "",
            artist = b.getString(S_ARTIST) ?: "",
            activityVisible = b.getBoolean(S_VISIBLE),
            pid = b.getInt(S_PID),
            videoWidth = b.getInt(S_VIDEO_W),
            videoHeight = b.getInt(S_VIDEO_H),
        )
    }
}

/**
 * Debug-build-only switches for one receiver run, never saved: [failInit] makes the start fail as a native init
 * failure would (to check that an ERROR exit is not restarted), [ports] fixes the five receiver ports
 * (tcp mirror data, tcp http/RTSP, udp timing, udp control, udp data) for a NAT-forwarded emulator.
 */
class DebugOptions(val failInit: Boolean = false, val ports: IntArray? = null) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("failInit", failInit)
        if (ports != null) putIntArray("ports", ports)
    }

    companion object {
        val NONE = DebugOptions()

        fun from(b: Bundle?): DebugOptions {
            if (b == null) return NONE
            val ports = b.getIntArray("ports")?.takeIf { p -> p.size == 5 && p.all { it in 0..65535 } }
            return DebugOptions(b.getBoolean("failInit"), ports)
        }
    }
}
