/*
 * Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
 * Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
 */
package io.github.abhishekkaisar.glassos.airplay.render

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/** Was jqssun's Display.kt. */
object DisplayInfo {
    /**
     * The physical panel resolution. Display modes are API 23, so Fire OS 5 (API 22) uses the display's real size
     * (which a TV's UI resolution may scale), then the display metrics.
     */
    fun realSize(ctx: Context): Pair<Int, Int> {
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            if (Build.VERSION.SDK_INT >= 23) {
                val mode = display.mode
                if (mode.physicalWidth > 0 && mode.physicalHeight > 0) return mode.physicalWidth to mode.physicalHeight
            }
            val p = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(p)
            if (p.x > 0 && p.y > 0) return p.x to p.y
        }
        return ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }
}
