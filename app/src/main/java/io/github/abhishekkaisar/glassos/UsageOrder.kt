package io.github.abhishekkaisar.glassos

/** A projection of the saved layout, never a replacement for it. No Android dependencies. */
object UsageOrder {
    fun arrange(base: HomeLayout.Placement, foregroundMillis: Map<String, Long>, maxDock: Int): HomeLayout.Placement {
        fun time(pkg: String) = (foregroundMillis[pkg] ?: 0L).coerceAtLeast(0L)
        fun total(id: String): Long = base.folderMembers[id]?.fold(0L) { sum, pkg ->
            val n = time(pkg)
            if (Long.MAX_VALUE - sum < n) Long.MAX_VALUE else sum + n
        } ?: time(id)
        fun ranked(ids: List<String>, score: (String) -> Long): List<String> {
            val position = ids.withIndex().associate { it.value to it.index }
            return ids.sortedWith(compareByDescending<String> { score(it) }.thenBy { position[it] })
        }
        val all = base.dock + base.drawer
        // A fresh device, inaccessible history, or usage only from hidden/uninstalled apps must not disturb layout.
        if (all.none { total(it) > 0 }) return base
        val sorted = ranked(all, ::total)
        return HomeLayout.Placement(sorted.take(maxDock.coerceAtLeast(0)), sorted.drop(maxDock.coerceAtLeast(0)),
            base.folderMembers.mapValues { (_, members) -> ranked(members, ::time) })
    }
}
