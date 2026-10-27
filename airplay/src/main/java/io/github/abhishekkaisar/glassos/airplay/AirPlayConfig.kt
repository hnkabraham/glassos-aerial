/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay

// Pure (no android.* imports), unit tested on the JVM.

/**
 * Screen Mirroring receiver settings: every key, its default, its valid values and whether changing it restarts the
 * receiver (jqssun's Prefs.kt schema). The launcher's "glass" prefs keep them as `airplay_<name>` ([PREFIX]) and are
 * their only writer; the receiver process gets a whole config with every START and APPLY.
 *
 * Basic keys (Settings): [NAME], [PIN], [RES], [SYNC_VOLUME]. The others are advanced, reachable only through the
 * manager's `airplay-set key=value`. Fixed in this build, so not keys at all: no video (HLS) handover until phase 2,
 * no ALAC and no forced software decoders (FFmpeg is compiled out), and at most 1920x1080 at 60 fps.
 */
class AirPlayConfig private constructor(private val values: Map<String, Any>) {

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: Key<T>): T = values[key.name] as T

    /** Empty means "the TV's own name", which [normalise] fills in. */
    val name: String get() = this[NAME]
    val pin: Boolean get() = this[PIN]
    val res: String get() = this[RES]
    val syncVolume: Boolean get() = this[SYNC_VOLUME]
    val port: Int get() = this[PORT]
    /** null = automatic, true/false = forced on/off. */
    val h265: Boolean? get() = forced(this[H265])
    val maxFps: Int get() = this[MAX_FPS]
    val overscanned: Boolean get() = this[OVERSCANNED]
    val allowNewConnection: Boolean get() = this[ALLOW_NEW_CONN]
    val audioLatencyMs: Int get() = this[AUDIO_LATENCY_MS]
    val enforceSdr: Boolean get() = this[ENFORCE_SDR]
    val allowFrameDrop: Boolean get() = this[ALLOW_FRAME_DROP]
    val operatingRate: Boolean? get() = forced(this[OPERATING_RATE])
    val scheduledRelease: Boolean get() = this[SCHEDULED_RELEASE]
    val realtimePriority: Boolean get() = this[REALTIME_PRIORITY]
    val lowLatency: Boolean get() = this[LOW_LATENCY]
    val audioAutoBuffer: Boolean get() = this[AUDIO_AUTO_BUFFER]
    val audioCushionMs: Int get() = this[AUDIO_CUSHION_MS]
    val audioAdaptiveStep: Int get() = this[AUDIO_ADAPTIVE_STEP]
    val oboeBufferFrames: Int get() = this[OBOE_BUFFER_FRAMES]
    val aac: Boolean get() = this[AAC]
    val advertiseMirroring: Boolean get() = this[ADVERTISE_MIRRORING]
    val advertiseAudio: Boolean get() = this[ADVERTISE_AUDIO]
    val benchmarkLog: Boolean get() = this[BENCHMARK_LOG]

    /** The arrival-delay percentile the adaptive audio cushion aims at (jqssun's slider steps). */
    val audioPercentile: Int get() = ADAPTIVE_PERCENTILES[audioAdaptiveStep]

    /** A copy with [key] set to [value]; an invalid value leaves the key as it was. */
    fun <T : Any> with(key: Key<T>, value: T): AirPlayConfig =
        if (key.valid(value)) AirPlayConfig(values + (key.name to value)) else this

    /**
     * What the receiver runs with: the name trimmed, stripped of control characters and cut to [MAX_NAME_BYTES]
     * UTF-8 bytes (one DNS-SD label), or [deviceName] (cleaned the same way) when it is blank.
     */
    fun normalise(deviceName: String): AirPlayConfig {
        val own = cleanName(name)
        val effective = own.ifEmpty { cleanName(deviceName) }.ifEmpty { FALLBACK_NAME }
        return AirPlayConfig(values + (NAME.name to effective))
    }

    /** Every key with its value, by key name (the Bundle and prefs edges convert from and to this). */
    fun toMap(): Map<String, Any> = LinkedHashMap(values)

    override fun equals(other: Any?) = other is AirPlayConfig && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = "AirPlayConfig$values"

    // ---------- Schema ----------

    enum class Kind { BOOL, INT, TEXT }

    /** One setting. [parse] reads `airplay-set` text; [coerce] accepts a stored or sent value of the right type. */
    sealed class Key<T : Any>(val name: String, val def: T, val restart: Boolean, val kind: Kind) {
        abstract fun parse(text: String): T?
        abstract fun valid(v: T): Boolean
        abstract fun coerce(raw: Any?): T?
        /** The prefs key in the launcher's "glass" file. */
        val prefKey: String get() = PREFIX + name
    }

    class BoolKey internal constructor(name: String, def: Boolean, restart: Boolean) :
        Key<Boolean>(name, def, restart, Kind.BOOL) {
        override fun parse(text: String) = when (text.trim().lowercase()) { "true" -> true; "false" -> false; else -> null }
        override fun valid(v: Boolean) = true
        override fun coerce(raw: Any?) = raw as? Boolean
    }

    class IntKey internal constructor(name: String, def: Int, val range: IntRange, restart: Boolean) :
        Key<Int>(name, def, restart, Kind.INT) {
        override fun parse(text: String) = text.trim().toIntOrNull()?.takeIf(::valid)
        override fun valid(v: Int) = v in range
        override fun coerce(raw: Any?) = (raw as? Int)?.takeIf(::valid)
    }

    class ChoiceKey internal constructor(name: String, def: String, val choices: List<String>, restart: Boolean) :
        Key<String>(name, def, restart, Kind.TEXT) {
        override fun parse(text: String) = text.trim().lowercase().takeIf(::valid)
        override fun valid(v: String) = v in choices
        override fun coerce(raw: Any?) = (raw as? String)?.takeIf(::valid)
    }

    class NameKey internal constructor(name: String) : Key<String>(name, "", true, Kind.TEXT) {
        override fun parse(text: String) = text.trim().takeIf(::valid)
        /** At most [MAX_NAME_BYTES] UTF-8 bytes, no control characters, no surrounding spaces; empty is allowed. */
        override fun valid(v: String) =
            v == v.trim() && v.none { Character.isISOControl(it) } && v.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES
        override fun coerce(raw: Any?) = (raw as? String)?.takeIf(::valid)
    }

    companion object {
        /** Prefix of the keys in the launcher's "glass" prefs. */
        const val PREFIX = "airplay_"
        /** A DNS-SD service instance name is one DNS label: at most 63 bytes. */
        const val MAX_NAME_BYTES = 63
        const val FALLBACK_NAME = "GlassOS"
        const val AUTO = "auto"
        const val ON = "on"
        const val OFF = "off"
        const val RES_AUTO = "auto"
        const val RES_1080 = "1080p"
        const val RES_720 = "720p"
        val ADAPTIVE_PERCENTILES = intArrayOf(80, 85, 90, 95, 99)
        private val TRI = listOf(AUTO, ON, OFF)

        private val all = mutableListOf<Key<*>>()
        private fun <K : Key<*>> add(k: K): K = k.also { all += it }
        private fun bool(name: String, def: Boolean, restart: Boolean = false) = add(BoolKey(name, def, restart))
        private fun int(name: String, def: Int, range: IntRange, restart: Boolean = false) = add(IntKey(name, def, range, restart))
        private fun choice(name: String, def: String, choices: List<String>, restart: Boolean = false) =
            add(ChoiceKey(name, def, choices, restart))

        // Basic: Settings > Screen Mirroring.
        val NAME = add(NameKey("name"))
        val PIN = bool("pin", false, restart = true)
        val RES = choice("res", RES_1080, listOf(RES_AUTO, RES_1080, RES_720), restart = true)
        val SYNC_VOLUME = bool("sync_volume", true)

        // Advanced: GlassOS Manager's `airplay-set` only. Defaults are jqssun's (Prefs.kt:75-116).
        val PORT = int("port", 7000, 1..65535, restart = true)
        val H265 = choice("h265", AUTO, TRI, restart = true)
        val MAX_FPS = int("max_fps", 60, 1..60, restart = true)
        val OVERSCANNED = bool("overscanned", false, restart = true)
        val ALLOW_NEW_CONN = bool("allow_new_conn", true, restart = true)
        val AUDIO_LATENCY_MS = int("audio_latency_ms", -1, -1..1000, restart = true)
        val ENFORCE_SDR = bool("enforce_sdr", true, restart = true)
        val ALLOW_FRAME_DROP = bool("allow_frame_drop", true, restart = true)
        val OPERATING_RATE = choice("operating_rate", AUTO, TRI, restart = true)
        val SCHEDULED_RELEASE = bool("scheduled_output_buffer_release", false, restart = true)
        val REALTIME_PRIORITY = bool("realtime_priority", true)
        val LOW_LATENCY = bool("low_latency", false)
        val AUDIO_AUTO_BUFFER = bool("audio_auto_buffer", true)
        val AUDIO_CUSHION_MS = int("audio_cushion_ms", 40, 1..1000)
        val AUDIO_ADAPTIVE_STEP = int("audio_adaptive_step", 3, ADAPTIVE_PERCENTILES.indices)
        val OBOE_BUFFER_FRAMES = int("oboe_buffer_frames", 0, 0..8192)
        val AAC = bool("aac_enabled", true, restart = true)
        val ADVERTISE_MIRRORING = bool("advertise_mirroring", true, restart = true)
        val ADVERTISE_AUDIO = bool("advertise_audio", true, restart = true)
        val BENCHMARK_LOG = bool("benchmark_log", false, restart = true)

        /** Every key, basic first, in a fixed order. */
        val KEYS: List<Key<*>> = all.toList()

        val DEFAULTS = AirPlayConfig(KEYS.associate { it.name to it.def })

        fun key(name: String): Key<*>? = KEYS.firstOrNull { it.name == name }

        /**
         * A config from loosely typed values by key name (a Bundle or prefs read): a missing, wrongly typed or
         * invalid value reads as its default, and unknown names are ignored.
         */
        fun from(raw: Map<String, Any?>): AirPlayConfig =
            AirPlayConfig(KEYS.associate { k -> k.name to (k.coerce(raw[k.name]) ?: k.def) })

        /** [from] over a prefs map, whose keys carry [PREFIX]. */
        fun fromPrefs(prefs: Map<String, Any?>): AirPlayConfig =
            AirPlayConfig(KEYS.associate { k -> k.name to (k.coerce(prefs[k.prefKey]) ?: k.def) })

        /** Whether going from [old] (null: not running) to [new] needs the receiver restarted. */
        fun restartNeeded(old: AirPlayConfig?, new: AirPlayConfig): Boolean =
            old == null || KEYS.any { it.restart && old.values[it.name] != new.values[it.name] }

        fun forced(v: String): Boolean? = when (v) { ON -> true; OFF -> false; else -> null }

        /** Control characters become spaces, runs of white space one space; trimmed and cut to [MAX_NAME_BYTES]. */
        fun cleanName(s: String): String {
            val spaced = String(CharArray(s.length) { i -> if (Character.isISOControl(s[i])) ' ' else s[i] })
            val oneLine = spaced.trim().replace(Regex("\\s+"), " ")
            return truncateUtf8(oneLine, MAX_NAME_BYTES).trim()
        }

        /** The longest prefix of [s] that is at most [maxBytes] in UTF-8, never splitting a character. */
        fun truncateUtf8(s: String, maxBytes: Int): String {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + n > maxBytes) break
                bytes += n
                i += Character.charCount(cp)
            }
            return s.substring(0, i)
        }
    }
}
