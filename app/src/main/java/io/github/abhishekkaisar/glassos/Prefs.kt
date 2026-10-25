package io.github.abhishekkaisar.glassos

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) { sp.edit().putBoolean("sound", v).apply() }

    /**
     * Fallback background, shown when the Top Shelf has no title art: a built-in scene (scene:..., default),
     * the user's own photos in Pictures/Glass (hero:photos) or one image (file:...).
     */
    var scene: String
        get() = sp.getString("fallback", DEFAULT_SCENE) ?: DEFAULT_SCENE
        set(v) { sp.edit().putString("fallback", v).apply() }

    /** Top Shelf source: [HeroRepo.SRC_TMDB] (default), [HeroRepo.SRC_APPLE] or [HeroRepo.SRC_SCENES]. */
    var topShelf: String
        get() = sp.getString("topShelf", HeroRepo.SRC_TMDB) ?: HeroRepo.SRC_TMDB
        set(v) { sp.edit().putString("topShelf", v).apply() }

    /** TMDB key entered in Settings; the build's own key (BuildConfig.TMDB_API_KEY) is used when this is empty. */
    var tmdbKey: String
        get() = sp.getString("tmdbKey", "") ?: ""
        set(v) { sp.edit().putString("tmdbKey", v.trim()).apply() }

    val effectiveTmdbKey: String get() = tmdbKey.ifEmpty { BuildConfig.TMDB_API_KEY }

    /** ISO 3166 country code for the Top Shelf region (streaming availability). */
    var country: String
        get() = sp.getString("country", "") ?: ""
        set(v) { sp.edit().putString("country", v).apply() }

    var replaceHome: Boolean
        get() = sp.getBoolean("replaceHome", true)
        set(v) { sp.edit().putBoolean("replaceHome", v).apply() }

    var dock: List<String>?
        get() = sp.getString("dock", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) { sp.edit().putString("dock", v?.joinToString(",")).apply() }

    /** The drawer's order once the user has moved an app (null: alphabetical). Apps not in it go at the end. */
    var order: List<String>?
        get() = sp.getString("order", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) { sp.edit().putString("order", v?.joinToString(",")).apply() }

    /** Automatic order is a projection; dock/order/folder member lists keep the user's manual layout. */
    var mostUsed: Boolean
        get() = sp.getBoolean("mostUsed", false)
        set(v) { sp.edit().putBoolean("mostUsed", v).apply() }

    /** Apps left out of the drawer, the top row and the top row picker (Settings > Hidden apps). */
    var hidden: Set<String>
        get() = sp.getString("hidden", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) { sp.edit().putString("hidden", v.joinToString(",")).apply() }

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) { sp.edit().putString("name", v).apply() }

    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) { sp.edit().putBoolean("setupDone", v).apply() }

    var askedStorage: Boolean
        get() = sp.getBoolean("askedStorage", false)
        set(v) { sp.edit().putBoolean("askedStorage", v).apply() }

    // ---------- Scenery (local aerial videos) ----------

    var sceneCollection: String
        get() = (sp.getString("sceneCollection", "all") ?: "all").takeIf { it in SceneCollections.modes } ?: "all"
        set(v) { sp.edit().putString("sceneCollection", v.takeIf { it in SceneCollections.modes } ?: "all").apply() }

    var sceneShuffle: Boolean
        get() = sp.getBoolean("sceneShuffle", false)
        set(v) { sp.edit().putBoolean("sceneShuffle", v).apply() }

    var sceneFavoritesOnly: Boolean
        get() = sp.getBoolean("sceneFavOnly", false)
        set(v) { sp.edit().putBoolean("sceneFavOnly", v).apply() }

    /** The scene's name shown for a few seconds when the clip changes. */
    var sceneLabels: Boolean
        get() = sp.getBoolean("sceneLabels", true)
        set(v) { sp.edit().putBoolean("sceneLabels", v).apply() }

    /** Clip file names. JSON arrays, not commas: file names may contain commas. */
    var sceneFavorites: Set<String>
        get() = names("sceneFav")
        set(v) { sp.edit().putString("sceneFav", org.json.JSONArray(v.toList()).toString()).apply() }

    var sceneHidden: Set<String>
        get() = names("sceneHidden")
        set(v) { sp.edit().putString("sceneHidden", org.json.JSONArray(v.toList()).toString()).apply() }

    private fun names(key: String): Set<String> = try {
        val a = org.json.JSONArray(sp.getString(key, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.opt(it) as? String }.filter { SceneNames.isValidId(it) }.toSet()
    } catch (e: Exception) { emptySet() }

    // ---------- Cinema, night and effects ----------

    /** Seconds without input before the home fades to its scenery; 0 is off. */
    var cinemaDelaySec: Int
        get() = sp.getInt("cinemaDelay", 60).takeIf { it in BackupCodec.CINEMA_DELAYS } ?: 60
        set(v) { sp.edit().putInt("cinemaDelay", v).apply() }

    var cinemaClock: Boolean
        get() = sp.getBoolean("cinemaClock", true)
        set(v) { sp.edit().putBoolean("cinemaClock", v).apply() }

    var nightEnabled: Boolean
        get() = sp.getBoolean("night", false)
        set(v) { sp.edit().putBoolean("night", v).apply() }

    /** Minutes of the day, local time. */
    var nightStart: Int
        get() = sp.getInt("nightStart", NightSchedule.DEFAULT_START).coerceIn(0, NightSchedule.DAY - 1)
        set(v) { sp.edit().putInt("nightStart", v).apply() }

    var nightEnd: Int
        get() = sp.getInt("nightEnd", NightSchedule.DEFAULT_END).coerceIn(0, NightSchedule.DAY - 1)
        set(v) { sp.edit().putInt("nightEnd", v).apply() }

    /** 0 low, 1 medium, 2 strong. */
    var nightLevel: Int
        get() = sp.getInt("nightLevel", 1).coerceIn(0, BackupCodec.NIGHT_LEVELS - 1)
        set(v) { sp.edit().putInt("nightLevel", v).apply() }

    var reduceTransparency: Boolean
        get() = sp.getBoolean("reduceTransparency", false)
        set(v) { sp.edit().putBoolean("reduceTransparency", v).apply() }

    var reduceMotion: Boolean
        get() = sp.getBoolean("reduceMotion", false)
        set(v) { sp.edit().putBoolean("reduceMotion", v).apply() }

    // ---------- Folders and backups ----------

    var folders: List<AppFolder>
        get() = Folders.parseLenient(sp.getString("folders", null))
        set(v) { sp.edit().putString("folders", Folders.serialize(v)).apply() }

    /** Where the last backup was written (shown in Settings). */
    var lastBackup: String
        get() = sp.getString("lastBackup", "") ?: ""
        set(v) { sp.edit().putString("lastBackup", v).apply() }

    /** The whitelisted settings a backup carries (see [BackupData]). */
    fun toBackup(): BackupData = BackupData(
        dock = dock, order = order, hidden = hidden.toList(), folders = folders,
        display = DisplayBackup(sound = sound, topShelf = topShelf, scene = scene.takeIf { it in BackupCodec.SCENE_IDS },
            cinemaDelaySec = cinemaDelaySec, cinemaClock = cinemaClock, nightEnabled = nightEnabled, nightStart = nightStart,
            nightEnd = nightEnd, nightLevel = nightLevel, reduceTransparency = reduceTransparency, reduceMotion = reduceMotion,
            mostUsed = mostUsed),
        scenery = SceneryBackup(shuffle = sceneShuffle, favoritesOnly = sceneFavoritesOnly, labels = sceneLabels,
            favorites = sceneFavorites.sorted(), hidden = sceneHidden.sorted(), collection = sceneCollection))

    /** Applies a validated backup in one commit; sections and fields it does not have keep their values. */
    fun apply(d: BackupData): Boolean {
        val e = sp.edit()
        if (d.hasLayout) {
            // The layout travels as one: a top row or order missing from it means "default", not "keep".
            if (d.dock != null) e.putString("dock", d.dock.joinToString(",")) else e.remove("dock")
            if (d.order != null) e.putString("order", d.order.joinToString(",")) else e.remove("order")
            e.putString("hidden", (d.hidden ?: emptyList()).joinToString(","))
        }
        d.folders?.let { e.putString("folders", Folders.serialize(it)) }
        d.display?.let { s ->
            s.sound?.let { e.putBoolean("sound", it) }
            s.topShelf?.let { e.putString("topShelf", it) }
            s.scene?.let { e.putString("fallback", it) }
            s.cinemaDelaySec?.let { e.putInt("cinemaDelay", it) }
            s.cinemaClock?.let { e.putBoolean("cinemaClock", it) }
            s.nightEnabled?.let { e.putBoolean("night", it) }
            s.nightStart?.let { e.putInt("nightStart", it) }
            s.nightEnd?.let { e.putInt("nightEnd", it) }
            s.nightLevel?.let { e.putInt("nightLevel", it) }
            s.reduceTransparency?.let { e.putBoolean("reduceTransparency", it) }
            s.reduceMotion?.let { e.putBoolean("reduceMotion", it) }
            s.mostUsed?.let { e.putBoolean("mostUsed", it) }
        }
        d.scenery?.let { s ->
            s.collection?.let { e.putString("sceneCollection", it) }
            s.shuffle?.let { e.putBoolean("sceneShuffle", it) }
            s.favoritesOnly?.let { e.putBoolean("sceneFavOnly", it) }
            s.labels?.let { e.putBoolean("sceneLabels", it) }
            s.favorites?.let { e.putString("sceneFav", org.json.JSONArray(it).toString()) }
            s.hidden?.let { e.putString("sceneHidden", org.json.JSONArray(it).toString()) }
        }
        return e.commit()
    }

    companion object {
        const val DEFAULT_SCENE = "scene:desert"
    }
}

/**
 * Lighter visual effects, process-wide so both home screens and every painter read the same values.
 * Reduce transparency: glass panels are drawn solid, without sampling the blurred backdrop (and so without redrawing
 * as the art behind them changes). Reduce motion: no springs, tilts, wiggles or sliding panels; focus changes and
 * scrolling are immediate, and fades are kept short. Both default off: the Fire TV sticks GlassOS targets do not
 * report themselves as low-RAM devices, so there is nothing reliable to switch them on automatically.
 */
object Effects {
    @JvmStatic var reduceTransparency = false
    @JvmStatic var reduceMotion = false

    fun load(p: Prefs) {
        reduceTransparency = p.reduceTransparency
        reduceMotion = p.reduceMotion
    }

    /** An animation's duration, or 0 under reduce motion. */
    fun ms(d: Long): Long = if (reduceMotion) 0L else d

    /** A fade's duration: fades stay under reduce motion (they do not move anything), only shorter. */
    fun fade(d: Long): Long = if (reduceMotion) minOf(d, 150L) else d
}
