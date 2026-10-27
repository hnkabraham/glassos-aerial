package io.github.abhishekkaisar.glassos

import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.Mode
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.State
import io.github.abhishekkaisar.glassos.airplay.ui.ReceiverScreen

// Pure (no android.* imports), unit tested on the JVM.

/**
 * What the launcher shows for Screen Mirroring (the Control Center tile, Settings' status row) and does when the
 * tile is pressed, from airplay_on and the receiver's last status. Off reads Off whatever the receiver last said
 * (a failed start included). Never shows a PIN, and never names the protocol's products beyond Settings' subtitle.
 */
object MirrorState {
    enum class Action { START, STOP, SHOW }

    /** A sender is there with something the receiver screen shows: a mirrored screen, a video, music or a PIN. */
    fun inSession(s: AirPlayStatus): Boolean = s.state == State.RUNNING && (s.mode != Mode.IDLE || s.pin != null)

    /** Control Center's tile: during a session it brings the receiver screen up, otherwise it turns mirroring on or off. */
    fun tileAction(on: Boolean, s: AirPlayStatus): Action = when {
        on && inSession(s) -> Action.SHOW
        on -> Action.STOP
        else -> Action.START
    }

    /** The tile's label: what the receiver is doing, or the feature's name. */
    fun tileLabel(on: Boolean, s: AirPlayStatus): String = when {
        !on || s.state != State.RUNNING -> "Screen Mirroring"
        s.pin != null -> "PIN shown"
        s.mode == Mode.MIRROR || s.mode == Mode.VIDEO -> "Mirroring"
        s.mode == Mode.AUDIO -> "Playing"
        else -> "Screen Mirroring"
    }

    /** Settings' status row. [gaveUp]: the receiver kept crashing and is not restarted again. */
    fun statusLine(on: Boolean, s: AirPlayStatus, gaveUp: Boolean): String = when {
        !on -> "Off"
        gaveUp || s.error == AirPlayStatus.ERROR_CRASHED -> "Stopped unexpectedly"
        s.state == State.ERROR -> errorText(s.error)
        s.state != State.RUNNING -> "Starting…"
        // While its screen is hidden, the tile brings it back.
        else -> (if (s.pin != null) "Showing a code" else ReceiverScreen.statusLine(s)) + if (s.offersView) " · hidden" else ""
    }

    /** Why the receiver did not start, in a few words. */
    fun errorText(code: String?): String = when (code) {
        AirPlayStatus.ERROR_PORT -> "No free network port"
        AirPlayStatus.ERROR_NATIVE_MISSING -> "Not in this build"
        AirPlayStatus.ERROR_UNSUPPORTED -> "Needs Fire OS 6 or later"
        else -> "Could not start"
    }

    /**
     * Whether the launcher starts the receiver when it comes up (boot, update, the home resuming): Screen Mirroring is
     * on and the launcher knows of no receiver, running or starting. One that failed is left alone: turning Screen
     * Mirroring off and on again retries it.
     */
    fun shouldStart(on: Boolean, connected: Boolean, s: AirPlayStatus): Boolean = on && !connected && s.state == State.STOPPED

    fun resolutionLabel(res: String): String = when (res) {
        AirPlayConfig.RES_AUTO -> "Auto"
        AirPlayConfig.RES_720 -> "720p"
        else -> "1080p"
    }

    /** The resolution [d] steps away from [res] (Settings' Left and Right), wrapping around. */
    fun stepResolution(res: String, d: Int): String {
        val all = AirPlayConfig.RES.choices
        val i = all.indexOf(res).coerceAtLeast(0)
        return all[((i + d) % all.size + all.size) % all.size]
    }
}
