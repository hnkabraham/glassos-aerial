package io.github.abhishekkaisar.glassos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Small weather icons drawn with paths (no image assets), in the colours of Apple's Weather symbols: a warm sun, a
 * white cloud, pale blue rain, a yellow bolt. [draw] fits the icon in a [size] square centred on cx, cy.
 */
object WeatherIcon {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val cloud = Path()
    private val tmp = Path()
    private val r = RectF()
    private val cache = HashMap<String, Bitmap>()

    private const val SUN = 0xFFFFD45A.toInt()
    private const val MOON = 0xFFEDEFF7.toInt()
    private const val CLOUD = 0xF2FFFFFF.toInt()
    private const val CLOUD_DIM = 0xD9C9D2E0.toInt()
    private const val DROP = 0xFF7FC4FF.toInt()
    private const val BOLT = 0xFFFFD45A.toInt()

    /** The icon as a bitmap of [px] pixels square, cached (for text spans). Main thread. */
    fun bitmap(sky: Sky, day: Boolean, px: Int): Bitmap {
        val key = "$sky$day$px"
        return cache.getOrPut(key) {
            if (cache.size > 24) cache.clear()
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), sky, day, px / 2f, px / 2f, px.toFloat()) }
        }
    }

    fun draw(c: Canvas, sky: Sky, day: Boolean, cx: Float, cy: Float, size: Float) {
        val s = size
        val x0 = cx - s / 2
        val y0 = cy - s / 2
        when (sky) {
            Sky.CLEAR -> if (day) sun(c, cx, cy, s * 0.5f) else moon(c, cx, cy, s * 0.42f)
            Sky.PARTLY -> {
                if (day) sun(c, x0 + s * 0.36f, y0 + s * 0.36f, s * 0.36f) else moon(c, x0 + s * 0.38f, y0 + s * 0.36f, s * 0.3f)
                cloud(c, x0 + s * 0.08f, y0 + s * 0.38f, s * 0.86f, CLOUD)
            }
            Sky.CLOUDY -> {
                cloud(c, x0 + s * 0.26f, y0 + s * 0.12f, s * 0.7f, CLOUD_DIM)
                cloud(c, x0 + s * 0.02f, y0 + s * 0.26f, s * 0.86f, CLOUD)
            }
            Sky.FOG -> {
                cloud(c, x0 + s * 0.1f, y0 + s * 0.02f, s * 0.8f, CLOUD)
                line.color = CLOUD_DIM
                line.strokeWidth = s * 0.07f
                for ((i, y) in listOf(0.72f, 0.86f).withIndex()) {
                    val inset = if (i == 0) 0.12f else 0.24f
                    c.drawLine(x0 + s * inset, y0 + s * y, x0 + s * (1 - inset), y0 + s * y, line)
                }
            }
            Sky.DRIZZLE, Sky.RAIN, Sky.FREEZING, Sky.SNOW, Sky.STORM -> {
                cloud(c, x0 + s * 0.06f, y0, s * 0.88f, CLOUD)
                val top = y0 + s * 0.66f
                when (sky) {
                    Sky.RAIN -> drops(c, x0, top, s, 3, long = true)
                    Sky.DRIZZLE -> drops(c, x0, top, s, 3, long = false)
                    Sky.FREEZING -> { drops(c, x0, top, s, 2, long = true); flakes(c, x0 + s * 0.5f, top, s, 1) }
                    Sky.SNOW -> flakes(c, x0, top, s, 3)
                    else -> bolt(c, x0 + s * 0.5f, top - s * 0.04f, s)
                }
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, d: Float) {
        fill.color = SUN
        c.drawCircle(cx, cy, d * 0.3f, fill)
        line.color = SUN
        line.strokeWidth = d * 0.09f
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            c.drawLine(cx + cos * d * 0.42f, cy + sin * d * 0.42f, cx + cos * d * 0.52f, cy + sin * d * 0.52f, line)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, d: Float) {
        tmp.reset()
        tmp.addCircle(cx, cy, d / 2, Path.Direction.CW)
        cloud.reset()
        cloud.addCircle(cx + d * 0.28f, cy - d * 0.2f, d * 0.42f, Path.Direction.CW)
        tmp.op(cloud, Path.Op.DIFFERENCE)
        fill.color = MOON
        c.drawPath(tmp, fill)
    }

    /** A cloud [w] wide with its top-left at x, y (about 0.6 w tall). */
    private fun cloud(c: Canvas, x: Float, y: Float, w: Float, color: Int) {
        cloud.reset()
        cloud.addCircle(x + w * 0.3f, y + w * 0.4f, w * 0.2f, Path.Direction.CW)
        tmp.reset()
        tmp.addCircle(x + w * 0.56f, y + w * 0.3f, w * 0.26f, Path.Direction.CW)
        cloud.op(tmp, Path.Op.UNION)
        tmp.reset()
        r.set(x + w * 0.08f, y + w * 0.34f, x + w * 0.94f, y + w * 0.62f)
        tmp.addRoundRect(r, w * 0.14f, w * 0.14f, Path.Direction.CW)
        cloud.op(tmp, Path.Op.UNION)
        fill.color = color
        c.drawPath(cloud, fill)
    }

    private fun drops(c: Canvas, x0: Float, top: Float, s: Float, n: Int, long: Boolean) {
        line.color = DROP
        line.strokeWidth = s * 0.07f
        val len = if (long) s * 0.2f else s * 0.08f
        for (i in 0 until n) {
            val x = x0 + s * (0.32f + 0.2f * i) - (if (n == 2) s * 0.1f else 0f)
            c.drawLine(x, top + s * 0.06f, x - len * 0.35f, top + s * 0.06f + len, line)
        }
    }

    private fun flakes(c: Canvas, x0: Float, top: Float, s: Float, n: Int) {
        fill.color = CLOUD
        for (i in 0 until n) {
            val x = if (n == 1) x0 else x0 + s * (0.3f + 0.2f * i)
            c.drawCircle(x, top + s * (if (i % 2 == 0) 0.12f else 0.22f), s * 0.05f, fill)
        }
    }

    private fun bolt(c: Canvas, cx: Float, top: Float, s: Float) {
        tmp.reset()
        tmp.moveTo(cx + s * 0.04f, top)
        tmp.lineTo(cx - s * 0.12f, top + s * 0.2f)
        tmp.lineTo(cx, top + s * 0.2f)
        tmp.lineTo(cx - s * 0.06f, top + s * 0.34f)
        tmp.lineTo(cx + s * 0.13f, top + s * 0.12f)
        tmp.lineTo(cx + s * 0.01f, top + s * 0.12f)
        tmp.close()
        fill.color = BOLT
        c.drawPath(tmp, fill)
    }
}
