package io.github.abhishekkaisar.glassos

import org.json.JSONObject
import kotlin.random.Random

// Pure scenery logic (no android.* imports), unit tested on the JVM: clip names, optional metadata, which clips may
// play, and the play order.

/** A clip's on-screen name: the scene and, when known, where it is. */
data class SceneName(val title: String, val location: String = "", val collections: Set<String> = emptySet())

object SceneNames {
    val VIDEO_EXTS = setOf("mp4", "mov", "m4v")
    private val LEADING_NUMBER = Regex("^\\s*\\d{1,4}\\s*[-_.)\\]]*\\s*")
    private val QUALITY = setOf("4k", "8k", "uhd", "hdr", "hdr10", "sdr", "hevc", "h264", "h265", "x264", "x265",
        "1080p", "2160p", "720p", "60fps", "30fps", "24fps", "dv")
    private val SMALL = setOf("a", "an", "and", "at", "by", "de", "del", "in", "of", "on", "the", "to")

    /**
     * "03_golden-gate_bridge 4K.mp4" -> "Golden Gate Bridge"; "07 - Victoria Harbour - Hong Kong.mov" ->
     * "Victoria Harbour" in "Hong Kong". Drops the ordering number and quality tags; title-cases names written all in
     * one case and keeps mixed-case names as they are.
     */
    fun humanize(fileName: String): SceneName {
        var base = fileName.trim()
        val dot = base.lastIndexOf('.')
        if (dot > 0 && base.substring(dot + 1).lowercase() in VIDEO_EXTS + setOf("jpg", "jpeg", "png")) base = base.substring(0, dot)
        val original = base
        val stripped = LEADING_NUMBER.replace(base, "")
        if (stripped.any { it.isLetterOrDigit() }) base = stripped
        // " - " separates the scene from its place; inside slug names hyphens and underscores are spaces.
        val parts = base.split(Regex("\\s+-\\s+|\\s*--\\s*|\\s*__\\s*"), limit = 2)
        val title = clean(parts[0])
        val location = if (parts.size > 1) clean(parts[1]) else ""
        if (title.isEmpty()) return SceneName(clean(original).ifEmpty { original }, "")
        return SceneName(title, location)
    }

    private fun clean(s: String): String {
        // Dots separate words only in names without spaces (a slug like golden.gate).
        var t = s.replace('_', ' ')
        if (!t.contains(' ')) t = t.replace('.', ' ')
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last().lowercase().trim('(', ')', '[', ']') in QUALITY) words.removeAt(words.size - 1)
        val joined = words.joinToString(" ")
        val letters = joined.filter { it.isLetter() }
        val oneCase = letters.isNotEmpty() && (letters == letters.lowercase() || letters == letters.uppercase())
        // A name written in mixed case was typed by a person: its hyphens (Saint-Tropez) stay. In a one-case slug
        // (golden-gate_bridge) a hyphen only joins words.
        if (!oneCase) return joined
        // A lone short capitalised word is an abbreviation (PA, USA, NYC), not shouting.
        if (words.size == 1 && joined.length <= 3 && joined == joined.uppercase()) return joined
        return joined.replace('-', ' ').split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
            val l = w.lowercase()
            if (i > 0 && l in SMALL) l else l.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
    }

    /** A file name GlassOS accepts as a scene id: a plain name, no path, no control characters. */
    fun isValidId(id: String): Boolean = id.isNotEmpty() && id.length <= 255 && id != "." && id != ".." &&
        id.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F }
}

/**
 * Optional `scenes.json` next to the clips, mapping a clip's file name (with or without its extension) to a title and
 * location: `{"01-golden-gate.mp4": {"title": "Golden Gate Bridge", "location": "San Francisco"}}`, or just a title
 * string. Anything malformed is ignored and the names come from the file names.
 */
object SceneMetadata {
    const val FILE = "scenes.json"
    const val MAX_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 1000
    private const val MAX_TEXT = 120

