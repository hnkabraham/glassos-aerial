package io.github.abhishekkaisar.glassos.airplay.ui

import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.Mode
import io.github.abhishekkaisar.glassos.airplay.AirPlayStatus.State

// Pure (no android.* imports), unit tested on the JVM.

/**
 * The receiver screen's rules: when the service brings it up, what it shows, when it closes by itself, and its text.
 * User-facing text never names the protocol's products (see THIRD_PARTY_NOTICES.md).
 */
object ReceiverScreen {
    /** Why the service may bring the receiver screen up. */
    enum class Trigger {
        /** mirror_video_running(true): a sender starts mirroring its screen. */
        MIRROR_START,
        /** onVideoPlay: a sender hands a video over (phase 2; not advertised in this build). */
        VIDEO_PLAY,
        /** display_pin: a sender asks for the PIN, which only the TV can show. */
        PIN,
        /** SHOW from the launcher (its tile, or GlassOS Manager). */
        SHOW,
    }

    /**
     * Whether the service starts the receiver screen for [t]. A mirrored screen, a handed-over video and a PIN open
     * it; music alone never does (the scenery keeps playing, Now Playing shows the track). After Back hid it
     * ([userHid]) the same session does not bring it back by itself: only a PIN or SHOW does.
     */
    fun opens(t: Trigger, visible: Boolean, userHid: Boolean): Boolean = when {
        visible -> false
        t == Trigger.PIN || t == Trigger.SHOW -> true
        else -> !userHid
    }

    enum class Content { OFF, READY, CONNECTING, PIN, VIDEO, NOW_PLAYING }

    fun content(s: AirPlayStatus): Content = when {
        s.state != State.RUNNING -> Content.OFF
        s.pin != null -> Content.PIN
        s.mode == Mode.MIRROR || s.mode == Mode.VIDEO -> Content.VIDEO
        s.mode == Mode.AUDIO -> Content.NOW_PLAYING
        s.connections > 0 -> Content.CONNECTING
        else -> Content.READY
    }

    /**
     * A PIN, a mirrored screen, a video, or music with its track known: what the screen shows a session for. Music
     * without a track (a sender that just stopped mirroring) is not: the screen goes back to what was there.
     */
    private fun inSession(s: AirPlayStatus) = s.state == State.RUNNING &&
        (s.pin != null || s.mode == Mode.MIRROR || s.mode == Mode.VIDEO || (s.mode == Mode.AUDIO && s.title.isNotEmpty()))

    /**
     * The screen closes by itself: the receiver stopped, or the session it showed ended (mirroring stopped, the PIN
     * was entered or dismissed and nothing followed). Opened by SHOW with nothing playing, it stays until Back.
     */
    fun ended(before: AirPlayStatus, now: AirPlayStatus): Boolean =
        (before.state == State.RUNNING && now.state != State.RUNNING) || (inSession(before) && !inSession(now))

    /**
     * The screen opened for [t] finds nothing to show when it first hears from the receiver (the session ended while
     * it opened): it closes. Opened by SHOW, it stays.
     */
    fun stale(t: Trigger, now: AirPlayStatus): Boolean = t != Trigger.SHOW && !inSession(now)

    /** The screen keeps the TV awake: a sender is connected, or something shows. */
    fun keepsScreenOn(s: AirPlayStatus): Boolean = s.state == State.RUNNING && (s.connections > 0 || s.mode != Mode.IDLE || s.pin != null)

    /** Disconnect is offered whenever a sender is there (or a PIN waits for one). */
    fun offersDisconnect(s: AirPlayStatus): Boolean = s.state == State.RUNNING && (s.connections > 0 || s.mode != Mode.IDLE || s.pin != null)

    /** The screen's main line under its title. */
    fun statusLine(s: AirPlayStatus): String = when (content(s)) {
        Content.OFF -> if (s.state == State.STARTING) "Starting…" else "Screen Mirroring is off"
        Content.READY -> "Ready as “${s.name}”"
        Content.CONNECTING -> "Connecting…"
        Content.PIN -> "Enter this code on your iPhone, iPad or Mac"
        Content.VIDEO -> "Mirroring"
        Content.NOW_PLAYING -> "Playing"
    }

    /** The smaller line under it: what to do next, and what Back does. */
    fun hint(s: AirPlayStatus): String = when (content(s)) {
        Content.OFF -> ""
        Content.READY -> "On your iPhone, iPad or Mac, open Screen Mirroring and choose “${s.name}”."
        Content.CONNECTING -> "Back returns to GlassOS."
        Content.PIN -> "Back closes the code."
        Content.VIDEO -> "Back hides this screen. Mirroring goes on."
        Content.NOW_PLAYING -> "Back returns to GlassOS. The music goes on."
    }
}
