package io.github.abhishekkaisar.glassos

import android.content.Context
import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig

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

    var movingClock: Boolean
        get() = sp.getBoolean("movingClock", true)
        set(v) { sp.edit().putBoolean("movingClock", v).apply() }

    var theme: String
        get() = Themes.get(sp.getString("theme", "glass") ?: "glass").id
        set(v) { sp.edit().putString("theme", Themes.get(v).id).apply() }

    var customArt: Boolean
        get() = sp.getBoolean("customArt", true)
        set(v) { sp.edit().putBoolean("customArt", v).apply() }

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

    /** Night follows sunset and sunrise (with the offsets below) instead of [nightStart] and [nightEnd]. */
    var nightSun: Boolean
        get() = sp.getBoolean("nightSun", false)
        set(v) { sp.edit().putBoolean("nightSun", v).apply() }

    /** Minutes after sunset (negative: before) when night starts in sun mode. */
    var nightStartOffset: Int
        get() = sp.getInt("nightStartOffset", 0).coerceIn(-NightSchedule.MAX_OFFSET, NightSchedule.MAX_OFFSET)
        set(v) { sp.edit().putInt("nightStartOffset", v).apply() }

    /** Minutes after sunrise (negative: before) when night ends in sun mode. */
    var nightEndOffset: Int
        get() = sp.getInt("nightEndOffset", 0).coerceIn(-NightSchedule.MAX_OFFSET, NightSchedule.MAX_OFFSET)
        set(v) { sp.edit().putInt("nightEndOffset", v).apply() }

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

    // ---------- Sun times place (never in a backup) ----------

    /**
     * Where sun times are worked out, set from GlassOS Manager: latitude and longitude rounded to 0.1° and a short
     * name. Null when not set: sun times then come from the TV's time zone (see [SunNow]).
     */
    val sunPlace: Place?
        get() {
            val lat = sp.getString("sunLat", null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString("sunLon", null)?.toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return Place(lat, lon, sp.getString("sunLabel", "") ?: "", "manager")
        }

    /** Saves or (null) clears [sunPlace] in one commit. False if not saved. */
    fun setSunPlace(p: Place?): Boolean {
        val e = sp.edit()
        if (p == null) e.remove("sunLat").remove("sunLon").remove("sunLabel")
        else e.putString("sunLat", p.lat.toString()).putString("sunLon", p.lon.toString()).putString("sunLabel", p.label)
        return e.commit()
    }

    // ---------- Folders and backups ----------

    var folders: List<AppFolder>
        get() = Folders.parseLenient(sp.getString("folders", null))
        set(v) { sp.edit().putString("folders", Folders.serialize(v)).apply() }

    /** Where the last backup was written (shown in Settings). */
    var lastBackup: String
        get() = sp.getString("lastBackup", "") ?: ""
        set(v) { sp.edit().putString("lastBackup", v).apply() }

    // ---------- Screen Mirroring (the airplay module; off until turned on) ----------

    /** Screen Mirroring is on: the receiver runs, and is restarted after a crash, only while this is true. */
    var airplayOn: Boolean
        get() = sp.getBoolean("airplay_on", false)
        set(v) { sp.edit().putBoolean("airplay_on", v).apply() }

    /** The receiver's settings, kept here as "airplay_<key>" ([AirPlayConfig.KEYS]); unset or invalid ones default. */
    val airplayConfig: AirPlayConfig
        get() = AirPlayConfig.fromPrefs(sp.all)

    /** Saves one receiver setting, or (null) goes back to its default. False if not saved. */
    fun setAirplay(key: AirPlayConfig.Key<*>, value: Any?): Boolean {
        val e = sp.edit()
        when {
            value == null -> e.remove(key.prefKey)
            value is Boolean -> e.putBoolean(key.prefKey, value)
            value is Int -> e.putInt(key.prefKey, value)
            value is String -> e.putString(key.prefKey, value)
            else -> return false
        }
        return e.commit()
    }

    // ---------- App lock (never in a backup) ----------

    /** Apps that need the lock code (Hold OK > Lock app). Kept when an app is uninstalled, so a reinstall stays locked. */
    var locked: Set<String>
        get() = sp.getString("locked", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) { sp.edit().putString("locked", v.joinToString(",")).apply() }

    /** The code as [LockCode.store] keeps it; empty until the first app is locked. Written only with [setLock]. */
    val lockCode: String
        get() = sp.getString("lockCode", "") ?: ""

    /** The locked apps and the code in one commit, so a reader never sees one without the other. False if not saved. */
    fun setLock(locked: Set<String>, code: String): Boolean =
        sp.edit().putString("locked", locked.joinToString(",")).putString("lockCode", code).commit()

    /**
     * The whitelisted settings a backup carries (see [BackupData]); Screen Mirroring's only where it is available
     * ([includeMirroring]), so a TV or build without it writes none.
     */
    fun toBackup(includeMirroring: Boolean): BackupData = BackupData(
        dock = dock, order = order, hidden = hidden.toList(), folders = folders,
        display = DisplayBackup(sound = sound, topShelf = topShelf, scene = scene.takeIf { it in BackupCodec.SCENE_IDS },
            cinemaDelaySec = cinemaDelaySec, cinemaClock = cinemaClock, nightEnabled = nightEnabled, nightStart = nightStart,
            nightEnd = nightEnd, nightLevel = nightLevel, nightSun = nightSun, nightStartOffset = nightStartOffset,
            nightEndOffset = nightEndOffset, reduceTransparency = reduceTransparency, reduceMotion = reduceMotion,
            mostUsed = mostUsed, theme = theme, movingClock = movingClock, customArt = customArt),
        scenery = SceneryBackup(shuffle = sceneShuffle, favoritesOnly = sceneFavoritesOnly, labels = sceneLabels,
            favorites = sceneFavorites.sorted(), hidden = sceneHidden.sorted(), collection = sceneCollection),
        mirroring = if (!includeMirroring) null else airplayConfig.let { c ->
            MirroringBackup(on = airplayOn, name = c.name, pin = c.pin, resolution = c.res, syncVolume = c.syncVolume)
        })

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
            s.theme?.let { e.putString("theme", it) }
            s.movingClock?.let { e.putBoolean("movingClock", it) }
            s.customArt?.let { e.putBoolean("customArt", it) }
            s.cinemaClock?.let { e.putBoolean("cinemaClock", it) }
            s.nightEnabled?.let { e.putBoolean("night", it) }
            s.nightStart?.let { e.putInt("nightStart", it) }
            s.nightEnd?.let { e.putInt("nightEnd", it) }
            s.nightLevel?.let { e.putInt("nightLevel", it) }
            s.nightSun?.let { e.putBoolean("nightSun", it) }
            s.nightStartOffset?.let { e.putInt("nightStartOffset", it) }
            s.nightEndOffset?.let { e.putInt("nightEndOffset", it) }
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
        d.mirroring?.let { m ->
            m.on?.let { e.putBoolean("airplay_on", it) }
            // Empty is the TV's own name, the default.
            m.name?.let { if (it.isEmpty()) e.remove(AirPlayConfig.NAME.prefKey) else e.putString(AirPlayConfig.NAME.prefKey, it) }
            m.pin?.let { e.putBoolean(AirPlayConfig.PIN.prefKey, it) }
            m.resolution?.let { e.putString(AirPlayConfig.RES.prefKey, it) }
            m.syncVolume?.let { e.putBoolean(AirPlayConfig.SYNC_VOLUME.prefKey, it) }
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
        Themes.current = Themes.get(p.theme)
    }

    /** An animation's duration, or 0 under reduce motion. */
    fun ms(d: Long): Long = if (reduceMotion) 0L else d

    /** A fade's duration: fades stay under reduce motion (they do not move anything), only shorter. */
    fun fade(d: Long): Long = if (reduceMotion) minOf(d, 150L) else d
}
