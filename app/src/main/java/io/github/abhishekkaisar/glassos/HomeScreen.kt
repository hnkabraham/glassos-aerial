package io.github.abhishekkaisar.glassos

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** What a home screen needs from the window that shows it (MainActivity, or the overlay window after Home). */
interface HomeHost {
    /** Starts an app. [from] is the tile it was opened from, if any. */
    fun startApp(i: Intent, pkg: String, from: View?)
}

/** Where the user was on a home screen, carried between the overlay and MainActivity. */
class HomeState(val focusPkg: String?, val focusGear: Boolean, val focusHero: Boolean, val lastDockIndex: Int,
                val settingsOpen: Boolean, val ccOpen: Boolean = false)

/** Process-wide data both home screens share, so the overlay can show the home at once without loading anything. */
object HomeShare {
    @Volatile var apps: List<AppEntry>? = null
    @Volatile var avatar: Bitmap? = null
    @Volatile var mainResumed = false
    /** The home screen of MainActivity, while it exists. */
    var main: HomeScreen? = null
    @Volatile private var snd: Sound? = null
    fun sound(ctx: Context): Sound? = snd
    fun ensureSound(ctx: Context) {
        if (snd != null) return
        synchronized(this) { if (snd == null) snd = try { Sound(ctx.applicationContext) } catch (e: Throwable) { null } }
    }
}

/**
 * The Glass home: the rotating Top Shelf, the dock shelf, the app drawer, the user pill and Settings.
 * One instance lives in MainActivity; a second, short-lived one is shown in an overlay window right after
 * the Home key (see [HomeOverlay]). Both share the decoded art and app icons through [HomeShare].
 */
class HomeScreen(val ctx: Context, private val host: HomeHost, seed: HomeScreen? = null) {
    val prefs = Prefs(ctx)
    lateinit var root: FrameLayout
        private set
    lateinit var hero: HeroView
        private set
    private lateinit var aerial: AerialVideoView
    private lateinit var home: FrameLayout
    private lateinit var scrim: View
    private lateinit var header: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var shelf: GlassBox
    private lateinit var gear: UserPill
    private lateinit var chevron: View
    private lateinit var hint: TextView
    lateinit var settings: SettingsView
        private set
    lateinit var cc: ControlCenter
        private set
    lateinit var setup: SetupView
        private set
    lateinit var appMenu: AppMenu
        private set
    private lateinit var movePill: GlassBox
    lateinit var browser: SceneBrowser
        private set
    lateinit var folderPanel: FolderPanel
        private set
    private lateinit var sceneLabel: SceneLabel
    private lateinit var cinemaClock: CinemaClock
    private lateinit var nightDim: View

    private var apps: List<AppEntry> = emptyList()
    /** What the grid shows, by id: apps not in a folder, and each folder's own tile (id "folder:..."). */
    private var entries: Map<String, AppEntry> = emptyMap()
    /** Each folder's installed, unhidden apps, as last placed. */
    private var folderMembers: Map<String, List<String>> = emptyMap()
    /** The local aerial clips from the last scan. */
    private var aerialClips: List<AerialClip> = emptyList()
    /** A scene chosen in the browser while another Top Shelf source was on: played once the clips are loaded. */
    private var pendingScene: String? = null
    private var browserFromSettings = false
    private var browserReturn: View? = null
    /** True while the home has faded to its scenery after [Prefs.cinemaDelaySec] without input. */
    private var cinema = false
    private var lastInput = SystemClock.uptimeMillis()
    /** The key that woke cinema mode: its repeats and release are eaten too, so it never reaches a tile. */
    private var eatKey = -1
    private var cinemaFocus: View? = null
    private val idleCheck = Runnable { checkIdle() }
    private var nightOn = false
    private var nightLevelShown = -1f
    /** The date, zone and clock format Settings' sun values were last read for (see [updateTime]). */
    private var sunDay = ""
    private val ok: List<Pair<String, () -> Unit>> = listOf("OK" to {})
    private val dockTiles = ArrayList<TileView>()
    private val dockPkgs = ArrayList<String>()
    /** The drawer's apps in their order (the saved order, then new installs). Hidden apps are in neither list. */
    private val drawerPkgs = ArrayList<String>()
    /** The app being moved after a hold of OK, and the two lists as they were, for Back. */
    private var movingPkg: String? = null
    private var moveFrom: Pair<List<String>, List<String>>? = null
    /** The OK press that dropped a move: its release must not reach the tile, or the app would open. */
    private var eatOkUp = false
    private val rows = ArrayList<ArrayList<TileView>>()
    private var currentRow = 0
    private var lastDockIndex = 0
    var editMode = false
        private set
    private var pickedIndex = -1
    private var wallToken = 0
    private var appsToken = 0
    private var usageToken = 0
    private var usage = AppUsage.Snapshot()
    private var usagePending = false
    private var usageInputEpoch = 0L
    private var inputEpoch = 0L
    private var focusedOnce = false
    /** True while the window showing this home is in front (activity resumed, or the overlay shown). */
    var resumed = false
        set(v) { field = v; updateHeroRunning() }
    private var released = false
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sound get() = HomeShare.sound(ctx)
    private val backdropListener: () -> Unit = {
        if (::root.isInitialized) invalidateDeep(root)
    }
    /** Called once per hero frame: only the glass surfaces that sample the backdrop redraw. */
    private val frameListener: () -> Unit = {
        if (::root.isInitialized) {
            gear.invalidate()
            chevron.invalidate()
            // Solid panels (reduce transparency) do not show the art behind them: nothing to redraw.
            if (!Effects.reduceTransparency) {
                shelf.invalidate()
                if (scrim.alpha > 0f) scrim.invalidate()
                if (settings.isOpen) invalidateDeep(settings.panel)
                if (cc.isOpen) invalidateDeep(cc.panel)
                if (setup.isOpen) invalidateDeep(setup.panel)
                if (appMenu.isOpen) invalidateDeep(appMenu.panel)
                if (browser.isOpen) invalidateDeep(browser.panel)
                if (folderPanel.isOpen) invalidateDeep(folderPanel.panel)
                if (movePill.visibility == View.VISIBLE) movePill.invalidate()
            }
        }
    }

    init {
        Backdrop.addListener(backdropListener)
        Backdrop.addFrameListener(frameListener)
        Effects.load(prefs)
        AppLock.load(prefs)
        buildUi()
        applyNight()
        worker.execute { HomeShare.ensureSound(ctx) }
        val seedHero = seed?.hero
        if (seedHero != null && seedHero.slides.isNotEmpty()) hero.adopt(seedHero) else setWallpaper(prefs.scene, save = false)
        HomeShare.avatar?.let { gear.avatar = it }
        val cached = HomeShare.apps
        if (cached != null) applyApps(cached) else loadApps()
        // Scenery stops while the Screen Mirroring receiver decodes a mirrored screen (it starts nothing).
        ScreenMirroring.watch(ctx, prefs)
    }

    fun release() {
        if (released) return
        released = true
        Backdrop.removeListener(backdropListener)
        Backdrop.removeFrameListener(frameListener)
        hero.release()
        aerial.release()
        // Screen Mirroring's status listeners, if a panel was open.
        if (::settings.isInitialized) settings.release()
        if (::cc.isInitialized) cc.release()
        labelAnim?.cancel()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    private fun ui(r: () -> Unit) { main.post { if (!released) r() } }
    private fun focused(): View? = root.findFocus()

    // ---------- UI ----------

    private val sw get() = Backdrop.screenW
    private val sh get() = Backdrop.screenH
    // tvOS 26 (measured from a real 1080p screenshot): shelf 12px from the sides and 25px above the bottom,
    // radius 48px, 38px side and 40px top/bottom padding, 5:3 tiles with 52px gaps.
    private val shelfMargin get() = sw * 12 / 1920
    private val shelfPad get() = sw * 38 / 1920
    private val shelfPadV get() = sh * 40 / 1080
    private val margin get() = shelfMargin + shelfPad
    private val gap get() = sw * 52 / 1920
    private val tileW get() = (sw - 2 * margin - 5 * gap) / COLS
    private val tileH get() = tileW * 3 / 5
    private val labelH get() = ctx.dpi(28f)
    private val rowH get() = tileH + labelH + ctx.dpi(22f)
    /** About 30% of the first drawer row peeks in at the bottom edge, 22px below the shelf (labels hidden while peeking). */
    private val peek get() = tileH * 30 / 100
    private val peekGap get() = sh * 22 / 1080
    private val row0Top get() = sh - peek - peekGap - shelfPadV - tileH
    private fun rowTop(r: Int) = if (r == 0) row0Top else row0Top + tileH + shelfPadV + peekGap + (r - 1) * rowH

    private fun buildUi() {
        // No background here: the hero paints the night colour itself only while it has no art, so the full-screen
        // art is not drawn over a full-screen fill every frame (overdraw on a fill-rate bound GPU).
        root = KeyRoot(ctx).apply { clipChildren = false }
        // Rotating Top Shelf behind everything (aerials, Apple TV titles, or one static background).
        hero = HeroView(ctx) { s -> HeroRepo.load(ctx, s, sw, sh) }.apply {
            id = View.generateViewId()
            shelfTop = (row0Top - shelfPadV).toFloat()
            leftX = sw * 60f / 1920f
            topY = sh * 30f / 1080f
            onOpen = { openSlide(it) }
            openLabel = { s -> s.pkg?.let { p -> HomeShare.apps?.firstOrNull { it.pkg == p }?.label }?.let { "Open in $it" } ?: "Not on your apps" }
            onFocusDown = { focusDock() }
        }
        root.addView(hero, FrameLayout.LayoutParams(-1, -1))
        aerial = AerialVideoView(ctx).apply {
            onClipChanged = { clip ->
                clip.poster?.let { poster ->
                    val key = "aerial:${poster.path}:${clip.revision}"
                    // Preparing a player can announce the same clip before its still finishes loading.
                    if (hero.slides.singleOrNull()?.key != key) hero.setSlides(listOf(Slide(key, Slide.STATIC, art = poster)))
                }
                if (clip.id != SceneSession.labeled) {
                    SceneSession.labeled = clip.id
                    showSceneName(clip)
                }
                if (browser.isOpen) browser.refresh()
            }
            onUnavailable = { if (browser.isOpen) browser.refresh() }
            onOwnerChanged = { updateHeroRunning() }
        }
        root.addView(aerial, FrameLayout.LayoutParams(-1, -1))
        // With the aerial scenery, Left and Right on the Top Shelf change the scene and OK opens Scenery.
        hero.onStep = { d -> if (aerial.isActive) { skipScene(d); true } else false }
        hero.onPress = { if (aerial.isActive) { openSceneBrowser(); true } else false }
        // Behind the grid: the pre-blurred backdrop with a dark tint, faded in when scrolled (tvOS style).
        scrim = BlurScrim(ctx).apply { alpha = 0f }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        home = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        header = FrameLayout(ctx).apply { clipChildren = false }
        home.addView(header, FrameLayout.LayoutParams(-1, -1))
        // tvOS keeps the Top Shelf clean: no big clock, the time lives in the user pill.
        gear = UserPill(ctx).apply {
            id = View.generateViewId()
            nextFocusDownId = hero.id
            name = prefs.name
            onClick = { openControlCenter() }
            onLongPress = { openSettings() }
        }
        header.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = sw * 60 / 1920; topMargin = sh * 48 / 1080
        })
        hero.nextFocusUpId = gear.id
        updateTime()
        if (HomeShare.avatar == null) loadAvatar()