    fun parse(text: String?): Map<String, SceneName> {
        if (text == null || text.length > MAX_BYTES) return emptyMap()
        val o = try { JSONObject(text) } catch (e: Exception) { return emptyMap() }
        val out = HashMap<String, SceneName>()
        val keys = o.keys()
        while (keys.hasNext() && out.size < MAX_ENTRIES) {
            val k = keys.next()
            if (!SceneNames.isValidId(k)) continue
            val v = o.opt(k)
            val name = when (v) {
                is String -> SceneName(v.trim().take(MAX_TEXT))
                is JSONObject -> {
                    val t = (v.opt("title") as? String)?.trim()?.take(MAX_TEXT) ?: ""
                    val l = (v.opt("location") as? String)?.trim()?.take(MAX_TEXT) ?: ""
                    if (t.isEmpty() && l.isEmpty()) null else SceneName(t, l, SceneCollections.parse(v.optJSONArray("collections")))
                }
                else -> null
            } ?: continue
            out[k] = name
        }
        return out
    }

    /** The clip's name: its metadata entry (by file name, then by name without extension), else the humanized name. */
    fun nameFor(fileName: String, meta: Map<String, SceneName>): SceneName {
        val human = SceneNames.humanize(fileName)
        val m = meta[fileName] ?: meta[fileName.substringBeforeLast('.')] ?: return human
        return SceneName(m.title.ifEmpty { human.title }, m.location.ifEmpty { if (m.title.isEmpty()) human.location else "" }, m.collections)
    }
}

/** Which clips may play, from the user's favourites and hidden scenes. */
object SceneFilter {
    enum class Reason { OK, FAVORITES_EMPTY, ALL_HIDDEN, ALL_FAILED, EMPTY_LIBRARY, COLLECTION_EMPTY }

    class Result(val eligible: List<String>, val reason: Reason)

    /**
     * [library] in its play order. Hidden and failed clips never play. With [favoritesOnly], only favourites play; when
     * no favourite can play, every visible clip does instead (reason [Reason.FAVORITES_EMPTY]) so the scenery never
     * goes blank because of a filter.
     */
    fun eligible(library: List<String>, favorites: Set<String>, hidden: Set<String>, favoritesOnly: Boolean,
                 failed: Set<String> = emptySet()): Result {
        if (library.isEmpty()) return Result(emptyList(), Reason.EMPTY_LIBRARY)
        val visible = library.filter { it !in hidden }
        if (visible.isEmpty()) return Result(emptyList(), Reason.ALL_HIDDEN)
        val playable = visible.filter { it !in failed }
        if (playable.isEmpty()) return Result(emptyList(), Reason.ALL_FAILED)
        if (!favoritesOnly) return Result(playable, Reason.OK)
        val favs = playable.filter { it in favorites }
        return if (favs.isEmpty()) Result(playable, Reason.FAVORITES_EMPTY) else Result(favs, Reason.OK)
    }
}

/**
 * The play order. In order mode clips follow the library order and wrap. In shuffle mode each cycle plays every
 * eligible clip once in a random order before any repeats, and a new cycle never starts with the clip that ended the
 * last one. A clip that becomes eligible mid-cycle (unhidden, favourited, newly copied) joins the rest of the current
 * cycle at a random place; one that stops being eligible is never chosen again.
 */
class ScenePlaylist(private val random: Random = Random.Default) {
    var shuffle = false
        private set
    /** The clip playing (or about to), which may have just stopped being eligible: the caller then calls [next]. */
    var current: String? = null
        private set
    private var library: List<String> = emptyList()
    private var eligible: List<String> = emptyList()
    private var eligibleSet: Set<String> = emptySet()
    /** Shuffle: the rest of this cycle, in play order. */
    private val bag = ArrayList<String>()
    /** Shuffle: clips already played this cycle, including [current]. */
    private val played = LinkedHashSet<String>()
    private val history = ArrayList<String>()

    val eligibleIds: List<String> get() = eligible
    /** Shuffle: the clips still to come this cycle (for tests and the state string). */
    val remaining: List<String> get() = bag.toList()

    fun isEligible(id: String?) = id != null && id in eligibleSet

    /** All clips in library order (used for order mode). */
    fun setLibrary(ids: List<String>) {
        library = ids.distinct()
    }

    /** Sets the clips that may play (in library order). Returns whether [current] is still one of them. */
    fun setEligible(ids: List<String>): Boolean {
        eligible = ids.distinct()
        eligibleSet = eligible.toSet()
        bag.retainAll(eligibleSet)
        history.retainAll(eligibleSet)
        if (shuffle) {
            for (id in eligible) if (id !in bag && id !in played && id != current) insertRandom(id)
        }
        return isEligible(current)
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        bag.clear()
        played.clear()
        if (on) {
            current?.let { played.add(it) }
            for (id in eligible.shuffled(random)) if (id != current) bag.add(id)
        }
    }

