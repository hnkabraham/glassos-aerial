package io.github.abhishekkaisar.glassos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** App art at thumbnail size: the banner, or the icon centred on its own colour. */
object AppArt {
    fun thumb(a: AppEntry, w: Int, h: Int): Bitmap? {
        val art = a.art ?: return null
        if (a.isBanner) return art
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(a.color)
        val s = h * 0.62f
        c.drawBitmap(art, null, RectF((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return b
    }
}

/**
 * A folder's tile: up to six of its apps in a small grid on dark glass, at banner size, so the grid draws it like
 * any other banner tile. Rendered once per folder and contents and cached (both home screens share it).
 */
object FolderArt {
    private const val W = 400
    private const val H = 225
    private val cache = LinkedHashMap<String, Bitmap>()

    fun render(id: String, members: List<AppEntry>): Bitmap {
        val shown = members.take(6)
        val key = id + "|" + shown.joinToString(",") { it.pkg + ":" + System.identityHashCode(it.art) }
        cache[key]?.let { return it }
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val base = shown.firstOrNull()?.color ?: 0xFF2A3350.toInt()
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), lerpColor(base, 0xFF1A1E29.toInt(), 0.55f),
                lerpColor(base, Color.BLACK, 0.75f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val cols = 3
        val gap = 14f
        val cw = 104f
        val ch = 58f
        val left = (W - cols * cw - (cols - 1) * gap) / 2
        val top = (H - 2 * ch - gap) / 2
        val cell = RectF()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val clip = Path()
        for (i in 0 until 6) {
            val x = left + (i % cols) * (cw + gap)
            val y = top + (i / cols) * (ch + gap)
            cell.set(x, y, x + cw, y + ch)
            val a = shown.getOrNull(i)
            if (a == null) {
                p.color = 0x1FFFFFFF
                c.drawRoundRect(cell, 10f, 10f, p)
                continue
            }
            c.save()
            clip.reset(); clip.addRoundRect(cell, 10f, 10f, Path.Direction.CW)
            c.clipPath(clip)
            val art = a.art
            if (art != null && a.isBanner) c.drawBitmap(art, null, cell, p)
            else {
                p.color = a.color
                c.drawRect(cell, p)
                if (art != null) {
                    val s = ch * 0.7f
                    c.drawBitmap(art, null, RectF(cell.centerX() - s / 2, cell.centerY() - s / 2, cell.centerX() + s / 2, cell.centerY() + s / 2), p)
                }
            }
            c.restore()
        }
        b.setHasAlpha(false)
        if (cache.size >= 60) cache.remove(cache.keys.first())
        cache[key] = b
        return b
    }
}

/** The scene's name and place, top left, shown for a few seconds when the clip changes (and while the Top Shelf is focused). */
class SceneLabel(ctx: Context) : LinearLayout(ctx) {
    private val title = TextView(ctx).apply {
        textSize = 24f; typeface = Fonts.medium; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(ctx.dp(8f), 0f, ctx.dp(1f), 0xB3000000.toInt())
    }
    private val place = TextView(ctx).apply {
        textSize = 15f; typeface = Fonts.regular; setTextColor(0xE6FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(ctx.dp(6f), 0f, ctx.dp(1f), 0xB3000000.toInt())
    }
    private val hint = TextView(ctx).apply {
        textSize = 13f; typeface = Fonts.medium; setTextColor(0xCCFFFFFF.toInt()); maxLines = 1
        setShadowLayer(ctx.dp(6f), 0f, ctx.dp(1f), 0xB3000000.toInt())
    }
    private val hideLater = Runnable { hide() }

    init {
        orientation = VERTICAL
        alpha = 0f
        visibility = View.GONE
        isFocusable = false
        addView(title)
        addView(place, LayoutParams(-2, -2).apply { topMargin = ctx.dpi(2f) })
        addView(hint, LayoutParams(-2, -2).apply { topMargin = ctx.dpi(8f) })
    }

    /** Shows [name] (with [hintText] under it) for [holdMs], or until [hide] when [holdMs] is 0. */
    fun show(name: SceneName, hintText: String?, holdMs: Long) {
        removeCallbacks(hideLater)
        title.text = name.title
        place.text = name.location
        place.visibility = if (name.location.isEmpty()) View.GONE else View.VISIBLE
        hint.text = hintText ?: ""
        hint.visibility = if (hintText.isNullOrEmpty()) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(Effects.fade(350)).start()
        if (holdMs > 0) postDelayed(hideLater, holdMs)
    }

    fun hide(instant: Boolean = false) {
        removeCallbacks(hideLater)
        animate().cancel()
        if (instant || visibility != View.VISIBLE) { alpha = 0f; visibility = View.GONE; return }
        animate().alpha(0f).setDuration(Effects.fade(600)).withEndAction { visibility = View.GONE }.start()
    }
}

/**
 * Scenery: the local aerial clips as poster thumbnails along the bottom of the screen, over the playing video.
 * OK plays a scene; Menu marks it a favourite or hides it (hidden scenes stay listed, faded, so they can be shown
 * again); the buttons step to the next scene and switch shuffle, favourites only and scene names.
 */
class SceneBrowser(private val home: HomeScreen) : FrameLayout(home.ctx) {
    var isOpen = false
        private set
    private val scrim = View(context).apply { setBackgroundColor(0x33000000) }
    val panel = GlassBox(context, dp(30f)).apply {
        glass.tint = 0.08f; glass.dark = 0.38f; glass.sheen = 0.06f; glass.highlight = 0.45f; shadowAlpha = 0.45f
    }
    private val title = TextView(context).apply { text = "Scenery"; textSize = 24f; typeface = Fonts.medium; setTextColor(Color.WHITE) }
    private val status = TextView(context).apply { textSize = 14f; typeface = Fonts.regular; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2 }
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val actionScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false
    }
    private val thumbsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val thumbsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = true; clipToPadding = false
    }
    private val empty = TextView(context).apply {
        textSize = 16f; typeface = Fonts.regular; setTextColor(0xE6FFFFFF.toInt()); gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f)
    }
    private val hint = TextView(context).apply {
        text = "OK plays a scene  ·  Menu: favourite or hide  ·  Back closes"
        textSize = 13f; typeface = Fonts.regular; setTextColor(0x99FFFFFF.toInt())
    }
    private val thumbs = ArrayList<ThumbView>()
    private var clips: List<AerialClip> = emptyList()
    /** Poster thumbnails, decoded off the UI thread at thumbnail size while the browser is open. */
    private val bitmaps = HashMap<String, Bitmap>()
    private val tw = dpi(176f)
    private val th = tw * 9 / 16

    init {
        visibility = View.GONE
        clipChildren = false
        addView(scrim, LayoutParams(-1, -1))
        val m = Backdrop.screenW * 40 / 1920
        addView(panel, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(m, 0, m, Backdrop.screenH * 36 / 1080) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.setPadding(dpi(26f), dpi(18f), dpi(26f), dpi(14f))
        panel.addView(col, LayoutParams(-1, -2))
        col.addView(title)
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2f) })
        actionScroll.addView(actions, ViewGroup.LayoutParams(-2, -2))
        actions.setPadding(dpi(4f), dpi(10f), dpi(4f), dpi(10f))
        col.addView(actionScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        thumbsRow.setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(6f))
        thumbsScroll.addView(thumbsRow, ViewGroup.LayoutParams(-2, -2))
        col.addView(thumbsScroll, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = -dpi(12f); rightMargin = -dpi(12f) })
        col.addView(empty, LinearLayout.LayoutParams(-1, th + dpi(40f)))
        col.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4f) })
        panel.clipChildren = true
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        visibility = View.VISIBLE
        bitmaps.clear()
        clips = home.sceneLibrary()
        build(focusCurrent = true)
        alpha = 0f
        animate().alpha(1f).setDuration(Effects.fade(200)).withEndAction { invalidateDeep(panel) }.start()
        // Pick up clips copied since the last scan.
        home.rescanScenes { list -> if (isOpen && list.map { it.video.path } != clips.map { it.video.path }) { clips = list; build(focusCurrent = false) } }
    }

    fun close(instant: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        animate().cancel()
        // Fading out: nothing in it may take focus any more.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val done = {
            visibility = View.GONE
            thumbsRow.removeAllViews(); thumbs.clear(); bitmaps.clear()
            lastScene = null
        }
        if (instant || Effects.reduceMotion) done() else animate().alpha(0f).setDuration(160).withEndAction { done() }.start()
        home.onSceneBrowserClosed()
    }

    fun back() = close()

    /** The scene focused last, so focus lost in a rebuild or behind a closing card comes back to it. */
    private var lastScene: String? = null

    /**
     * Menu: the focused scene's options; on a button, closes the browser. If focus is nowhere in the browser (lost
     * while a card closed), it returns to the last scene and opens that scene's options.
     */
    fun onMenu() {
        val f = rootView?.findFocus()
        val t = thumbs.firstOrNull { it.isFocused }
        when {
            t != null -> options(t.wallId)
            f != null && f.parent === actions -> close()
            else -> thumbs.firstOrNull { it.wallId == lastScene }?.let { it.requestFocus(); options(it.wallId) } ?: close()
        }
    }

    /** Re-reads favourites, hidden scenes and the playing clip (after a change made here or elsewhere). */
    fun refresh() { if (isOpen) build(focusCurrent = false) }

    private fun build(focusCurrent: Boolean) {
        val focus = rootView?.findFocus()
        val keepThumb = (focus as? ThumbView)?.takeIf { it.parent === thumbsRow }?.wallId
        val keepAction = if (focus != null && focus.parent === actions) actions.indexOfChild(focus) else -1
        val prefs = home.prefs
        val fav = prefs.sceneFavorites
        val hidden = prefs.sceneHidden
        val aerialOn = prefs.topShelf == HeroRepo.SRC_AERIAL
        val current = home.currentScene()

        actions.removeAllViews()
        fun action(label: String, value: String = "", click: () -> Unit) = RowView(context, label).apply {
            id = View.generateViewId()
            titleView.textSize = 16f
            valueView.textSize = 15f
            valueView.text = value
            onClick = click
            actions.addView(this, LinearLayout.LayoutParams(dpi(if (value.isEmpty()) 170f else 210f), dpi(44f)).apply { rightMargin = dpi(10f) })
        }
        if (!aerialOn) action("Use aerial videos") { home.setTopShelf(HeroRepo.SRC_AERIAL) }
        else {
            action("Next scene") { home.skipScene(1); postDelayed({ refresh() }, 100) }
            action("Previous") { home.skipScene(-1); postDelayed({ refresh() }, 100) }
        }
        action("Shuffle", if (prefs.sceneShuffle) "On" else "Off") { prefs.sceneShuffle = !prefs.sceneShuffle; home.applySceneChoice(); refresh() }
        action("Favourites only", if (prefs.sceneFavoritesOnly) "On" else "Off") { prefs.sceneFavoritesOnly = !prefs.sceneFavoritesOnly; home.applySceneChoice(); refresh() }
        action("Scene names", if (prefs.sceneLabels) "On" else "Off") { prefs.sceneLabels = !prefs.sceneLabels; refresh() }
        if (hidden.any { h -> clips.any { it.id == h } }) action("Show all scenes") { prefs.sceneHidden = emptySet(); home.applySceneChoice(); refresh() }

        // The same clips: update the thumbnails in place, so the focused one keeps focus (no view is removed).
        if (thumbs.map { it.wallId } != clips.map { it.id }) {
            thumbsRow.removeAllViews()
            thumbs.clear()
            for ((i, clip) in clips.withIndex()) {
                val t = ThumbView(context, tw, th, clip.id, "")
                t.id = View.generateViewId()
                t.onClick = { choose(clip) }
                t.setOnFocusChangeListener { _, has -> if (has) lastScene = clip.id }
                thumbsRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dpi(16f) })
                thumbs.add(t)
            }
        }
        for ((t, clip) in thumbs.zip(clips)) {
            t.label = when {
                clip.id in hidden -> "Hidden · " + clip.name.title
                clip.id in fav -> "★ " + clip.name.title
                else -> clip.name.title
            }
            t.dim = clip.id in hidden
            t.chosen = aerialOn && clip.id == current?.id
            if (t.bitmap == null) bitmaps[clip.id]?.let { t.bitmap = it }
        }
        val reason = home.sceneReason()
        val shown = clips.size - clips.count { it.id in hidden }
        status.text = statusText(aerialOn, shown, fav.count { f -> clips.any { it.id == f } }, clips.size - shown, reason)
        empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        thumbsScroll.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
        empty.text = when {
            !Grants.storage(context) -> "GlassOS needs storage access to read Movies/GlassAerial.\nAllow it in Settings > Permissions, then open Scenery again."
            else -> "No videos found in Movies/GlassAerial.\nCopy .mp4, .mov or .m4v clips there (with a .jpg poster of the same name), then open Scenery again."
        }
        loadPosters()
        // Focus: the scene or button that had it; on opening, the scene playing; focus lost elsewhere (behind a card
        // that closed), the scene focused last. The scroll view brings the focused scene into view once laid out.
        val target: View? = when {
            keepThumb != null -> thumbs.firstOrNull { it.wallId == keepThumb }
            keepAction >= 0 -> actions.getChildAt(keepAction.coerceAtMost(actions.childCount - 1))
            focusCurrent -> thumbs.firstOrNull { it.chosen } ?: thumbs.firstOrNull()
            else -> thumbs.firstOrNull { it.wallId == lastScene } ?: thumbs.firstOrNull { it.chosen }
        } ?: thumbs.firstOrNull() ?: actions.getChildAt(0)
        if (target != null && !target.isFocused) target.requestFocus()
        if (Build.VERSION.SDK_INT >= 23) thumbsScroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateDeep(thumbsRow) }
        invalidateDeep(panel)
    }

    private fun statusText(aerialOn: Boolean, shown: Int, favs: Int, hidden: Int, reason: SceneFilter.Reason): String {
        if (clips.isEmpty()) return "Movies/GlassAerial"
        val counts = listOfNotNull("$shown ${if (shown == 1) "scene" else "scenes"}",
            if (favs > 0) "$favs ${if (favs == 1) "favourite" else "favourites"}" else null,
            if (hidden > 0) "$hidden hidden" else null).joinToString("  ·  ")
        val note = when {
            !aerialOn -> "The Top Shelf shows ${HeroRepo.sourceLabel(home.prefs.topShelf)}. Choose a scene or Use aerial videos to play these."
            reason == SceneFilter.Reason.ALL_HIDDEN -> "Every scene is hidden, so a still background shows. Choose one and press Menu to show it again."
            reason == SceneFilter.Reason.ALL_FAILED -> "None of these clips could be played on this TV. The poster or background shows instead."
            reason == SceneFilter.Reason.FAVORITES_EMPTY -> "No favourite can play, so every scene plays."
            else -> null
        }
        return if (note == null) counts else "$counts\n$note"
    }

    private fun loadPosters() {
        val todo = clips.filter { it.id !in bitmaps && it.poster != null }
        if (todo.isEmpty()) return
        home.background({
            for (c in todo) {
                if (!isOpen) break
                val b = try { WallpaperStore.decodeCrop(c.poster!!, tw, th) } catch (e: Throwable) { null } ?: continue
                post {
                    if (!isOpen) return@post
                    bitmaps[c.id] = b
                    thumbs.firstOrNull { it.wallId == c.id }?.bitmap = b
                }
            }
        })
    }

    private fun choose(clip: AerialClip) {
        if (clip.id in home.prefs.sceneHidden) { options(clip.id); return }
        home.playScene(clip.id)
        postDelayed({ refresh() }, 150)
    }

    private fun options(id: String) {
        val clip = clips.firstOrNull { it.id == id } ?: return
        lastScene = id
        val prefs = home.prefs
        val fav = id in prefs.sceneFavorites
        val hidden = id in prefs.sceneHidden
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!hidden) items.add("Play now" to { home.playScene(id); postDelayed({ refresh() }, 150) })
        items.add((if (fav) "Remove from favourites" else "Add to favourites") to {
            prefs.sceneFavorites = if (fav) prefs.sceneFavorites - id else prefs.sceneFavorites + id
            home.applySceneChoice(); refresh()
        })
        items.add((if (hidden) "Show this scene again" else "Hide this scene") to {
            prefs.sceneHidden = if (hidden) prefs.sceneHidden - id else prefs.sceneHidden + id
            home.applySceneChoice(); refresh()
            if (!hidden) Toast.makeText(context, "Hidden. It stays here, faded, to show again.", Toast.LENGTH_SHORT).show()
        })
        val where = clip.name.location.takeIf { it.isNotEmpty() }
        home.card(clip.name.title, items, listOfNotNull(where, clip.id).joinToString("\n"))
    }
}

