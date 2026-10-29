package io.github.abhishekkaisar.glassos

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The kind of weather, from the WMO code Open-Meteo reports; what the icon draws. */
enum class Sky(val label: String) {
    CLEAR("Clear"), PARTLY("Partly cloudy"), CLOUDY("Cloudy"), FOG("Fog"), DRIZZLE("Drizzle"), RAIN("Rain"),
    FREEZING("Freezing rain"), SNOW("Snow"), STORM("Thunderstorm")
}

/** One reading: the temperature in [fahrenheit] or Celsius, the WMO [code], day or night, when, and for which place. */
class WeatherNow(val temp: Double, val fahrenheit: Boolean, val code: Int, val day: Boolean, val at: Long, val place: String) {
    val sky: Sky get() = Weather.sky(code)
    /** "61°": the unit is the TV's choice (Settings > Weather), so it is not repeated. */
    val degrees: String get() = "${Math.round(temp)}°"
}

/**
 * The current weather for the time pill, cinema mode and the screensaver, from Open-Meteo's free forecast API (no
 * key, no account). Off unless chosen in Settings > Weather. The only thing sent is the sun times place rounded to
 * 0.1° (about 11 km, as GlassOS Manager already rounds it) and the unit; asked at most every [REFRESH_MS] while a
 * home or the screensaver is on screen, never in the background. Pure parts here; [WeatherState] fetches and shares.
 */
object Weather {
    const val REFRESH_MS = 30 * 60_000L
    /** An older reading shows nothing rather than mislead. */
    const val STALE_MS = 2 * 60 * 60_000L
    /** After a failed request, the next try waits this long. */
    const val RETRY_MS = 10 * 60_000L
    const val UNITS_AUTO = "auto"
    /**
     * Open-Meteo's certificates chain to ISRG Root X1, which Android trusts from 7.1 (API 25: Fire OS 6 and later).
     * Below that the request cannot succeed, so the setting says so instead of trying.
     */
    val supported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 25
    val UNITS = listOf(UNITS_AUTO, "f", "c")
    /** Countries that use Fahrenheit (the United States and its territories, and a few others). */
    private val F_COUNTRIES = setOf("US", "PR", "GU", "VI", "AS", "MP", "UM", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    fun sky(code: Int): Sky = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        51, 53, 55 -> Sky.DRIZZLE
        56, 57, 66, 67 -> Sky.FREEZING
        61, 63, 65, 80, 81, 82 -> Sky.RAIN
        71, 73, 75, 77, 85, 86 -> Sky.SNOW
        // 97 is in Open-Meteo's table (heavy thunderstorm), though its server currently reports 96 instead.
        95, 96, 97, 99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    /** [unit] "f" or "c", or "auto": Fahrenheit where the TV's [country] (setup's, else the system's) uses it. */
    fun fahrenheit(unit: String, country: String): Boolean = when (unit) {
        "f" -> true
        "c" -> false
        else -> country.uppercase(Locale.US) in F_COUNTRIES
    }

    /** The place as sent: rounded to 0.1°, never more precise. */
    fun placeKey(lat: Double, lon: Double): String = String.format(Locale.US, "%.1f,%.1f", lat, lon)

    fun url(place: String, fahrenheit: Boolean): String {
        val (lat, lon) = place.split(',')
        return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&temperature_unit=${if (fahrenheit) "fahrenheit" else "celsius"}&timezone=auto"
    }

    /** The "current" block of Open-Meteo's reply, checked; null for anything unexpected. */
    fun parse(text: String?, fahrenheit: Boolean, place: String, now: Long): WeatherNow? {
        if (text == null || text.length > 64 * 1024) return null
        return try {
            val c = JSONObject(text).getJSONObject("current")
            val t = c.getDouble("temperature_2m")
            val code = c.getInt("weather_code")
            if (t.isNaN() || t < -100 || t > 160 || code !in 0..99) return null
            // The unit Open-Meteo says it used, when it says one: a mismatch is not shown in the wrong unit.
            val unit = JSONObject(text).optJSONObject("current_units")?.optString("temperature_2m", "") ?: ""
            if (unit.isNotEmpty() && unit.contains('F') != fahrenheit) return null
            WeatherNow(t, fahrenheit, code, c.optInt("is_day", 1) != 0, now, place)
        } catch (e: Exception) { null }
    }

    /**
     * Whether [w] may show now: same unit and place, and not stale. A reading "from the future" (the TV's clock was
     * set back since) counts by how far off it is, so a clock correction does not blank the weather.
     */
    fun usable(w: WeatherNow?, fahrenheit: Boolean, place: String, now: Long): Boolean =
        w != null && w.fahrenheit == fahrenheit && w.place == place && Math.abs(now - w.at) <= STALE_MS

    fun encode(w: WeatherNow): String = JSONObject().put("temp", w.temp).put("f", w.fahrenheit).put("code", w.code)
        .put("day", w.day).put("at", w.at).put("place", w.place).toString()

    fun decode(s: String?): WeatherNow? = try {
        if (s == null) null else JSONObject(s).let { o ->
            WeatherNow(o.getDouble("temp"), o.getBoolean("f"), o.getInt("code"), o.getBoolean("day"), o.getLong("at"), o.getString("place"))
        }
    } catch (e: Exception) { null }
}

/**
 * The weather one process shows: every home and the screensaver read [current] and ask [refresh]; one request runs at
 * a time on its own thread (never in the way of the scenery's loading), the reading is kept in the private "weather"
 * preferences (never in backups), and listeners hear of a new one.
 */
object WeatherState {
    private val busy = AtomicBoolean(false)
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-weather").apply { isDaemon = true } }
    /** When the last request started (elapsed time: a clock change does not hold the next one back). */
    @Volatile private var lastTry = 0L
    /** The place and unit it was for: a different one is asked for at once. */
    @Volatile private var lastAsked = ""
    @Volatile private var cached: WeatherNow? = null
    private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** The request this TV would make now, or null: weather off, or no sun times place. */
    private fun want(prefs: Prefs): Pair<String, Boolean>? {
        if (!prefs.weather || !Weather.supported) return null
        val p = SunNow.place(prefs) ?: return null
        val country = prefs.country.ifEmpty { Locale.getDefault().country }
        return Weather.placeKey(p.lat, p.lon) to Weather.fahrenheit(prefs.weatherUnit, country)
    }

