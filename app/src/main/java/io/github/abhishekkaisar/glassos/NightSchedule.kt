package io.github.abhishekkaisar.glassos

// Pure (no android.* imports), unit tested on the JVM.

/**
 * Night mode's daily window in local minutes of the day (0..1439). [start] is inclusive and [end] exclusive; a
 * window whose start is after its end runs past midnight (22:00 to 07:00). Equal start and end is an empty window:
 * night mode never turns on (the Settings rows never produce it, an imported backup could).
 */
class NightSchedule(start: Int, end: Int) {
    val start = start.coerceIn(0, DAY - 1)
    val end = end.coerceIn(0, DAY - 1)

    fun isNight(minuteOfDay: Int): Boolean {
        val m = ((minuteOfDay % DAY) + DAY) % DAY
        return when {
            start == end -> false
            start < end -> m in start until end
            else -> m >= start || m < end
        }
    }

    /** Minutes from [minuteOfDay] until the window next opens or closes (1..1440); for display and tests. */
    fun minutesToNextChange(minuteOfDay: Int): Int {
        if (start == end) return DAY
        val m = ((minuteOfDay % DAY) + DAY) % DAY
        fun until(t: Int) = ((t - m) % DAY + DAY) % DAY
        val a = until(start).takeIf { it > 0 } ?: DAY
        val b = until(end).takeIf { it > 0 } ?: DAY
        return minOf(a, b)
    }

    companion object {
        const val DAY = 24 * 60
        const val STEP = 30
        const val DEFAULT_START = 22 * 60
        const val DEFAULT_END = 7 * 60

        /** Settings steps a time by half an hour, wrapping around midnight. */
        fun step(minute: Int, dir: Int): Int = (((minute / STEP) * STEP + dir * STEP) % DAY + DAY) % DAY

        /** "22:00" or "10:00 PM". */
        fun format(minute: Int, is24h: Boolean): String {
            val h = minute / 60
            val m = minute % 60
            if (is24h) return "%d:%02d".format(h, m)
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
        }
    }
}
