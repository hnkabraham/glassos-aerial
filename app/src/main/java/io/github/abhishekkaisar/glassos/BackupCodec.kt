package io.github.abhishekkaisar.glassos

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Pure (no android.* imports), unit tested on the JVM.

/** Display choices that travel in a backup. A null field was not in the backup and keeps its current value. */
data class DisplayBackup(
    val sound: Boolean? = null,
    val topShelf: String? = null,
    val scene: String? = null,
    val cinemaDelaySec: Int? = null,
    val cinemaClock: Boolean? = null,
    val nightEnabled: Boolean? = null,
    val nightStart: Int? = null,
    val nightEnd: Int? = null,
    val nightLevel: Int? = null,
    val reduceTransparency: Boolean? = null,
    val reduceMotion: Boolean? = null,
    val mostUsed: Boolean? = null,
    val theme: String? = null,
    val movingClock: Boolean? = null,
    val customArt: Boolean? = null
)

data class SceneryBackup(
    val shuffle: Boolean? = null,
    val favoritesOnly: Boolean? = null,
    val labels: Boolean? = null,
    val favorites: List<String>? = null,
    val hidden: List<String>? = null,
    val collection: String? = null
)

/**
 * Everything a backup may hold: a whitelist of layout and look preferences. Never credentials (TMDB key), the
 * user's name or country, setup and permission state, home routing, the sleep timer, playback positions or anything
 * about the device. A null section was missing and leaves the current settings alone.
 */
data class BackupData(
    /** Top row ids (packages and folder ids); null: not saved (the default top row). */
    val dock: List<String>? = null,
    /** Drawer order; null: alphabetical. */
    val order: List<String>? = null,
    val hidden: List<String>? = null,
    val folders: List<AppFolder>? = null,
    val display: DisplayBackup? = null,
    val scenery: SceneryBackup? = null,
    /** The backup had a layout section: its top row, order and hidden apps replace the current ones as a whole. */
    val hasLayout: Boolean = dock != null || order != null || hidden != null
) {
    /** One line for the import confirmation. */
    fun summary(): String {
        val parts = ArrayList<String>()
        dock?.let { parts.add(plural(it.size, "top row item")) }
        folders?.let { parts.add(plural(it.size, "folder")) }
        hidden?.let { parts.add(plural(it.size, "hidden app")) }
        scenery?.favorites?.let { parts.add(plural(it.size, "favourite scene")) }
        scenery?.hidden?.let { parts.add(plural(it.size, "hidden scene")) }
        if (display != null) parts.add("display settings")
        return parts.joinToString(", ").ifEmpty { "no settings" }
    }

    private fun plural(n: Int, w: String) = if (n == 1) "1 $w" else "$n ${w}s"
}

sealed class BackupResult {
    class Ok(val data: BackupData, val exportedAt: String) : BackupResult()
    class Error(val message: String) : BackupResult()
}

/**
 * Versioned JSON backups. [decode] checks the whole document (format, version, size, counts, types, identifiers and
 * how folders, the top row and the drawer refer to each other) before returning anything, so an import either
 * applies completely or not at all. Apps and scenes that are not on this TV are kept: they show up if installed or
 * copied later.
 */
object BackupCodec {
    const val FORMAT = "glassos-aerial-backup"
    const val VERSION = 1
    const val MAX_BYTES = 256 * 1024
    private const val MAX_LIST = 2000

    val TOP_SHELF_IDS = setOf("tmdb", "apple", "scenes", "aerial")
    /** Built-in fallback backgrounds; a picture path (file:...) is specific to one TV and is not exported. */
    val SCENE_IDS = setOf("scene:desert", "scene:ocean", "scene:aurora", "scene:dark", "hero:photos")
    val CINEMA_DELAYS = listOf(0, 30, 60, 120, 300, 600, 1800)
    const val NIGHT_LEVELS = 3

