package io.github.abhishekkaisar.glassos

import org.junit.Assert.*
import org.junit.Test

class UsageOrderTest {
    @Test fun promotesFrequentAppsAndPreservesManualLayout() {
        val base = HomeLayout.Placement(listOf("a", "b"), listOf("c", "d"), emptyMap())
        val result = UsageOrder.arrange(base, mapOf("a" to 10L, "b" to 20L, "c" to 100L), 2)
        assertEquals(listOf("c", "b"), result.dock)
        assertEquals(listOf("a", "d"), result.drawer)
        assertEquals(listOf("a", "b"), base.dock)
        assertEquals(listOf("c", "d"), base.drawer)
    }

    @Test fun tiesKeepTheSavedOrderAndUnusedAppsFollow() {
        val base = HomeLayout.Placement(listOf("z", "a"), listOf("b", "c"), emptyMap())
        val result = UsageOrder.arrange(base, mapOf("c" to 20L, "z" to 10L, "a" to 10L), 2)
        assertEquals(listOf("c", "z", "a", "b"), result.dock + result.drawer)
    }

    @Test fun missingOrIrrelevantHistoryKeepsEvenAShortDock() {
        val base = HomeLayout.Placement(listOf("a"), listOf("b", "c"), emptyMap())
        assertSame(base, UsageOrder.arrange(base, emptyMap(), 6))
        assertSame(base, UsageOrder.arrange(base, mapOf("hidden" to 1000L, "a" to -1L), 6))
    }

    @Test fun foldersUseOnlyTheirVisibleMembersAndKeepMembership() {
        val base = HomeLayout.Placement(listOf("a"), listOf("folder:tv", "b"),
            mapOf("folder:tv" to listOf("c", "d")))
        val result = UsageOrder.arrange(base, mapOf("a" to 50L, "c" to 20L, "d" to 40L, "hidden" to 1000L), 2)
        assertEquals(listOf("folder:tv", "a"), result.dock)
        assertEquals(listOf("b"), result.drawer)
        assertEquals(listOf("d", "c"), result.folderMembers["folder:tv"])
        assertEquals(listOf("c", "d"), base.folderMembers["folder:tv"])
    }

    @Test fun folderTotalsCannotOverflowIntoNegativeUsage() {
        val base = HomeLayout.Placement(listOf("a"), listOf("folder:tv"),
            mapOf("folder:tv" to listOf("b", "c")))
        assertEquals(listOf("folder:tv"), UsageOrder.arrange(base,
            mapOf("a" to 9L, "b" to Long.MAX_VALUE, "c" to 1L), 1).dock)
    }

    @Test fun rankingDoesNotLoseOrDuplicateAppsAcrossDockDrawerAndFolders() {
        val installed = (1..40).map { "app.p$it" }
        val hidden = setOf("app.p4", "app.p18")
        val base = HomeLayout.place(installed, hidden,
            listOf(AppFolder("folder:tv", "TV", installed.take(5))), installed.takeLast(3), null, 6,
            { it }, { it.take(6) })
        val result = UsageOrder.arrange(base, installed.withIndex().associate { it.value to it.index.toLong() }, 6)
        val visible = result.dock + result.drawer
        val apps = visible.filterNot { Folders.isFolderId(it) } + result.folderMembers.values.flatten()
        assertEquals(6, result.dock.size)
        assertEquals(installed.toSet() - hidden, apps.toSet())
        assertEquals(apps.size, apps.distinct().size)
    }
}
