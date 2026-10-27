package io.github.abhishekkaisar.glassos

import org.json.JSONArray
import org.json.JSONObject

/** Stable collection ids shared with the offline Mac manager. */
object SceneCollections {
    val labels = linkedMapOf("all" to "All scenes", "coast" to "Coastlines", "mountains" to "Mountains",
        "forest" to "Forests & gardens", "desert" to "Deserts", "underwater" to "Underwater",
        "space" to "Space", "city" to "Cities", "other" to "Other")
    val modes = labels.keys + "scheduled"
    fun label(id: String) = if (id == "scheduled") "Scheduled" else labels[id] ?: labels.getValue("all")
    fun parse(a: JSONArray?): Set<String> = if (a == null) emptySet() else
        (0 until minOf(a.length(), labels.size)).mapNotNull { a.opt(it) as? String }.filter { it in labels && it != "all" }.toSet()
    fun contains(collection: String, tags: Set<String>) = collection == "all" || collection in tags || (collection == "other" && tags.isEmpty())
}

/**
 * One daily rhythm entry. [start] (local minute of the day) is always set: it is the time 1.5.x builds use, and the
 * fallback when [anchor] ("sunrise" or "sunset", plus [offset] minutes) has no sun time today.
 */
data class ScenePeriod(val start: Int, val collection: String, val anchor: String? = null, val offset: Int = 0)

/**
 * A daily schedule in TV local time; the last entry wraps across midnight. Invalid files use all scenes. Version 1 with
 * optional per-entry `anchor` and `offset`: builds before 1.6.0 read only `start` and `collection`, so they run the
 * fixed times of a file that follows the sun.
 */
object SceneSchedule {
    const val FILE = "scenery.json"
    val ANCHORS = setOf("sunrise", "sunset")
    /** The largest sun offset a file may give, in minutes either way. */
    const val MAX_OFFSET = 240
    fun parse(text: String?): List<ScenePeriod> {
        if (text == null || text.length > 8192) return emptyList()
        return try {
            val o = JSONObject(text)
            if (o.opt("version") != 1) return emptyList()
            val a = o.getJSONArray("schedule")
            if (a.length() !in 1..24) return emptyList()
            val result = (0 until a.length()).map { i ->
                val row = a.getJSONObject(i)
                val start = row.opt("start") as? Int ?: return emptyList()
                val collection = row.opt("collection") as? String ?: return emptyList()
                if (start !in 0..1439 || collection !in SceneCollections.labels) return emptyList()
                // Lenient, unlike start: an anchor this build does not know, or a bad offset, leaves the fixed time.
                val anchor = (row.opt("anchor") as? String)?.takeIf { it in ANCHORS }
                val offset = if (row.has("offset")) row.opt("offset") as? Int else 0
                if (anchor != null && offset != null && offset in -MAX_OFFSET..MAX_OFFSET) ScenePeriod(start, collection, anchor, offset)
                else ScenePeriod(start, collection)
            }
            if (result.map { it.start }.distinct().size != result.size) emptyList() else result.sortedBy { it.start }
        } catch (_: Exception) { emptyList() }
    }
    /**
     * Today's schedule: each sun entry starts at [sun]'s time plus its offset, or at its fixed start when there is no
     * such event today (no place, polar day or night). Sorted by start; on a tie the later entry wins in [at].
     */
    fun resolve(periods: List<ScenePeriod>, sun: SunDay?): List<ScenePeriod> {
        if (periods.none { it.anchor != null }) return periods
        return periods.mapIndexed { i, p -> i to (p.anchor?.let { a -> sun?.minute(a, p.offset)?.let { p.copy(start = it) } } ?: p) }
            .sortedWith(compareBy({ it.second.start }, { it.first })).map { it.second }
    }

    fun at(periods: List<ScenePeriod>, minute: Int): String {
        if (periods.isEmpty() || minute !in 0..1439) return "all"
        return (periods.lastOrNull { it.start <= minute } ?: periods.last()).collection
    }
}

/** What may play: the library and the user's scene choices. */
class SceneChoice(val favorites: Set<String>, val hidden: Set<String>, val favoritesOnly: Boolean, val shuffle: Boolean, val collection: String = "all") {
    companion object {
        /**
         * The choice for [collectionPref] at [minute] of today: "scheduled" becomes the daily rhythm's collection, with
         * today's sun times when [sun] is known. Shared by every place that plays scenery, so they agree.
         */
        fun of(favorites: Set<String>, hidden: Set<String>, favoritesOnly: Boolean, shuffle: Boolean, collectionPref: String,
               schedule: List<ScenePeriod>, sun: SunDay?, minute: Int): SceneChoice {
            val collection = if (collectionPref == "scheduled") SceneSchedule.at(SceneSchedule.resolve(schedule, sun), minute) else collectionPref
            return SceneChoice(favorites, hidden, favoritesOnly, shuffle, collection)
        }
    }
}

/** Pure watchdog policy: no progress for 20 seconds while Home owns the decoder means try another clip. */
class PlaybackProgress {
    private var lastPosition = -1
    private var lastProgress = 0L
    fun reset(now: Long) { lastPosition = -1; lastProgress = now }
    fun stalled(position: Int, now: Long): Boolean {
        if (position != lastPosition) { lastPosition = position; lastProgress = now }
        return now - lastProgress >= 20_000L
    }
}