    fun encode(d: BackupData, appVersion: String, nowMillis: Long): String {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("version", VERSION)
        o.put("app", appVersion)
        o.put("exportedAt", iso(nowMillis))
        val folderIds = d.folders?.map { it.id }?.toSet() ?: emptySet()
        val members = d.folders?.flatMap { it.members }?.toSet() ?: emptySet()
        // Never write a reference the decoder would refuse.
        fun clean(l: List<String>) = l.filter { (if (Folders.isFolderId(it)) it in folderIds else Folders.isValidPackage(it)) && it !in members }.distinct()
        val layout = JSONObject()
        d.dock?.let { layout.put("dock", JSONArray(clean(it).take(HomeScreen.COLS))) }
        d.order?.let { layout.put("order", JSONArray(clean(it).take(MAX_LIST))) }
        d.hidden?.let { layout.put("hidden", JSONArray(it.filter { p -> Folders.isValidPackage(p) }.distinct().take(MAX_LIST))) }
        if (d.hasLayout) o.put("layout", layout)
        d.folders?.let { o.put("folders", Folders.toJson(it.take(Folders.MAX_FOLDERS))) }
        d.display?.let { s ->
            val j = JSONObject()
            s.sound?.let { j.put("sound", it) }
            s.topShelf?.takeIf { it in TOP_SHELF_IDS }?.let { j.put("topShelf", it) }
            s.scene?.takeIf { it in SCENE_IDS }?.let { j.put("background", it) }
            s.cinemaDelaySec?.takeIf { it in CINEMA_DELAYS }?.let { j.put("cinemaDelaySec", it) }
            s.theme?.takeIf { it in Themes.ids }?.let { j.put("theme", it) }
            s.movingClock?.let { j.put("movingClock", it) }
            s.customArt?.let { j.put("customArt", it) }
            s.cinemaClock?.let { j.put("cinemaClock", it) }
            val n = JSONObject()
            s.nightEnabled?.let { n.put("enabled", it) }
            s.nightStart?.let { n.put("start", it) }
            s.nightEnd?.let { n.put("end", it) }
            s.nightLevel?.let { n.put("level", it) }
            j.put("night", n)
            s.reduceTransparency?.let { j.put("reduceTransparency", it) }
            s.reduceMotion?.let { j.put("reduceMotion", it) }
            s.mostUsed?.let { j.put("mostUsed", it) }
            o.put("display", j)
        }
        d.scenery?.let { s ->
            val j = JSONObject()
            s.collection?.takeIf { it in SceneCollections.modes }?.let { j.put("collection", it) }
            s.shuffle?.let { j.put("shuffle", it) }
            s.favoritesOnly?.let { j.put("favoritesOnly", it) }
            s.labels?.let { j.put("labels", it) }
            s.favorites?.let { j.put("favorites", JSONArray(it.filter { n -> SceneNames.isValidId(n) }.distinct().take(MAX_LIST))) }
            s.hidden?.let { j.put("hidden", JSONArray(it.filter { n -> SceneNames.isValidId(n) }.distinct().take(MAX_LIST))) }
            o.put("scenery", j)
        }
        return o.toString(2)
    }

    /** A backup that would be larger than [MAX_BYTES] in UTF-8, the size [decode] and reading a file refuse. */
    class TooLarge(val bytes: Int) : java.io.IOException(
        "The backup would be ${(bytes + 1023) / 1024} KB, over the ${MAX_BYTES / 1024} KB limit. Remove some folders, hidden apps " +
            "or scene choices and try again. No file was written.")

    /** The file's bytes for [text] (UTF-8), or [TooLarge] before anything is written. */
    fun fileBytes(text: String): ByteArray {
        // Each character is at least one byte: a longer text is too large without encoding it.
        if (text.length > MAX_BYTES) throw TooLarge(text.length)
        val b = text.toByteArray(Charsets.UTF_8)
        if (b.size > MAX_BYTES) throw TooLarge(b.size)
        return b
    }

    private fun tooLarge(bytes: Int) = "The file is too large for a backup (${(bytes + 1023) / 1024} KB; the limit is ${MAX_BYTES / 1024} KB)."

    private class Invalid(msg: String) : Exception(msg)

    private fun fail(msg: String): Nothing = throw Invalid(msg)

