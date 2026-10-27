/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.jqssun.airplay.bridge

/**
 * log lines from native code, forwarded to UI; called directly from native threads
 * (attached to JVM for the call), implementations must be thread-safe and cheap
 */
interface LogListener {
    fun onLog(msg: String)
}