    /** What to show now, or null (off, no place, nothing fresh). Main thread. */
    fun current(ctx: Context, prefs: Prefs, now: Long = System.currentTimeMillis()): WeatherNow? {
        val (place, f) = want(prefs) ?: return null
        if (!loaded) {
            loaded = true
            cached = Weather.decode(ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("now", null))
        }
        return cached?.takeIf { Weather.usable(it, f, place, now) }
    }

    /** Asks Open-Meteo when the reading is due (or missing); listeners hear the result on the main thread. */
    fun refresh(ctx: Context, prefs: Prefs, now: Long = System.currentTimeMillis()) {
        val (place, f) = want(prefs) ?: return
        val have = current(ctx, prefs, now)
        if (have != null && Math.abs(now - have.at) < Weather.REFRESH_MS) return
        val asked = "$place $f"
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (asked == lastAsked && lastTry != 0L && elapsed - lastTry < Weather.RETRY_MS) return
        if (!busy.compareAndSet(false, true)) return
        lastTry = elapsed
        lastAsked = asked
        val app = ctx.applicationContext
        try {
            net.execute {
                try {
                    // A plain User-Agent: Android's default names the TV's model and build.
                    val (code, body) = HeroRepo.get(Weather.url(place, f), mapOf("User-Agent" to "GlassOS-Aerial"))
                    val w = if (code == 200) Weather.parse(body, f, place, System.currentTimeMillis()) else null
                    WLog.i(if (w != null) "weather updated" else "weather request failed ($code)")
                    if (w != null) {
                        app.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().putString("now", Weather.encode(w)).apply()
                        main.post {
                            cached = w
                            loaded = true
                            listeners.toList().forEach { it() }
                        }
                    }
                } finally { busy.set(false) }
            }
        } catch (e: Exception) { busy.set(false) }
    }

    /** Weather was turned on or its unit chosen: the next [refresh] asks at once. */
    fun reset() { lastTry = 0L }
}