    fun decode(text: String): BackupResult = try {
        // The limit is in UTF-8 bytes, as written to and read from the file.
        if (text.length > MAX_BYTES) fail(tooLarge(text.length))
        text.toByteArray(Charsets.UTF_8).size.let { if (it > MAX_BYTES) fail(tooLarge(it)) }
        val o = try { JSONObject(text) } catch (e: Exception) { fail("The file is not a GlassOS Aerial backup: it is not valid JSON.") }
        if (o.opt("format") != FORMAT) fail("The file is not a GlassOS Aerial backup (no \"format\": \"$FORMAT\").")
        val version = int(o, "version", "version") ?: fail("The backup has no version number.")
        if (version > VERSION) fail("This backup is from a newer GlassOS Aerial (format $version). Update the app to import it.")
        if (version < 1) fail("The backup's version ($version) is not valid.")
        val exportedAt = o.opt("exportedAt") as? String ?: ""

        val layout = obj(o, "layout")
        val dock = layout?.let { ids(it, "dock", "layout.dock", HomeScreen.COLS) }
        val order = layout?.let { ids(it, "order", "layout.order", MAX_LIST) }
        val hidden = layout?.let { list(it, "hidden", "layout.hidden", MAX_LIST) { p, at -> if (!Folders.isValidPackage(p)) fail("$at is not a package name.") } }?.distinct()
        val folders = folders(o)

        val folderIds = folders?.map { it.id }?.toSet() ?: emptySet()
        val members = HashMap<String, String>()
        folders?.forEach { f -> f.members.forEach { members[it] = f.name } }
        for ((name, l) in listOf("layout.dock" to dock, "layout.order" to order)) {
            l?.forEachIndexed { i, id ->
                if (Folders.isFolderId(id) && id !in folderIds) fail("$name[$i] refers to a folder that is not in the backup ($id).")
                members[id]?.let { fail("$name[$i]: $id is in the folder \"$it\" and cannot also be listed on its own.") }
            }
        }
        BackupResult.Ok(BackupData(dock, order, hidden, folders, display(o), scenery(o), hasLayout = layout != null), exportedAt)
    } catch (e: Invalid) {
        BackupResult.Error(e.message ?: "The backup is not valid.")
    } catch (e: Exception) {
        BackupResult.Error("The backup could not be read (${e.javaClass.simpleName}).")
    }

    private fun folders(o: JSONObject): List<AppFolder>? {
        val arr = o.opt("folders") ?: return null
        if (arr !is JSONArray) fail("\"folders\" must be a list.")
        if (arr.length() > Folders.MAX_FOLDERS) fail("The backup has ${arr.length()} folders; the limit is ${Folders.MAX_FOLDERS}.")
        val ids = HashSet<String>()
        val owner = HashMap<String, String>()
        return (0 until arr.length()).map { i ->
            val at = "folders[$i]"
            val f = arr.opt(i) as? JSONObject ?: fail("$at must be an object.")
            val id = f.opt("id") as? String ?: fail("$at has no id.")
            if (!Folders.isValidId(id)) fail("$at has an invalid id ($id).")
            if (!ids.add(id)) fail("$at repeats the folder id $id.")
            val raw = f.opt("name") as? String ?: fail("$at has no name.")
            val name = Folders.cleanName(raw)
            if (name.isEmpty()) fail("$at has an empty name.")
            if (raw.trim().length > Folders.MAX_NAME) fail("$at's name is longer than ${Folders.MAX_NAME} characters.")
            val m = list(f, "members", "$at.members", Folders.MAX_MEMBERS) { p, a -> if (!Folders.isValidPackage(p)) fail("$a is not a package name.") } ?: emptyList()
            m.forEach { p ->
                owner[p]?.let { other -> fail("$p is in more than one folder (\"$other\" and \"$name\").") }
                owner[p] = name
            }
            AppFolder(id, name, m)
        }
    }

