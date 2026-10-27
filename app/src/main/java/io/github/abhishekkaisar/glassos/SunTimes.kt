package io.github.abhishekkaisar.glassos

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

// Pure (no android.* imports), unit tested on the JVM.

/**
 * Where sun times are worked out: 0.1° latitude and longitude (north and east positive) and a short name. [source]
 * is "manager" (set from GlassOS Manager) or "timezone" (estimated from the TV's time zone, see [ZoneCoords]).
 */
class Place(val lat: Double, val lon: Double, val label: String, val source: String = "timezone") {
    companion object {
        const val LABEL_MAX = 40

        /**
         * GlassOS Manager's "lat,lon,label" as a place, or null when it is not one. Coordinates are rounded to 0.1°;
         * the name (which may hold commas) keeps letters, digits, spaces and . , ' - and at most 40 characters.
         */
        fun parse(arg: String): Place? {
            val f = arg.split(',', limit = 3)
            if (f.size < 2) return null
            val lat = f[0].trim().toDoubleOrNull() ?: return null
            val lon = f[1].trim().toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val label = (f.getOrNull(2) ?: "").filter { it.isLetter() || it in "0123456789 .,'-" }
                .replace(Regex(" +"), " ").trim().take(LABEL_MAX).trim()
            return Place(tenth(lat), tenth(lon), label, "manager")
        }

        // + 0.0 turns -0.0 into 0.0.
        private fun tenth(v: Double): Double = Math.round(v * 10) / 10.0 + 0.0
    }
}

/**
 * One local day's sunrise and sunset as instants (epoch ms), or null for both with [polar] "up" (the sun never sets)
 * or "down" (it never rises). Both null with [polar] null: no place to work them out from.
 */
class SunDay(val sunrise: Long?, val sunset: Long?, val polar: String?, val tz: TimeZone) {
    /**
     * Local minute of day (0..1439) of [anchor] ("sunrise"/"sunset") + [offset] minutes, or null when that event
     * does not happen today. The offset is elapsed time (instant arithmetic, right across a DST change); the local
     * time uses the zone's offset at that instant, rounded to the nearest minute and folded into one day.
     */
    fun minute(anchor: String, offset: Int): Int? {
        val t = when (anchor) { "sunrise" -> sunrise; "sunset" -> sunset; else -> null } ?: return null
        return SunTimes.localMinute(t + offset * 60_000L, tz)
    }
}

/**
 * Sunrise and sunset: the NOAA solar calculator (Meeus, Astronomical Algorithms, low-precision solar position),
 * evaluated again at each event's estimated instant, with 0.833° for refraction and the sun's disc. Within a minute of
 * the USNO tables between ±72° latitude. The same code as GlassOS Manager's sun.py, so both show the same times.
 */
object SunTimes {
    const val DAY_MS = 86_400_000L
    private const val D2R = Math.PI / 180
    private val COS_Z = cos(90.833 * D2R)

    /** Days from 1970-01-01 to a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
    fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val yy = (if (m <= 2) y - 1 else y).toLong()
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Sunrise and sunset on the local date (in [tz]) that contains [nowMs]. */
    fun day(nowMs: Long, tz: TimeZone, lat: Double, lon: Double): SunDay {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return SunDay(null, null, null, tz)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        // The guess only picks the day: a DST hour either way changes nothing.
        val noon = daysFromCivil(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)) * DAY_MS +
            DAY_MS / 2 - tz.rawOffset
        val r = riseSet(noon, lat, lon)
        return when {
            r === UP -> SunDay(null, null, "up", tz)
            r === DOWN -> SunDay(null, null, "down", tz)
            else -> SunDay(r[0].roundToLong(), r[1].roundToLong(), null, tz)
        }
    }

    /** The local minute of day (0..1439) at [ms], rounded to the nearest minute as the USNO tables are. */
    fun localMinute(ms: Long, tz: TimeZone): Int {
        val r = floorDiv(ms + 30_000, 60_000) * 60_000
        return ((((r + tz.getOffset(r)) % DAY_MS) + DAY_MS) % DAY_MS / 60_000).toInt()
    }

    // Math.floorDiv is API 24.
    private fun floorDiv(a: Long, b: Long): Long = (a - ((a % b) + b) % b) / b

    private val UP = DoubleArray(0)
    private val DOWN = DoubleArray(0)

    /**
     * [noonGuess] is 12:00 local clock time on the local date. Returns the rise and set instants (ms), or [UP] / [DOWN]
     * when the sun stays above or below the horizon all day.
     */
    private fun riseSet(noonGuess: Long, lat: Double, lon: Double): DoubleArray {
        val eqt = sun(noonGuess.toDouble())[0]
        // 00:00 UTC of the guess's UTC day, moved a day when the sun's transit is more than half a day away from noon:
        // keeps the events on the requested local date in zones far from UTC (Kiritimati, Chatham, Apia).
        var base = (noonGuess - ((noonGuess % DAY_MS) + DAY_MS) % DAY_MS).toDouble()
        val transit = base + (720 - 4 * lon - eqt) * 60_000
        if (transit - noonGuess > DAY_MS / 2) base -= DAY_MS
        else if (noonGuess - transit > DAY_MS / 2) base += DAY_MS
        val out = DoubleArray(2)
        for ((i, sign) in intArrayOf(1, -1).withIndex()) {
            // Rise has a positive hour angle in the NOAA formula. Two passes: the second at the first's instant.
            var est = base + (720 - 4 * lon - eqt) * 60_000
            repeat(2) {
                val s = sun(est)
                val cosH = COS_Z / (cos(lat * D2R) * cos(s[1])) - tan(lat * D2R) * tan(s[1])
                if (cosH > 1) return DOWN
                if (cosH < -1) return UP
                val h = acos(cosH) / D2R
                est = base + (720 - 4 * (lon + sign * h) - s[0]) * 60_000
            }
            out[i] = est
        }
        return out
    }

    /** The equation of time (minutes) and the sun's declination (radians) at an instant. */
    private fun sun(epochMs: Double): DoubleArray {
        val t = (epochMs / DAY_MS + 2440587.5 - 2451545.0) / 36525
        val l0 = ((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360 + 360) % 360
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(m * D2R) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m * D2R) * (0.019993 - 0.000101 * t) + sin(3 * m * D2R) * 0.000289
        val om = 125.04 - 1934.136 * t
        val lam = l0 + c - 0.00569 - 0.00478 * sin(om * D2R)
        val eps = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60 + 0.00256 * cos(om * D2R)
        val decl = asin(sin(eps * D2R) * sin(lam * D2R))
        val y = tan(eps * D2R / 2).let { it * it }
        val eqt = 4 / D2R * (y * sin(2 * l0 * D2R) - 2 * e * sin(m * D2R) +
            4 * e * y * sin(m * D2R) * cos(2 * l0 * D2R) -
            0.5 * y * y * sin(4 * l0 * D2R) - 1.25 * e * e * sin(2 * m * D2R))
        return doubleArrayOf(eqt, decl)
    }
}
