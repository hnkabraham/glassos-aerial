package io.github.abhishekkaisar.glassos

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Draws a Liquid Glass style rounded rect over the pre-blurred backdrop, in the order Apple's material builds up:
 *
 * 1. The face: the backdrop behind the shape. Regular glass (panels full of text) shows the frosted copy; clear glass
 *    ([clear]: the shelf, the pills, Now Playing) shows the lightly blurred copy through Apple's clear colour transform,
 *    dimmed a little over bright art, with some frost over it.
 * 2. Lensing: the rim bends the backdrop. [BANDS] stroked bands around the edge each sample it magnified about the
 *    shape's centre, by A(1 - sqrt(1 - x^2)) across the bevel (x 1 at the edge), so content near the edge comes from
 *    further in, most at the very rim. (Apple's rim also mirrors the content there; this one only bends it.)
 * 3. Shade and tint ([dark], [tint]); regular glass also gets a trace of grain and a top sheen ([grain], [sheen]),
 *    clear glass neither.
 * 4. Light on the rim: a dark hairline at the silhouette, a faint rim all round, and a specular glint: with the light
 *    from the top left, each part of the rim shines by how squarely it faces it (|n·L|²), so the top-left and
 *    bottom-right corners catch it most ([highlight], [border]).
 *
 * No per-frame blur: both blurs were made once per image. Each painter owns its own shaders, because on API 25 shader
 * matrices are shared by reference.
 */
class GlassPainter {
    companion object {
        /** Panel colour under reduce transparency: the night colour, a little lifted. */
        const val SOLID = 0xFF1A1E29.toInt()
        /** Bands in the refracting rim: each is one stroked round rect over the bevel. */
        private const val BANDS = 5
        /**
         * Where the bands start and end across the bevel, as a fraction of its width from the edge: thinner towards the
         * edge, where the bending changes fastest, so each band moves its sample by about the same step.
         */
        private val EDGES = floatArrayOf(0f, 0.06f, 0.15f, 0.30f, 0.52f, 1f)
        /** Each band's share of the bevel's light (1 at the edge): the slope of the glass there, so the rim glows. */
        private val SLOPE = FloatArray(BANDS) { k ->
            val x = { t: Float -> 1f - t }
            val s = { k2: Int -> val m = (EDGES[k2] + EDGES[k2 + 1]) / 2f; 1f - sqrt((1f - x(m) * x(m)).coerceAtLeast(0f)) }
            s(k) / s(0)
        }
        /** The bevel's light at its edge: brighter by 10% plus 8 levels, less further in. */
        private const val BEVEL_GAIN = 0.10f
        private const val BEVEL_LIFT = 8f
        private val bevelFilters = arrayOfNulls<ColorMatrixColorFilter>(BANDS * 10)
        /** How much of the frosted copy lies over clear glass's face (Apple mixes a light and a fully diffused blur). */
        private const val FROST = 0.24f
        /** The light's direction on screen (top left), as an angle clockwise from the right, in degrees. */
        private const val LIGHT = 225.0
        /**
         * How tightly the glint gathers where the rim faces the light: |n·L| to this power, the same along the corners
         * and the straight edges, so the glint has no step where they meet (the long top edge at half the corners').
         */
        private const val FALLOFF = 2.0
        /** Brightness of the rim at [deg] (its outward normal's angle): facing the light, or facing away a little less. */
        private fun shine(deg: Double): Float {
            val c = cos(Math.toRadians(deg - LIGHT))
            return (Math.pow(abs(c), FALLOFF) * (if (c >= 0) 1.0 else 0.6)).toFloat()
        }

        /** Band [k]'s filter: its share of the bevel's light, after the clear face's colour at [dim] (null for regular). */
        private fun bevelFilter(k: Int, dim: Float?): ColorMatrixColorFilter {
            val i = k * 10 + (if (dim == null) 9 else Math.round(dim / 0.35f * 8f).coerceIn(0, 8))
            bevelFilters[i]?.let { return it }
            val s = SLOPE[k]
            val g = 1f + BEVEL_GAIN * s
            val light = ColorMatrix(floatArrayOf(g, 0f, 0f, 0f, BEVEL_LIFT * s, 0f, g, 0f, 0f, BEVEL_LIFT * s,
                0f, 0f, g, 0f, BEVEL_LIFT * s, 0f, 0f, 0f, 1f, 0f))
            if (dim != null) light.preConcat(clearMatrix(dim))
            return ColorMatrixColorFilter(light).also { bevelFilters[i] = it }
        }
        /** The clear face's colour transform at 9 dimming steps (0 to 35% darker over bright art, per Apple's HIG). */
        private val clearFilters = arrayOfNulls<ColorMatrixColorFilter>(9)

        /**
         * Apple's clear glass as fitted from captures: out = g*C + (T - g)*Y + E, with chroma gain g 1.057, luma
         * transfer T 0.954 and a white emission of about 0.126, then darkened by [dim].
         */
        private fun clearFilter(dim: Float): ColorMatrixColorFilter {
            val i = Math.round(dim / 0.35f * 8f).coerceIn(0, 8)
            clearFilters[i]?.let { return it }
            return ColorMatrixColorFilter(clearMatrix(i * 0.35f / 8f)).also { clearFilters[i] = it }
        }

        private fun clearMatrix(dim: Float): ColorMatrix {
            val g = 1.057f
            val t = 0.954f
            val e = 32f
            val k = 1f - Math.round(dim / 0.35f * 8f).coerceIn(0, 8) * 0.35f / 8f
            val y = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
            val m = FloatArray(20)
            for (row in 0 until 3) {
                for (col in 0 until 3) m[row * 5 + col] = ((if (row == col) g else 0f) + (t - g) * y[col]) * k
                m[row * 5 + 4] = e * k
            }
            m[18] = 1f
            return ColorMatrix(m)
        }
    }

    var tint = 0.14f
    var tintColor = Color.WHITE
    var dark = 0f
    var border = 0.26f
    var highlight = 0.42f
    var sheen = 0.09f
    /** Liquid Glass faces are smooth: a trace of grain only, enough to keep the upscaled blur from banding. */
    var grain = 0.10f
    var stroke = 1.5f
    /**
     * Draw the parts that do not follow the backdrop (dark, tint, grain, sheen, rim) from one cached bitmap: one
     * textured pass instead of several full-size passes per frame. For large, static glass (the shelf).
     */
    var cacheStatic = false
    /** Clear glass: for surfaces that float over the scenery with little text (see the class comment). */
    var clear = false
    /** How strongly the rim bends the backdrop: 0 not at all, 1 the variant's usual amount. */
    var lens = 1f
    /**
     * Text sits on this glass: over bright art it darkens past [dark] as far as white text needs for 4.5:1 (measured
     * from the backdrop once per image and position).
     */
    var textGuard = false

    /** A paint drawing one backdrop bitmap through its own shader, rebuilt only when the bitmap changes. */
    private class Sampler(style: Paint.Style = Paint.Style.FILL) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.style = style }
        private var bmp: Bitmap? = null
        fun use(b: Bitmap, local: Matrix, alpha: Float): Paint {
            if (b !== bmp) { bmp = b; paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            paint.shader.setLocalMatrix(local)
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            return paint
        }
    }

    private val faceA = Sampler()
    private val faceB = Sampler()
    private val liteA = Sampler()
    private val liteB = Sampler()
    private val frostA = Sampler()
    private val frostB = Sampler()
    private val bandsA = Array(BANDS) { Sampler(Paint.Style.STROKE) }
    private val bandsB = Array(BANDS) { Sampler(Paint.Style.STROKE) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = Grain.newShader() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val glintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    /** Each corner's glint, a sweep around the corner's own centre, so its brightness follows the turning rim. */
    private val cornerShaders = arrayOfNulls<Shader>(4)
    private val oval = RectF()
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33000000 }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val inv = Matrix()
    private val lm = Matrix()
    private val liteInv = Matrix()
    private val inset = RectF()
    private val onScreen = RectF()
    private val pts = FloatArray(2)
    private var key = ""
    private var staticBmp: Bitmap? = null
    private var staticKey = ""
    private val staticPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var dimOf: Bitmap? = null
    private var dimRect = ""
    private var dim = 0f
    /** The dimming drawn now, easing towards [dim]. */
    private var dimShown = -1f
    private var luma = 0.2f
    /** The dark hairline's opacity now (see [measureUnder]). */
    private var hairAlpha = 0f
    /** With [textGuard], the dark this glass needs over the art behind it for its text (at least [dark]). */
    private var guardDark = 0f

    fun draw(c: Canvas, v: View, r: RectF, radius: Float) {
        if (Effects.reduceTransparency) { drawSolid(c, r, radius); return }
        val a = Backdrop.a
        if (a.bmp != null) {
            viewToWindow(v, m)
            m.invert(inv)
            val density = v.resources.displayMetrics.density
            face(c, r, radius, a, true, density)
            // Still easing: another frame (a few at most, after the art behind changed).
            if (dimShown != dim) v.postInvalidateOnAnimation()
            val b = Backdrop.b
            if (b.bmp != null && b.alpha > 0.004f) face(c, r, radius, b, false, density)
        } else {
            fill.color = 0xFF141A30.toInt()
            c.drawRoundRect(r, radius, radius, fill)
        }
        if (cacheStatic) { drawStatic(c, r, radius); return }
        drawOverlay(c, r, radius)
    }

    /** The backdrop behind [r] and its refracting rim, for one layer ([isA]: the current one, else the incoming one). */
    private fun face(c: Canvas, r: RectF, radius: Float, l: BackLayer, isA: Boolean, density: Float) {
        val heavy = l.bmp ?: return
        val lite = l.lite
        val alpha = l.alpha
        if (isA) {
            measureUnder(r, l, if (clear && lite != null) lite else heavy, if (clear && lite != null) l.liteM else l.m)
            // The dimming eases towards what the art behind needs, so Live glass's new frames never step it.
            dimShown = if (dimShown < 0f) dim else dimShown + (dim - dimShown) * 0.35f
            if (Math.abs(dim - dimShown) < 0.005f) dimShown = dim
        }
        // The incoming layer's face only inside the bevel: its own bands cover the rim, so the rim crossfades cleanly.
        val faceRect = if (isA || lens <= 0f) r else bevelInset(r, density)
        val faceRadius = if (faceRect === r) radius else (radius - (faceRect.left - r.left)).coerceAtLeast(0f)
        if (clear && lite != null) {
            lm.set(inv); lm.preConcat(l.liteM)
            val p = (if (isA) liteA else liteB).use(lite, lm, alpha)
            p.colorFilter = clearFilter(0f)
            c.drawRoundRect(faceRect, faceRadius, faceRadius, p)
            // The rim bends the light blur; the dimming and then the frost lie evenly over face and rim alike.
            if (lens > 0f) bands(c, r, radius, l, isA, alpha, density)
            if (isA && dimShown > 0.002f) {
                fill.color = alphaOf(Color.BLACK, dimShown)
                c.drawRoundRect(r, radius, radius, fill)
            }
            lm.set(inv); lm.preConcat(l.m)
            c.drawRoundRect(r, radius, radius, (if (isA) frostA else frostB).use(heavy, lm, alpha * FROST))
        } else {
            lm.set(inv); lm.preConcat(l.m)
            c.drawRoundRect(faceRect, faceRadius, faceRadius, (if (isA) faceA else faceB).use(heavy, lm, alpha))
            if (lens > 0f) bands(c, r, radius, l, isA, alpha, density)
        }
    }

    /** The bevel's width for [r] (see [bands]). */
    private fun bevel(r: RectF, density: Float): Float {
        val short = minOf(r.width(), r.height())
        // Larger glass is thicker: a wider bevel and more bending (Apple: "more pronounced lensing" as glass grows),
        // from about 14 dp on a pill to 22 dp on a panel.
        return minOf(short * 0.25f, minOf(12f + 0.03f * short / density, 22f) * density)
    }

    private val faceInset = RectF()

    /** [r] less its bevel: the face inside the refracting rim. */
    private fun bevelInset(r: RectF, density: Float): RectF {
        val h = bevel(r, density)
        faceInset.set(r.left + h, r.top + h, r.right - h, r.bottom - h)
        return faceInset
    }

    /**
     * The lensing rim: [BANDS] stroked bands over the bevel, inner first, each sampling the backdrop magnified about
     * the centre so it shows what lies further in, and each a little brighter by the glass's slope there, so the bevel
     * reads as a lit, thicker edge (Apple's lensing is a wide zone, not an outline). Clear glass bends the light blur;
     * regular glass its frosted copy, more gently.
     */
    private fun bands(c: Canvas, r: RectF, radius: Float, l: BackLayer, isA: Boolean, alpha: Float, density: Float) {
        val src = (if (clear) l.lite else null) ?: l.bmp ?: return
        val srcM = if (src === l.lite) l.liteM else l.m
        val short = minOf(r.width(), r.height())
        val h = bevel(r, density)
        val amount = minOf(short * 0.5f, h * 2.4f) * lens * (if (clear) 1f else 0.55f)
        if (h < 2f || amount < 0.5f) return
        val halfW = r.width() / 2f
        val halfH = r.height() / 2f
        val cx = r.centerX()
        val cy = r.centerY()
        val set = if (isA) bandsA else bandsB
        val face = if (clear && src === l.lite) 0f else null
        for (k in BANDS - 1 downTo 0) {
            val t0 = EDGES[k] * h
            val t1 = EDGES[k + 1] * h
            val x = 1f - (t0 + t1) / 2f / h
            val d = amount * (1f - sqrt((1f - x * x).coerceAtLeast(0f)))
            val sx = (1f - d / halfW).coerceAtLeast(0.2f)
            val sy = (1f - d / halfH).coerceAtLeast(0.2f)
            lm.set(inv); lm.preConcat(srcM)
            lm.postScale(1f / sx, 1f / sy, cx, cy)
            val p = set[k].use(src, lm, alpha)
            // Each stroke overlaps the next band inward by a pixel, never outward: the outer band's edge lies exactly
            // on the silhouette, with no fringe outside it.
            p.strokeWidth = t1 - t0 + 1f
            p.colorFilter = bevelFilter(k, face)
            val t = t0 + p.strokeWidth / 2f
            inset.set(r.left + t, r.top + t, r.right - t, r.bottom - t)
            val rr = (radius - t).coerceAtLeast(0f)
            c.drawRoundRect(inset, rr, rr, p)
        }
    }

    /**
     * How bright the art behind [r] is (mean plus half its spread, so a bright patch behind part of a wide shelf
     * counts), measured from [bmp] once per image and position, never per frame. It sets clear glass's dimming (0 over
     * dark art, up to 35% over bright art, Apple's HIG for clear glass on media), how far [textGuard] darkens, and
     * the hairline's strength.
     */
    private fun measureUnder(r: RectF, l: BackLayer, bmp: Bitmap, bmpM: Matrix) {
        onScreen.set(r)
        m.mapRect(onScreen)
        val k = "${onScreen.left.toInt() / 8},${onScreen.top.toInt() / 8},${onScreen.width().toInt() / 8},${onScreen.height().toInt() / 8}"
        if (bmp === dimOf && k == dimRect) return
        dimOf = bmp
        dimRect = k
        luma = try {
            bmpM.invert(liteInv)
            var sum = 0.0
            var sq = 0.0
            var n = 0
            for (iy in 0 until 6) for (ix in 0 until 10) {
                pts[0] = onScreen.left + onScreen.width() * (ix + 0.5f) / 10f
                pts[1] = onScreen.top + onScreen.height() * (iy + 0.5f) / 6f
                liteInv.mapPoints(pts)
                val px = pts[0].toInt().coerceIn(0, bmp.width - 1)
                val py = pts[1].toInt().coerceIn(0, bmp.height - 1)
                val col = bmp.getPixel(px, py)
                val y = (0.2126 * ((col shr 16) and 255) + 0.7152 * ((col shr 8) and 255) + 0.0722 * (col and 255)) / 255.0
                sum += y; sq += y * y; n++
            }
            val mean = sum / n
            (mean + 0.5 * sqrt((sq / n - mean * mean).coerceAtLeast(0.0))).toFloat().coerceIn(0f, 1f)
        } catch (e: Exception) { 0.2f }
        val t = ((luma - 0.40f) / 0.40f).coerceIn(0f, 1f)
        dim = if (clear) t * t * (3 - 2 * t) * 0.35f else 0f
        // The dark hairline only where it defines the edge: over bright art, not over night skies.
        val h = ((luma - 0.45f) / 0.40f).coerceIn(0f, 1f)
        hairAlpha = Math.round(0.22f * h * h * (3 - 2 * h) * 20f) / 20f
        guardDark = if (!textGuard) 0f else {
            var d = dark
            var guard = 0
            while (Tone.contrast(luma, tint, d) < 4.5f && d < 0.75f && guard++ < 60) d += 0.01f
            Math.round(d * 50f) / 50f
        }
    }

    /**
     * Reduce transparency: an opaque panel (lighter for more tint, as the glass would look) and the rim, with no
     * backdrop sampling, grain or sheen. It does not change with the art behind it, so it never needs redrawing for it.
     */
    private fun drawSolid(c: Canvas, r: RectF, radius: Float) {
        fill.color = lerpColor(Themes.current.solid, Themes.current.tint, (tint * 0.6f).coerceIn(0f, 0.18f))
        c.drawRoundRect(r, radius, radius, fill)
        edge.shader = null
        edge.strokeWidth = stroke
        edge.color = alphaOf(Color.WHITE, (0.10f + highlight * 0.35f).coerceAtMost(0.4f))
        val h = stroke / 2f
        inset.set(r.left + h, r.top + h, r.right - h, r.bottom - h)
        c.drawRoundRect(inset, radius - h, radius - h, edge)
        // The gradient rim is rebuilt the next time glass is drawn.
        key = ""
    }

    private fun drawStatic(c: Canvas, r: RectF, radius: Float) {
        val sk = "${r.left},${r.top},${r.width()},${r.height()},$radius,$dark,$guardDark,$hairAlpha,$tint,$tintColor,$grain,$sheen,$highlight,$border,$stroke,$clear,${Themes.current.id}"
        if (sk != staticKey || staticBmp == null) {
            staticKey = sk
            // Not recycled: the last recorded frame may still draw the old one.
            val bw = Math.ceil(r.width().toDouble()).toInt().coerceAtLeast(1)
            val bh = Math.ceil(r.height().toDouble()).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val bc = Canvas(b)
            // Same coordinates as on screen, so the grain tile and gradients land exactly where they did.
            bc.translate(-r.left, -r.top)
            drawOverlay(bc, r, radius)
            staticBmp = b
        }
        c.drawBitmap(staticBmp!!, r.left, r.top, staticPaint)
    }

    private fun drawOverlay(c: Canvas, r: RectF, radius: Float) {
        val shade = (maxOf(dark, guardDark) + Themes.current.shade).coerceAtMost(0.85f)
        if (shade > 0f) {
            fill.color = alphaOf(if (Themes.current.id == "glass") 0xFF040814.toInt() else Themes.current.solid, shade)
            c.drawRoundRect(r, radius, radius, fill)
        }
        if (tint > 0f) {
            fill.color = alphaOf(if (tintColor == Color.WHITE) Themes.current.tint else tintColor, tint)
            c.drawRoundRect(r, radius, radius, fill)
        }
        // Clear glass is smooth: no grain.
        val g = if (clear) 0f else grain
        if (g > 0f) {
            grainPaint.alpha = (g * 255).toInt()
            c.drawRoundRect(r, radius, radius, grainPaint)
        }
        // Liquid Glass is evenly lit, with the light on the rim: no gloss on clear glass, a trace on panels.
        val sh = if (clear) 0f else sheen.coerceAtMost(0.04f)
        val k = "${r.top},${r.height()},$radius,$sh"
        if (k != key) {
            key = k
            sheenPaint.shader = if (sh > 0f) LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.6f,
                intArrayOf(alphaOf(Color.WHITE, sh), alphaOf(Color.WHITE, sh * 0.25f), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) else null
            // The glass's thickness: a little shade gathers inside the bottom edge.
            depthPaint.shader = LinearGradient(0f, r.bottom - r.height() * 0.35f, 0f, r.bottom, 0, 0x14000000, Shader.TileMode.CLAMP)
            // A faint rim all round: brighter at the top, where the light comes from. Built at full strength and dimmed
            // by [border] with the paint, so focus can brighten it without a new gradient each frame.
            edge.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.WHITE, alphaOf(Color.WHITE, 0.41f), alphaOf(Color.WHITE, 0.64f)),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            glintKey = ""
        }
        if (sheenPaint.shader != null) c.drawRoundRect(r, radius, radius, sheenPaint)
        c.drawRoundRect(r, radius, radius, depthPaint)
        // A dark hairline at the silhouette gives the glass its edge over bright art (none over dark art).
        hairline.strokeWidth = stroke * 0.6f
        var h = hairline.strokeWidth / 2f
        if (hairAlpha > 0f) {
            hairline.color = alphaOf(Color.BLACK, hairAlpha)
            inset.set(r.left + h, r.top + h, r.right - h, r.bottom - h)
            c.drawRoundRect(inset, radius - h, radius - h, hairline)
        }
        h = stroke * 0.6f + stroke / 2f
        inset.set(r.left + h, r.top + h, r.right - h, r.bottom - h)
        if (border > 0f) {
            edge.strokeWidth = stroke
            edge.alpha = ((border * 1.1f).coerceAtMost(1f) * 255).toInt()
            c.drawRoundRect(inset, radius - h, radius - h, edge)
        }
        if (highlight > 0f) drawGlint(c, inset, (radius - h).coerceAtLeast(0f), stroke * 1.3f)
    }

    private var glintKey = ""

    /**
     * The specular glint along the rim [r] (already inset): straight edges at their fixed brightness, and each corner
     * as an arc whose sweep gradient, centred on the corner, follows the rim as it turns towards or away from the light.
     */
    private fun drawGlint(c: Canvas, r: RectF, radius: Float, width: Float) {
        val peak = highlight.coerceAtMost(0.95f)
        // The sweeps are built at full strength and dimmed with the paint, so a focus animation that raises the
        // highlight every frame never rebuilds them.
        val k = "${r.left},${r.top},${r.width()},${r.height()},$radius"
        // Corner centres, clockwise from the top left, and where each corner's arc starts (Canvas angles).
        val cx = floatArrayOf(r.left + radius, r.right - radius, r.right - radius, r.left + radius)
        val cy = floatArrayOf(r.top + radius, r.top + radius, r.bottom - radius, r.bottom - radius)
        val start = floatArrayOf(180f, 270f, 0f, 90f)
        if (k != glintKey) {
            glintKey = k
            for (i in 0 until 4) {
                val steps = 24
                val colors = IntArray(steps + 1) { s -> alphaOf(Color.WHITE, shine(s * 360.0 / steps)) }
                cornerShaders[i] = SweepGradient(cx[i], cy[i], colors, FloatArray(steps + 1) { it / steps.toFloat() })
            }
        }
        glint.strokeWidth = width
        glint.alpha = (peak * 255).toInt()
        glintLine.strokeWidth = width
        if (radius > 0.5f) for (i in 0 until 4) {
            glint.shader = cornerShaders[i]
            oval.set(cx[i] - radius, cy[i] - radius, cx[i] + radius, cy[i] + radius)
            c.drawArc(oval, start[i], 90f, false, glint)
        }
        // The straight edges face up, right, down and left.
        glintLine.color = alphaOf(Color.WHITE, peak * shine(270.0))
        if (r.width() > 2 * radius) c.drawLine(r.left + radius, r.top, r.right - radius, r.top, glintLine)
        glintLine.color = alphaOf(Color.WHITE, peak * shine(0.0))
        if (r.height() > 2 * radius) c.drawLine(r.right, r.top + radius, r.right, r.bottom - radius, glintLine)
        glintLine.color = alphaOf(Color.WHITE, peak * shine(90.0))
        if (r.width() > 2 * radius) c.drawLine(r.left + radius, r.bottom, r.right - radius, r.bottom, glintLine)
        glintLine.color = alphaOf(Color.WHITE, peak * shine(180.0))
        if (r.height() > 2 * radius) c.drawLine(r.left, r.top + radius, r.left, r.bottom - radius, glintLine)
    }
}
