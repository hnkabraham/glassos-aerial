package io.github.abhishekkaisar.glassos

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

// Pure (no android.* imports), unit tested on the JVM.

/** An app folder: shown as one tile on the top row or in the drawer. [members] are package names, in order. */
data class AppFolder(val id: String, val name: String, val members: List<String>)

object Folders {
    /** Folder ids live in the same lists as package names (dock, order); the colon never occurs in a package name. */
    const val PREFIX = "folder:"
    const val MAX_FOLDERS = 50
    const val MAX_MEMBERS = 200
    const val MAX_NAME = 40
    private val ID = Regex("^folder:[a-z0-9]{1,24}$")
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun isFolderId(s: String) = s.startsWith(PREFIX)
    fun isValidId(s: String) = ID.matches(s)
    fun isValidPackage(s: String) = s.length <= 255 && PKG.matches(s)

    /** A folder name as typed: trimmed, single spaces, no control characters, at most [MAX_NAME] characters. */
    fun cleanName(s: String): String = s.filter { it.code >= 0x20 && it.code != 0x7F }.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()

    fun newId(existing: Collection<String>, random: Random = Random.Default): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        while (true) {
            val id = PREFIX + (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            if (id !in existing) return id
        }
    }

    /** "Folder", "Folder 2", ... the first not already used. */
    fun defaultName(folders: List<AppFolder>): String {
        val names = folders.map { it.name.lowercase() }.toSet()
        if ("folder" !in names) return "Folder"
        var n = 2
        while ("folder $n" in names) n++
        return "Folder $n"
    }

    fun toJson(folders: List<AppFolder>): JSONArray = JSONArray().apply {
        for (f in folders) put(JSONObject().put("id", f.id).put("name", f.name).put("members", JSONArray(f.members)))
    }

    /**
     * Folders as saved in preferences, read leniently: invalid entries are skipped, a package already in an earlier
     * folder is dropped from later ones, and duplicates and limits are enforced, so the grid never sees bad data.
     * (Backups are validated strictly instead, see [BackupCodec].)
     */
    fun parseLenient(text: String?): List<AppFolder> {
        if (text.isNullOrEmpty()) return emptyList()
        val arr = try { JSONArray(text) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<AppFolder>()
        val ids = HashSet<String>()
        val taken = HashSet<String>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_FOLDERS) break
            val o = arr.opt(i) as? JSONObject ?: continue
            val id = o.opt("id") as? String ?: continue
            if (!isValidId(id) || !ids.add(id)) continue
            val name = cleanName(o.opt("name") as? String ?: "").ifEmpty { "Folder" }
            val m = o.opt("members") as? JSONArray ?: JSONArray()
            val members = ArrayList<String>()
            for (j in 0 until m.length()) {
                val p = m.opt(j) as? String ?: continue
                if (members.size < MAX_MEMBERS && isValidPackage(p) && taken.add(p)) members.add(p)
            }
            out.add(AppFolder(id, name, members))
        }
        return out
    }

    fun serialize(folders: List<AppFolder>): String = toJson(folders).toString()
}

/**
 * Where every app goes on the home: the top row, the drawer or a folder. Pure, so the "no app is ever lost"
 * invariant is unit tested: each installed, unhidden app appears exactly once, on its own or inside one folder.
 */
object HomeLayout {
    class Placement(
        /** Top-level ids (packages and folder ids) on the top row, at most [maxDock]. */
        val dock: List<String>,
        /** Top-level ids in the drawer, in order. */
        val drawer: List<String>,
        /** Folder id to its installed, unhidden members. */
        val folderMembers: Map<String, List<String>>
    )

    /**
     * [installed] in alphabetical order; [labelOf] gives a folder's or app's label for alphabetical placement.
     * [savedDock] null means no saved dock: [defaultDock] chooses. [savedOrder] null keeps the drawer alphabetical.
     */
    fun place(installed: List<String>, hidden: Set<String>, folders: List<AppFolder>, savedDock: List<String>?,
              savedOrder: List<String>?, maxDock: Int, labelOf: (String) -> String,
              defaultDock: (List<String>) -> List<String>): Placement {
        val shown = installed.filter { it !in hidden }
        val shownSet = shown.toSet()
        val inFolder = HashSet<String>()
        val members = LinkedHashMap<String, List<String>>()
        for (f in folders) {
            val list = f.members.filter { it in shownSet && inFolder.add(it) }
            members[f.id] = list
        }
        // Top-level items: apps not in a folder, and every folder (even empty, so it can be filled or removed).
        val loose = shown.filter { it !in inFolder }
        val top = (loose + folders.map { it.id }).sortedBy { labelOf(it).lowercase() }
        val topSet = top.toSet()
        var dock = (savedDock ?: defaultDock(top)).filter { it in topSet }.distinct().take(maxDock)
        if (dock.isEmpty()) dock = defaultDock(top).filter { it in topSet }.take(maxDock)
        val dockSet = dock.toSet()
        val rest = top.filter { it !in dockSet }
        val drawer = if (savedOrder.isNullOrEmpty()) rest else {
            val restSet = rest.toSet()
            val seen = HashSet<String>()
            val ordered = savedOrder.filter { it in restSet && seen.add(it) }
            ordered + rest.filter { it !in seen }
        }
        return Placement(dock, drawer, members)
    }

    /** [pkg] moved into folder [folderId] (out of any other folder). */
    fun addToFolder(folders: List<AppFolder>, folderId: String, pkg: String): List<AppFolder> = folders.map { f ->
        when {
            f.id == folderId -> if (pkg in f.members || f.members.size >= Folders.MAX_MEMBERS) f else f.copy(members = f.members + pkg)
            pkg in f.members -> f.copy(members = f.members - pkg)
            else -> f
        }
    }

    fun removeFromFolder(folders: List<AppFolder>, pkg: String): List<AppFolder> =
        folders.map { if (pkg in it.members) it.copy(members = it.members - pkg) else it }

    /**
     * Removes a folder; its members return to the drawer at the folder's place in [order] (or at the front when the
     * folder sat on the top row). Returns the new folders and order.
     */
    fun removeFolder(folders: List<AppFolder>, folderId: String, order: List<String>?): Pair<List<AppFolder>, List<String>?> {
        val f = folders.firstOrNull { it.id == folderId } ?: return folders to order
        val rest = folders.filter { it.id != folderId }
        if (order == null) return rest to null
        val i = order.indexOf(folderId)
        val without = order.filter { it != folderId && it !in f.members }
        val at = if (i < 0) 0 else order.take(i).count { it != folderId && it !in f.members }
        return rest to (without.take(at) + f.members + without.drop(at))
    }
}
