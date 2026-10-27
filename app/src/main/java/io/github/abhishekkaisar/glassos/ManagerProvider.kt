package io.github.abhishekkaisar.glassos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import io.github.abhishekkaisar.glassos.airplay.AirPlay
import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.DebugOptions
import io.github.abhishekkaisar.glassos.airplay.Protocol
import io.github.abhishekkaisar.glassos.airplay.debug.DebugHooks
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * GlassOS Manager's controls on the TV, for ADB only:
 * `adb shell content call --uri content://<application id>.manager --method <method> [--arg <arg>]`.
 *
 * Two gates: the provider needs DUMP, which apps cannot hold (signature|privileged|development: only a `pm grant`
 * from the shell gives it to one), and [call] refuses every caller but the shell and root. Anyone with ADB shell can
 * already defeat the app lock (pm clear, appops, force-stop), so this adds no power, only a checked path; the TV shows
 * a toast for every change. Every reply is a Bundle whose one key, "glass", holds compact JSON: {"ok":true,...} or
 * {"ok":false,"error":"<code>","message":"<text>"}. Never null (`content` on API 18 to 28 fails on a null result), and
 * never the lock code or a Screen Mirroring PIN.
 */
class ManagerProvider : ContentProvider() {
    // Runs at every process start, before anything else: nothing to do until a call arrives.
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) return failed("denied", "Only adb shell can use these controls.")
        val ctx = context?.applicationContext ?: return failed("failed", "GlassOS is not ready. Try again.")
        // Prefs are read and written here, on the binder thread; the home screens change on the main thread.
        return try {
            when (method) {
                "status" -> reply(status(ctx))
                "lock-set-code" -> setCode(ctx, arg)
                "lock-off" -> lockOff(ctx)
                "sun-location" -> sunLocation(ctx, arg)
                "airplay-status" -> airplayStatus(ctx)
                "airplay-start" -> airplayStart(ctx)
                "airplay-stop" -> airplayStop(ctx)
                "airplay-set" -> airplaySet(ctx, arg)
                "airplay-show" -> airplayShow(ctx)
                "airplay-debug" -> airplayDebug(ctx, arg)
                else -> failed("unknown-method", "This GlassOS does not know that control.")
            }
        } catch (e: Throwable) {
            failed("failed", "GlassOS could not do that.")
        }
    }

    private fun status(ctx: Context): JSONObject {
        val p = Prefs(ctx)
        val locked = p.locked
        val pm = ctx.packageManager
        val installed = locked.count { pkg -> try { pm.getApplicationInfo(pkg, 0); true } catch (e: Exception) { false } }
        val lock = JSONObject()
            .put("code", p.lockCode.isNotEmpty())
            .put("locked", locked.size)
            .put("installedLocked", installed)
            .put("guard", AppLockGuard.available(ctx))
            .put("usage", HomeWatcher.hasAccess(ctx))
            .put("overlay", Grants.canDrawOverlays(ctx))
            .put("guardRunning", AppLockGuard.running)
        return ok()
            .put("version", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("lock", lock)
            .put("sun", sun(p))
            .put("screensaver", screensaver(ctx))
    }

    /**
     * Where sun times come from and today's sunrise and sunset ("HH:mm", TV local time), as Settings > Sun times shows
     * them. lat/lon (0.1°) and label only when there is a place; sunrise/sunset null on a polar day or without one.
     */
    private fun sun(p: Prefs): JSONObject {
        val place = SunNow.place(p)
        val o = JSONObject().put("source", place?.source ?: "none").put("zone", TimeZone.getDefault().id)
        if (place != null) o.put("label", place.label).put("lat", place.lat).put("lon", place.lon)
        val day = if (place != null) SunNow.today(p) else null
        fun hhmm(m: Int?): Any = m?.let { String.format(Locale.US, "%02d:%02d", it / 60, it % 60) } ?: JSONObject.NULL
        return o.put("sunrise", hhmm(day?.minute("sunrise", 0)))
            .put("sunset", hhmm(day?.minute("sunset", 0)))
            .put("polar", day?.polar ?: JSONObject.NULL)
    }

    /**
     * Sets ("lat,lon,label") or, with no argument, clears the place sun times are worked out for. `content call`
     * cannot send an empty --arg, so clearing leaves it out; an empty one clears too. Coordinates are rounded to 0.1°.
     */
    private fun sunLocation(ctx: Context, arg: String?): Bundle {
        val place = if (arg.isNullOrEmpty()) null else Place.parse(arg) ?: return failed("bad-location", "That is not a place on Earth.")
        val p = Prefs(ctx)
        if (!p.setSunPlace(place)) return failed("not-saved", "GlassOS could not save the place.")
        SunNow.invalidate()
        WLog.init(ctx)
        WLog.i("sun times: place ${if (place == null) "cleared" else "set"} by GlassOS Manager")
        // Night mode and the scheduled scenery follow at once, as on a minute tick.
        Handler(Looper.getMainLooper()).post {
            for (s in listOfNotNull(HomeShare.main, HomeOverlay.screen).distinct()) s.sunPlaceChanged()
            Toast.makeText(ctx, if (place == null) "Sun times now follow the TV\u2019s time zone" else "Sun times place set from GlassOS Manager",
                Toast.LENGTH_LONG).show()
        }
        return reply(ok().put("sun", sun(p)))
    }

    /** Which screensaver the TV uses, read only. A setting that is not there reads as null. */
    private fun screensaver(ctx: Context): JSONObject {
        val components = AerialDream.setting(ctx)
        val enabled = try { Settings.Secure.getString(ctx.contentResolver, "screensaver_enabled") } catch (e: Exception) { null }
        // Settings > Screensaver decides the same way.
        return JSONObject()
            .put("component", components ?: JSONObject.NULL)
            .put("active", AerialDream.isThis(ctx, components))
            .put("enabled", when (enabled?.trim()) { "1" -> true; "0" -> false; else -> JSONObject.NULL })
    }

    private fun setCode(ctx: Context, code: String?): Bundle {
        if (code == null || !LockCode.valid(code)) return failed("bad-code", "A code is ${LockCode.LENGTH} remote buttons.")
        val p = Prefs(ctx)
        if (!AppLock.setCode(ctx, p, code)) return failed("not-saved", "GlassOS could not save the code.")
        changed(ctx, false, "App lock code changed from GlassOS Manager")
        return reply(ok().put("locked", p.locked.size))
    }

    private fun lockOff(ctx: Context): Bundle {
        if (!AppLock.turnOff(ctx, Prefs(ctx))) return failed("not-saved", "GlassOS could not turn the app lock off.")
        changed(ctx, true, "App lock turned off from GlassOS Manager")
        return reply(ok())
    }

    /** Shows the change on the TV: padlocks redrawn on both homes, the lock screen gone once the lock is off, a toast. */
    private fun changed(ctx: Context, off: Boolean, text: String) {
        WLog.init(ctx)
        WLog.i("app lock: ${if (off) "turned off" else "code changed"} by GlassOS Manager")
        Handler(Looper.getMainLooper()).post {
            for (s in listOfNotNull(HomeShare.main, HomeOverlay.screen).distinct()) s.lockChangedElsewhere(off)
            if (off) LockActivity.finishShown()
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Screen Mirroring (the airplay module) ----------
    //
    // The receiver runs in GlassOS's :airplay process; these calls reach it through the AirPlay facade, which never
    // loads its native code here. Each reply carries "airplay": the receiver's state ("STOPPED", "STARTING",
    // "RUNNING" or "ERROR" with an "error" code), its name, port, connections, mode, whether its screen shows
    // (activityVisible) or could be shown (offersView), whether a PIN is on screen (never the PIN), the mirrored
    // stream's size, pid, the supervisor's restart count and every setting. Settings are "key=value" with the keys of AirPlayConfig: name, pin, res
    // (auto|1080p|720p), sync_volume, port, h265 (auto|on|off), max_fps (1-60), overscanned, allow_new_conn,
    // audio_latency_ms (-1-1000), enforce_sdr, allow_frame_drop, operating_rate (auto|on|off),
    // scheduled_output_buffer_release, realtime_priority, low_latency, audio_auto_buffer, audio_cushion_ms (1-1000),
    // audio_adaptive_step (0-4), oboe_buffer_frames (0-8192), aac_enabled, advertise_mirroring, advertise_audio and
    // benchmark_log; booleans are true or false, and an empty value ("port=") restores the default.

    private fun airplayStatus(ctx: Context): Bundle {
        val p = Prefs(ctx)
        // Binds to a receiver that may already run (never starts one) and waits briefly for its status.
        val s = if (attachAirplay(ctx, p)) AirPlay.await(ctx, STATUS_WAIT_MS) { it.connected && it.status.pid != 0 }
        else AirPlay.snapshot
        return reply(ok().put("airplay", airplay(ctx, p, s)))
    }

    /**
     * Lets this process watch a receiver started before it (it restarts it after a crash while airplay_on is set).
     * False when this TV or build has no receiver.
     */
    private fun attachAirplay(ctx: Context, p: Prefs): Boolean {
        if (!AirPlay.isSupported(ctx)) return false
        AirPlay.attach(ctx, p.airplayOn, p.airplayConfig)
        return true
    }

    /** Turns Screen Mirroring on (airplay_on) and starts the receiver; replies once it runs or failed. */
    private fun airplayStart(ctx: Context, debug: DebugOptions = DebugOptions.NONE): Bundle {
        if (!AirPlay.isSupported(ctx)) return failed("unsupported", "Screen Mirroring needs Fire OS 6 or later and a build with it.")
        val p = Prefs(ctx)
        p.airplayOn = true
        AirPlay.start(ctx, p.airplayConfig, debug)
        val s = AirPlay.await(ctx, START_WAIT_MS) {
            it.status.state == AirPlayStatus.State.RUNNING || it.status.state == AirPlayStatus.State.ERROR
        }
        airplayChanged(ctx, "on", "Screen Mirroring turned on from GlassOS Manager")
        return reply(ok().put("airplay", airplay(ctx, p, s)))
    }

    /** Turns Screen Mirroring off and stops the receiver; replies once its process has gone. */
    private fun airplayStop(ctx: Context): Bundle {
        val p = Prefs(ctx)
        p.airplayOn = false
        AirPlay.stop(ctx)
        val s = AirPlay.await(ctx, STOP_WAIT_MS) { !it.connected && !it.status.running }
        airplayChanged(ctx, "off", "Screen Mirroring turned off from GlassOS Manager")
        return reply(ok().put("airplay", airplay(ctx, p, s)))
    }

    /**
     * Brings the receiver screen to the front, as the launcher's tile does (SHOW): after Back hid it, or to see the
     * receiver's state. Replies once it shows, or after a short wait (Android may refuse to start it from the
     * background: GlassOS must be on screen, or hold Display over other apps).
     */
    private fun airplayShow(ctx: Context): Bundle {
        val p = Prefs(ctx)
        if (!attachAirplay(ctx, p)) return failed("unsupported", "Screen Mirroring needs Fire OS 6 or later and a build with it.")
        val now = AirPlay.await(ctx, STATUS_WAIT_MS) { it.connected && it.status.pid != 0 }
        if (now.status.state != AirPlayStatus.State.RUNNING) return failed("not-running", "Screen Mirroring is not running.")
        AirPlay.show(ctx)
        val s = AirPlay.await(ctx, SHOW_WAIT_MS) { it.status.activityVisible }
        return reply(ok().put("airplay", airplay(ctx, p, s)))
    }

    /** One setting ("key=value"); a running receiver gets it at once and restarts only for a restart key. */
    private fun airplaySet(ctx: Context, arg: String?): Bundle {
        val eq = arg?.indexOf('=') ?: -1
        if (arg == null || eq <= 0) return failed("bad-setting", "Send a setting as key=value.")
        val key = AirPlayConfig.key(arg.substring(0, eq).trim())
            ?: return failed("bad-setting", "Screen Mirroring has no setting by that name.")
        val text = arg.substring(eq + 1)
        val value: Any? = if (text.isEmpty()) null else key.parse(text) ?: return failed("bad-value", "That value is not allowed for ${key.name}.")
        val p = Prefs(ctx)
        if (!p.setAirplay(key, value)) return failed("not-saved", "GlassOS could not save the setting.")
        val config = p.airplayConfig
        // A running receiver gets it now (or as soon as this process is bound to it); a stopped one at its next start.
        if (attachAirplay(ctx, p) && p.airplayOn) AirPlay.apply(ctx, config)
        WLog.init(ctx)
        WLog.i("screen mirroring: ${key.name} set by GlassOS Manager")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, "Screen Mirroring setting changed from GlassOS Manager", Toast.LENGTH_LONG).show()
        }
        return reply(ok().put("key", key.name).put("value", config.toMap()[key.name]).put("restart", key.restart)
            .put("airplay", airplay(ctx, p, AirPlay.snapshot)))
    }

    /**
     * Debug builds only: `discover` (browses and resolves _airplay._tcp and _raop._tcp from this process), `dump-txt`
     * (what the receiver registered, with dns-sd lines for a host-side proxy), `fail-init` (a start that fails as a
     * native init failure would) and `ports <tcp data>,<tcp rtsp>,<udp timing>,<udp control>,<udp data>` or
     * `ports off` (fixed receiver ports, kept until the next plain start). The last two turn Screen Mirroring on.
     * With the receiver running: `feed-mp4 PATH [SECONDS]` plays the H.264 track of an MP4 on the TV (a path without
     * spaces, readable by GlassOS) into it as a sender's mirror session would arrive, looped for SECONDS (default
     * 60); `feed-stop` ends that session as a sender leaving would; `pin NNNN` shows a PIN as a sender asking for one
     * would, `pin off` takes it away. The PIN is never in a reply.
     */
    private fun airplayDebug(ctx: Context, arg: String?): Bundle {
        if (!DebugHooks.enabled(ctx)) return failed("unknown-method", "This GlassOS does not know that control.")
        val words = arg?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        return when (words.firstOrNull()) {
            "discover" -> {
                val found = DebugHooks.discover(ctx, listOf("_airplay._tcp", "_raop._tcp"), DISCOVER_MS)
                val list = JSONArray()
                for (f in found) list.put(JSONObject().put("type", f.type).put("name", f.name).put("host", f.host)
                    .put("port", f.port).put("txt", JSONObject(f.txt)))
                reply(ok().put("services", list))
            }
            "dump-txt" -> {
                if (attachAirplay(ctx, Prefs(ctx))) AirPlay.await(ctx, STATUS_WAIT_MS) { it.connected }
                val b = AirPlay.adverts(ctx, STATUS_WAIT_MS) ?: return failed("not-running", "Screen Mirroring is not running.")
                val list = JSONArray()
                val dnsSd = JSONArray()
                @Suppress("DEPRECATION")
                val adverts = b.getParcelableArrayList<Bundle>(Protocol.ADVERTS) ?: arrayListOf()
                for (a in adverts) {
                    val name = a.getString(Protocol.ADVERT_NAME) ?: ""
                    val type = a.getString(Protocol.ADVERT_TYPE) ?: ""
                    val port = a.getInt(Protocol.ADVERT_PORT)
                    val txt = JSONObject()
                    val pairs = StringBuilder()
                    a.getBundle(Protocol.ADVERT_TXT)?.let { t ->
                        for (k in t.keySet().sorted()) {
                            val v = t.getString(k) ?: ""
                            txt.put(k, v)
                            pairs.append(" '").append(k).append('=').append(v).append('\'')
                        }
                    }
                    list.put(JSONObject().put("type", type).put("name", name).put("port", port).put("txt", txt))
                    dnsSd.put("dns-sd -P '$name' $type local $port glassos-emu.local 127.0.0.1$pairs")
                }
                reply(ok().put("services", list).put("dnsSd", dnsSd))
            }
            // These two stop a running receiver first, so the reply describes the new run.
            "fail-init" -> {
                airplayStop(ctx)
                airplayStart(ctx, DebugOptions(failInit = true))
            }
            "ports" -> {
                val spec = words.drop(1).joinToString(",")
                val ports = if (spec == "off") null else spec.split(',').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: -1 }
                if (ports != null && (ports.size != 5 || ports.any { it !in 0..65535 })) {
                    return failed("bad-ports", "Give five ports: tcp data, tcp rtsp, udp timing, udp control, udp data.")
                }
                airplayStop(ctx)
                airplayStart(ctx, DebugOptions(ports = ports?.toIntArray()))
            }
            "feed-mp4" -> {
                val path = words.getOrNull(1) ?: return failed("bad-debug", "Give the MP4's path on the TV.")
                val seconds = words.getOrNull(2)?.let { w -> w.toIntOrNull()?.takeIf { it in 1..3600 } ?: return failed("bad-debug", "Give 1 to 3600 seconds.") } ?: 0
                if (!java.io.File(path).isFile) return failed("no-file", "GlassOS cannot read a file there.")
                val p = Prefs(ctx)
                val now = runningReceiver(ctx, p) ?: return failed("not-running", "Screen Mirroring is not running.")
                if (now.status.mode != AirPlayStatus.Mode.IDLE) return failed("busy", "A session is on. Send feed-stop first.")
                AirPlay.debug(ctx, Bundle().apply {
                    putString(Protocol.DEBUG_OP, Protocol.OP_FEED)
                    putString(Protocol.DEBUG_PATH, path)
                    putInt(Protocol.DEBUG_SECONDS, seconds)
                })
                val s = AirPlay.await(ctx, FEED_WAIT_MS) { it.status.mode == AirPlayStatus.Mode.MIRROR }
                reply(ok().put("feeding", s.status.mode == AirPlayStatus.Mode.MIRROR).put("airplay", airplay(ctx, p, s)))
            }
            "feed-stop" -> {
                val p = Prefs(ctx)
                runningReceiver(ctx, p) ?: return failed("not-running", "Screen Mirroring is not running.")
                AirPlay.debug(ctx, Bundle().apply { putString(Protocol.DEBUG_OP, Protocol.OP_FEED_STOP) })
                val s = AirPlay.await(ctx, FEED_WAIT_MS) { it.status.mode == AirPlayStatus.Mode.IDLE }
                reply(ok().put("airplay", airplay(ctx, p, s)))
            }
            "pin" -> {
                val v = words.getOrNull(1)
                val pin = if (v == "off") null else v?.takeIf { PIN.matches(it) } ?: return failed("bad-pin", "A PIN is four digits, or off.")
                val p = Prefs(ctx)
                runningReceiver(ctx, p) ?: return failed("not-running", "Screen Mirroring is not running.")
                AirPlay.debug(ctx, Bundle().apply {
                    putString(Protocol.DEBUG_OP, Protocol.OP_PIN)
                    putString(Protocol.DEBUG_PIN, pin)
                })
                val s = AirPlay.await(ctx, SHOW_WAIT_MS) { (it.status.pin != null) == (pin != null) && it.status.activityVisible == (pin != null) }
                reply(ok().put("airplay", airplay(ctx, p, s)))
            }
            else -> failed("bad-debug", "Use discover, dump-txt, fail-init, ports, feed-mp4, feed-stop or pin.")
        }
    }

    /** The receiver's snapshot once this process is bound to it, or null when none runs. */
    private fun runningReceiver(ctx: Context, p: Prefs): AirPlay.Snapshot? {
        if (!attachAirplay(ctx, p)) return null
        val s = AirPlay.await(ctx, STATUS_WAIT_MS) { it.connected && it.status.pid != 0 }
        return s.takeIf { it.connected && it.status.state == AirPlayStatus.State.RUNNING }
    }

    private fun airplay(ctx: Context, p: Prefs, s: AirPlay.Snapshot): JSONObject {
        val st = s.status
        val settings = JSONObject()
        for ((k, v) in p.airplayConfig.toMap()) settings.put(k, v)
        return JSONObject()
            .put("supported", AirPlay.isSupported(ctx))
            .put("on", p.airplayOn)
            .put("connected", s.connected)
            .put("state", st.state.name)
            .put("error", st.error ?: JSONObject.NULL)
            .put("name", st.name)
            .put("port", st.port)
            .put("connections", st.connections)
            .put("mode", st.mode.name)
            .put("activityVisible", st.activityVisible)
            .put("offersView", st.offersView)
            .put("pinShown", st.pin != null)
            .put("videoWidth", st.videoWidth)
            .put("videoHeight", st.videoHeight)
            .put("pid", st.pid)
            .put("restarts", s.restarts)
            .put("gaveUp", s.gaveUp)
            .put("settings", settings)
    }

    private fun airplayChanged(ctx: Context, what: String, text: String) {
        WLog.init(ctx)
        WLog.i("screen mirroring: turned $what by GlassOS Manager")
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun reply(json: JSONObject) = Bundle().apply { putString(KEY, json.toString()) }

    private fun failed(error: String, message: String) =
        reply(JSONObject().put("ok", false).put("error", error).put("message", message))

    // Not a data provider: GlassOS Manager uses call() only.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?,
                       sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        private const val KEY = "glass"
        // Process.SHELL_UID and ROOT_UID, which API 22 does not publish.
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private const val STATUS_WAIT_MS = 1_500L
        private const val START_WAIT_MS = 8_000L
        private const val STOP_WAIT_MS = 5_000L
        private const val DISCOVER_MS = 4_000L
        private const val SHOW_WAIT_MS = 3_000L
        private const val FEED_WAIT_MS = 5_000L
        private val PIN = Regex("\\d{4}")
    }
}
