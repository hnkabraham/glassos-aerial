package io.github.abhishekkaisar.glassos

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.abhishekkaisar.glassos.airplay.AirPlay
import io.github.abhishekkaisar.glassos.airplay.AirPlayConfig

/** Right-hand glass settings panel. */
class SettingsView(private val act: HomeScreen) : FrameLayout(act.ctx) {
    var isOpen = false
        private set
    private val scrim = View(context).apply { setBackgroundColor(0x73000000) }
    private val panelW = dpi(410f)
    private val margin = dpi(26f)
    val panel = GlassBox(context, dp(36f)).apply {
        glass.tint = 0.10f
        glass.dark = 0.30f
        glass.sheen = 0.10f
        shadowAlpha = 0.5f
    }
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = false
    }
    private val nameRow = RowView(context, "Name")
    private val photoRow = RowView(context, "Profile photo")
    private val countryRow = RowView(context, "Country")
    private val shelfRow = RowView(context, "Top Shelf")
    private val keyRow = RowView(context, "TMDB API key")
    private val appsRow = RowView(context, "Top row apps")
    private val sortRow = RowView(context, "App order")
    private val sortHint = TextView(context).apply {
        textSize = 14f
        typeface = Fonts.medium
        setTextColor(0x99FFFFFF.toInt())
        setPadding(dpi(20f), 0, dpi(20f), dpi(10f))
    }
    private val hiddenRow = RowView(context, "Hidden apps")
    private val lockRow = RowView(context, "App lock")
    private val permsRow = RowView(context, "Permissions")
    private val setupRow = RowView(context, "Run setup again")
    private val aboutRow = RowView(context, "About")
    private val uninstallRow = RowView(context, "Prepare to uninstall")
    private val soundRow = RowView(context, "Focus sounds")
    private val editRow = RowView(context, "Edit top row")
    private val bgRow = RowView(context, "Background").apply { isFocusable = false; isClickable = false }
    private val allRow = RowView(context, "Show all apps")
    private val replaceRow = RowView(context, "Replace ${Platform.name(context)} home")
    private val fireSettingsRow = RowView(context, "${Platform.name(context)} settings")
    private val fireHomeRow = RowView(context, "${Platform.name(context)} home")
    /** Android TV and Google TV: make GlassOS the default home app. */
    private val homeAppRow = RowView(context, "Home screen")
    // Scenery, cinema, night and effects.
    private val sceneryRow = RowView(context, "Scenery")
    private val shuffleRow = RowView(context, "Shuffle scenes")
    private val favOnlyRow = RowView(context, "Favourite scenes only")
    private val labelsRow = RowView(context, "Scene names")
    private val cinemaRow = StepperRow(context, "Cinema mode") { d -> stepCinema(d) }
    private val themeRow = StepperRow(context, "Glass theme") { d ->
        val all = Themes.all
        act.prefs.theme = all[(all.indexOfFirst { it.id == act.prefs.theme } + d + all.size) % all.size].id
        changed()
    }
    private val artRow = RowView(context, "Custom app art")
    private val movingClockRow = RowView(context, "Move cinema clock")
    private val clockRow = RowView(context, "Cinema clock")
    private val screensaverRow = RowView(context, "Screensaver")
    private val nightRow = RowView(context, "Night mode")
    private val nightSunRow = RowView(context, "Night follows")
    // In sun mode the steppers move the offsets from sunset and sunrise; the clock times stay as the fallback.
    private val nightStartRow = StepperRow(context, "Night starts") { d ->
        val p = act.prefs
        if (p.nightSun) p.nightStartOffset = NightSchedule.stepOffset(p.nightStartOffset, d) else p.nightStart = NightSchedule.step(p.nightStart, d)
        changed()
    }
    private val nightEndRow = StepperRow(context, "Night ends") { d ->
        val p = act.prefs
        if (p.nightSun) p.nightEndOffset = NightSchedule.stepOffset(p.nightEndOffset, d) else p.nightEnd = NightSchedule.step(p.nightEnd, d)
        changed()
    }
    private val nightLevelRow = StepperRow(context, "Night dimming") { d ->
        act.prefs.nightLevel = ((act.prefs.nightLevel + d) % BackupCodec.NIGHT_LEVELS + BackupCodec.NIGHT_LEVELS) % BackupCodec.NIGHT_LEVELS; changed()
    }
    private val sunRow = RowView(context, "Sun times")
    private var sunRoom = 0
    private val transparencyRow = RowView(context, "Reduce transparency")
    private val motionRow = RowView(context, "Reduce motion")
    // Screen Mirroring (the airplay module), only where it can run: Fire OS 6 or later, in a build with the receiver.
    private val mirroring = ScreenMirroring.available(context)
    private val mirrorSubtitle = TextView(context).apply {
        text = "Compatible with AirPlay from iPhone, iPad and Mac"
        textSize = 14f
        typeface = Fonts.medium
        setTextColor(0x80FFFFFF.toInt())
        setPadding(dpi(20f), 0, dpi(20f), dpi(10f))
    }
    private val mirrorOnRow = RowView(context, "Mirroring")
    private val mirrorStatusRow = RowView(context, "Status").apply { isFocusable = false; isClickable = false }
    private val mirrorNameRow = RowView(context, "TV name")
    private val mirrorPinRow = RowView(context, "Require PIN")
    private val mirrorResRow = StepperRow(context, "Resolution") { d ->
        ScreenMirroring.set(context, act.prefs, AirPlayConfig.RES, MirrorState.stepResolution(act.prefs.airplayConfig.res, d))
        updateMirroring()
    }
    private val mirrorVolumeRow = RowView(context, "Sender controls TV volume")
    // Android 10 and later (Fire OS 8) refuse to bring the receiver screen up over another app without Display over
    // other apps; before that it comes up anyway, so the row and its hint are not shown.
    private val mirrorOverlay = mirroring && android.os.Build.VERSION.SDK_INT >= 29
    private val mirrorOverlayRow = RowView(context, "Over other apps")
    private val mirrorOverlayHint = TextView(context).apply {
        text = "Without Display over other apps, mirroring that starts while another app is open plays hidden: choose " +
            "Mirroring in Control Center to see it."
        textSize = 14f
        typeface = Fonts.medium
        setTextColor(0x99FFFFFF.toInt())
        setPadding(dpi(20f), 0, dpi(20f), dpi(10f))
    }
    /** Room for the status and name values beside their titles (see [fitValue]). */
    private var mirrorRoom = 0
    private val mirrorListener = AirPlay.Listener { if (isOpen) updateMirroring() }
    private val folderRow = RowView(context, "New app folder")
    private val backupRow = RowView(context, "Back up settings")
    private val restoreRow = RowView(context, "Restore from backup")
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = true
    }
    private val thumbsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val thumbsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = false
        setFadingEdgeLength(0)
        clipChildren = true
        clipToPadding = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val thumbs = ArrayList<ThumbView>()

    init {
        visibility = View.GONE
        clipChildren = false
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(panelW, LayoutParams.MATCH_PARENT, Gravity.END).apply { setMargins(margin, margin, margin, margin) })
        val pad = dpi(22f)
        list.setPadding(pad, dpi(8f), pad, dpi(8f))
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // On API 25 a parent's clipChildren sets its children's clip, so the panel clips the scroll to its bounds,
        // inset from the rounded corners.
        panel.clipChildren = true
        panel.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dpi(12f); bottomMargin = dpi(14f)
        })
        if (android.os.Build.VERSION.SDK_INT >= 23) scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateDeep(list) }

        val title = TextView(context).apply {
            text = "Settings"
            textSize = 26f
            typeface = Fonts.medium
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dpi(6f), 0, 0, dpi(8f))
        }
        list.addView(title)
        val rowLp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48f)).apply { bottomMargin = dpi(5f) } }
        list.addView(nameRow, rowLp())
        list.addView(photoRow, rowLp())
        list.addView(countryRow, rowLp())
        list.addView(shelfRow, rowLp())
        list.addView(keyRow, rowLp())
        list.addView(soundRow, rowLp())
        list.addView(bgRow, rowLp())
        val bgLabel = TextView(context).apply {
            text = "When Top Shelf art is unavailable"
            textSize = 15f
            typeface = Fonts.medium
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dpi(20f), dpi(8f), 0, dpi(2f))
        }
        list.addView(bgLabel)
        thumbsScroll.setPadding(0, dpi(12f), 0, dpi(14f))
        thumbsRow.setPadding(dpi(18f), 0, dpi(18f), 0)
        thumbsScroll.addView(thumbsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.addView(thumbsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = -dpi(14f); rightMargin = -dpi(14f); bottomMargin = dpi(6f)
        })
        list.addView(section("Scenery and display"))
        for (r in listOf(sceneryRow, shuffleRow, favOnlyRow, labelsRow, cinemaRow, clockRow, movingClockRow, screensaverRow, themeRow, artRow,
                nightRow, nightSunRow, nightStartRow, nightEndRow, nightLevelRow, sunRow, transparencyRow, motionRow)) list.addView(r, rowLp())
        if (mirroring) {
            list.addView(section("Screen Mirroring"))
            list.addView(mirrorSubtitle)
            for (r in listOf(mirrorOnRow, mirrorStatusRow, mirrorNameRow, mirrorPinRow, mirrorResRow, mirrorVolumeRow)) list.addView(r, rowLp())
            if (mirrorOverlay) {
                list.addView(mirrorOverlayRow, rowLp())
                list.addView(mirrorOverlayHint)
            }
        }
        list.addView(section("Apps"))
        list.addView(sortRow, rowLp())
        list.addView(sortHint)
        list.addView(editRow, rowLp())
        list.addView(appsRow, rowLp())
        list.addView(hiddenRow, rowLp())
        list.addView(lockRow, rowLp())
        list.addView(allRow, rowLp())
        list.addView(folderRow, rowLp())
        list.addView(replaceRow, rowLp())
        list.addView(permsRow, rowLp())
        if (!Platform.isFireTv(context)) list.addView(homeAppRow, rowLp())
        list.addView(fireSettingsRow, rowLp())
        if (Platform.stockHome(context) != null) list.addView(fireHomeRow, rowLp())
        list.addView(backupRow, rowLp())
        list.addView(restoreRow, rowLp())
        list.addView(setupRow, rowLp())
        list.addView(uninstallRow, rowLp())
        list.addView(aboutRow, rowLp())

        fun step(row: View, s: SetupView.Step) { resumeRow = row; act.openSetup(s, single = true) }
        nameRow.onClick = { step(nameRow, SetupView.Step.NAME) }
        photoRow.onClick = { step(photoRow, SetupView.Step.PHOTO) }
        countryRow.onClick = { step(countryRow, SetupView.Step.COUNTRY) }
        keyRow.onClick = { step(keyRow, SetupView.Step.TMDB_KEY) }
        appsRow.onClick = { act.editManualLayout { step(appsRow, SetupView.Step.APPS) } }
        sortRow.onClick = { act.chooseAppOrder() }
        hiddenRow.onClick = { step(hiddenRow, SetupView.Step.HIDDEN) }
        lockRow.onClick = { act.appLockSettings() }
        permsRow.onClick = { step(permsRow, SetupView.Step.PERMISSIONS) }
        homeAppRow.onClick = { step(homeAppRow, SetupView.Step.HOME) }
        setupRow.onClick = { resumeRow = setupRow; act.openSetup() }
        aboutRow.onClick = { step(aboutRow, SetupView.Step.ABOUT) }
        // Device admin blocks uninstalling; an app may always remove its own admin.
        uninstallRow.onClick = {
            Power.removeAdmin(context)
            act.setReplaceHome(false)
            updateValues()
            // Removing the admin lands a moment later; refresh the permissions count once it has.
            postDelayed({ if (isOpen) updateValues() }, 800)
            val msg = if (Platform.isFireTv(context) || !Platform.isDefaultHome(context)) "Device admin removed and ${Platform.name(context)} home restored. You can uninstall GlassOS now."
                else "Device admin removed. Choose your old home in Settings > Apps > Default apps (or on Home), then uninstall GlassOS."
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        shelfRow.onClick = {
            val ids = HeroRepo.SOURCES.map { it.first }
            act.setTopShelf(ids[(ids.indexOf(act.prefs.topShelf) + 1) % ids.size])
            updateValues()
        }
        soundRow.onClick = {
            act.prefs.sound = !act.prefs.sound
            updateValues()
        }
        editRow.onClick = { close { act.enterEdit() } }
        allRow.onClick = { close { act.focusAllApps() } }
        replaceRow.onClick = {
            act.setReplaceHome(!act.prefs.replaceHome)
            updateValues()
        }
        fireSettingsRow.onClick = { close { act.openFireSettings() } }
        sceneryRow.onClick = { resumeRow = sceneryRow; act.openSceneBrowser(fromSettings = true) }
        shuffleRow.onClick = { act.prefs.sceneShuffle = !act.prefs.sceneShuffle; act.applySceneChoice(); updateValues() }
        favOnlyRow.onClick = { act.prefs.sceneFavoritesOnly = !act.prefs.sceneFavoritesOnly; act.applySceneChoice(); updateValues() }
        labelsRow.onClick = { act.prefs.sceneLabels = !act.prefs.sceneLabels; updateValues() }
        artRow.onClick = { act.prefs.customArt = !act.prefs.customArt; act.loadApps(); changed() }
        movingClockRow.onClick = { act.prefs.movingClock = !act.prefs.movingClock; changed() }
        clockRow.onClick = { act.prefs.cinemaClock = !act.prefs.cinemaClock; changed() }
        nightRow.onClick = { act.prefs.nightEnabled = !act.prefs.nightEnabled; changed() }
        nightSunRow.onClick = { act.prefs.nightSun = !act.prefs.nightSun; changed() }
        sunRow.onClick = { act.sunTimesInfo() }
        screensaverRow.onClick = { act.screensaverInfo() }
        // Sun mode's values are longer ("Sunset +30 · 6:42 PM"): one line, cut short rather than squeezing the title.
        for (r in listOf(nightStartRow, nightEndRow, sunRow)) {
            r.valueView.maxLines = 1
            r.valueView.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // Sun times' value has the row less its title and a gap (see updateValues).
        sunRoom = panelW - list.paddingLeft - list.paddingRight - sunRow.paddingLeft - sunRow.paddingRight - dpi(16f) -
            sunRow.titleView.paint.measureText(sunRow.titleView.text.toString()).toInt()
        sunRow.valueView.maxWidth = sunRoom
        transparencyRow.onClick = { act.prefs.reduceTransparency = !act.prefs.reduceTransparency; changed() }
        motionRow.onClick = { act.prefs.reduceMotion = !act.prefs.reduceMotion; changed() }
        folderRow.onClick = { close(instant = true) { act.createFolder() } }
        mirrorOnRow.onClick = {
            ScreenMirroring.turn(context, act.prefs, !act.prefs.airplayOn, "Settings")
            updateMirroring()
            showMirrorStatus()
        }
        // Reached from above, Mirroring sits at the bottom edge with Status just below it: both come into view.
        mirrorOnRow.setOnFocusChangeListener { _, has -> if (has) showMirrorStatus() }
        mirrorNameRow.onClick = { step(mirrorNameRow, SetupView.Step.MIRROR_NAME) }
        mirrorPinRow.onClick = { ScreenMirroring.set(context, act.prefs, AirPlayConfig.PIN, !act.prefs.airplayConfig.pin); updateMirroring() }
        mirrorVolumeRow.onClick = {
            ScreenMirroring.set(context, act.prefs, AirPlayConfig.SYNC_VOLUME, !act.prefs.airplayConfig.syncVolume); updateMirroring()
        }
        // The permissions step offers Display over other apps with the others; GlassOS never changes it by itself.
        mirrorOverlayRow.onClick = { step(mirrorOverlayRow, SetupView.Step.PERMISSIONS) }
        for (r in listOf(mirrorStatusRow, mirrorNameRow)) {
            r.valueView.maxLines = 1
            r.valueView.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mirrorRoom = panelW - list.paddingLeft - list.paddingRight - mirrorStatusRow.paddingLeft - mirrorStatusRow.paddingRight - dpi(16f) -
            mirrorStatusRow.titleView.paint.measureText(mirrorStatusRow.titleView.text.toString()).toInt()
        backupRow.onClick = { act.exportBackup() }
        restoreRow.onClick = { act.importBackup() }
        fireHomeRow.onClick = { close { act.openAmazonHome() } }
        for (r in listOf(fireSettingsRow, fireHomeRow, photoRow, appsRow, setupRow, aboutRow, sceneryRow, folderRow, restoreRow)) { r.valueView.text = "›"; r.valueView.textSize = 24f }
        allRow.valueView.text = "›"
        editRow.valueView.text = "›"
        allRow.valueView.textSize = 24f
        editRow.valueView.textSize = 24f
    }

    private fun section(t: String) = TextView(context).apply {
        text = t
        textSize = 15f
        typeface = Fonts.medium
        setTextColor(0x99FFFFFF.toInt())
        setPadding(dpi(20f), dpi(14f), 0, dpi(6f))
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun stepCinema(d: Int) {
        val l = BackupCodec.CINEMA_DELAYS
        val i = l.indexOf(act.prefs.cinemaDelaySec).coerceAtLeast(0)
        act.prefs.cinemaDelaySec = l[((i + d) % l.size + l.size) % l.size]
        changed()
    }

    /** A display setting changed: show it and apply it at once. */
    private fun changed() {
        act.settingsChanged()
        updateValues()
    }

    /** The home is going: stop following the receiver. */
    fun release() { if (mirroring) AirPlay.removeListener(mirrorListener) }

    /** Re-reads every value (after a backup was written or restored). */
    fun refresh() { if (isOpen) { updateValues(); refreshThumbs() } }

    /** Re-reads the values only (the time zone or the date changed): the thumbnails and the focus stay. */
    fun refreshValues() { if (isOpen) updateValues() }

    private fun updateValues() {
        val p = act.prefs
        sortRow.valueView.text = if (p.mostUsed) "Most used" else "Manual"
        sortHint.text = act.usageOrderDescription
        shuffleRow.valueView.text = onOff(p.sceneShuffle)
        favOnlyRow.valueView.text = onOff(p.sceneFavoritesOnly)
        labelsRow.valueView.text = onOff(p.sceneLabels)
        cinemaRow.value = p.cinemaDelaySec.let { s -> when { s == 0 -> "Off"; s < 60 -> "After $s s"; else -> "After ${s / 60} min" } }
        themeRow.valueView.text = Themes.get(act.prefs.theme).label
        artRow.valueView.text = onOff(act.prefs.customArt)
        movingClockRow.valueView.text = onOff(act.prefs.movingClock)
        clockRow.valueView.text = onOff(p.cinemaClock)
        nightRow.valueView.text = onOff(p.nightEnabled)
        nightSunRow.valueView.text = if (p.nightSun) "Sunset & sunrise" else "Clock"
        nightStartRow.value = act.nightValue(start = true)
        nightEndRow.value = act.nightValue(start = false)
        for (r in listOf(nightStartRow, nightEndRow)) r.valueView.textSize = if (p.nightSun) 15f else 17f
        nightLevelRow.value = listOf("Low", "Medium", "Strong")[p.nightLevel]
        for (r in listOf(nightSunRow, nightStartRow, nightEndRow, nightLevelRow)) r.alpha = if (p.nightEnabled) 1f else 0.55f
        val place = SunNow.place(p)
        val source = when (place?.source) {
            "manager" -> " \u00B7 from Mac"
            "timezone" -> " \u00B7 estimate"
            else -> null
        }
        val sunValue = sunRow.valueView
        sunValue.textSize = 17f
        sunValue.text = if (place == null || source == null) "Unavailable" else {
            // A long place takes the night rows' smaller size, then is cut short; its source always shows.
            val label = if (place.source == "manager") place.label.ifEmpty { "Your place" } else place.label
            val room = sunRoom - dp(2f)
            if (sunValue.paint.measureText(label + source) > room) sunValue.textSize = 15f
            android.text.TextUtils.ellipsize(label, sunValue.paint, room - sunValue.paint.measureText(source),
                android.text.TextUtils.TruncateAt.END).toString() + source
        }
        clockRow.alpha = if (p.cinemaDelaySec > 0) 1f else 0.55f
        // Read each time Settings opens: GlassOS Manager changes it from the Mac.
        screensaverRow.valueView.text = if (AerialDream.chosen(context)) "GlassOS scenery" else "${Platform.name(context)}\u2019s own"
        transparencyRow.valueView.text = onOff(p.reduceTransparency)
        motionRow.valueView.text = onOff(p.reduceMotion)
        backupRow.valueView.text = if (p.lastBackup.isEmpty()) "Documents/${Backup.FOLDER}" else p.lastBackup.substringAfterLast('/')
        backupRow.valueView.textSize = 14f
        backupRow.valueView.maxLines = 1
        backupRow.valueView.ellipsize = android.text.TextUtils.TruncateAt.START
        backupRow.valueView.maxWidth = dpi(200f)
        soundRow.valueView.text = if (act.prefs.sound) "On" else "Off"
        val hiddenCount = act.prefs.hidden.count { p -> HomeShare.apps?.any { it.pkg == p } ?: true }
        hiddenRow.valueView.text = if (hiddenCount == 0) "None" else "$hiddenCount"
        val lockedCount = AppLock.locked.count { p -> HomeShare.apps?.any { it.pkg == p } ?: true }
        lockRow.valueView.text = if (lockedCount == 0) "Off" else if (lockedCount == 1) "1 app" else "$lockedCount apps"
        nameRow.valueView.text = act.prefs.name.ifEmpty { "Not set" }
        countryRow.valueView.text = act.prefs.country.ifEmpty { "Not set" }.let { c -> if (c.length == 2) java.util.Locale("", c).displayCountry else c }
        shelfRow.valueView.text = HeroRepo.sourceLabel(act.prefs.topShelf)
        keyRow.valueView.text = when {
            act.prefs.tmdbKey.isNotEmpty() -> "Your key"
            BuildConfig.TMDB_API_KEY.isNotEmpty() -> "Built in"
            else -> "Not set"
        }
        permsRow.valueView.text = "${Grants.ALL.count { it.isGranted(context) }} of ${Grants.ALL.size}"
        bgRow.valueView.text = WallpaperStore.label(act.prefs.scene)
        replaceRow.valueView.text = if (act.prefs.replaceHome) "On" else "Off"
        homeAppRow.valueView.text = if (Platform.isDefaultHome(context)) "GlassOS" else "Not GlassOS"
        updateMirroring()
    }

    /** Screen Mirroring's rows, from airplay_on, its settings and the receiver's last status push. */
    private fun updateMirroring() {
        if (!mirroring) return
        val p = act.prefs
        val c = p.airplayConfig
        val s = AirPlay.snapshot
        mirrorOnRow.valueView.text = onOff(p.airplayOn)
        fitValue(mirrorStatusRow, MirrorState.statusLine(p.airplayOn, s.status, s.gaveUp), mirrorRoom)
        val nameRoom = mirrorRoom + mirrorStatusRow.titleView.paint.measureText(mirrorStatusRow.titleView.text.toString()).toInt() -
            mirrorNameRow.titleView.paint.measureText(mirrorNameRow.titleView.text.toString()).toInt()
        fitValue(mirrorNameRow, c.name.ifEmpty { AirPlayConfig.cleanName(AirPlay.defaultName(context)) }, nameRoom)
        mirrorPinRow.valueView.text = onOff(c.pin)
        mirrorResRow.value = MirrorState.resolutionLabel(c.res)
        mirrorVolumeRow.valueView.text = onOff(c.syncVolume)
        if (mirrorOverlay) {
            val overlay = Grants.canDrawOverlays(context)
            mirrorOverlayRow.valueView.text = if (overlay) "Allowed" else "Not allowed"
            mirrorOverlayHint.visibility = if (overlay) View.GONE else View.VISIBLE
        }
        // Its settings apply the next time it is on.
        for (r in listOf(mirrorNameRow, mirrorPinRow, mirrorResRow, mirrorVolumeRow)) r.alpha = if (p.airplayOn) 1f else 0.55f
    }

    /** Mirroring and the Status row under it on screen, so turning it on shows at once whether it started. */
    private fun showMirrorStatus() {
        // After the scroll's own move to the focused row.
        scroll.post {
            if (!isOpen || mirrorStatusRow.parent !== list) return@post
            val gap = (mirrorStatusRow.layoutParams as LinearLayout.LayoutParams).bottomMargin
            scroll.requestChildRectangleOnScreen(list,
                Rect(mirrorOnRow.left, mirrorOnRow.top, mirrorStatusRow.right, mirrorStatusRow.bottom + gap), false)
        }
    }

    /** [text] in [row]'s value: the smaller size when it is long, then cut short to [room]. */
    private fun fitValue(row: RowView, text: String, room: Int) {
        val v = row.valueView
        v.textSize = 17f
        if (v.paint.measureText(text) > room) v.textSize = 15f
        v.maxWidth = room
        v.text = text
    }

    fun refreshThumbs() {
        thumbsRow.removeAllViews()
        thumbs.clear()
        val ids = WallpaperStore.scenes.map { it.first } + listOf(HeroRepo.PHOTOS) + act.userImageIds()
        val tw = dpi(136f)
        val th = dpi(76f)
        val current = act.prefs.scene
        for ((i, id) in ids.withIndex()) {
            val t = ThumbView(context, tw, th, id, WallpaperStore.label(id))
            t.id = View.generateViewId()
            t.chosen = id == current
            t.onClick = {
                act.setWallpaper(id)
                thumbs.forEach { it.chosen = it.wallId == id }
                updateValues()
            }
            thumbsRow.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) leftMargin = dpi(16f)
            })
            thumbs.add(t)
        }
        act.loadThumbs(thumbs)
        val chosen = thumbs.firstOrNull { it.chosen } ?: thumbs.firstOrNull()
        soundRow.nextFocusDownId = chosen?.id ?: View.NO_ID
        sceneryRow.nextFocusUpId = chosen?.id ?: View.NO_ID
        if (chosen != null) thumbsScroll.post { thumbsScroll.scrollTo((chosen.left - dpi(18f)).coerceAtLeast(0), 0) }
    }

    /** The row that opened a setup step; Settings comes back with it focused rather than at the top. */
    private var resumeRow: View? = null

    /** Focuses the row a setup step was opened from, or the first row. */
    private fun focusStart() {
        val r = resumeRow
        resumeRow = null
        if (r != null) { r.requestFocus(); return }
        scroll.scrollTo(0, 0)
        nameRow.requestFocus()
    }

    fun open(instant: Boolean = false) {
        if (isOpen) return
        isOpen = true
        // The status row follows the receiver while Settings shows.
        if (mirroring) AirPlay.addListener(mirrorListener)
        updateValues()
        refreshThumbs()
        visibility = View.VISIBLE
        if (instant || Effects.reduceMotion) {
            scrim.alpha = 1f
            panel.translationX = 0f
            focusStart()
            return
        }
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()
        val off = (panelW + margin).toFloat()
        panel.translationX = off
        panel.animate().translationX(0f).setDuration(320)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .setUpdateListener { invalidateDeep(panel) }
            .withEndAction { invalidateDeep(panel) }
            .start()
        focusStart()
    }

    fun close(instant: Boolean = false, after: (() -> Unit)? = null) {
        if (!isOpen) return
        isOpen = false
        if (mirroring) AirPlay.removeListener(mirrorListener)
        if (instant || Effects.reduceMotion) {
            visibility = View.GONE
            act.onSettingsClosed()
            after?.invoke()
            return
        }
        val off = (panelW + margin).toFloat()
        scrim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationX(off).setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .setUpdateListener { invalidateDeep(panel) }
            .withEndAction {
                visibility = View.GONE
                act.onSettingsClosed()
                after?.invoke()
            }.start()
    }
}


