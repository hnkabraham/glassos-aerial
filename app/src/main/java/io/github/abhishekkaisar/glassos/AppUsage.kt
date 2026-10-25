package io.github.abhishekkaisar.glassos

import android.app.usage.UsageStatsManager
import android.content.Context

/** Reads the OS's existing on-device history off the UI thread. Never stores, logs or uploads it. */
object AppUsage {
    enum class State { READY, EMPTY, NO_ACCESS, UNAVAILABLE }
    data class Snapshot(val time: Map<String, Long> = emptyMap(), val state: State = State.EMPTY)
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    fun read(ctx: Context, now: Long = System.currentTimeMillis()): Snapshot {
        if (!HomeWatcher.hasAccess(ctx)) return Snapshot(state = State.NO_ACCESS)
        return try {
            val manager = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return Snapshot(state = State.UNAVAILABLE)
            // Android chooses retained day/week/month buckets. They can extend beyond the requested range, so the
            // UI describes this as about 30 days, rather than pretending it is an exact rolling watch-time counter.
            val stats = manager.queryAndAggregateUsageStats((now - WINDOW_MS).coerceAtLeast(0), now)
                ?: return Snapshot(state = State.UNAVAILABLE)
            val helpers = Platform.usageHelpers(ctx)
            val time = stats.filterKeys { it !in helpers }.mapValues { (_, value) -> value.totalTimeInForeground.coerceAtLeast(0) }
                .filterValues { it > 0 }
            Snapshot(time, if (time.isEmpty()) State.EMPTY else State.READY)
        } catch (e: SecurityException) {
            Snapshot(state = State.NO_ACCESS)
        } catch (e: RuntimeException) {
            Snapshot(state = State.UNAVAILABLE)
        }
    }
}
