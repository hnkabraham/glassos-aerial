package io.github.abhishekkaisar.glassos

/** Small, dependency-free palettes shared by every glass painter. No extra backdrop or animation. */
data class GlassTheme(val id: String, val label: String, val tint: Int, val solid: Int, val shade: Float = 0f)

object Themes {
    val all = listOf(
        GlassTheme("glass", "Classic glass", 0xFFFFFFFF.toInt(), 0xFF1A1E29.toInt()),
        GlassTheme("coast", "Coast", 0xFFA8DAF0.toInt(), 0xFF142B3C.toInt()),
        GlassTheme("forest", "Forest", 0xFFB5D3B0.toInt(), 0xFF1A2C24.toInt()),
        GlassTheme("ember", "Ember", 0xFFF3C399.toInt(), 0xFF34241E.toInt()),
        GlassTheme("midnight", "Midnight", 0xFFB7C3E2.toInt(), 0xFF111522.toInt(), 0.25f)
    )
    val ids = all.map { it.id }.toSet()
    fun get(id: String) = all.firstOrNull { it.id == id } ?: all.first()
    @Volatile var current = all.first()
}

/** Four corners, visited without adjacent repeated positions. Clock stays within the TV's safe margins. */
object CinemaPosition {
    fun corner(elapsedMinutes: Long, moving: Boolean): Int = if (moving) ((elapsedMinutes % 4L + 4L) % 4L).toInt() else 2
}
