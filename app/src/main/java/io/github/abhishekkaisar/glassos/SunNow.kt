package io.github.abhishekkaisar.glassos

import java.util.Calendar
import java.util.TimeZone

/**
 * Today's sun times on this TV, for Night mode and the daily rhythm. The place is the one GlassOS Manager set, else
 * the TV's time zone's city (an estimate, see [ZoneCoords]), else none: then everything keeps its clock times.
 * Worked out once per local date, zone and place; the minute tick only compares that key. Called from the main
 * thread and from [ManagerProvider]'s binder thread.
 */
object SunNow {
    private var key: String? = null
    private var day: SunDay? = null

    /** Where sun times come from now, or null (a zone without a place, such as UTC). */
    @Synchronized fun place(prefs: Prefs): Place? = prefs.sunPlace ?: ZoneCoords.lookup(TimeZone.getDefault().id)

    /** Today's sunrise and sunset, or null without a place. */
    @Synchronized fun today(prefs: Prefs, nowMs: Long = System.currentTimeMillis()): SunDay? {
        val p = place(prefs) ?: return null
        val tz = TimeZone.getDefault()
        val k = "${dayKey(nowMs, tz)} ${p.lat} ${p.lon}"
        if (k != key) {
            day = SunTimes.day(nowMs, tz, p.lat, p.lon)
            key = k
        }
        return day
    }

    /** The local date and zone at [nowMs] ("2026-299 America/Los_Angeles"): today's sun times change with them. */
    fun dayKey(nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)} ${tz.id}"
    }

    /** The place changed: work the times out again on the next call. */
    @Synchronized fun invalidate() {
        key = null
        day = null
    }
}
