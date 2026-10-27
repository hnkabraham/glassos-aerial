package io.github.abhishekkaisar.glassos.airplay

// Pure (no android.* imports).

/**
 * What the receiver in the :airplay process reports to the launcher (MSG_STATUS): pushed when a client registers and
 * on every change. The receiver always sends [State.STOPPED] or [State.ERROR] before its process exits, which is how
 * the launcher's Supervisor tells an intentional exit from a crash.
 */
data class AirPlayStatus(
    val state: State = State.STOPPED,
    /** Why the receiver stopped with [State.ERROR] (one of the ERROR_* codes), otherwise null. */
    val error: String? = null,
    /** The advertised name. */
    val name: String = "",
    /** The bound RTSP port (7000, or a dynamic one when 7000 was taken); 0 when not running. */
    val port: Int = 0,
    /** Open sender connections. */
    val connections: Int = 0,
    val mode: Mode = Mode.IDLE,
    /** The PIN the sender asks for, while it is shown. */
    val pin: String? = null,
    val title: String = "",
    val artist: String = "",
    /** The receiver screen (:airplay's activity) is showing. */
    val activityVisible: Boolean = false,
    /** The :airplay process id; 0 when not running. */
    val pid: Int = 0,
    /** The mirrored stream's size, once it reported one; 0 otherwise. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    /** IDLE: listening; MIRROR: the screen is mirrored; AUDIO: music only; VIDEO: a handed-over video (phase 2). */
    enum class Mode { IDLE, MIRROR, AUDIO, VIDEO }

    val running: Boolean get() = state == State.RUNNING || state == State.STARTING

    /** A mirrored screen or a video plays: the receiver decodes video, so the launcher's scenery must not. */
    val decodesVideo: Boolean get() = state == State.RUNNING && (mode == Mode.MIRROR || mode == Mode.VIDEO)

    /**
     * Something is on the receiver screen while it does not show (Back hid it, or Android did not let it open over
     * another app): the launcher offers to show it (Settings adds "· hidden"; the tile then sends SHOW).
     */
    val offersView: Boolean get() = state == State.RUNNING && !activityVisible && (decodesVideo || pin != null)

    /** Never shows the PIN, so a status in a log cannot leak it. */
    override fun toString() = "AirPlayStatus(state=$state, error=$error, name=$name, port=$port, connections=$connections, " +
        "mode=$mode, pin=${if (pin != null) "shown" else "none"}, activityVisible=$activityVisible, pid=$pid, " +
        "video=${videoWidth}x$videoHeight)"

    companion object {
        /** This Android is older than [AirPlay.MIN_SDK]: the receiver refuses to start (and never loads its library). */
        const val ERROR_UNSUPPORTED = "unsupported"
        /** The native library could not be loaded (a build without natives, or a broken install). */
        const val ERROR_NATIVE_MISSING = "native-missing"
        /** The receiver's native init failed. */
        const val ERROR_NATIVE_INIT = "native-init"
        /** Neither port 7000 nor a dynamic port could be bound. */
        const val ERROR_PORT = "port"
        /** The launcher could not start the receiver service. */
        const val ERROR_START = "start-failed"
        /** The receiver kept crashing and the launcher gave up restarting it (set by the Supervisor). */
        const val ERROR_CRASHED = "crashed"

        fun state(name: String?): State = State.values().firstOrNull { it.name == name } ?: State.STOPPED
        fun mode(name: String?): Mode = Mode.values().firstOrNull { it.name == name } ?: Mode.IDLE
    }
}