    /** Advances to the next clip and returns it, or null when nothing may play. */
    fun next(): String? {
        if (eligible.isEmpty()) { moveTo(null); return null }
        val n = if (shuffle) {
            if (bag.isEmpty()) refill()
            bag.removeAt(0)
        } else {
            val cur = current
            val i = if (cur == null) -1 else eligible.indexOf(cur)
            if (i >= 0) eligible[(i + 1) % eligible.size]
            else if (cur == null) eligible[0]
            else {
                // The current clip is no longer eligible: the next eligible one after it in library order.
                val at = library.indexOf(cur)
                eligible.firstOrNull { library.indexOf(it) > at } ?: eligible[0]
            }
        }
        moveTo(n)
        return n
    }

    /** Back to the clip before. Order mode: the previous in order; shuffle: the last one played, if any. */
    fun previous(): String? {
        if (eligible.isEmpty()) return null
        val cur = current
        if (!shuffle) {
            val i = if (cur == null) 0 else eligible.indexOf(cur)
            val p = eligible[((if (i < 0) 0 else i) - 1 + eligible.size) % eligible.size]
            current = p
            return p
        }
        while (history.isNotEmpty()) {
            val p = history.removeAt(history.size - 1)
            if (p !in eligibleSet || p == cur) continue
            if (cur != null && cur in eligibleSet) { bag.add(0, cur); played.remove(cur) }
            current = p
            played.add(p)
            bag.remove(p)
            return p
        }
        return null
    }

    /** The user chose [id]: it plays now and counts as played this cycle. Any library clip may be chosen. */
    fun select(id: String) {
        if (id == current) return
        bag.remove(id)
        moveTo(id)
    }

    private fun moveTo(id: String?) {
        val cur = current
        if (cur != null && cur != id) {
            history.add(cur)
            if (history.size > HISTORY) history.removeAt(0)
        }
        current = id
        if (id != null && shuffle) played.add(id)
    }

    private fun refill() {
        played.clear()
        val order = eligible.shuffled(random).toMutableList()
        val cur = current
        if (order.size > 1 && order[0] == cur) {
            val j = 1 + random.nextInt(order.size - 1)
            order[0] = order[j]; order[j] = cur
        }
        bag.clear()
        bag.addAll(order)
    }

    private fun insertRandom(id: String) { bag.add(random.nextInt(bag.size + 1), id) }

    /** The state worth keeping across app restarts: mode, current clip and the rest of the shuffle cycle. */
    fun save(): String = listOf("v1", if (shuffle) "1" else "0", current ?: "",
        bag.joinToString(SEP), played.joinToString(SEP)).joinToString("\n")

    /** Restores [save]'s state; anything malformed is ignored. Call [setLibrary]/[setEligible] after it. */
    fun restore(state: String?) {
        val lines = state?.split('\n') ?: return
        if (lines.size != 5 || lines[0] != "v1") return
        fun ids(s: String) = if (s.isEmpty()) emptyList() else s.split(SEP).filter { SceneNames.isValidId(it) }
        shuffle = lines[1] == "1"
        current = lines[2].takeIf { SceneNames.isValidId(it) }
        bag.clear(); bag.addAll(ids(lines[3]).distinct())
        played.clear(); played.addAll(ids(lines[4]))
        history.clear()
    }

    companion object {
        private const val SEP = "\u001F"
        private const val HISTORY = 50
    }
}

/**
 * Who may play scenery video ([SceneSession.owner]): a hold by a decoder outside the scenery views (Screen Mirroring's
 * receiver) comes first, then the view that claimed it (the screensaver); null: whichever view can.
 */
class SceneClaims {
    /** The view that claimed the decoder (the screensaver), if any. */
    var view: Any? = null
        private set
    /** The outside decoder holding it (Screen Mirroring), if any. */
    var hold: Any? = null
        private set
    val owner: Any? get() = hold ?: view

    /** [by] claims as a view, or holds as an outside decoder ([outside]). True when the owner changed. */
    fun claim(by: Any, outside: Boolean): Boolean {
        val before = owner
        if (outside) hold = by else view = by
        return owner !== before
    }

    /** [by] lets go, if it held or claimed. True when the owner changed. */
    fun yield(by: Any): Boolean {
        val before = owner
        if (hold === by) hold = null
        if (view === by) view = null
        return owner !== before
    }
}
