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

        /** Night mode's dark layer at each level (low, medium, strong). */
        val ALPHA = floatArrayOf(0.28f, 0.42f, 0.56f)

        /** Sun mode's offsets: 15-minute steps, up to two hours either way (a backup may hold up to [MAX_OFFSET]). */
        const val OFFSET_STEP = 15
        const val OFFSET_LIMIT = 120
        const val MAX_OFFSET = 240

        /**
         * Today's window. Sun mode with both events today: [startOffset] minutes after sunset to [endOffset] minutes
         * after sunrise (negative: before). Otherwise, also on polar days, without a place or when the offsets leave no
         * night or no day, the clock times.
         */
        fun resolve(sunMode: Boolean, fixedStart: Int, fixedEnd: Int, startOffset: Int, endOffset: Int, sun: SunDay?): NightSchedule =
            (if (sunMode) sunWindow(startOffset, endOffset, sun) else null) ?: NightSchedule(fixedStart, fixedEnd)

        /**
         * Today's window from the sun, or null without both events or when the offsets leave no night or no day (short
         * nights and days far north and south): [startOffset] after sunset to [endOffset] after tomorrow's sunrise (a
         * day after today's) must last more than nothing and less than a day.
         */
        fun sunWindow(startOffset: Int, endOffset: Int, sun: SunDay?): NightSchedule? {
            val rise = sun?.sunrise ?: return null
            val set = sun.sunset ?: return null
            val length = rise + SunTimes.DAY_MS + endOffset * 60_000L - (set + startOffset * 60_000L)
            if (length <= 0 || length >= SunTimes.DAY_MS) return null
            val start = sun.minute("sunset", startOffset) ?: return null
            return NightSchedule(start, sun.minute("sunrise", endOffset) ?: return null)
        }

        /** The dark layer's alpha at [minute]: 0 when night mode is off or outside its window. */
        fun dimAlpha(enabled: Boolean, schedule: NightSchedule, level: Int, minute: Int): Float =
            if (enabled && schedule.isNight(minute)) ALPHA[level.coerceIn(0, ALPHA.size - 1)] else 0f

        /** Settings steps a sun offset by 15 minutes, stopping at two hours either way. */
        fun stepOffset(offset: Int, dir: Int): Int {
            val snapped = Math.round(offset / OFFSET_STEP.toFloat()) * OFFSET_STEP
            return (snapped + dir * OFFSET_STEP).coerceIn(-OFFSET_LIMIT, OFFSET_LIMIT)
        }

        /** "Sunset", "Sunset +30" or "Sunrise −15": short enough for a Settings value next to a time. */
        fun formatOffset(anchor: String, offset: Int): String {
            val name = if (anchor == "sunrise") "Sunrise" else "Sunset"
            return when {
                offset > 0 -> "$name +$offset"
                offset < 0 -> "$name \u2212${-offset}"
                else -> name
            }
        }

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
