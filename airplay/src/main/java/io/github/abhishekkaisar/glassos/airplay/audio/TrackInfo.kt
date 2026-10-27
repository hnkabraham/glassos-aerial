/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay.audio

// Pure (no android.* imports), unit tested on the JVM. GlassOS: the cover art (a Bitmap) is kept by the service.

data class TrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val durationMs: Long = 0,
) {
    companion object {
        fun fromDmap(map: Map<String, Any>): TrackInfo {
            // DMAP data is often wrapped in mlit container
            val flat = _flatten(map)
            return TrackInfo(
                title = flat["minm"] as? String ?: "",
                artist = flat["asar"] as? String ?: "",
                album = flat["asal"] as? String ?: "",
                genre = flat["asgn"] as? String ?: "",
                durationMs = (flat["astm"] as? Long) ?: 0L,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun _flatten(map: Map<String, Any>): Map<String, Any> {
            val result = mutableMapOf<String, Any>()
            for ((k, v) in map) {
                if (v is Map<*, *>) {
                    result.putAll(_flatten(v as Map<String, Any>))
                } else {
                    result[k] = v
                }
            }
            return result
        }
    }
}
