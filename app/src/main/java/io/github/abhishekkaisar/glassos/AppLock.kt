package io.github.abhishekkaisar.glassos

import java.security.MessageDigest
import java.security.SecureRandom

// Pure (no android.* imports), unit tested on the JVM.

/**
 * The app lock's code: [LENGTH] presses of remote buttons every Fire TV remote has (the arrows, Rewind, Play/Pause and
 * Fast forward), kept as a salted SHA-256 hash. It keeps someone at the TV out of an app; it is not encryption.
 */
object LockCode {
    const val LENGTH = 4
    /** Wrong codes in a row before entry pauses for [WAIT_MS]. */
    const val TRIES = 5
    const val WAIT_MS = 30_000L

    /** Android key codes a code may use, to the letter stored for each. Play and Pause count as Play/Pause. */
    private val KEYS = mapOf(
        19 to 'U', 20 to 'D', 21 to 'L', 22 to 'R',
        89 to 'W', 90 to 'F',
        85 to 'P', 126 to 'P', 127 to 'P'
    )

    /** Every letter a code may hold. GlassOS Manager checks the same seven (manager/core.py, lock_code). */
    val LETTERS: Set<Char> = KEYS.values.toSet()

    /** The letter for [keyCode], or null when that button cannot be part of a code (OK, Back, Home, Menu). */
    fun letter(keyCode: Int): Char? = KEYS[keyCode]

    /** Exactly [LENGTH] of [LETTERS]. Any other code could never be entered with the remote. */
    fun valid(code: String): Boolean = code.length == LENGTH && code.all { it in LETTERS }

    /** "salt:hash", the form [Prefs.lockCode] keeps. Only a [valid] code may be stored. */
    fun store(code: String, salt: String = newSalt()): String {
        require(valid(code))
        return "$salt:${hash(salt, code)}"
    }

    fun matches(stored: String, code: String): Boolean {
        val salt = stored.substringBefore(':', "")
        val want = stored.substringAfter(':', "")
        if (salt.isEmpty() || want.isEmpty() || code.length != LENGTH) return false
        return MessageDigest.isEqual(want.toByteArray(), hash(salt, code).toByteArray())
    }

    private fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()

    private fun hash(salt: String, code: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray(Charsets.UTF_8)).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/** Wrong codes in a row, shared by every prompt: after [LockCode.TRIES] the next try waits [LockCode.WAIT_MS]. */
class LockTries {
    private var wrong = 0
    private var until = 0L

    /** Milliseconds before another code may be tried (0: now). [now] is a monotonic clock. */
    fun waitMs(now: Long): Long = (until - now).coerceAtLeast(0)

    /** A wrong code at [now]. True when entry must now wait. */
    fun fail(now: Long): Boolean {
        if (++wrong < LockCode.TRIES) return false
        wrong = 0
        until = now + LockCode.WAIT_MS
        return true
    }

    fun pass() { wrong = 0; until = 0 }
}

/**
 * The locked app the code last opened. It stays open while that app is in front: another app coming to the front
 * after the unlock, or the screen going off, locks it again. Times are wall clock ms, as usage events give them.
 */
class LockSession {
    var pkg: String? = null
        @Synchronized get
        private set
    private var at = 0L

    @Synchronized fun unlock(pkg: String, now: Long) { this.pkg = pkg; at = now }

    @Synchronized fun end() { pkg = null }

    /** [pkg] came to the front at [time]. True when it is locked and needs the code. */
    @Synchronized fun front(pkg: String, time: Long, locked: Set<String>): Boolean {
        if (pkg == this.pkg) return false
        // Events from before the unlock (the home that asked for the code) do not end it.
        if (time >= at) this.pkg = null
        return pkg in locked
    }
}

/**
 * Foreground events that are a screensaver starting or showing, which [AppLockGuard] lets pass: the unlocked app the
 * screensaver covers stays unlocked when it ends (the TV going to sleep still locks it again). A locked app never
 * passes, whatever it looks like.
 */
object ScreensaverFront {
    /** The framework's host for a running screensaver from Android 11 (a hidden class, so by name). */
    const val DREAM_ACTIVITY = "android.service.dreams.DreamActivity"
    /** AOSP's trampoline that starts the screensaver now (a remote's or Settings' "start screensaver"). */
    const val SYSTEM_UI = "com.android.systemui"
    const val SOMNAMBULATOR = "com.android.systemui.Somnambulator"

    /**
     * True when [pkg]/[cls] coming to the front is the screensaver: the dream host, the trampoline, Amazon's Ambient
     * Experience on a Fire TV ([fire]), or an activity of the TV's chosen screensaver package ([saver], read only when
     * needed) when that package has no entry people open ([opens]: a launcher or home activity), so an ordinary app or
     * a home is never mistaken for one.
     */
    fun matches(pkg: String, cls: String?, locked: Set<String>, fire: Boolean, saver: () -> String?,
                opens: (String) -> Boolean): Boolean {
        if (pkg in locked) return false
        if (cls == DREAM_ACTIVITY) return true
        if (pkg == SYSTEM_UI && cls == SOMNAMBULATOR) return true
        // Fire OS's screensaver hands off to this activity while Ambient Experience is on, whatever the setting names.
        if (fire && pkg == Platform.FIRE_AMBIENT) return true
        return pkg == saver() && !opens(pkg)
    }

    /** The package of the first component in a `screensaver_components` value (the one that plays), or null. */
    fun saverPackage(components: String?): String? =
        components?.substringBefore(',')?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }
}
