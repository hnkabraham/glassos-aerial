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

data class ScenePeriod(val start: Int, val collection: String)

/** A daily schedule in TV local time; the last entry wraps across midnight. Invalid files use all scenes. */
object SceneSchedule {
    const val FILE = "scenery.json"
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
                ScenePeriod(start, collection)
            }
            if (result.map { it.start }.distinct().size != result.size) emptyList() else result.sortedBy { it.start }
        } catch (_: Exception) { emptyList() }
    }
    fun at(periods: List<ScenePeriod>, minute: Int): String {
        if (periods.isEmpty() || minute !in 0..1439) return "all"
        return (periods.lastOrNull { it.start <= minute } ?: periods.last()).collection
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