        // Scrolling app grid: row 0 is the dock on a glass shelf, the rest are every other app.
        // content clips, so rows scrolled out of view are culled by the renderer (a big win on the Fire TV GPU).
        content = FrameLayout(ctx).apply { clipChildren = true; clipToPadding = false }
        home.addView(content, FrameLayout.LayoutParams(-1, sh * 4))
        // Clear glass: the art shows through softly blurred and mildly darkened, with a bright thin top rim.
        shelf = GlassBox(ctx, sw * 48f / 1920f).apply {
            glass.tint = 0.03f
            glass.dark = 0.16f
            glass.sheen = 0.05f
            glass.highlight = 0.62f
            glass.border = 0.10f
            glass.cacheStatic = true
            shadowAlpha = 0.18f
        }
        content.addView(shelf, FrameLayout.LayoutParams(sw - 2 * shelfMargin, tileH + 2 * shelfPadV).apply {
            leftMargin = shelfMargin
            topMargin = row0Top - shelfPadV
        })
        chevron = Chevron(ctx)
        home.addView(chevron, FrameLayout.LayoutParams(ctx.dpi(18f), ctx.dpi(9f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = row0Top - shelfPadV - ctx.dpi(17f)
        })

        hint = TextView(ctx).apply {
            text = "Edit top row: press OK to pick up an app, Left or Right to move it, Menu to remove it, Back when done"
            textSize = 15f
            typeface = Fonts.regular
            setTextColor(0xE6FFFFFF.toInt())
            setShadowLayer(ctx.dp(8f), 0f, ctx.dp(1f), 0x80000000.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        home.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = row0Top - ctx.dpi(56f) })

        // Move mode hint: a small glass pill at the top centre, clear of the shelf and the drawer rows.
        val pillH = ctx.dpi(30f)
        movePill = GlassBox(ctx, pillH / 2f).apply {
            glass.tint = 0.10f
            glass.dark = 0.34f
            glass.sheen = 0.06f
            shadowAlpha = 0.25f
            visibility = View.GONE
            setPadding(ctx.dpi(18f), 0, ctx.dpi(18f), 0)
        }
        movePill.addView(TextView(ctx).apply {
            text = "Arrows to move · OK to drop · Menu for options · Back to cancel"
            textSize = 14f
            typeface = Fonts.regular
            setTextColor(0xF2FFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        }, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        root.addView(movePill, FrameLayout.LayoutParams(-2, pillH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = sh * 48 / 1080
        })

        // The scene's name, top left where title logos go (the aerial source has none); bottom left in cinema mode while
        // the clock is top left.
        sceneLabel = SceneLabel(ctx)
        root.addView(sceneLabel, FrameLayout.LayoutParams(sw * 50 / 100, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = sw * 60 / 1920; topMargin = sh * 44 / 1080
        })
        // Cinema clock moves between safe corners once per minute while the rest of home is faded out.
        cinemaClock = CinemaClock(ctx)
        root.addView(cinemaClock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = sw * 64 / 1920; bottomMargin = sh * 48 / 1080
        })
        // Night mode: one dark layer over the scenery and the app grid, under every panel (menus stay full brightness).
        nightDim = View(ctx).apply { visibility = View.GONE; isFocusable = false; isClickable = false }
        root.addView(nightDim, FrameLayout.LayoutParams(-1, -1))

        settings = SettingsView(this)
        root.addView(settings, FrameLayout.LayoutParams(-1, -1))
        cc = ControlCenter(this)
        root.addView(cc, FrameLayout.LayoutParams(-1, -1))
        setup = SetupView(this)
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))
        browser = SceneBrowser(this)
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        folderPanel = FolderPanel(this)
        root.addView(folderPanel, FrameLayout.LayoutParams(-1, -1))
        // Last: option cards open over every panel.
        appMenu = AppMenu(this)
        root.addView(appMenu, FrameLayout.LayoutParams(-1, -1))

        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (old != null && new != null && old !== new && prefs.sound && !FocusAnim.forceInstant) sound?.tick()
            if (new === hero || old === hero) updateHeroRunning()
            if (new === hero) aerial.current()?.let { sceneLabel.show(it.name, SCENE_HINT, 0, labelCorner()) }
            else if (old === hero) sceneLabel.hide()
            if (::chevron.isInitialized) chevron.animate().alpha(if (new is TileView && (new.tag as? Int) == 0) 1f else 0f).setDuration(200).start()
            if (new is TileView) {
                val r = (new.tag as? Int) ?: 0
                if (r == 0) lastDockIndex = dockTiles.indexOf(new).coerceAtLeast(0)
                scrollToRow(r)
            } else if (new === gear || new === hero) scrollToRow(0)
        }
        root.viewTreeObserver.addOnGlobalLayoutListener { invalidateDeep(root) }
    }

    private fun scrollToRow(r: Int) {
        if (r == currentRow) return
        currentRow = r
        val target = if (r == 0) 0f else -(rowTop(r) - sh * 0.30f)
        val instant = FocusAnim.forceInstant || Effects.reduceMotion
        if (r != 0) sceneLabel.hide(instant = true)
        if (instant) {
            content.animate().cancel(); header.animate().cancel(); scrim.animate().cancel()
            content.translationY = target
            header.alpha = if (r == 0) 1f else 0f
            header.translationY = if (r == 0) 0f else -ctx.dp(40f)
            scrim.alpha = if (r == 0) 0f else 1f
            hero.visibility = if (r == 0) View.VISIBLE else View.INVISIBLE
        } else {
            content.animate().translationY(target).setDuration(380)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .setUpdateListener { shelf.invalidate() }
                .withEndAction { shelf.invalidate() }
                .start()
            header.animate().alpha(if (r == 0) 1f else 0f).translationY(if (r == 0) 0f else -ctx.dp(40f)).setDuration(300).start()
            // The opaque scrim hides the hero completely once faded in: stop drawing the hero under it.
            if (r == 0) hero.visibility = View.VISIBLE
            scrim.animate().alpha(if (r == 0) 0f else 1f).setDuration(380)
                .withEndAction { if (currentRow != 0 && scrim.alpha >= 1f) hero.visibility = View.INVISIBLE }
                .start()
        }
        // The move hint rises above the row over the moving one while the drawer is scrolled.
        val pillY = if (r == 0) 0f else -(sh * 40 / 1080).toFloat()
        if (instant) movePill.translationY = pillY else movePill.animate().translationY(pillY).setDuration(380).start()
        fadeLabels(r > 0, instant)
        updateHeroRunning()
    }

    private var labelAnim: android.animation.ValueAnimator? = null
    private var labelsShown = false

    /** Drawer labels stay hidden while the first row only peeks in, and fade in once the drawer scrolls up. */
    private fun fadeLabels(show: Boolean, instant: Boolean = false) {
        if (show == labelsShown) return
        labelsShown = show
        labelAnim?.cancel()
        if (instant) {
            val v = if (show) 1f else 0f
            for (i in 1 until rows.size) rows[i].forEach { it.labelAlpha = v }
            return
        }
        val from = rows.getOrNull(1)?.firstOrNull()?.labelAlpha ?: 0f
        labelAnim = android.animation.ValueAnimator.ofFloat(from, if (show) 1f else 0f).apply {
            duration = 260
            addUpdateListener { a -> val v = a.animatedValue as Float; for (i in 1 until rows.size) rows[i].forEach { it.labelAlpha = v } }
            start()
        }
    }

    /**
     * The hero rotates only while the home row is on screen, nothing covers it and it is not focused, and not while the
     * screensaver holds the decoder (on Android 10 and earlier the home stays resumed beneath it).
     */
    fun updateHeroRunning() {
        if (!::hero.isInitialized || released) return
        // Scenery keeps playing behind its own browser (and the browser's option cards), so a choice shows at once.
        val covered = settings.isOpen || cc.isOpen || setup.isOpen || folderPanel.isOpen || (appMenu.isOpen && !browser.isOpen)
        val visible = resumed && currentRow == 0 && !covered && !editMode && movingPkg == null
        hero.setRunning(visible && SceneSession.owner == null && !browser.isOpen && !hero.isFocused &&
            prefs.topShelf != HeroRepo.SRC_AERIAL)
        aerial.setRunning(visible)
        syncIdle()
    }

    /**
     * OK on a title: opens the title in its app when a deep link is known (the Apple TV source gives one), else
     * opens the streaming service's app. TMDB gives no app-specific title ids, so TMDB titles open the app.
     */
    private fun openSlide(s: Slide) {
        if (s.kind != Slide.TITLE) return
        val pkg = s.pkg
        if (pkg == null || HomeShare.apps?.any { it.pkg == pkg } == false) {
            Toast.makeText(ctx, "None of your apps streams this title here", Toast.LENGTH_SHORT).show()
            return
        }
        unlockThen(pkg) {
            val url = s.url
            if (url != null) {
                val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i.resolveActivity(ctx.packageManager) != null) {
                    host.startApp(i, pkg, null)
                    return@unlockThen
                }
            }
            openApp(pkg, null)
        }
    }

    /**
     * Fire TV's stand-alone Settings grid. android.settings.SETTINGS opens the Settings tab inside Amazon's home
     * instead, which the watcher covers at once and which gives no event when the user leaves it. From
     * MainActivity it opens in Glass's own task, so Back from it returns straight to Glass.
     */
    fun openFireSettings() {
        val i = Platform.settingsIntent(ctx)
        startIntent(i, Platform.handler(ctx, i))
    }

    /**
     * Opens a system screen from Control Center (no tile to scale from). Several Fire TV pages (Display & Audio,
     * Accessibility, Parental Controls, the profile picker) only open for Amazon-signed apps: for those the
     * Settings grid opens instead, with [hint] naming the tile to choose there.
     */
    fun startIntent(i: Intent, pkg: String, hint: String? = null) {
        val ri = ctx.packageManager.resolveActivity(i, 0)
        val perm = ri?.activityInfo?.permission
        if (ri == null || (perm != null && ctx.checkCallingOrSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)) {
            val grid = Platform.settingsIntent(ctx)
            if (grid.resolveActivity(ctx.packageManager) == null) {
                Toast.makeText(ctx, "Not available on this TV", Toast.LENGTH_SHORT).show()
                return
            }
            if (hint != null) Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
            if (ownTask()) grid.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            host.startApp(grid, Platform.handler(ctx, grid), null)
            return
        }
        if (ownTask()) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        host.startApp(i, pkg, null)
    }

    /**
     * Settings pages open in their own task from the overlay, and on Android TV, where GlassOS may be the default
     * home: a page left in the home task would come back on Home instead of GlassOS. On Fire TV they stay in
     * Glass's task, so Back from them returns straight to Glass.
     */
    private fun ownTask() = ctx !is Activity || !Platform.isFireTv(ctx)

    fun openAmazonHome() = HomeWatcher.openAmazonHome(ctx)

    fun setReplaceHome(on: Boolean) {
        prefs.replaceHome = on
        HomeWatcher.sync(ctx)
        if (on && !HomeWatcher.hasAccess(ctx)) {
            Toast.makeText(ctx, "Needs usage access: adb shell appops set ${ctx.packageName} GET_USAGE_STATS allow", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Wallpaper ----------

    fun setWallpaper(id: String, save: Boolean = true) {
        if (save) prefs.scene = id
        val token = ++wallToken
        worker.execute {
            val list = slidesFor(id)
            val on = prefs.topShelf == HeroRepo.SRC_AERIAL
            val videos = if (on) AerialStore.clips(ctx) else emptyList()
            ui {
                if (token == wallToken) {
                    hero.setSlides(list)
                    if (on) aerialClips = videos
                    aerial.setPlaylist(videos, on, sceneChoice())
                    pendingScene?.let { pendingScene = null; aerial.play(it) }
                    updateHeroRunning()
                    if (browser.isOpen) browser.refresh()
                }
            }
            refreshTitles(id, token)
        }
    }

    /** Switches the Top Shelf source and reloads it (fetching at once when the cache is stale). */
    fun setTopShelf(src: String) {
        prefs.topShelf = src
        setWallpaper(prefs.scene, save = false)
    }

    /** Refetches the Top Shelf now (after a new country, key or favourite apps). */
    fun refetchTopShelf() {
        HeroRepo.invalidate(ctx, prefs.topShelf)
        setWallpaper(prefs.scene, save = false)
    }

    /** Top Shelf titles (live or cached); without any, the fallback: a scene, the user's photos or an image, then the desert. */
    private fun slidesFor(id: String): List<Slide> = HeroRepo.cachedTitles(ctx, prefs.topShelf).ifEmpty { fallbackSlides(id) }

    private fun fallbackSlides(id: String): List<Slide> = when {
        HeroRepo.isHero(id) -> HeroRepo.photoSlides(ctx, storageGranted())
        id.startsWith("file:") -> {
            val f = java.io.File(id.removePrefix("file:"))
            listOf(Slide("f:" + f.path + ":" + f.lastModified(), Slide.STATIC, art = f))
        }
        else -> listOf(Slide(id, Slide.STATIC, sceneId = id))
    }.ifEmpty { listOf(Slide("scene:desert", Slide.STATIC, sceneId = "scene:desert")) }

    /** Refreshes the Top Shelf titles when the cache is older than 6 hours, then swaps in the new list. Runs on [worker]. */
    private fun refreshTitles(id: String, token: Int) {
        val src = prefs.topShelf
        if (!HeroRepo.isStale(ctx, src) || !refreshing.compareAndSet(false, true)) return
        Thread {
            val changed = try { HeroRepo.refresh(ctx, src) } finally { refreshing.set(false) }
            if (changed) {
                val list = slidesFor(id)
                ui { if (token == wallToken) hero.setSlides(list) }
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** The window went to the back (an app, the screensaver, Settings): a move in progress is put back. */
    fun onPaused() {
        exitCinema(instant = true)
        resumed = false
        if (::aerial.isInitialized) aerial.setRunning(false)
        if (::appMenu.isInitialized) leaveMove()
    }

    fun onResumed() {
        lastInput = SystemClock.uptimeMillis()
        resumed = true
        // Home in front: an app opened with the lock code is locked again.
        AppLock.session.end()
        updateHeroRunning()
        if (::setup.isInitialized) setup.onReturned()
        updateTime()
        if (!worker.isShutdown) worker.execute { refreshTitles(prefs.scene, wallToken) }
        if (usagePending) { usageInputEpoch = inputEpoch; applyPendingUsage() }
        refreshUsageOrder()
        if (prefs.topShelf == HeroRepo.SRC_AERIAL) rescanScenes { if (browser.isOpen) browser.refresh() }
    }

    fun updateTime() {
        if (!::gear.isInitialized) return
        gear.time = CinemaClock.time(ctx)
        if (::cinemaClock.isInitialized) cinemaClock.text = gear.time
        if (cinema) positionCinemaClock()
        // The minute tick, time and time zone changes all land here: night mode follows them without a timer.
        applyNight()
        // So does an open Settings panel's sun values, but only when they change: a new zone, date or clock format.
        val day = SunNow.dayKey() + if (android.text.format.DateFormat.is24HourFormat(ctx)) " 24h" else ""
        if (day != sunDay) {
            sunDay = day
            if (::settings.isInitialized) settings.refreshValues()
        }
        // While the screensaver holds the decoder, it keeps the shared play order on schedule (the home never claims).
        if (::aerial.isInitialized && SceneSession.owner == null && prefs.sceneCollection == "scheduled" &&
            prefs.topShelf == HeroRepo.SRC_AERIAL) aerial.setPlaylist(aerialClips, true, sceneChoice())
    }

    private fun storageGranted() = Build.VERSION.SDK_INT < 23 ||
        ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * The profile photo chosen in setup, kept in private storage (see [Profile]). Without one, the glass
     * silhouette is drawn.
     */
    fun loadAvatar() {
        worker.execute {
            val priv = Profile.photoFile(ctx)
            val b = if (priv.exists()) try {
                android.graphics.BitmapFactory.decodeFile(priv.path)?.let { src ->
                    val side = minOf(src.width, src.height)
                    val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    Bitmap.createScaledBitmap(sq, 160, 160, true)
                }
            } catch (e: Throwable) { null } else null
            HomeShare.avatar = b
            ui { gear.avatar = b }
        }
    }

    /** Re-reads the name, photo and top row after setup changed them. */
    fun reloadProfile() {
        gear.name = prefs.name
        loadAvatar()
        reloadDock()
    }

    fun userImageIds(): List<String> {
        if (!storageGranted()) return emptyList()
        return WallpaperStore.userImages().map { "file:" + it.path }
    }

    fun loadThumbs(thumbs: List<ThumbView>) {
        val tw = ctx.dpi(136f)
        val th = ctx.dpi(76f)
        worker.execute {
            for (t in thumbs) {
                val b = try {
                    if (HeroRepo.isHero(t.wallId)) HeroRepo.thumb(ctx, t.wallId, storageGranted(), tw, th) ?: WallpaperStore.thumb(ctx, "scene:desert", tw, th)
                    else WallpaperStore.thumb(ctx, t.wallId, tw, th)
                } catch (e: Throwable) { null }
                ui { t.bitmap = b }
            }
        }
    }

    fun onStoragePermission() {
        setup.onStoragePermission()
        loadAvatar()
        if (settings.isOpen) settings.refreshThumbs()
    }

    // ---------- Apps and grid ----------

    fun loadApps() {
        val token = ++appsToken
        worker.execute {
            val list = try { AppRepo.load(ctx) } catch (e: Exception) { emptyList() }
            HomeShare.apps = list
            ui { if (token == appsToken) applyApps(list) }
        }
    }

    private fun applyApps(list: List<AppEntry>) {
        // The app list changed under a move (an install or uninstall): the move ends unsaved.
        if (movingPkg != null) endMove()
        if (appMenu.isOpen) appMenu.close()
        apps = list
        val byPkg = list.associateBy { it.pkg }
        val names = prefs.folders.associate { it.id to it.name }
        // Automatic ranking is derived from the saved layout, so switching back to manual restores it.
        val base = manualPlacement()
        val p = if (prefs.mostUsed) UsageOrder.arrange(base, usage.time, COLS) else base
        folderMembers = p.folderMembers
        val e = HashMap<String, AppEntry>()
        for (id in p.dock + p.drawer) {
            e[id] = if (Folders.isFolderId(id)) {
                val members = (folderMembers[id] ?: emptyList()).mapNotNull { byPkg[it] }
                AppEntry(id, names[id] ?: "Folder", FolderArt.render(id, members), true, 0xFF2A3350.toInt())
            } else byPkg[id] ?: continue
        }
        entries = e
        dockPkgs.clear()
        dockPkgs.addAll(p.dock)
        drawerPkgs.clear()
        drawerPkgs.addAll(p.drawer)
        rebuildGrid()
        if (folderPanel.isOpen) folderPanel.refresh()
    }

    /** What the top row picker offers: apps not in a folder, and folders, alphabetically. */
    fun topLevelEntries(): List<AppEntry> = (dockPkgs + drawerPkgs).mapNotNull { entries[it] }.sortedBy { it.label.lowercase() }

    private fun saveDock() { if (!prefs.mostUsed) prefs.dock = dockPkgs.toList() }
    private fun saveOrder() { if (!prefs.mostUsed) prefs.order = drawerPkgs.toList() }

    private fun manualPlacement(): HomeLayout.Placement {
        val byPkg = apps.associateBy { it.pkg }
        val folders = prefs.folders
        val names = folders.associate { it.id to it.name }
        return HomeLayout.place(apps.map { it.pkg }, prefs.hidden, folders, prefs.dock, prefs.order, COLS,
            labelOf = { id -> names[id] ?: byPkg[id]?.label ?: id },
            defaultDock = { ids -> AppRepo.defaultDock(ids.mapNotNull { byPkg[it] }) })
    }

    val usageOrderDescription: String get() = when {
        !prefs.mostUsed -> "Your saved manual layout"
        !HomeWatcher.hasAccess(ctx) -> "Needs Usage access in Permissions"
        usage.state == AppUsage.State.UNAVAILABLE -> "Usage unavailable; using manual order"
        usage.time.isEmpty() -> "No usage yet; using manual order"
        else -> "Recent app time · about 30 days"
    }

    fun chooseAppOrder() {
        card("App order", listOf("Manual" to { setMostUsed(false) }, "Most used" to { setMostUsed(true) }),
            "Sort the top row, drawer and folder contents by recent time spent (about 30 days). " +
            "Folders use their visible apps' combined time.\n\n" +
            "Needs local Usage access. GlassOS does not save or upload history or change Amazon's privacy settings.\n\n" +
            "Manual restores your saved layout." + if (prefs.mostUsed) "\n\n$usageOrderDescription." else "")
    }

    fun setMostUsed(on: Boolean) {
        ++usageToken
        usagePending = false
        prefs.mostUsed = on
        if (!on) usage = AppUsage.Snapshot()
        reloadDock()
        syncOther()
        settings.refresh()
        if (on) refreshUsageOrder(explicit = true)
    }

    /** Refresh only on returning Home or choosing the mode. No timer, background service, or saved history. */
    private fun refreshUsageOrder(explicit: Boolean = false) {
        if (!prefs.mostUsed || released || worker.isShutdown) return
        val token = ++usageToken
        val epoch = inputEpoch
        worker.execute {
            val result = AppUsage.read(ctx)
            ui {
                if (token != usageToken || !prefs.mostUsed) return@ui
                usage = result
                usageInputEpoch = epoch
                usagePending = true
                if (settings.isOpen) settings.refresh()
                // A choice in Settings may update the grid beneath it. A slow query must not move tiles under
                // someone's remote input; its result is held until the next return Home instead.
                if (explicit && settings.isOpen && !appMenu.isOpen && !setup.isOpen && !browser.isOpen &&
                    !folderPanel.isOpen && !editMode && movingPkg == null) applyUsageOrder()
                else applyPendingUsage()
            }
        }
    }

    private fun applyPendingUsage() {
        if (usagePending && prefs.mostUsed && resumed && root.hasWindowFocus() && inputEpoch == usageInputEpoch &&
            !settings.isOpen && !cc.isOpen && !setup.isOpen && !appMenu.isOpen && !browser.isOpen &&
            !folderPanel.isOpen && !editMode && movingPkg == null) applyUsageOrder()
    }

    private fun applyUsageOrder() {
        if (apps.isEmpty()) return // App discovery may still be running; applyApps will use this snapshot.
        usagePending = false
        val next = UsageOrder.arrange(manualPlacement(), usage.time, COLS)
        if (next.dock != dockPkgs || next.drawer != drawerPkgs || next.folderMembers != folderMembers) applyApps(apps)
    }

    /** Manual editing starts from the saved layout, never from an automatically ranked projection. */
    fun editManualLayout(action: () -> Unit) {
        if (!prefs.mostUsed) { action(); return }
        card("Edit manual layout?", listOf("Cancel" to {}, "Use manual order" to { setMostUsed(false); action() }),
            "Most used is arranging your apps. Switch to Manual to edit the saved top row and app order.")
    }

    private fun openTileMenu(pkg: String) {
        val app = entries[pkg] ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (prefs.mostUsed) items.add("Use manual order" to {
            setMostUsed(false); tileOf(pkg)?.requestFocus()
        }) else {
            items.add("Move" to { tileOf(pkg)?.let { startMove(it) } })
            items.add((if (pkg in dockPkgs) "Remove from top row" else "Add to top row") to { toggleDock(pkg) })
        }
        if (Folders.isFolderId(pkg)) {
            items.add("Open folder" to { openFolder(pkg) })
            items.add("Rename folder" to { openFolder(pkg, rename = true) })
            items.add("Remove folder" to { confirmRemoveFolder(pkg) })
        } else {
            items.add("Add to a folder" to { chooseFolder(pkg) })
            items.add("Hide app" to { hideApp(pkg) })
            lockItem(pkg)?.let { items.add(it) }
            if (Platform.canUninstall(ctx, pkg)) items.add("Uninstall" to { requestUninstall(pkg) })
        }
        card(app.label, items, if (prefs.mostUsed)
            "Most used controls this tile's position. Switch to Manual to move it yourself." else null)
    }

    /**
     * An app leaving the top row goes to the front of the drawer once the user has arranged it, or back to its
     * alphabetical place while the drawer is still alphabetical.
     */
    private fun returnToDrawer(pkg: String) {
        if (prefs.order == null) {
            val all = (drawerPkgs + pkg).distinct().mapNotNull { entries[it] }.sortedBy { it.label.lowercase() }
            drawerPkgs.clear()
            drawerPkgs.addAll(all.map { it.pkg }.filter { it !in dockPkgs })
        } else {
            drawerPkgs.remove(pkg)
            drawerPkgs.add(0, pkg)
            saveOrder()
        }
    }

    private fun makeTile(app: AppEntry, row: Int): TileView {
        val t = TileView(ctx, tileW, tileH, if (row == 0) 0 else labelH)
        t.app = app
        t.id = View.generateViewId()
        t.tag = row
        // Read the tile's app at click time: a move swaps apps between tiles in place.
        t.onClick = { val a = t.app; if (!editMode && a != null) launch(a.pkg, t) }
        t.onLongPress = { if (!editMode && movingPkg == null) heldAppMenu { openTileMenu(t.app?.pkg ?: app.pkg) } }
        return t
    }

    /**
     * Each row is full width and padded so a focused (1.2x) tile and its shadow stay inside the row's bounds.
     * content clips its rows to their bounds, which lets the renderer cull rows that are off screen.
     */
    private fun addRow(tiles: List<TileView>, r: Int) {
        val padTop = ctx.dpi(18f)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(margin, padTop, 0, 0)
        }
        tiles.forEachIndexed { i, t -> row.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = gap }) }
        content.addView(row, FrameLayout.LayoutParams(-1, padTop + tileH + ctx.dpi(60f)).apply { topMargin = rowTop(r) - padTop })
    }

    /** Rebuilds every row. Keeps focus on [focusPkg] (or on whatever was focused) when possible. */
    private fun rebuildGrid(focusPkg: String? = null) {
        val keep = focusPkg ?: (focused() as? TileView)?.app?.pkg
        while (content.childCount > 1) content.removeViewAt(content.childCount - 1)
        dockTiles.clear()
        rows.clear()
        val byPkg = entries
        val dockRow = ArrayList<TileView>()
        for (pkg in dockPkgs) byPkg[pkg]?.let { val t = makeTile(it, 0); t.nextFocusUpId = hero.id; dockRow.add(t) }
        dockTiles.addAll(dockRow)
        rows.add(dockRow)
        addRow(dockRow, 0)
        val rest = drawerPkgs.mapNotNull { byPkg[it] }
        rest.chunked(COLS).forEachIndexed { i, chunk ->
            val r = i + 1
            val list = ArrayList(chunk.map { makeTile(it, r).apply { labelAlpha = if (labelsShown) 1f else 0f } })
            rows.add(list)
            addRow(list, r)
        }
        content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
            height = rowTop(rows.size) + rowH + ctx.dpi(120f)
        }
        if (editMode) dockTiles.forEach { it.setJiggle(true) }
        if (settings.isOpen || appMenu.isOpen || browser.isOpen || folderPanel.isOpen) return
        val target = rows.flatten().firstOrNull { it.app?.pkg == keep }
        val cur = focused()
        when {
            target != null -> target.requestFocus()
            !focusedOnce && dockTiles.isNotEmpty() -> dockTiles[0].requestFocus()
            cur == null || cur.parent == null -> focusDock()
        }
        if (dockTiles.isNotEmpty()) focusedOnce = true
    }

    fun launch(pkg: String, from: View?) {
        if (Folders.isFolderId(pkg)) { openFolder(pkg); return }
        unlockThen(pkg) { openApp(pkg, from) }
    }

    private fun openApp(pkg: String, from: View?) {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (i == null) {
            Toast.makeText(ctx, "Cannot open this app", Toast.LENGTH_SHORT).show()
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        host.startApp(i, pkg, from)
    }

    /** The default start, used by MainActivity: a scale-up from the tile. */
    fun startFrom(i: Intent, from: View?) {
        aerial.setRunning(false)
        try {
            val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
            ctx.startActivity(i, opts)
        } catch (e: Exception) {
            updateHeroRunning()
            Toast.makeText(ctx, "Cannot open this app", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleDock(pkg: String) {
        if (pkg in dockPkgs) {
            if (dockPkgs.size <= 1) {
                Toast.makeText(ctx, "The top row needs at least one app", Toast.LENGTH_SHORT).show()
                return
            }
            dockPkgs.remove(pkg)
            returnToDrawer(pkg)
            Toast.makeText(ctx, "Removed from the top row", Toast.LENGTH_SHORT).show()
        } else {
            if (dockPkgs.size >= COLS) {
                Toast.makeText(ctx, "The top row holds $COLS apps. Remove one first.", Toast.LENGTH_SHORT).show()
                return
            }
            dockPkgs.add(pkg)
            if (drawerPkgs.remove(pkg) && prefs.order != null) saveOrder()
            Toast.makeText(ctx, "Added to the top row", Toast.LENGTH_SHORT).show()
        }
        saveDock()
        rebuildGrid(pkg)
        syncOther()
    }

    /** The other home screen (activity or overlay) follows the saved dock, order and hidden apps. */
    private fun syncOther() { HomeShare.main?.takeIf { it !== this }?.reloadDock() }

    /** Leaves [pkg] out of the drawer and the top row (Settings > Hidden apps shows it again). */
    fun hideApp(pkg: String) {
        if (Folders.isFolderId(pkg)) return
        if (pkg in dockPkgs && dockPkgs.size <= 1) {
            Toast.makeText(ctx, "The top row needs at least one app", Toast.LENGTH_SHORT).show()
            return
        }
        val flat = dockPkgs + drawerPkgs
        val i = flat.indexOf(pkg)
        val next = flat.getOrNull(i + 1)?.takeIf { (it in dockPkgs) == (pkg in dockPkgs) } ?: flat.getOrNull(i - 1)
        prefs.hidden = prefs.hidden + pkg
        if (prefs.mostUsed) {
            reloadDock()
            tileOf(next)?.requestFocus()
            Toast.makeText(ctx, "Hidden. Settings > Hidden apps shows it again.", Toast.LENGTH_LONG).show()
            syncOther()
            return
        }
        if (dockPkgs.remove(pkg)) saveDock()
        drawerPkgs.remove(pkg)
        rebuildGrid(next)
        Toast.makeText(ctx, "Hidden. Settings > Hidden apps shows it again.", Toast.LENGTH_LONG).show()
        syncOther()
    }

    // ---------- App actions and move mode ----------

    private fun tileOf(pkg: String?): TileView? {
        if (pkg == null) return null
        for (r in rows) for (t in r) if (t.app?.pkg == pkg) return t
        return null
    }

    /** Consume the release/repeats of the OK hold that opened a menu, never its first action. */
    fun heldAppMenu(open: () -> Unit) { eatOkUp = true; open() }

    /** Opens only the platform's confirmation. Cancellation leaves layout and installed apps untouched. */
    fun requestUninstall(pkg: String) {
        val intent = try { Platform.uninstallIntent(ctx, pkg) } catch (e: RuntimeException) { null }
        if (intent == null) {
            card("Uninstall unavailable", listOf("OK" to {}),
                "This app cannot be uninstalled here. You can manage it in ${Platform.name(ctx)} Settings.")
            return
        }
        if (folderPanel.isOpen) folderPanel.close(instant = true)
        leaveMove()
        host.startApp(intent, Platform.handler(ctx, intent), null)
    }

    /** Move from the held-OK menu: the tile lifts and wiggles; arrows move it across the shelf and drawer. */
    private fun startMove(t: TileView) {
        val pkg = t.app?.pkg ?: return
        if (editMode || movingPkg != null || settings.isOpen || cc.isOpen) return
        if (prefs.mostUsed) { openTileMenu(pkg); return }
        movingPkg = pkg
        moveFrom = Pair(dockPkgs.toList(), drawerPkgs.toList())
        t.picked = true
        t.setJiggle(true, gentle = true)
        movePill.alpha = 0f
        movePill.visibility = View.VISIBLE
        movePill.animate().alpha(1f).setDuration(200).start()
        sound?.takeIf { prefs.sound }?.tick()
        updateHeroRunning()
    }

    /** Settles the moving tile and leaves move mode, without saving. */
    private fun endMove() {
        tileOf(movingPkg)?.let { it.setJiggle(false); it.picked = false }
        movingPkg = null
        moveFrom = null
        movePill.animate().cancel()
        movePill.translationY = if (currentRow == 0) 0f else -(sh * 40 / 1080).toFloat()
        movePill.visibility = View.GONE
        updateHeroRunning()
    }

    /** OK while moving: the app stays where it is and the order is saved. */
    private fun dropMove() {
        if (movingPkg == null) return
        val changed = moveFrom?.let { it.first != dockPkgs || it.second != drawerPkgs } ?: false
        endMove()
        if (!changed) return
        saveDock()
        saveOrder()
        syncOther()
    }

    /** Back while moving: everything goes back where it was. */
    private fun cancelMove() {
        val from = moveFrom ?: return
        val pkg = movingPkg
        endMove()
        setLists(from.first, from.second)
        tileOf(pkg)?.requestFocus()
    }

    /**
     * Puts new lists on screen. When the shelf and the drawer keep their sizes (a move within either, or a swap
     * across them) the tiles stay and only their apps change: no views are made or removed.
     */
    private fun setLists(dock: List<String>, drawer: List<String>) {
        val same = dock.size == dockPkgs.size && drawer.size == drawerPkgs.size && rows.size == 1 + (drawer.size + COLS - 1) / COLS
        dockPkgs.clear(); dockPkgs.addAll(dock)
        drawerPkgs.clear(); drawerPkgs.addAll(drawer)
        if (!same) { rebuildGrid(movingPkg); return }
        val byPkg = entries
        dockTiles.forEachIndexed { i, t -> val a = byPkg[dockPkgs[i]]; if (t.app !== a) t.app = a }
        var k = 0
        for (r in 1 until rows.size) for (t in rows[r]) { val a = byPkg[drawerPkgs[k++]]; if (t.app !== a) t.app = a }
    }

    /**
     * Arrows while moving. Left and Right step through the shelf, or through the drawer in reading order; Down
     * and Up move a row. Up from the drawer's first row puts the app on the shelf (a full shelf passes its last app
     * down to the front of the drawer); Down from the shelf puts it in the drawer's first row.
     */
    private fun moveBy(code: Int) {
        val pkg = movingPkg ?: return
        val d = ArrayList(dockPkgs)
        val w = ArrayList(drawerPkgs)
        val inDock = pkg in d
        val i = if (inDock) d.indexOf(pkg) else w.indexOf(pkg)
        fun shift(list: ArrayList<String>, to: Int): Boolean {
            if (to < 0 || to >= list.size) return false
            list.removeAt(i); list.add(to, pkg); return true
        }
        val moved = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> shift(if (inDock) d else w, i - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> shift(if (inDock) d else w, i + 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                inDock && d.size > 1 -> { d.removeAt(i); w.add(minOf(i, w.size), pkg); true }
                inDock -> { Toast.makeText(ctx, "The top row needs at least one app", Toast.LENGTH_SHORT).show(); false }
                i / COLS < (w.size - 1) / COLS -> shift(w, minOf(i + COLS, w.size - 1))
                else -> false
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                inDock -> false
                i >= COLS -> shift(w, i - COLS)
                else -> {
                    w.removeAt(i)
                    d.add(minOf(i, d.size), pkg)
                    if (d.size > COLS) w.add(0, d.removeAt(d.size - 1))
                    true
                }
            }
            else -> false
        }
        val old = tileOf(pkg)
        if (!moved) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> old?.nudge(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> old?.nudge(1, 0)
                KeyEvent.KEYCODE_DPAD_UP -> old?.nudge(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> old?.nudge(0, 1)
            }
            return
        }
        setLists(d, w)
        val t = tileOf(pkg) ?: return
        if (t !== old) {
            t.picked = true
            t.setJiggle(true, gentle = true)
            old?.setJiggle(false)
            old?.picked = false
        }
        t.requestFocus()
        if ((t.tag as? Int) == 0) lastDockIndex = dockTiles.indexOf(t).coerceAtLeast(0)
    }

    /** Menu while moving: add to or remove from the top row, or hide. Each action first drops the app where it is. */
    private fun openAppMenu() {
        val pkg = movingPkg ?: return
        val app = entries[pkg] ?: return
        val onShelf = pkg in dockPkgs
        blockHome(true)
        movePill.visibility = View.INVISIBLE
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add((if (onShelf) "Remove from top row" else "Add to top row") to { dropMove(); toggleDock(pkg) })
        if (Folders.isFolderId(pkg)) {
            items.add("Open folder" to { dropMove(); openFolder(pkg) })
            items.add("Rename folder" to { dropMove(); openFolder(pkg, rename = true) })
            items.add("Remove folder" to { dropMove(); confirmRemoveFolder(pkg) })
        } else {
            items.add("Add to a folder" to { dropMove(); chooseFolder(pkg) })
            items.add("Hide app" to { dropMove(); hideApp(pkg) })
            lockItem(pkg) { dropMove() }?.let { items.add(it) }
            if (Platform.canUninstall(ctx, pkg)) items.add("Uninstall" to { cancelMove(); requestUninstall(pkg) })
        }
        appMenu.open(app.label, items) {
            blockHome(false)
            if (movingPkg != null) {
                movePill.visibility = View.VISIBLE
                tileOf(movingPkg)?.requestFocus()
            }
            updateHeroRunning()
        }
        updateHeroRunning()
    }

    /** Ends move mode and the app menu (another panel is opening, or the home is being reset). */
    private fun leaveMove() {
        if (appMenu.isOpen) appMenu.close()
        if (movingPkg != null) cancelMove()
    }

    /** Re-reads the saved dock (changed on the other home screen). */
    fun reloadDock() { if (apps.isNotEmpty()) applyApps(apps) }

    // ---------- Overlays and modes ----------

    private fun blockHome(block: Boolean) {
        home.descendantFocusability = if (block) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        hero.isFocusable = !block
    }

    /** Home's tiles stay out of focus while any panel is over them. */
    private fun syncHomeBlock() {
        blockHome(settings.isOpen || cc.isOpen || setup.isOpen || browser.isOpen || folderPanel.isOpen || appMenu.isOpen)
    }

    fun focusDock() {
        if (dockTiles.isNotEmpty()) dockTiles[lastDockIndex.coerceIn(0, dockTiles.size - 1)].requestFocus()
        else gear.requestFocus()
    }

    fun focusAllApps() {
        val first = rows.getOrNull(1)?.firstOrNull()
        if (first != null) first.requestFocus() else focusDock()
    }

    fun openSettings() {
        if (cc.isOpen) cc.close(instant = true)
        if (browser.isOpen) browser.close(instant = true)
        if (folderPanel.isOpen) folderPanel.close(instant = true)
        leaveMove()
        if (editMode) exitEdit()
        val a = ctx as? Activity
        if (a != null && Build.VERSION.SDK_INT >= 23 && !prefs.askedStorage && !storageGranted()) {
            prefs.askedStorage = true
            a.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        }
        blockHome(true)
        settings.open()
        updateHeroRunning()
    }

    /** Opens first-run setup, or one of its steps from Settings (Settings comes back when it closes). */
    fun openSetup(step: SetupView.Step = SetupView.Step.WELCOME, single: Boolean = false) {
        if (ctx !is Activity) return
        val fromSettings = settings.isOpen
        if (cc.isOpen) cc.close(instant = true)
        if (settings.isOpen) settings.close(instant = true)
        if (browser.isOpen) browser.close(instant = true)
        if (folderPanel.isOpen) folderPanel.close(instant = true)
        leaveMove()
        if (editMode) exitEdit()
        blockHome(true)
        setup.open(step, single) { if (fromSettings) openSettings() }
        updateHeroRunning()
    }

    fun onSetupClosed() {
        blockHome(false)
        focusDock()
        updateHeroRunning()
        refreshUsageOrder(explicit = true)
    }

    fun openControlCenter() {
        if (cc.isOpen || settings.isOpen || setup.isOpen || browser.isOpen || folderPanel.isOpen) return
        leaveMove()
        if (editMode) exitEdit()
        val from = focused()
        blockHome(true)
        cc.open(from = from)
        updateHeroRunning()
    }

    fun onControlCenterClosed(returnTo: View?) {
        blockHome(false)
        if (!settings.isOpen && !editMode) {
            if (returnTo != null && returnTo.isAttachedToWindow && returnTo.parent != null) returnTo.requestFocus() else focusDock()
        }
        updateHeroRunning()
    }

    fun onSettingsClosed() {
        blockHome(false)
        if (!editMode) gear.requestFocus()
        updateHeroRunning()
    }

    fun enterEdit() {
        if (prefs.mostUsed) { editManualLayout { enterEdit() }; return }
        if (dockTiles.isEmpty()) return
        leaveMove()
        editMode = true
        pickedIndex = -1
        hint.visibility = View.VISIBLE
        dockTiles.forEach { it.setJiggle(true) }
        blockHome(false)
        focusDock()
        updateHeroRunning()
    }

    fun exitEdit() {
        if (pickedIndex in dockTiles.indices) dockTiles[pickedIndex].picked = false
        editMode = false
        pickedIndex = -1
        hint.visibility = View.GONE
        dockTiles.forEach { it.setJiggle(false) }
        saveDock()
        updateHeroRunning()
    }

    private fun togglePick(idx: Int) {
        if (pickedIndex >= 0) {
            dockTiles.getOrNull(pickedIndex)?.picked = false
            pickedIndex = -1
            saveDock()
        } else {
            pickedIndex = idx
            dockTiles[idx].picked = true
        }
    }

    private fun moveTile(dir: Int) {
        val from = pickedIndex
        val to = from + dir
        if (from !in dockTiles.indices || to !in dockTiles.indices) { dockTiles.getOrNull(from)?.nudge(dir, 0); return }
        val pkg = dockPkgs.removeAt(from)
        dockPkgs.add(to, pkg)
        val t = dockTiles.removeAt(from)
        dockTiles.add(to, t)
        val row = t.parent as LinearLayout
        row.removeAllViews()
        dockTiles.forEachIndexed { i, v -> row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = gap }) }
        // Leaving the window stopped each tile's jiggle: start them again.
        dockTiles.forEach { it.setJiggle(true) }
        rows[0] = ArrayList(dockTiles)
        pickedIndex = to
        lastDockIndex = to
        t.requestFocus()
        sound?.takeIf { prefs.sound }?.tick()
    }

    private fun removeFocusedFromDock() {
        val idx = dockTiles.indexOfFirst { it.isFocused }
        if (idx < 0 || dockPkgs.size <= 1) return
        if (pickedIndex >= 0) { dockTiles.getOrNull(pickedIndex)?.picked = false; pickedIndex = -1 }
        val pkg = dockPkgs.removeAt(idx)
        returnToDrawer(pkg)
        saveDock()
        rebuildGrid(dockPkgs[idx.coerceAtMost(dockPkgs.size - 1)])
    }

    // ---------- Option cards ----------

    /**
     * An option card over whatever is open (the home, Settings, Scenery, a folder). The panels under it cannot take
     * focus while it is up; when it closes without an action that moves focus, focus returns where it was.
     */
    fun card(heading: String, items: List<Pair<String, () -> Unit>>, message: String? = null, code: ((String) -> Unit)? = null) {
        if (appMenu.isOpen) appMenu.close()
        val back = focused()
        val under = listOf<ViewGroup>(settings, browser, folderPanel).filter { it.visibility == View.VISIBLE }
        under.forEach { it.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        blockHome(true)
        appMenu.open(heading, items, message, code) {
            under.forEach { it.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
            syncHomeBlock()
            if (back != null && back.isAttachedToWindow && back.isShown) back.requestFocus()
            else if (!settings.isOpen && !browser.isOpen && !folderPanel.isOpen) focusDock()
            updateHeroRunning()
        }
        updateHeroRunning()
    }

    // ---------- App lock ----------

    private fun labelOf(pkg: String) = apps.firstOrNull { it.pkg == pkg }?.label ?: entries[pkg]?.label ?: pkg

    /** Hold OK's Lock app or Unlock app; null for GlassOS, home apps and screensavers. [before] runs first. */
    fun lockItem(pkg: String, before: () -> Unit = {}): Pair<String, () -> Unit>? = when {
        !AppLock.canLock(ctx, pkg) -> null
        pkg in AppLock.locked -> "Unlock app" to { before(); unlockApp(pkg) }
        else -> "Lock app" to { before(); lockApp(pkg) }
    }

    /** Locking needs no code (the first lock chooses it); unlocking does. */
    private fun lockApp(pkg: String) {
        if (prefs.lockCode.isEmpty()) { chooseCode { lockApp(pkg) }; return }
        setLocked(pkg, true)
        val label = labelOf(pkg)
        if (AppLockGuard.available(ctx)) Toast.makeText(ctx, "$label is locked. Opening it needs your code.", Toast.LENGTH_LONG).show()
        else card("$label is locked", permissionItems(), "Opening it from GlassOS needs your code. To lock it when it is cast to " +
            "or opened by Alexa or a remote button too, allow Usage access and Display over other apps.")
    }

    private fun unlockApp(pkg: String) {
        val label = labelOf(pkg)
        askCode("Unlock $label", "Enter your lock code.") {
            setLocked(pkg, false)
            Toast.makeText(ctx, "$label is unlocked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLocked(pkg: String, on: Boolean) {
        AppLock.set(ctx, prefs, if (on) AppLock.locked + pkg else AppLock.locked - pkg)
        redrawLocks()
        syncOther()
    }

    /** Tiles draw their padlock from [AppLock.locked]: redraw them and what shows the count. */
    private fun redrawLocks() {
        for (r in rows) for (t in r) t.invalidate()
        if (folderPanel.isOpen) invalidateDeep(folderPanel)
        if (settings.isOpen) settings.refresh()
    }

    /**
     * GlassOS Manager changed the lock (see [ManagerProvider]). Turned off, a code prompt on screen could never be
     * answered, so it closes. Main thread.
     */
    fun lockChangedElsewhere(off: Boolean) {
        if (released) return
        if (off && appMenu.asksCode) appMenu.close()
        redrawLocks()
    }

    /** GlassOS Manager set or cleared the sun times place (see [ManagerProvider]): what a minute tick does, now. Main thread. */
    fun sunPlaceChanged() {
        if (released) return
        updateTime()
        if (::settings.isInitialized) settings.refresh()
    }

    /** Runs [open] at once, or for a locked app once its code is right; the app then stays open while in front. */
    private fun unlockThen(pkg: String, open: () -> Unit) {
        if (pkg !in AppLock.locked || prefs.lockCode.isEmpty()) { open(); return }
        askCode("${labelOf(pkg)} is locked", "Enter the code with your remote.") {
            AppLock.session.unlock(pkg, System.currentTimeMillis())
            open()
        }
    }

    /** Asks for the lock code; [then] runs once it is right. Wrong codes in a row lead to a short wait. */
    private fun askCode(heading: String, text: String, then: () -> Unit) {
        val wait = AppLock.tries.waitMs(SystemClock.elapsedRealtime())
        if (wait > 0) { card(heading, ok, AppLock.waitText(wait)); return }
        card(heading, emptyList(), text, code = { entered ->
            when {
                LockCode.matches(prefs.lockCode, entered) -> { AppLock.tries.pass(); appMenu.close(); then() }
                AppLock.tries.fail(SystemClock.elapsedRealtime()) -> card(heading, ok, AppLock.waitText(LockCode.WAIT_MS))
                else -> appMenu.rejectCode("Wrong code. Try again.")
            }
        })
    }

    /** A new code, entered twice; [then] runs once it is saved. */
    private fun chooseCode(then: () -> Unit) {
        card("Choose a lock code", emptyList(), "Press ${LockCode.LENGTH} buttons on your remote: the arrows, Rewind, " +
            "Play/\u2060Pause or Fast forward, in any order. A button can repeat.", code = { first ->
            card("Enter the code again", emptyList(), "Press the same ${LockCode.LENGTH} buttons.", code = { second ->
                if (second != first) card("The codes did not match", listOf("Try again" to { chooseCode(then) }, "Cancel" to {}),
                    "No code was saved.")
                else { AppLock.setCode(ctx, prefs, first); appMenu.close(); then() }
            })
        })
    }

    private fun permissionItems(): List<Pair<String, () -> Unit>> =
        if (ctx is Activity) listOf("OK" to {}, "Permissions" to { openSetup(SetupView.Step.PERMISSIONS, single = true) }) else ok

    /** Settings > App lock: choose the code, or with it change it or turn the lock off (every app unlocked). */
    fun appLockSettings() {
        if (prefs.lockCode.isEmpty()) {
            card("App lock", listOf("Choose a code" to {
                chooseCode { Toast.makeText(ctx, "Code saved. Hold OK on an app and choose Lock app.", Toast.LENGTH_LONG).show() }
            }, "Cancel" to {}), "Hold OK on an app and choose Lock app. It then needs a code of remote buttons to open.")
            return
        }
        askCode("App lock", "Enter your lock code.") {
            val n = AppLock.locked.count { p -> apps.any { it.pkg == p } }
            val status = (if (n == 1) "1 app is locked." else "$n apps are locked.") + " Hold OK on an app to lock or unlock it."
            val guard = if (AppLockGuard.available(ctx)) " Locked apps also need the code when cast to or opened by Alexa."
                else " Casting, Alexa and remote buttons can still open them: allow Usage access and Display over other apps."
            val items = arrayListOf<Pair<String, () -> Unit>>(
                "Change code" to { chooseCode { Toast.makeText(ctx, "Lock code changed", Toast.LENGTH_SHORT).show() } },
                "Turn off app lock" to {
                    AppLock.turnOff(ctx, prefs)
                    redrawLocks()
                    syncOther()
                    Toast.makeText(ctx, "App lock is off. Every app opens without a code.", Toast.LENGTH_LONG).show()
                })
            if (!AppLockGuard.available(ctx) && ctx is Activity) items.add("Permissions" to { openSetup(SetupView.Step.PERMISSIONS, single = true) })
            items.add("Done" to {})
            card("App lock", items, status + guard)
        }
    }

    // ---------- Scenery ----------

    fun sceneChoice(): SceneChoice = sceneChoice(prefs)
    fun chooseCollection() {
        card("Scenery collection", SceneCollections.modes.map { id -> SceneCollections.label(id) to {
            prefs.sceneCollection = id; applySceneChoice(); if (browser.isOpen) browser.refresh()
        } }, "Scheduled follows the daily rhythm saved in GlassOS Manager, using this TV’s local time; entries there can also " +
            "follow sunrise or sunset (Settings > Sun times). An empty collection plays your other visible scenes.")
    }
    fun sceneLibrary(): List<AerialClip> = aerialClips
    fun currentScene(): AerialClip? = aerial.current()
    fun sceneReason(): SceneFilter.Reason = aerial.reason

    /** Runs [work] on the home's worker thread (decoding thumbnails, reading files). */
    fun background(work: () -> Unit) { if (!worker.isShutdown) worker.execute(work) }

    fun refreshSceneLibrary() { rescanScenes { if (browser.isOpen) browser.refresh() } }

    /** Rescans Movies/GlassAerial off the UI thread. */
    fun rescanScenes(done: (List<AerialClip>) -> Unit) {
        background {
            val list = AerialStore.clips(ctx)
            ui {
                aerialClips = list
                if (prefs.topShelf == HeroRepo.SRC_AERIAL) aerial.setPlaylist(list, true, sceneChoice())
                done(list)
            }
        }
    }

    /** Favourites, hidden scenes, favourites only or shuffle changed: the playing clip changes only if it must. */
    fun applySceneChoice() {
        val on = prefs.topShelf == HeroRepo.SRC_AERIAL
        aerial.setPlaylist(aerialClips, on, sceneChoice())
        // Nothing may play now (every scene hidden): back to the still background rather than a hidden scene's poster.
        if (on && !aerial.isActive) setWallpaper(prefs.scene, save = false)
        syncOtherScenery()
    }

    private fun syncOtherScenery() { HomeShare.main?.takeIf { it !== this }?.let { it.aerial.setPlaylist(it.aerialClips, it.prefs.topShelf == HeroRepo.SRC_AERIAL, it.sceneChoice()) } }

    fun skipScene(dir: Int) {
        if (aerial.skip(dir)) return
        val msg = if (prefs.topShelf != HeroRepo.SRC_AERIAL) "Turn on Top Shelf > Local aerial videos for scenes"
            else "No scene can play. Show a hidden scene in Scenery, or copy clips to Movies/GlassAerial."
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    /** Plays scene [id] now, switching the Top Shelf to the aerial videos first if needed. */
    fun playScene(id: String) {
        if (prefs.topShelf != HeroRepo.SRC_AERIAL) {
            pendingScene = id
            setTopShelf(HeroRepo.SRC_AERIAL)
            return
        }
        if (!aerial.play(id)) Toast.makeText(ctx, "This scene cannot play now", Toast.LENGTH_SHORT).show()
    }

    /** The scene's name for a few seconds (also in cinema mode); while the Top Shelf is focused, with the keys to use. */
    private fun showSceneName(clip: AerialClip) {
        if (currentRow != 0 || !resumed || setup.isOpen) return
        if (hero.isFocused) sceneLabel.show(clip.name, SCENE_HINT, 0, labelCorner())
        else if (prefs.sceneLabels) sceneLabel.show(clip.name, null, SCENE_LABEL_MS, labelCorner())
    }

    /** The scene name's corner: clear of the cinema clock while it shows (see [CinemaPosition.labelCorner]). */
    private fun labelCorner() = CinemaPosition.labelCorner(if (cinema && prefs.cinemaClock) cinemaClock.corner else null)

    /** The scene name leaves its corner: with the Top Shelf focused it moves to [at] with the keys, otherwise it goes. */
    private fun moveSceneName(at: Int, instant: Boolean) {
        val clip = if (sceneLabel.shown && hero.isFocused) aerial.current() else null
        if (clip != null) sceneLabel.show(clip.name, SCENE_HINT, 0, at) else sceneLabel.hide(instant)
    }

    fun openSceneBrowser(fromSettings: Boolean = false) {
        exitCinema(instant = true)
        if (cc.isOpen) cc.close(instant = true)
        if (settings.isOpen) settings.close(instant = true)
        if (folderPanel.isOpen) folderPanel.close(instant = true)
        leaveMove()
        if (editMode) exitEdit()
        browserFromSettings = fromSettings
        browserReturn = focused()
        // Scenery shows on the home row.
        if (currentRow != 0) { FocusAnim.forceInstant = true; try { focusDock() } finally { FocusAnim.forceInstant = false } }
        sceneLabel.hide(instant = true)
        blockHome(true)
        browser.open()
        updateHeroRunning()
    }

    fun onSceneBrowserClosed() {
        syncHomeBlock()
        if (browserFromSettings) { browserFromSettings = false; openSettings(); return }
        val back = browserReturn
        browserReturn = null
        if (!settings.isOpen && !folderPanel.isOpen) {
            // Back where it was (on the home row: the browser left the drawer), else the Top Shelf with its scene name.
            when {
                back is TileView && back.isAttachedToWindow && (back.tag as? Int) == 0 -> back.requestFocus()
                aerial.isActive && hero.isFocusable -> hero.requestFocus()
                else -> focusDock()
            }
        }
        updateHeroRunning()
    }

    // ---------- Folders ----------

    fun folderName(id: String): String? = prefs.folders.firstOrNull { it.id == id }?.name

    /** The folder's installed, unhidden apps, in folder order. */
    fun folderApps(id: String): List<AppEntry> {
        val byPkg = apps.associateBy { it.pkg }
        return (folderMembers[id] ?: emptyList()).mapNotNull { byPkg[it] }
    }

    /** Apps the folder's picker offers: its own first, then loose apps, then apps in other folders (with that folder's name). */
    fun folderCandidates(id: String): List<Pair<AppEntry, String?>> {
        val hidden = prefs.hidden
        val owner = HashMap<String, String>()
        prefs.folders.forEach { f -> if (f.id != id) f.members.forEach { owner[it] = f.name } }
        val mine = folderApps(id)
        val mineSet = mine.map { it.pkg }.toSet()
        val rest = apps.filter { it.pkg !in hidden && it.pkg !in mineSet }
        return mine.map { it to null } + rest.filter { it.pkg !in owner }.map { it to null } +
            rest.filter { it.pkg in owner }.map { it to owner[it.pkg] }
    }

    /**
     * Sets folder [id]'s shown apps to [pkgs]. Apps moving in leave the top row (the folder takes the first one's
     * place there), the drawer order and any other folder; apps taken out return to the drawer next to the folder.
     * Members that are hidden or not installed stay members.
     */
    fun setFolderMembers(id: String, pkgs: List<String>) {
        val folders = prefs.folders
        val f = folders.firstOrNull { it.id == id } ?: return
        val visible = folderMembers[id] ?: emptyList()
        val picked = pkgs.filter { Folders.isValidPackage(it) }.distinct()
        // A sorted picker must not silently save its projected order over the folder's manual order.
        val ordered = if (prefs.mostUsed) f.members.filter { it in picked || it !in visible } + picked.filter { it !in f.members }
            else picked + f.members.filter { it !in visible }
        val members = ordered.distinct().take(Folders.MAX_MEMBERS)
        val removed = f.members.filter { it in visible && it !in picked }
        val manualDock = manualPlacement().dock
        prefs.folders = folders.map { g -> if (g.id == id) g.copy(members = members) else g.copy(members = g.members - picked.toSet()) }
        val dock = ArrayList(manualDock)
        var dockChanged = false
        for (pkg in picked) {
            val i = dock.indexOf(pkg)
            if (i < 0) continue
            if (id !in dock) dock[i] = id else dock.removeAt(i)
            dockChanged = true
        }
        if (dockChanged) prefs.dock = dock
        prefs.order?.let { o ->
            var order = o.filter { it !in picked }
            if (removed.isNotEmpty()) {
                val at = order.indexOf(id).let { if (it < 0) 0 else it + 1 }
                order = order.take(at) + removed + order.drop(at)
            }
            prefs.order = order
        }
        reloadDock()
        syncOther()
    }

    fun removeFromFolder(id: String, pkg: String) = setFolderMembers(id, (folderMembers[id] ?: emptyList()) - pkg)

    private fun addToFolder(id: String, pkg: String) {
        setFolderMembers(id, (folderMembers[id] ?: emptyList()) + pkg)
        tileOf(id)?.requestFocus()
        Toast.makeText(ctx, "Added to ${folderName(id) ?: "the folder"}", Toast.LENGTH_SHORT).show()
    }

    fun renameFolder(id: String, name: String) {
        prefs.folders = prefs.folders.map { if (it.id == id) it.copy(name = Folders.cleanName(name).ifEmpty { it.name }) else it }
        reloadDock()
        syncOther()
    }

    /** Removes the folder; its apps go back to the drawer where the folder was. */
    fun removeFolder(id: String) {
        val manualDock = manualPlacement().dock
        val (rest, order) = HomeLayout.removeFolder(prefs.folders, id, prefs.order)
        prefs.folders = rest
        if (prefs.order != null) prefs.order = order
        if (id in manualDock) prefs.dock = manualDock.filter { it != id }
        reloadDock()
        syncOther()
        focusDock()
    }

    private fun confirmRemoveFolder(id: String) {
        val name = folderName(id) ?: return
        card("Remove \u201C$name\u201D?", listOf("Cancel" to {}, "Remove folder" to { removeFolder(id) }),
            "Its apps go back to the app list. No app is uninstalled.")
    }

    /** A new folder, named "Folder" (then 2, 3...), in [with]'s place if given; opens it to be named. */
    fun createFolder(with: String? = null) {
        val folders = prefs.folders
        if (folders.size >= Folders.MAX_FOLDERS) {
            Toast.makeText(ctx, "GlassOS holds ${Folders.MAX_FOLDERS} folders. Remove one first.", Toast.LENGTH_LONG).show()
            return
        }
        val manualDock = manualPlacement().dock
        val id = Folders.newId(folders.map { it.id })
        var next = folders + AppFolder(id, Folders.defaultName(folders), emptyList())
        if (with != null) next = HomeLayout.addToFolder(next, id, with)
        prefs.folders = next
        if (with != null && with in manualDock) prefs.dock = manualDock.map { if (it == with) id else it }
        prefs.order?.let { o ->
            val i = if (with == null) -1 else o.indexOf(with)
            prefs.order = if (i >= 0) o.toMutableList().apply { set(i, id) } else listOf(id) + o
        }
        reloadDock()
        syncOther()
        openFolder(id, rename = true, isNew = true)
    }

    /** Move menu: a folder for [pkg], or a new one. */
    private fun chooseFolder(pkg: String) {
        val label = entries[pkg]?.label ?: pkg
        val items = prefs.folders.map { f -> f.name to { addToFolder(f.id, pkg) } } + ("New folder" to { createFolder(pkg) })
        card("Add \u201C$label\u201D to", items)
    }

    fun openFolder(id: String, rename: Boolean = false, isNew: Boolean = false) {
        if (folderName(id) == null) return
        exitCinema(instant = true)
        if (cc.isOpen) cc.close(instant = true)
        if (settings.isOpen) settings.close(instant = true)
        if (browser.isOpen) browser.close(instant = true)
        leaveMove()
        if (editMode) exitEdit()
        blockHome(true)
        folderPanel.open(id, rename, isNew)
        updateHeroRunning()
    }

    fun onFolderClosed(id: String?) {
        syncHomeBlock()
        if (!settings.isOpen && !browser.isOpen && !appMenu.isOpen) (id?.let { tileOf(it) })?.requestFocus() ?: focusDock()
        updateHeroRunning()
    }

    // ---------- Cinema mode (idle) ----------

    /** Only the real home (not the overlay after Home), in front, uncovered and not being edited, goes to cinema. */
    private fun cinemaAllowed() = ctx is Activity && !released && prefs.cinemaDelaySec > 0 && resumed && root.hasWindowFocus() &&
        !settings.isOpen && !cc.isOpen && !setup.isOpen && !appMenu.isOpen && !browser.isOpen && !folderPanel.isOpen &&
        !editMode && movingPkg == null

    /** One pending check at most; it reschedules itself for the time left rather than on every key press. */
    private fun syncIdle() {
        if (!::root.isInitialized) return
        main.removeCallbacks(idleCheck)
        if (!cinema && cinemaAllowed()) main.postDelayed(idleCheck, (prefs.cinemaDelaySec * 1000L - (SystemClock.uptimeMillis() - lastInput)).coerceAtLeast(500))
    }

    private fun checkIdle() {
        if (cinema || !cinemaAllowed()) return
        val left = prefs.cinemaDelaySec * 1000L - (SystemClock.uptimeMillis() - lastInput)
        if (left > 0) main.postDelayed(idleCheck, left) else enterCinema()
    }

    private fun enterCinema() {
        cinema = true
        cinemaFocus = focused()
        // The scenery shows on the home row: leave the drawer without sound or animation (focus returns on wake).
        if (currentRow != 0) { FocusAnim.forceInstant = true; try { focusDock() } finally { FocusAnim.forceInstant = false } }
        sceneLabel.hide()
        home.animate().cancel()
        home.animate().alpha(0f).setDuration(Effects.fade(900)).withLayer().start()
        if (prefs.cinemaClock) {
            positionCinemaClock()
            cinemaClock.visibility = View.VISIBLE
            cinemaClock.animate().cancel()
            cinemaClock.animate().alpha(1f).setDuration(Effects.fade(900)).start()
        }
        WLog.i("cinema on")
    }

    private fun exitCinema(instant: Boolean) {
        if (!::root.isInitialized || !cinema) return
        cinema = false
        lastInput = SystemClock.uptimeMillis()
        home.animate().cancel()
        cinemaClock.animate().cancel()
        if (instant) {
            home.alpha = 1f
            cinemaClock.alpha = 0f; cinemaClock.visibility = View.GONE
        } else {
            home.animate().alpha(1f).setDuration(Effects.fade(250)).withLayer().start()
            cinemaClock.animate().alpha(0f).setDuration(Effects.fade(200)).withEndAction { if (!cinema) cinemaClock.visibility = View.GONE }.start()
        }
        val f = cinemaFocus
        cinemaFocus = null
        if (f != null && f !== focused() && f.isAttachedToWindow && f.isShown) f.requestFocus()
        // A name moved out of the clock's way would sit on the returning app rows: it goes, or returns to the top left
        // while the Top Shelf is focused.
        if (sceneLabel.corner != 0) moveSceneName(0, instant)
        syncIdle()
    }

    /** Wakes cinema mode on the first key, which is consumed (with its repeats and release). Volume keys still work. */
    private fun cinemaKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (eatKey != -1) {
            if (code == eatKey) {
                // A fresh press of the same key (its release was lost) is a new press, not part of the waking one.
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && !cinema) eatKey = -1
                else { if (e.action == KeyEvent.ACTION_UP) eatKey = -1; return true }
            }
        }
        val volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
        if (cinema) {
            exitCinema(instant = false)
            if (volume) return false
            eatKey = if (e.action == KeyEvent.ACTION_DOWN) code else -1
            return true
        }
        lastInput = SystemClock.uptimeMillis()
        return false
    }

    // ---------- Night mode ----------

    /** Dims the scenery and the grid inside the user's night window (local time). Launcher UI only: never the TV's brightness. */
    fun applyNight() {
        if (!::nightDim.isInitialized) return
        val a = nightAlpha(prefs)
        val on = a > 0f
        if (on == nightOn && (!on || nightLevelShown == a)) return
        nightOn = on
        nightLevelShown = a
        nightDim.setBackgroundColor(android.graphics.Color.argb((a * 255).toInt(), 0, 0, 0))
        nightDim.visibility = if (on) View.VISIBLE else View.GONE
        WLog.i("night ${if (on) "on" else "off"}")
    }

    /** Today's sun times for Settings, such as "Sunset +30 · 6:42 PM", or the clock time used instead. */
    fun nightValue(start: Boolean): String {
        val h24 = android.text.format.DateFormat.is24HourFormat(ctx)
        val fixed = if (start) prefs.nightStart else prefs.nightEnd
        if (!prefs.nightSun) return NightSchedule.format(fixed, h24)
        val anchor = if (start) "sunset" else "sunrise"
        val offset = if (start) prefs.nightStartOffset else prefs.nightEndOffset
        val day = SunNow.today(prefs)
        val window = NightSchedule.resolve(true, prefs.nightStart, prefs.nightEnd, prefs.nightStartOffset, prefs.nightEndOffset, day)
        // The sun's window or none: a polar day, or offsets that leave no night or no day, keep the whole clock window,
        // as applyNight does.
        val sunTimes = NightSchedule.sunWindow(prefs.nightStartOffset, prefs.nightEndOffset, day) != null
        val time = NightSchedule.format(if (start) window.start else window.end, h24)
        return NightSchedule.formatOffset(anchor, offset) + if (sunTimes) " \u00B7 $time" else " \u00B7 uses $time"
    }

    /** Settings > Sun times: where they come from and today's times. */
    fun sunTimesInfo() {
        val place = SunNow.place(prefs)
        val day = SunNow.today(prefs)
        val h24 = android.text.format.DateFormat.is24HourFormat(ctx)
        val rise = day?.minute("sunrise", 0)
        val set = day?.minute("sunset", 0)
        val today = when {
            place == null -> "Sun times are unavailable: this TV\u2019s time zone (${java.util.TimeZone.getDefault().id}) has no place " +
                "to work them out from. Night mode and the daily rhythm use their clock times."
            day?.polar == "up" -> "The sun does not set here today, so Night mode and the daily rhythm use their clock times."
            day?.polar == "down" -> "The sun does not rise here today, so Night mode and the daily rhythm use their clock times."
            rise != null && set != null -> "Today: sunrise ${NightSchedule.format(rise, h24)}, sunset ${NightSchedule.format(set, h24)}."
            else -> "Sun times are unavailable today."
        }
        val source = when (place?.source) {
            "manager" -> "Worked out for ${place.label.ifEmpty { "the place" }} set in GlassOS Manager. To change it, use GlassOS " +
                "Manager > Daily rhythm on your Mac."
            "timezone" -> "Estimated for ${place.label}, from this TV\u2019s time zone, so they can be off by an hour or more. " +
                "For exact times, set your place in GlassOS Manager > Daily rhythm on your Mac."
            else -> "To use sun times, set your place in GlassOS Manager > Daily rhythm on your Mac."
        }
        card("Sun times", listOf("OK" to {}), "$today\n\n$source")
    }

    // ---------- Screensaver ----------

    /**
     * Settings > Screensaver: whether the TV shows this scenery when idle, and a preview. GlassOS cannot choose its own
     * screensaver (that needs a protected setting); GlassOS Manager does it over ADB.
     */
    fun screensaverInfo() {
        val text = if (AerialDream.chosen(ctx)) "Your scenery plays when the TV is idle in any app. It does not start while a video is playing."
            else "To show your scenery when the TV is idle, choose Use GlassOS scenery in GlassOS Manager > TV controls on " +
                "your Mac." + (if (Platform.isFireTv(ctx)) " If it turns Amazon\u2019s Ambient Experience off, restart the TV once." else "")
        val items = ArrayList<Pair<String, () -> Unit>>()
        // From MainActivity only: the overlay after Home hands launches to the app it waits for.
        if (ctx is Activity) items.add("Preview" to { host.startApp(Intent(ctx, ScreensaverPreview::class.java), ctx.packageName, null) })
        items.add("OK" to {})
        card("Screensaver", items, text)
    }

    // ---------- Settings applied at once, backups ----------

    private fun positionCinemaClock() {
        if (!::cinemaClock.isInitialized) return
        cinemaClock.place(prefs.movingClock)
        // The clock (already showing) moved into the scene name's corner: the name makes way at once.
        if (cinema && prefs.cinemaClock && cinemaClock.visibility == View.VISIBLE && sceneLabel.corner == cinemaClock.corner)
            moveSceneName(labelCorner(), instant = true)
    }

    /** Display settings changed (effects, night, cinema): apply them now. */
    fun settingsChanged() {
        Effects.load(prefs)
        nightLevelShown = -1f
        applyNight()
        if (prefs.cinemaDelaySec == 0) exitCinema(instant = true)
        syncIdle()
        invalidateDeep(root)
        HomeShare.main?.takeIf { it !== this }?.let { it.nightLevelShown = -1f; it.applyNight(); invalidateDeep(it.root) }
    }

    /** Everything a restored backup may have changed. */
    private fun reloadAll() {
        ++usageToken
        usagePending = false
        usage = AppUsage.Snapshot()
        settingsChanged()
        loadApps()
        reloadDock()
        setWallpaper(prefs.scene, save = false)
        HomeShare.main?.takeIf { it !== this }?.let { it.reloadDock(); it.setWallpaper(it.prefs.scene, save = false) }
        if (settings.isOpen) settings.refresh()
        refreshUsageOrder(explicit = true)
    }

    /** Settings > Back up settings. Asks once for shared storage, which keeps the file when GlassOS is uninstalled. */
    fun exportBackup() {
        val a = ctx as? Activity
        if (a != null && Build.VERSION.SDK_INT >= 23 && !Backup.canWriteShared(ctx)) {
            a.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
            return
        }
        writeBackup()
    }

    /** The storage answer came back (granted or not): write the backup wherever possible. */
    fun onWritePermission() { writeBackup() }

    private fun writeBackup() {
        background {
            val result: Any = try {
                Backup.write(ctx, BackupCodec.encode(prefs.toBackup(ScreenMirroring.available(ctx)), BuildConfig.VERSION_NAME,
                    System.currentTimeMillis()))
            } catch (e: Exception) { e }
            ui {
                if (result is Backup.Saved) {
                    val path = Backup.display(result.file)
                    prefs.lastBackup = path
                    if (settings.isOpen) settings.refresh()
                    card("Settings backed up", ok, "Saved to\n$path\n\n" + if (result.shared)
                        "To restore it on another TV, copy it to that TV's Documents/${Backup.FOLDER} folder and choose Restore from backup."
                    else "This folder is deleted if GlassOS is uninstalled. Allow storage access (Settings > Permissions) to save to Documents/${Backup.FOLDER} instead.")
                } else card("Backup failed", ok, (result as Exception).message ?: result.javaClass.simpleName)
            }
        }
    }

    /** Settings > Restore from backup: the backups found, newest first. */
    fun importBackup() {
        background {
            val files = try { Backup.list(ctx).take(12) } catch (e: Exception) { emptyList() }
            val canRead = Grants.storage(ctx)
            ui {
                if (files.isEmpty()) {
                    card("No backups found", ok, "Put a backup file (.json) in\n/sdcard/Documents/${Backup.FOLDER}\nand try again." +
                        if (canRead) "" else "\n\nGlassOS also needs storage access to read it (Settings > Permissions).")
                } else {
                    card("Restore from backup", files.map { f -> f.name to { confirmImport(f) } } + ("Cancel" to {}),
                        "Newest first, from Documents/${Backup.FOLDER}.")
                }
            }
        }
    }

    private fun confirmImport(f: java.io.File) {
        background {
            val r = Backup.read(f)
            ui {
                when (r) {
                    is BackupResult.Error -> card("Cannot restore this backup", ok, f.name + "\n\n" + r.message)
                    is BackupResult.Ok -> {
                        // Without Screen Mirroring here, its section is neither named nor restored.
                        val d = r.data.forThisTv(ScreenMirroring.available(ctx))
                        val warning = d.mirroringWarning(prefs.airplayOn, prefs.airplayConfig.pin)
                        card("Restore this backup?", listOf("Cancel" to {}, "Restore" to { restore(d) }),
                            f.name + (if (r.exportedAt.isNotEmpty()) "\nMade ${r.exportedAt.replace('T', ' ').removeSuffix("Z")} UTC" else "") +
                                "\n\nReplaces: ${d.summary()}.\nApps and scenes not on this TV are kept for later. Your name, TMDB key, " +
                                "permissions and home settings stay as they are." + if (warning != null) "\n\n$warning" else "")
                    }
                }
            }
        }
    }

    private fun restore(d: BackupData) {
        // commit() changes the settings in memory even when writing them to storage fails: show what is in use either way.
        val saved = prefs.apply(d)
        reloadAll()
        if (d.mirroring != null) ScreenMirroring.restored(ctx, prefs)
        if (saved) card("Settings restored", ok, d.summary().replaceFirstChar { it.uppercase() } + ".")
        else card("Restored, but not saved", ok, "The restored settings are in use now, but they could not be written to " +
            "storage, so GlassOS may go back to the earlier settings when it restarts. Choose Restore from backup again to retry.")
    }

    // ---------- State carried between the overlay and MainActivity ----------

    fun state(): HomeState {
        val f = if (cc.isOpen) cc.returnFocus else focused()
        return HomeState((f as? TileView)?.app?.pkg, f === gear, f === hero, lastDockIndex, settings.isOpen, cc.isOpen)
    }

    /** Puts focus, drawer scroll and the shown slide where [s] and [from] had them, with no animation. */
    fun restore(s: HomeState, from: HomeScreen?) {
        if (from != null && from.hero.slides.isNotEmpty()) hero.adopt(from.hero)
        if (setup.isOpen) return
        leaveMove()
        if (editMode) exitEdit()
        lastDockIndex = s.lastDockIndex
        exitCinema(instant = true)
        FocusAnim.forceInstant = true
        try {
            if (cc.isOpen) cc.close(instant = true)
            if (appMenu.isOpen) appMenu.close()
            if (browser.isOpen) browser.close(instant = true)
            if (folderPanel.isOpen) folderPanel.close(instant = true)
            if (s.settingsOpen && !settings.isOpen) { blockHome(true); settings.open(instant = true) }
            else if (!s.settingsOpen && settings.isOpen) settings.close(instant = true)
            if (!s.settingsOpen) {
                val t = s.focusPkg?.let { p -> rows.flatten().firstOrNull { it.app?.pkg == p } }
                when {
                    t != null -> t.requestFocus()
                    s.focusGear -> gear.requestFocus()
                    s.focusHero && hero.isFocusable -> hero.requestFocus()
                    else -> focusDock()
                }
                if (s.ccOpen) { blockHome(true); cc.open(instant = true, from = focused()) }
            }
        } finally {
            FocusAnim.forceInstant = false
        }
        updateHeroRunning()
    }

    /** The tile that opened an app from the overlay stays pressed until the app is in front. */
    fun pressed(v: View?) {
        (v as? TileView)?.animate()?.scaleX(1.1f)?.scaleY(1.1f)?.setDuration(120)?.start()
    }

    // ---------- Keys ----------

    /** Returns true when the key was handled here; false lets the window dispatch it normally. */
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (setup.isOpen) return false
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down && e.repeatCount == 0) onMenu()
            return true
        }
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (ok && eatOkUp) {
            if (!down) { eatOkUp = false; return true }
            if (e.repeatCount > 0) return true
            eatOkUp = false
        }
        if (settings.isOpen || cc.isOpen || appMenu.isOpen || browser.isOpen || folderPanel.isOpen) return false
        val focusTile = focused() as? TileView
        if (movingPkg != null) {
            // The hold that started the move keeps repeating until OK is let go: only a new press drops.
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down && e.repeatCount == 0) { dropMove(); eatOkUp = true }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down && e.repeatCount == 0) openAppMenu(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) moveBy(code); return true
                }
            }
            return false
        }
        if (editMode) {
            val idx = dockTiles.indexOf(focusTile)
            if (ok && idx >= 0) {
                if (down && e.repeatCount == 0) togglePick(idx)
                return true
            }
            if (down && pickedIndex >= 0) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveTile(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveTile(1); return true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && idx >= 0) return true
        } else if (down && focusTile != null) {
            // tvOS behaviour at the edges: no wrapping, no diagonal jumps, just a small tilt.
            val r = (focusTile.tag as? Int) ?: 0
            val row = rows.getOrNull(r)
            val col = row?.indexOf(focusTile) ?: -1
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (col == 0) { focusTile.nudge(-1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (row != null && col == row.size - 1) { focusTile.nudge(1, 0); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (r >= rows.size - 1) { focusTile.nudge(0, 1); return true }
                    else {
                        val next = rows[r + 1]
                        if (next.isNotEmpty()) { next[col.coerceIn(0, next.size - 1)].requestFocus(View.FOCUS_DOWN); return true }
                    }
                KeyEvent.KEYCODE_DPAD_UP -> if (r >= 1) {
                    val prev = rows[r - 1]
                    if (prev.isNotEmpty()) { prev[col.coerceIn(0, prev.size - 1)].requestFocus(View.FOCUS_UP); return true }
                }
            }
        } else if (down && focused() === gear && code == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusDock(); return true
        }
        return false
    }

    /** Menu opens Control Center anywhere on the home; while an app is moving it opens the app's options. */
    private fun onMenu() {
        when {
            cc.isOpen -> cc.close()
            appMenu.isOpen -> appMenu.close()
            folderPanel.isOpen -> folderPanel.onMenu()
            browser.isOpen -> browser.onMenu()
            settings.isOpen -> settings.close()
            movingPkg != null -> openAppMenu()
            editMode -> removeFocusedFromDock()
            else -> openControlCenter()
        }
    }

    fun onBack() {
        when {
            setup.isOpen -> setup.back()
            cc.isOpen -> cc.back()
            appMenu.isOpen -> appMenu.close()
            folderPanel.isOpen -> folderPanel.back()
            browser.isOpen -> browser.back()
            settings.isOpen -> settings.close()
            movingPkg != null -> cancelMove()
            editMode -> exitEdit()
            currentRow > 0 -> focusDock()
            else -> { /* Home stays put. */ }
        }
    }

    /** Home pressed again while this home is shown: like tvOS, close what is open and keep focus. */
    fun onHomeAgain(fromWatcher: Boolean) {
        refreshUsageOrder()
        exitCinema(instant = false)
        if (setup.isOpen) return
        if (fromWatcher && !settings.isOpen && !cc.isOpen && !editMode && movingPkg == null && !appMenu.isOpen &&
            !browser.isOpen && !folderPanel.isOpen) {
            if (focused() == null) focusDock()
            return
        }
        if (cc.isOpen) cc.close(instant = true)
        if (appMenu.isOpen) appMenu.close()
        if (browser.isOpen) browser.close(instant = true)
        if (folderPanel.isOpen) folderPanel.close(instant = true)
        if (settings.isOpen) settings.close()
        leaveMove()
        if (editMode) exitEdit()
        lastDockIndex = 0
        blockHome(false)
        focusDock()
    }

    /** Root view: sends keys to the home screen first, and Back to [onBack]. */
    private inner class KeyRoot(c: Context) : FrameLayout(c) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.action == KeyEvent.ACTION_DOWN) inputEpoch++
            if (cinemaKey(e)) return true
            if (onKey(e)) return true
            if (e.keyCode == KeyEvent.KEYCODE_BACK && ctx !is Activity) {
                if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) onBack()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) inputEpoch++
            if (e.actionMasked == MotionEvent.ACTION_DOWN && cinema) { exitCinema(instant = false); return true }
            lastInput = SystemClock.uptimeMillis()
            return super.dispatchTouchEvent(e)
        }

        override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
            inputEpoch++
            if (cinema) { exitCinema(instant = false); return true }
            lastInput = SystemClock.uptimeMillis()
            return super.dispatchGenericMotionEvent(e)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) applyPendingUsage()
            // Something covers the home (a system dialog, Alexa, the screensaver): no cinema under it.
            if (!hasWindowFocus) { exitCinema(instant = true); eatKey = -1 }
            lastInput = SystemClock.uptimeMillis()
            syncIdle()
        }
    }

    companion object {
        const val COLS = 6
        private const val SCENE_HINT = "Left or Right: change scene  \u00B7  OK: Scenery"
        private const val REQ_WRITE = 9
        /** How long a new scene's name shows (the home and the screensaver). */
        const val SCENE_LABEL_MS = 5000L

        private fun minuteNow(): Int {
            val cal = java.util.Calendar.getInstance()
            return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        }

        /**
         * The scenery choice now. The home and the screensaver both make it here: they share one play order, and a
         * different choice from either would change the clip under the other.
         */
        fun sceneChoice(prefs: Prefs): SceneChoice {
            val schedule = AerialStore.schedule
            val sun = if (prefs.sceneCollection == "scheduled" && schedule.any { it.anchor != null }) SunNow.today(prefs) else null
            return SceneChoice.of(prefs.sceneFavorites, prefs.sceneHidden, prefs.sceneFavoritesOnly, prefs.sceneShuffle,
                prefs.sceneCollection, schedule, sun, minuteNow())
        }

        /** Night mode's dark layer now (0 when it is off or outside its window), for the home and the screensaver. */
        fun nightAlpha(prefs: Prefs): Float {
            val sun = if (prefs.nightEnabled && prefs.nightSun) SunNow.today(prefs) else null
            val window = NightSchedule.resolve(prefs.nightSun, prefs.nightStart, prefs.nightEnd, prefs.nightStartOffset, prefs.nightEndOffset, sun)
            return NightSchedule.dimAlpha(prefs.nightEnabled, window, prefs.nightLevel, minuteNow())
        }
    }
}
