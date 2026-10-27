package io.github.abhishekkaisar.glassos.airplay

// Pure (no android.* imports), unit tested on the JVM.

/**
 * When the launcher restarts a receiver that died without saying so (a crash, not a STOPPED or ERROR status): after
 * 2 s, then 10 s, then 60 s, and after that not at all until the user starts it again. A receiver that stayed up
 * for [stableMs] before dying starts the count over. Times are milliseconds on one monotonic clock.
 */
class SupervisorBackoff(
    private val delaysMs: LongArray = DELAYS_MS,
    private val stableMs: Long = STABLE_MS,
) {
    /** Unexpected deaths since the last reset or stable run. */
    var deaths = 0
        private set

    /** True once the receiver died more often than there are delays; only [reset] clears it. */
    var gaveUp = false
        private set

    private var upSince = NOT_UP

    /** The receiver is up (connected) at [now]. Later calls before the next death keep the first time. */
    fun up(now: Long) {
        if (upSince == NOT_UP) upSince = now
    }

    /** The receiver died unexpectedly at [now]: milliseconds to wait before restarting it, or null to give up. */
    fun died(now: Long): Long? {
        if (gaveUp) return null
        if (upSince != NOT_UP && now - upSince >= stableMs) deaths = 0
        upSince = NOT_UP
        if (deaths >= delaysMs.size) {
            gaveUp = true
            return null
        }
        return delaysMs[deaths++]
    }

    /** A fresh start by the user (or an intentional stop): the next death waits the first delay again. */
    fun reset() {
        deaths = 0
        gaveUp = false
        upSince = NOT_UP
    }

    companion object {
        val DELAYS_MS = longArrayOf(2_000L, 10_000L, 60_000L)
        const val STABLE_MS = 5 * 60_000L
        private const val NOT_UP = Long.MIN_VALUE
    }
}