    private fun display(o: JSONObject): DisplayBackup? {
        val d = obj(o, "display") ?: return null
        val shelf = str(d, "topShelf", "display.topShelf")
        if (shelf != null && shelf !in TOP_SHELF_IDS) fail("display.topShelf ($shelf) is not a Top Shelf source.")
        val scene = str(d, "background", "display.background")
        if (scene != null && scene !in SCENE_IDS) fail("display.background ($scene) is not a built-in background.")
        val delay = int(d, "cinemaDelaySec", "display.cinemaDelaySec")
        if (delay != null && delay !in CINEMA_DELAYS) fail("display.cinemaDelaySec must be one of ${CINEMA_DELAYS.joinToString(", ")}.")
        val n = obj(d, "night", "display.night")
        val start = n?.let { int(it, "start", "display.night.start") }
        val end = n?.let { int(it, "end", "display.night.end") }
        val level = n?.let { int(it, "level", "display.night.level") }
        for ((k, v) in listOf("start" to start, "end" to end)) if (v != null && v !in 0 until NightSchedule.DAY) fail("display.night.$k must be minutes of the day (0 to 1439).")
        if (level != null && level !in 0 until NIGHT_LEVELS) fail("display.night.level must be 0, 1 or 2.")
        val theme = str(d, "theme", "display.theme")
        if (theme != null && theme !in Themes.ids) fail("Unknown glass theme.")
        return DisplayBackup(
            sound = bool(d, "sound", "display.sound"), topShelf = shelf, scene = scene, cinemaDelaySec = delay,
            cinemaClock = bool(d, "cinemaClock", "display.cinemaClock"),
            nightEnabled = n?.let { bool(it, "enabled", "display.night.enabled") }, nightStart = start, nightEnd = end, nightLevel = level,
            reduceTransparency = bool(d, "reduceTransparency", "display.reduceTransparency"),
            reduceMotion = bool(d, "reduceMotion", "display.reduceMotion"),
            mostUsed = bool(d, "mostUsed", "display.mostUsed"), theme = theme,
            movingClock = bool(d, "movingClock", "display.movingClock"),
            customArt = bool(d, "customArt", "display.customArt"))
    }

    private fun scenery(o: JSONObject): SceneryBackup? {
        val s = obj(o, "scenery") ?: return null
        val check = { n: String, at: String -> if (!SceneNames.isValidId(n)) fail("$at is not a plain file name.") }
        val collection = str(s, "collection", "scenery.collection")
        if (collection != null && collection !in SceneCollections.modes) fail("Unknown scenery collection.")
        return SceneryBackup(collection = collection,
            shuffle = bool(s, "shuffle", "scenery.shuffle"), favoritesOnly = bool(s, "favoritesOnly", "scenery.favoritesOnly"),
            labels = bool(s, "labels", "scenery.labels"),
            favorites = list(s, "favorites", "scenery.favorites", MAX_LIST, check)?.distinct(),
            hidden = list(s, "hidden", "scenery.hidden", MAX_LIST, check)?.distinct())
    }

    // ---------- Typed reads (opt + type checks, identical on Android's and the reference org.json) ----------

    private fun obj(o: JSONObject, k: String, at: String = k): JSONObject? = when (val v = o.opt(k)) {
        null, JSONObject.NULL -> null
        is JSONObject -> v
        else -> fail("\"$at\" must be an object.")
    }

    private fun bool(o: JSONObject, k: String, at: String): Boolean? = when (val v = o.opt(k)) {
        null, JSONObject.NULL -> null
        is Boolean -> v
        else -> fail("$at must be true or false.")
    }

    private fun str(o: JSONObject, k: String, at: String): String? = when (val v = o.opt(k)) {
        null, JSONObject.NULL -> null
        is String -> v
        else -> fail("$at must be text.")
    }

    private fun int(o: JSONObject, k: String, at: String): Int? = when (val v = o.opt(k)) {
        null, JSONObject.NULL -> null
        is Int -> v
        is Number -> if (v.toDouble() % 1.0 == 0.0 && v.toDouble() in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) v.toInt() else fail("$at must be a whole number.")
        else -> fail("$at must be a number.")
    }

    private fun list(o: JSONObject, k: String, at: String, max: Int, check: (String, String) -> Unit): List<String>? {
        val v = o.opt(k)
        if (v == null || v == JSONObject.NULL) return null
        if (v !is JSONArray) fail("$at must be a list.")
        if (v.length() > max) fail("$at has ${v.length()} entries; the limit is $max.")
        return (0 until v.length()).map { i ->
            val s = v.opt(i) as? String ?: fail("$at[$i] must be text.")
            check(s, "$at[$i]")
            s
        }
    }

    /** Top row or drawer ids: packages or folder ids, no repeats. */
    private fun ids(o: JSONObject, k: String, at: String, max: Int): List<String>? {
        val l = list(o, k, at, max) { s, a -> if (!Folders.isValidPackage(s) && !Folders.isValidId(s)) fail("$a is not a package name or folder id.") }
        l?.groupingBy { it }?.eachCount()?.entries?.firstOrNull { it.value > 1 }?.let { fail("$at lists ${it.key} more than once.") }
        return l
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
