package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderLayoutTest {
    private val installed = listOf("com.a.app", "com.b.app", "com.c.app", "com.d.app", "com.e.app", "com.f.app", "com.g.app", "com.h.app")
    private val labels = mapOf("folder:kids" to "Kids", "folder:music" to "Music")
    private fun label(id: String) = labels[id] ?: id.removePrefix("com.").removeSuffix(".app")
    private fun place(hidden: Set<String> = emptySet(), folders: List<AppFolder> = emptyList(), dock: List<String>? = null,
                      order: List<String>? = null, inst: List<String> = installed) =
        HomeLayout.place(inst, hidden, folders, dock, order, 6, ::label) { it.take(3) }

    /** Every installed, unhidden app appears exactly once: on the top row, in the drawer, or in one folder. */
    private fun assertNoneLost(p: HomeLayout.Placement, hidden: Set<String>, inst: List<String> = installed) {
        val seen = (p.dock + p.drawer).filter { !Folders.isFolderId(it) } + p.folderMembers.values.flatten()
        assertEquals(inst.filter { it !in hidden }.sorted(), seen.sorted())
        assertEquals(seen.size, seen.toSet().size)
        assertTrue(p.dock.size <= 6)
    }

    @Test fun foldersHoldTheirMembersOutOfTheGrid() {
        val f = AppFolder("folder:kids", "Kids", listOf("com.b.app", "com.c.app"))
        val p = place(folders = listOf(f), dock = listOf("com.a.app", "com.b.app"))
        assertEquals(listOf("com.a.app"), p.dock)
        assertTrue("folder:kids" in p.drawer)
        assertEquals(listOf("com.b.app", "com.c.app"), p.folderMembers["folder:kids"])
        assertNoneLost(p, emptySet())
    }

    @Test fun addingADockAppToAFolderKeepsItReachable() {
        var folders = listOf(AppFolder("folder:kids", "Kids", emptyList()))
        folders = HomeLayout.addToFolder(folders, "folder:kids", "com.a.app")
        val p = place(folders = folders, dock = listOf("com.a.app", "folder:kids"))
        assertEquals(listOf("folder:kids"), p.dock)
        assertNoneLost(p, emptySet())
    }

    @Test fun anAppIsInOneFolderAtATime() {
        var folders = listOf(AppFolder("folder:kids", "Kids", listOf("com.a.app")), AppFolder("folder:music", "Music", emptyList()))
        folders = HomeLayout.addToFolder(folders, "folder:music", "com.a.app")
        assertEquals(emptyList<String>(), folders[0].members)
        assertEquals(listOf("com.a.app"), folders[1].members)
        // Corrupt saved data listing an app twice still shows it once.
        val bad = listOf(AppFolder("folder:kids", "Kids", listOf("com.a.app")), AppFolder("folder:music", "Music", listOf("com.a.app")))
        assertNoneLost(place(folders = bad), emptySet())
    }

    @Test fun removingAFolderReturnsMembersToItsPlace() {
        val f = AppFolder("folder:kids", "Kids", listOf("com.b.app", "com.c.app"))
        val (rest, order) = HomeLayout.removeFolder(listOf(f), "folder:kids", listOf("com.d.app", "folder:kids", "com.e.app"))
        assertTrue(rest.isEmpty())
        assertEquals(listOf("com.d.app", "com.b.app", "com.c.app", "com.e.app"), order)
        val p = place(folders = rest, dock = listOf("com.a.app"), order = order)
        assertEquals(listOf("com.d.app", "com.b.app", "com.c.app", "com.e.app"), p.drawer.take(4))
        assertNoneLost(p, emptySet())
        // Alphabetical drawer: members simply rejoin it.
        assertEquals(null, HomeLayout.removeFolder(listOf(f), "folder:kids", null).second)
    }

    @Test fun hiddenAndUninstalledMembersStayMembers() {
        val f = AppFolder("folder:kids", "Kids", listOf("com.b.app", "com.c.app", "com.z.app"))
        val p = place(hidden = setOf("com.b.app"), folders = listOf(f))
        assertEquals(listOf("com.c.app"), p.folderMembers["folder:kids"])
        assertNoneLost(p, setOf("com.b.app"))
        // Unhidden, or com.z.app installed later: back in the folder, not loose in the drawer.
        val later = place(folders = listOf(f), inst = installed + "com.z.app")
        assertEquals(listOf("com.b.app", "com.c.app", "com.z.app"), later.folderMembers["folder:kids"])
        assertFalse("com.z.app" in later.drawer)
        assertNoneLost(later, emptySet(), installed + "com.z.app")
    }

    @Test fun emptyFoldersStayVisibleAndBrokenReferencesAreIgnored() {
        val f = AppFolder("folder:kids", "Kids", emptyList())
        val p = place(folders = listOf(f), dock = listOf("folder:gone", "com.nope.app"), order = listOf("folder:gone", "com.h.app"))
        assertTrue("folder:kids" in p.dock + p.drawer)
        assertFalse("folder:gone" in p.dock + p.drawer)
        assertEquals(3, p.dock.size) // the default top row stands in for a top row with nothing left on it
        assertNoneLost(p, emptySet())
    }

    @Test fun savedOrderPutsNewInstallsLast() {
        val p = place(dock = listOf("com.a.app"), order = listOf("com.h.app", "com.c.app"))
        assertEquals(listOf("com.h.app", "com.c.app"), p.drawer.take(2))
        assertNoneLost(p, emptySet())
    }

    @Test fun lenientParseDropsBadEntries() {
        val json = """[{"id":"folder:a1","name":"  Kids  ","members":["com.a.app","bad pkg","com.a.app"]},
            {"id":"folder:a1","name":"dup","members":[]},{"id":"nope","name":"x"},
            {"id":"folder:b2","name":"","members":["com.a.app","com.b.app"]}, 5]"""
        val f = Folders.parseLenient(json)
        assertEquals(listOf("folder:a1", "folder:b2"), f.map { it.id })
        assertEquals("Kids", f[0].name)
        assertEquals(listOf("com.a.app"), f[0].members)
        assertEquals(listOf("com.b.app"), f[1].members)
        assertEquals("Folder", f[1].name)
        assertEquals(f, Folders.parseLenient(Folders.serialize(f)))
        assertTrue(Folders.parseLenient("{").isEmpty())
    }

    @Test fun namesAndIds() {
        assertEquals("Folder 2", Folders.defaultName(listOf(AppFolder("folder:a", "Folder", emptyList()))))
        assertEquals("A b", Folders.cleanName(" A \u0007  b "))
        val id = Folders.newId(emptyList())
        assertTrue(Folders.isValidId(id))
        assertFalse(Folders.isValidPackage(id))
    }
}