/** A text field drawn as a glass capsule (folder names), typed with [Keyboard]. */
class NameField(ctx: Context, private val hintText: String) : View(ctx) {
    var value = ""
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.regular; color = Color.WHITE; textSize = ctx.dp(22f) }
    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = 0x26FFFFFF
        c.drawRoundRect(rect, height / 2f, height / 2f, fill)
        val pad = dp(22f)
        val empty = value.isEmpty()
        t.color = if (empty) 0x80FFFFFF.toInt() else Color.WHITE
        val s = TextUtils.ellipsize(if (empty) hintText else value, t, width - 2 * pad - dp(8f), TextUtils.TruncateAt.START).toString()
        val y = height / 2f - (t.ascent() + t.descent()) / 2
        c.drawText(s, pad, y, t)
        if (!empty) { t.color = 0xCCFFFFFF.toInt(); c.drawText("|", pad + t.measureText(s) + dp(1f), y, t) }
    }
}

/**
 * An app folder opened from its tile: its apps in a grid (OK opens one, Menu takes it out of the folder), and buttons
 * to add apps, rename the folder or remove it (its apps go back to the app list).
 */
class FolderPanel(private val home: HomeScreen) : FrameLayout(home.ctx) {
    private enum class Mode { MEMBERS, PICK, RENAME }