/**
 * Options for the app being moved (Menu while it wiggles): a small glass card in the middle of the screen, in the
 * style of the settings rows. Each action closes the card first.
 */
class AppMenu(private val act: HomeScreen) : FrameLayout(act.ctx) {
    var isOpen = false
        private set
    private val scrim = View(context).apply { setBackgroundColor(0x59000000) }
    val panel = GlassBox(context, dp(30f)).apply {
        glass.tint = 0.10f
        glass.dark = 0.30f
        glass.sheen = 0.10f
        shadowAlpha = 0.5f
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val title = TextView(context).apply {
        textSize = 22f
        typeface = Fonts.medium
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    /** Optional explanation under the heading (a saved backup's path, an import error). */
    private val message = TextView(context).apply {
        textSize = 15f
        typeface = Fonts.regular
        setTextColor(0xD9FFFFFF.toInt())
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
    }
    /** Rows scroll when there are many (folders to choose from, backups to restore). */
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    /** How far a focused row's shadow and zoom reach past its edges. */
    private val glow = dpi(16f)
    private val rowScroll = object : ScrollView(context) {
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(Backdrop.screenH * 55 / 100, MeasureSpec.AT_MOST))

        // Nothing above clips the rows (the card lets the focused row's glow spread), so they are clipped here: to the
        // scroll area, widened by the glow at the sides and at an end with no rows past it.
        override fun dispatchDraw(c: Canvas) {
            c.save()
            c.clipRect(scrollX - glow, if (canScrollVertically(-1)) scrollY else scrollY - glow,
                scrollX + width + glow, if (canScrollVertically(1)) scrollY + height else scrollY + height + glow)
            super.dispatchDraw(c)
            c.restore()
        }

        // A focused row stops a glow's width inside the scroll area, not at its edge.
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
            room.set(rect)
            room.inset(0, -glow)
            return super.computeScrollDeltaToGetChildRectOnScreen(room)
        }
        private val room = Rect()
    }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false; clipChildren = false }
    private var onClosed: (() -> Unit)? = null
    /** The app lock's code entry, shown instead of the rows. */
    private val code = CodeDots(context).apply { visibility = View.GONE }

    init {
        visibility = View.GONE
        clipChildren = false
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(dpi(440f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val pad = dpi(18f)
        list.setPadding(pad, dpi(16f), pad, dpi(12f))
        panel.clipChildren = true
        panel.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        list.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(10f) })
        list.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(12f) })
        list.addView(code, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dpi(16f)
        })
        rowScroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.addView(rowScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (android.os.Build.VERSION.SDK_INT >= 23) rowScroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateDeep(rows) }
    }

    /**
     * Shows [items] (label to action) under [heading] and an optional [text]; [closed] runs whenever the card closes,
     * before any action. With [onCode] it asks for the app lock's code instead of showing rows: each full entry goes
     * to [onCode], which closes the card or calls [rejectCode].
     */
    fun open(heading: String, items: List<Pair<String, () -> Unit>>, text: String? = null, onCode: ((String) -> Unit)? = null,
             closed: () -> Unit) {
        if (isOpen) return
        isOpen = true
        onClosed = closed
        code.clear()
        code.onCode = onCode
        code.visibility = if (onCode != null) View.VISIBLE else View.GONE
        rowScroll.visibility = if (onCode != null) View.GONE else View.VISIBLE
        rows.removeAllViews()
        title.text = heading
        message.text = text ?: ""
        message.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        var first: View? = null
        for ((label, action) in items) {
            val row = RowView(context, label)
            row.titleView.gravity = Gravity.CENTER
            row.titleView.maxLines = 1
            row.titleView.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            row.onClick = { close(); action() }
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48f)).apply { bottomMargin = dpi(5f) })
            if (first == null) first = row
        }
        rowScroll.scrollTo(0, 0)
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(Effects.fade(160)).withEndAction { invalidateDeep(panel) }.start()
        (if (onCode != null) code else first)?.requestFocus()
        invalidateDeep(panel)
    }

    /** True while the card asks for a code instead of showing rows. */
    val asksCode: Boolean get() = isOpen && code.onCode != null

    /** A wrong code: [text] replaces the message and the entry starts again. */
    fun rejectCode(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
        code.reject()
        invalidateDeep(panel)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        code.clear()
        code.onCode = null
        animate().cancel()
        visibility = View.GONE
        val c = onClosed
        onClosed = null
        c?.invoke()
    }
}
