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
 * 2. Lensing: the rim bends the backdrop. [BANDS] stroked bands around the edge each show it moved inward along the
 *    rim's normal, by A(1 - sqrt(1 - x^2)) across the bevel (x 1 at the edge), so content near the edge comes from
 *    further in, most at the very rim. (Apple's rim also mirrors the content there; this one only bends it.)
 * 3. Shade and tint ([dark], [tint]); regular glass also gets a trace of grain and a top sheen ([grain], [sheen]),
 *    clear glass neither.
 * 4. Light on the rim: a dark edge over bright art, a faint rim all round, and a specular glint: with the light from
 *    above and a little to the left ([light]), each part of the rim shines by how squarely it faces it (|n·L|²), so
 *    the top edge and the top-left corner catch it most, and the bottom edge, facing away, some ([highlight], [border]).
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
        private val bevelFilters = arrayOfNulls<ColorMatrixColorFilter>(BANDS * 2)
        /** [edgeOutside]'s contact shadow: each stroke's width (in [stroke]s) and strength (of the hairline's opacity). */
        private val CONTACT_W = floatArrayOf(0.8f, 2.2f)
        private val CONTACT_A = floatArrayOf(1f, 0.4f)
        /** How much of the frosted copy lies over clear glass's face (Apple mixes a light and a fully diffused blur). */
        private const val FROST = 0.24f
        /**
         * The light's usual direction on screen, as an angle clockwise from the right, in degrees: from above and a
         * little to the left, so the long top edges (the shelf's, the panels') catch it well. The corner glints are
         * built for it and turned when [light] differs.
         */
        const val LIGHT = 240f
        /**
         * How tightly the glint gathers where the rim faces the light: |n·L| to this power, the same along the corners
         * and the straight edges, so the glint has no step where they meet (the long top edge at three quarters of the
         * top-left corner's).
         */
        private const val FALLOFF = 2.0
        /**
         * Brightness of the rim at [deg] (its outward normal's angle) with the light at [light]: facing the light, or
         * facing away a little less.
         */
        private fun shine(deg: Double, light: Double = LIGHT.toDouble()): Float {
            val c = cos(Math.toRadians(deg - light))
            return (Math.pow(abs(c), FALLOFF) * (if (c >= 0) 1.0 else 0.6)).toFloat()
        }

        /** Band [k]'s filter: its share of the bevel's light, after the clear face's colour for [clear] glass. */
        private fun bevelFilter(k: Int, clear: Boolean): ColorMatrixColorFilter {
            val i = k * 2 + (if (clear) 1 else 0)
            bevelFilters[i]?.let { return it }
            return ColorMatrixColorFilter(bevelMatrix(k, clear)).also { bevelFilters[i] = it }
        }

        private fun bevelMatrix(k: Int, clear: Boolean): ColorMatrix {
            val s = SLOPE[k]
            val g = 1f + BEVEL_GAIN * s
            val light = ColorMatrix(floatArrayOf(g, 0f, 0f, 0f, BEVEL_LIFT * s, 0f, g, 0f, 0f, BEVEL_LIFT * s,
                0f, 0f, g, 0f, BEVEL_LIFT * s, 0f, 0f, 0f, 1f, 0f))
            if (clear) light.preConcat(clearMatrix())
            return light
        }

        /**
         * Apple's clear glass as fitted from captures: out = g*C + (T - g)*Y + E, with chroma gain g 1.057, luma
         * transfer T 0.954 and a white emission of about 0.126. Its dimming over bright art is drawn separately (see
         * [face]).
         */
        private val clearFilter by lazy { ColorMatrixColorFilter(clearMatrix()) }

        /**
         * How each band of the lensing rim bends the backdrop (see [bands]), for glass [w] by [h] with corners of
         * [radius], a bevel [bevel] deep and bending of [amount]. Each band shows what lies [LensBand.d] further in along
         * its inward normal: d = amount (1 - sqrt(1 - x^2)) at the band's middle, x 1 at the edge, and at most 0.7 of
         * the band's corner radius there, so a corner never gathers its whole arc into one point.
         */
        internal fun lensBands(w: Float, h: Float, radius: Float, bevel: Float, amount: Float): Array<LensBand> =
            Array(BANDS) { k ->
                val t0 = EDGES[k] * bevel
                val t1 = EDGES[k + 1] * bevel
                val tc = (t0 + t1) / 2f
                val x = 1f - tc / bevel
                val rho = radius - tc
                var d = amount * (1f - sqrt((1f - x * x).coerceAtLeast(0f)))
                if (rho > 1f) d = minOf(d, 0.7f * rho)
                LensBand(t0, t1, d,
                    sx = (1f - d / (w / 2f - tc)).coerceAtLeast(0.2f),
                    sy = (1f - d / (h / 2f - tc)).coerceAtLeast(0.2f),
                    s = if (rho > 1f) (rho - d) / rho else 0f)
            }

        private fun clearMatrix(): ColorMatrix {
            val g = 1.057f
            val t = 0.954f
            val e = 32f
            val y = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
            val m = FloatArray(20)
            for (row in 0 until 3) {
                for (col in 0 until 3) m[row * 5 + col] = (if (row == col) g else 0f) + (t - g) * y[col]
                m[row * 5 + 4] = e
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
    /** Where the light comes from ([LIGHT] unless set): focus swings it, so the glint slides round the rim. */
    var light = LIGHT
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
    /**
     * Over bright art the glass's edge is defined by a soft dark line just outside it, a contact shadow, rather than
     * by darkening its outermost pixels: for glass whose parent lets it draw past its bounds and that floats over the
     * scenery (the shelf, the pills, Now Playing). Panels over a dimmed screen keep the line inside.
     */
    var edgeOutside = false
    /** Clear glass's dimming now (0 to 0.35), for a shadow that deepens over bright art as Apple's does. */
    val dimNow: Float get() = maxOf(dimShown, 0f)

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
    /** Per band, its six pieces (two runs, four corners); each draws with its own shader matrix. */
    private val bandsA = Array(BANDS) { Array(6) { Sampler(Paint.Style.STROKE) } }
    private val bandsB = Array(BANDS) { Array(6) { Sampler(Paint.Style.STROKE) } }
    private val clip = RectF()
    /** [lensBands] for the last shape drawn, kept while it stays the same. */
    private val lensFor = FloatArray(5)
    private var lensNow: Array<LensBand>? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = Grain.newShader() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val glintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    /** Each corner's glint, a sweep around the corner's own centre, so its brightness follows the turning rim. */
    private val cornerShaders = arrayOfNulls<Shader>(4)
    /** Each corner sweep's turn from [LIGHT] to [light]: one matrix per shader (API 25 keeps it by reference). */
    private val cornerTurns = Array(4) { Matrix() }
    private val oval = RectF()
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33000000 }
    private val contact = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
    /** What [measureUnder] found behind the glass in one backdrop layer's image, kept until the image or place changes. */
    private class Under {
        var of: Bitmap? = null
        var rect = ""
        /** Clear glass's dimming over this image. */
        var dim = 0f
        /** The dark hairline's opacity. */
        var hair = 0f
        /** With [textGuard], the dark this glass needs over the image for its text (at least [dark]). */
        var guard = 0f
    }

    /** Behind the glass in the current slide (layer A) and in the one fading in over it (layer B). */
    private val underA = Under()
    private val underB = Under()
    /** Layer A's dimming drawn now, easing towards what its image needs. */
    private var dimShown = -1f
    /** The dark hairline's opacity now (see [measureUnder]). */
    private var hairAlpha = 0f
    /** With [textGuard], the dark this glass needs over the art behind it for its text (at least [dark]). */
    private var guardDark = 0f
    /** The incoming layer's face and band filters, darkened by its own dimming; made once per image. */
    private val incomingFilters = arrayOfNulls<ColorMatrixColorFilter>(BANDS + 1)
    private var incomingDim = -1f

    fun draw(c: Canvas, v: View, r: RectF, radius: Float) {
        if (Effects.reduceTransparency) { drawSolid(c, r, radius); return }
        val a = Backdrop.a
        if (a.bmp != null) {
            viewToWindow(v, m)
            m.invert(inv)
            val density = v.resources.displayMetrics.density
            face(c, r, radius, a, true, density)
            // Still easing: another frame (a few at most, after the art behind changed).
            if (dimShown != underA.dim) v.postInvalidateOnAnimation()
            val b = Backdrop.b
            val fading = b.bmp != null && b.alpha > 0.004f
            if (fading) face(c, r, radius, b, false, density)
            // Only a slide that has just faded in may carry its dimming over (see [face]), not one shown long ago.
            else underB.of = null
            // The text guard and the hairline follow whichever slide shows more, so through a fade they change once,
            // halfway, where the crossfade hides it (and large glass redraws its cached rim once, as before).
            val u = if (fading && b.alpha >= 0.5f) underB else underA
            guardDark = u.guard
            hairAlpha = u.hair
        } else {
            fill.color = 0xFF141A30.toInt()
            c.drawRoundRect(r, radius, radius, fill)
        }
        if (cacheStatic) drawStatic(c, r, radius) else drawOverlay(c, r, radius)
        if (edgeOutside && hairAlpha > 0f) drawContact(c, r, radius)
    }

    /** [edgeOutside]'s contact shadow: a thin dark line against the silhouette and a fainter, wider one round it. */
    private fun drawContact(c: Canvas, r: RectF, radius: Float) {
        for (i in CONTACT_W.indices) {
            contact.strokeWidth = stroke * CONTACT_W[i]
            contact.color = alphaOf(Color.BLACK, hairAlpha * CONTACT_A[i])
            val h = contact.strokeWidth / 2f
            inset.set(r.left - h, r.top - h, r.right + h, r.bottom + h)
            c.drawRoundRect(inset, radius + h, radius + h, contact)
        }
    }

    /** The backdrop behind [r] and its refracting rim, for one layer ([isA]: the current one, else the incoming one). */
    private fun face(c: Canvas, r: RectF, radius: Float, l: BackLayer, isA: Boolean, density: Float) {
        val heavy = l.bmp ?: return
        val alpha = l.alpha
        // Clear glass shows the light blur when the layer has one.
        val lite = if (clear) l.lite else null
        val under = lite ?: heavy
        val underM = if (lite != null) l.liteM else l.m
        if (isA) {
            val was = underA.of
            measureUnder(r, under, underM, underA)
            if (underA.of !== was && underA.of === underB.of) {
                // The slide that was fading in is now the current one. It was drawn at its own dimming all through the
                // fade, so the dimming carries on from there rather than easing over from the old slide's.
                dimShown = underA.dim
            }
            // The dimming eases towards what the art behind needs, so Live glass's new frames never step it.
            val dim = underA.dim
            dimShown = if (dimShown < 0f) dim else dimShown + (dim - dimShown) * 0.35f
            if (Math.abs(dim - dimShown) < 0.005f) dimShown = dim
        } else measureUnder(r, under, underM, underB)
        // The incoming layer is drawn over the current one, so a dark fill would darken both: its own dimming goes
        // into its colour filters instead (the same darkening, as the fill gives the current layer).
        val dimIn = if (isA) 0f else underB.dim
        // The incoming layer's face only inside the bevel: its own bands cover the rim, so the rim crossfades cleanly.
        val faceRect = if (isA || lens <= 0f) r else bevelInset(r, density)
        val faceRadius = if (faceRect === r) radius else (radius - (faceRect.left - r.left)).coerceAtLeast(0f)
        if (lite != null) {
            lm.set(inv); lm.preConcat(l.liteM)
            val p = (if (isA) liteA else liteB).use(lite, lm, alpha)
            p.colorFilter = if (dimIn > 0.002f) incomingFilter(BANDS, dimIn) else clearFilter
            c.drawRoundRect(faceRect, faceRadius, faceRadius, p)
            // The rim bends the light blur; the dimming and then the frost lie evenly over face and rim alike.
            if (lens > 0f) bands(c, r, radius, l, isA, alpha, density, dimIn)
            if (isA && dimShown > 0.002f) {
                fill.color = alphaOf(Color.BLACK, dimShown)
                c.drawRoundRect(r, radius, radius, fill)
            }
            lm.set(inv); lm.preConcat(l.m)
            c.drawRoundRect(r, radius, radius, (if (isA) frostA else frostB).use(heavy, lm, alpha * FROST))
        } else {
            lm.set(inv); lm.preConcat(l.m)
            c.drawRoundRect(faceRect, faceRadius, faceRadius, (if (isA) faceA else faceB).use(heavy, lm, alpha))
            if (lens > 0f) bands(c, r, radius, l, isA, alpha, density, 0f)
        }
    }

    /**
     * The incoming layer's filter darkened by its [dim]: band [i]'s, or the clear face's for [BANDS]. The same as the
     * dark fill over the current layer: everything it draws scaled by 1 - dim.
     */
    private fun incomingFilter(i: Int, dim: Float): ColorMatrixColorFilter {
        if (dim != incomingDim) { incomingDim = dim; incomingFilters.fill(null) }
        incomingFilters[i]?.let { return it }
        val cm = if (i == BANDS) clearMatrix() else bevelMatrix(i, true)
        cm.postConcat(ColorMatrix().apply { setScale(1f - dim, 1f - dim, 1f - dim, 1f) })
        return ColorMatrixColorFilter(cm).also { incomingFilters[i] = it }
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
     * The lensing rim: [BANDS] stroked bands over the bevel, inner first, each showing the backdrop moved inward along
     * the rim's normal by [lensBands], so the edge shows what lies further in, and each a little brighter by the glass's
     * slope there, so the bevel reads as a lit, thicker edge (Apple's lensing is a wide zone, not an outline). Clear
     * glass bends the light blur; regular glass its frosted copy, more gently. [dimIn]: the incoming layer's dimming
     * (see [incomingFilter]).
     *
     * Each band is drawn in six clipped pieces, so nothing slides along the edge: the left and right runs move the
     * backdrop across only, the top and bottom runs up or down only, and each corner towards its own centre; where an
     * arc meets a run both move it by the same amount, so lines crossing the rim stay whole.
     */
    private fun bands(c: Canvas, r: RectF, radius: Float, l: BackLayer, isA: Boolean, alpha: Float, density: Float, dimIn: Float) {
        val src = (if (clear) l.lite else null) ?: l.bmp ?: return
        val srcM = if (src === l.lite) l.liteM else l.m
        val short = minOf(r.width(), r.height())
        val h = bevel(r, density)
        val amount = minOf(short * 0.5f, h * 2.4f) * lens * (if (clear) 1f else 0.55f)
        if (h < 2f || amount < 0.5f) return
        val lb = lensOf(r.width(), r.height(), radius, h, amount)
        val cx = r.centerX()
        val cy = r.centerY()
        val set = if (isA) bandsA else bandsB
        val clearSrc = clear && src === l.lite
        // Every glass here is rounder than its bevel is deep; one that is not would have its runs overlap, so it bends
        // its whole rim about the centre instead, in one piece.
        val split = radius > h + 1f
        for (k in BANDS - 1 downTo 0) {
            val band = lb[k]
            // Each stroke overlaps the next band inward by a pixel, never outward: the outer band's edge lies exactly
            // on the silhouette, with no fringe outside it.
            val width = band.t1 - band.t0 + 1f
            val t = band.t0 + width / 2f
            inset.set(r.left + t, r.top + t, r.right - t, r.bottom - t)
            val rr = (radius - t).coerceAtLeast(0f)
            val filter = if (clearSrc && dimIn > 0.002f) incomingFilter(k, dimIn) else bevelFilter(k, clearSrc)
            for (i in 0 until (if (split) 6 else 1)) {
                lm.set(inv); lm.preConcat(srcM)
                when {
                    !split -> { clip.set(r); lm.postScale(1f / band.sx, 1f / band.sy, cx, cy) }
                    i == 0 -> {
                        // The left and right runs.
                        if (r.height() <= 2 * radius + 0.5f) continue
                        clip.set(r.left, r.top + radius, r.right, r.bottom - radius)
                        lm.postScale(1f / band.sx, 1f, cx, cy)
                    }
                    i == 1 -> {
                        // The top and bottom runs.
                        if (r.width() <= 2 * radius + 0.5f) continue
                        clip.set(r.left + radius, r.top, r.right - radius, r.bottom)
                        lm.postScale(1f, 1f / band.sy, cx, cy)
                    }
                    else -> {
                        // A corner, clockwise from the top left, about its own centre.
                        val left = i == 2 || i == 5
                        val top = i == 2 || i == 3
                        val ccx = if (left) r.left + radius else r.right - radius
                        val ccy = if (top) r.top + radius else r.bottom - radius
                        clip.set(if (left) r.left else ccx, if (top) r.top else ccy, if (left) ccx else r.right, if (top) ccy else r.bottom)
                        lm.postScale(1f / band.s, 1f / band.s, ccx, ccy)
                    }
                }
                val p = set[k][i].use(src, lm, alpha)
                p.strokeWidth = width
                p.colorFilter = filter
                c.save()
                c.clipRect(clip)
                c.drawRoundRect(inset, rr, rr, p)
                c.restore()
            }
        }
    }

    /** [lensBands] for this shape, worked out again only when it changes. */
    private fun lensOf(w: Float, h: Float, radius: Float, bevel: Float, amount: Float): Array<LensBand> {
        val f = lensFor
        lensNow?.let { if (f[0] == w && f[1] == h && f[2] == radius && f[3] == bevel && f[4] == amount) return it }
        f[0] = w; f[1] = h; f[2] = radius; f[3] = bevel; f[4] = amount
        return lensBands(w, h, radius, bevel, amount).also { lensNow = it }
    }

    /**
     * How bright the art behind [r] is (mean plus half its spread, so a bright patch behind part of a wide shelf
     * counts), measured from [bmp] once per image and position, never per frame. It sets clear glass's dimming (0 over
     * dark art, up to 35% over bright art, Apple's HIG for clear glass on media), how far [textGuard] darkens, and
     * the hairline's strength, into [u] (one per backdrop layer).
     */
    private fun measureUnder(r: RectF, bmp: Bitmap, bmpM: Matrix, u: Under) {
        onScreen.set(r)
        m.mapRect(onScreen)
        val k = "${onScreen.left.toInt() / 8},${onScreen.top.toInt() / 8},${onScreen.width().toInt() / 8},${onScreen.height().toInt() / 8}"
        if (bmp === u.of && k == u.rect) return
        u.of = bmp
        u.rect = k
        val luma = try {
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
        u.dim = if (clear) t * t * (3 - 2 * t) * 0.35f else 0f
        // The dark hairline only where it defines the edge: over bright art, not over night skies.
        val h = ((luma - 0.45f) / 0.40f).coerceIn(0f, 1f)
        u.hair = Math.round(0.22f * h * h * (3 - 2 * h) * 20f) / 20f
        u.guard = if (!textGuard) 0f else {
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
        val sk = "${r.left},${r.top},${r.width()},${r.height()},$radius,$dark,$guardDark,${if (edgeOutside) 0f else hairAlpha},$tint,$tintColor,$grain,$sheen,$highlight,$border,$stroke,$light,$clear,${Themes.current.id}"
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
        // A dark hairline at the silhouette gives the glass its edge over bright art (none over dark art), unless the
        // edge is drawn outside it ([edgeOutside]).
        hairline.strokeWidth = stroke * 0.6f
        var h = hairline.strokeWidth / 2f
        if (hairAlpha > 0f && !edgeOutside) {
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
            // Turned about the corner's centre, so the brightest part of the arc follows the light.
            cornerTurns[i].setRotate(light - LIGHT, cx[i], cy[i])
            cornerShaders[i]!!.setLocalMatrix(cornerTurns[i])
            glint.shader = cornerShaders[i]
            oval.set(cx[i] - radius, cy[i] - radius, cx[i] + radius, cy[i] + radius)
            c.drawArc(oval, start[i], 90f, false, glint)
        }
        // The straight edges face up, right, down and left.
        val l = light.toDouble()
        glintLine.color = alphaOf(Color.WHITE, peak * shine(270.0, l))
        if (r.width() > 2 * radius) c.drawLine(r.left + radius, r.top, r.right - radius, r.top, glintLine)
        glintLine.color = alphaOf(Color.WHITE, peak * shine(0.0, l))
        if (r.height() > 2 * radius) c.drawLine(r.right, r.top + radius, r.right, r.bottom - radius, glintLine)
        glintLine.color = alphaOf(Color.WHITE, peak * shine(90.0, l))
        if (r.width() > 2 * radius) c.drawLine(r.left + radius, r.bottom, r.right - radius, r.bottom, glintLine)
        glintLine.color = alphaOf(Color.WHITE, peak * shine(180.0, l))
        if (r.height() > 2 * radius) c.drawLine(r.left, r.top + radius, r.left, r.bottom - radius, glintLine)
    }
}

/**
 * One band of the lensing rim ([GlassPainter.lensBands]): it covers [t0] to [t1] across the bevel, measured from the
 * edge, and shows the backdrop [d] further in. The left and right runs get it by scaling across by [sx] about the
 * centre, the top and bottom runs by scaling up or down by [sy], and each corner by scaling by [s] about its own
 * centre (0 when the band lies deeper than the corner is round).
 */
internal class LensBand(val t0: Float, val t1: Float, val d: Float, val sx: Float, val sy: Float, val s: Float)