    var isOpen = false
        private set
    var folderId: String? = null
        private set
    private var mode = Mode.MEMBERS
    private val scrim = View(context).apply { setBackgroundColor(0x80000000.toInt()) }
    private val panelW = Backdrop.screenW * 72 / 100
    val panel = GlassBox(context, dp(36f)).apply { glass.tint = 0.10f; glass.dark = 0.34f; glass.sheen = 0.08f; shadowAlpha = 0.5f }
    private val title = TextView(context).apply { textSize = 28f; typeface = Fonts.medium; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val subtitle = TextView(context).apply { textSize = 15f; typeface = Fonts.regular; setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER }
    private val body = FrameLayout(context).apply { clipChildren = true; clipToPadding = false }
    private val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
    private val tiles = ArrayList<ThumbView>()
    private val picked = ArrayList<String>()
    private var nameText = ""
    /** Rename right after creating: Cancel keeps the suggested name, and an empty folder goes on to Add apps. */
    private var creating = false

    init {
        visibility = View.GONE
        clipChildren = false
        addView(scrim, LayoutParams(-1, -1))
        addView(panel, LayoutParams(panelW, Backdrop.screenH * 84 / 100, Gravity.CENTER))
        panel.clipChildren = true
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.setPadding(dpi(34f), dpi(24f), dpi(34f), dpi(20f))
        panel.addView(col, LayoutParams(-1, -1))
        col.addView(title, LinearLayout.LayoutParams(-1, -2))
        col.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12f); bottomMargin = dpi(10f) })
        col.addView(nav, LinearLayout.LayoutParams(-1, dpi(56f)))
    }

    /** Opens folder [id]; [rename] starts with its name on the keyboard; [isNew]: just created. */
    fun open(id: String, rename: Boolean = false, isNew: Boolean = false) {
        folderId = id
        isOpen = true
        creating = isNew
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(Effects.fade(200)).withEndAction { invalidateDeep(panel) }.start()
        show(if (rename) Mode.RENAME else Mode.MEMBERS)
    }

    fun close(instant: Boolean = false, after: (() -> Unit)? = null) {
        if (!isOpen) return
        isOpen = false
        animate().cancel()
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val done = { visibility = View.GONE; body.removeAllViews(); nav.removeAllViews(); tiles.clear() }
        if (instant || Effects.reduceMotion) done() else animate().alpha(0f).setDuration(160).withEndAction { done() }.start()
        val id = folderId
        folderId = null
        home.onFolderClosed(id)
        after?.invoke()
    }

    fun back() {
        if (mode == Mode.MEMBERS) close()
        else if (mode == Mode.RENAME && creating) { creating = false; show(if (home.folderApps(folderId ?: "").isEmpty()) Mode.PICK else Mode.MEMBERS) }
        else show(Mode.MEMBERS)
    }

    /** Menu: the focused app's options in the folder. */
    fun onMenu() {
        if (mode != Mode.MEMBERS) return
        val t = tiles.firstOrNull { it.isFocused } ?: return
        val id = folderId ?: return
        val pkg = t.wallId
        val label = home.folderApps(id).firstOrNull { it.pkg == pkg }?.label ?: pkg
        home.card(label, listOf(
            "Open" to { val p = pkg; close(instant = true) { home.launch(p, null) } },
            "Remove from folder" to { home.removeFromFolder(id, pkg); show(Mode.MEMBERS) }
        ))
    }

    /** The app list changed (install, uninstall, hide): redraw what is shown. */
    fun refresh() {
        val id = folderId ?: return
        if (home.folderName(id) == null) { close(instant = true); return }
        if (mode == Mode.MEMBERS) show(Mode.MEMBERS)
    }

    private fun show(m: Mode) {
        mode = m
        body.removeAllViews()
        nav.removeAllViews()
        tiles.clear()
        val id = folderId ?: return
        val name = home.folderName(id) ?: return
        when (m) {
            Mode.MEMBERS -> members(id, name)
            Mode.PICK -> pick(id, name)
            Mode.RENAME -> rename(id, name)
        }
        fitNav()
        invalidateDeep(panel)
    }

    private fun navButton(label: String, primary: Boolean = false, click: () -> Unit): RowView {
        val b = RowView(context, label).apply {
            id = View.generateViewId()
            setPadding(dpi(10f), 0, dpi(10f), 0)
            titleView.gravity = Gravity.CENTER
            titleView.textSize = 16f
            titleView.maxLines = 1
            titleView.ellipsize = TextUtils.TruncateAt.END
            if (primary) titleView.typeface = Fonts.medium
            onClick = click
        }
        nav.addView(b)
        return b
    }

    /**
     * Fits the buttons inside the panel at any screen size: three or more share the row equally; one or two keep a
     * comfortable fixed width, centred (shrunk to share the row only on a very narrow panel).
     */
    private fun fitNav() {
        val n = nav.childCount
        val gap = dpi(6f)
        val avail = panelW - dpi(68f)
        val fixed = dpi(200f)
        val share = n > 2 || n * (fixed + 2 * gap) > avail
        for (i in 0 until n) {
            nav.getChildAt(i).layoutParams = LinearLayout.LayoutParams(if (share) 0 else fixed, dpi(46f), if (share) 1f else 0f).apply {
                leftMargin = gap; rightMargin = gap
            }
        }
        // A smaller screen (720p): a size down rather than cut-off labels.
        if (share && n > 0) {
            val per = (avail - n * 2 * gap) / n
            val rows = (0 until n).mapNotNull { nav.getChildAt(it) as? RowView }
            val need = rows.maxOf { it.titleView.paint.measureText(it.titleView.text.toString()) + it.paddingLeft + it.paddingRight }
            if (need > per) rows.forEach { it.titleView.textSize = 14f }
        }
    }

    private fun members(id: String, name: String) {
        title.text = name
        val apps = home.folderApps(id)
        subtitle.text = if (apps.isEmpty()) "This folder is empty. Choose Add apps to fill it."
            else "${apps.size} ${if (apps.size == 1) "app" else "apps"}. OK opens an app; Menu takes it out of the folder."
        grid(apps.map { it to null }, { false }) { a, _ -> val p = a.pkg; close(instant = true) { home.launch(p, null) } }
        val add = navButton("Add apps") { show(Mode.PICK) }
        navButton("Rename") { show(Mode.RENAME) }
        navButton("Remove folder") { confirmRemove(id, name) }
        navButton("Done", primary = true) { close() }
        (tiles.firstOrNull() ?: add).requestFocus()
    }

    private fun pick(id: String, name: String) {
        title.text = "Add apps to $name"
        val cands = home.folderCandidates(id)
        picked.clear()
        picked.addAll(home.folderApps(id).map { it.pkg })
        fun count() { subtitle.text = "${picked.size} chosen. Apps already in another folder move here." }
        count()
        grid(cands, { it in picked }) { a, t ->
            if (!picked.remove(a.pkg)) {
                if (picked.size >= Folders.MAX_MEMBERS) { Toast.makeText(context, "A folder holds ${Folders.MAX_MEMBERS} apps", Toast.LENGTH_SHORT).show(); return@grid }
                picked.add(a.pkg)
            }
            t.chosen = a.pkg in picked
            count()
        }
        navButton("Cancel") { show(Mode.MEMBERS) }
        val done = navButton("Done", primary = true) { home.setFolderMembers(id, picked.toList()); show(Mode.MEMBERS) }
        (tiles.firstOrNull() ?: done).requestFocus()
    }

    private fun rename(id: String, name: String) {
        title.text = if (creating) "Name the new folder" else "Rename folder"
        subtitle.text = "Up to ${Folders.MAX_NAME} characters."
        nameText = name
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
        val field = NameField(context, "Folder name").apply { value = nameText }
        box.addView(field, LinearLayout.LayoutParams(dpi(420f), dpi(46f)))
        val kb = Keyboard(context, digits = false) { k ->
            nameText = when (k) {
                Keyboard.DEL -> nameText.dropLast(1)
                Keyboard.CLEAR -> ""
                " " -> if (nameText.isNotEmpty() && !nameText.endsWith(" ")) nameText + " " else nameText
                else -> nameText + k
            }.take(Folders.MAX_NAME)
            field.value = nameText
        }
        box.addView(kb, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(12f) })
        body.addView(box, LayoutParams(-1, -2, Gravity.CENTER))
        navButton("Cancel") { back() }
        val save = navButton("Save", primary = true) {
            val clean = Folders.cleanName(nameText)
            if (clean.isEmpty()) { Toast.makeText(context, "Type a name first", Toast.LENGTH_SHORT).show(); return@navButton }
            home.renameFolder(id, clean)
            val wasCreating = creating
            creating = false
            show(if (wasCreating && home.folderApps(id).isEmpty()) Mode.PICK else Mode.MEMBERS)
        }
        kb.firstKey()?.requestFocus()
        kb.downTo(save)
    }

    private fun confirmRemove(id: String, name: String) {
        home.card("Remove “$name”?", listOf(
            "Cancel" to {},
            "Remove folder" to { close(instant = true); home.removeFolder(id) }
        ), "Its apps go back to the app list. No app is uninstalled.")
    }

    /** Five a row, like setup's app pickers; [note] marks an app that is in another folder. */
    private fun grid(apps: List<Pair<AppEntry, String?>>, chosen: (String) -> Boolean, click: (AppEntry, ThumbView) -> Unit) {
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = true; clipToPadding = false }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        grid.setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(14f))
        scroll.addView(grid, ViewGroup.LayoutParams(-1, -2))
        body.addView(scroll, LayoutParams(-1, -1))
        if (Build.VERSION.SDK_INT >= 23) scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateDeep(grid) }
        val cols = 5
        val tw = (panelW - dpi(68f) - dpi(28f) - (cols - 1) * dpi(18f)) / cols
        val th = tw * 9 / 16
        var row: LinearLayout? = null
        apps.forEachIndexed { i, (a, note) ->
            if (i % cols == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dpi(10f) })
            }
            val t = ThumbView(context, tw, th, a.pkg, if (note != null) "${a.label} · $note" else a.label).apply {
                id = View.generateViewId()
                bitmap = AppArt.thumb(a, tw, th)
                this.chosen = chosen(a.pkg)
            }
            t.onClick = { click(a, t) }
            row!!.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i % cols > 0) leftMargin = dpi(18f) })
            tiles.add(t)
        }
    }
}
