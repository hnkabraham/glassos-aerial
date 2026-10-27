package io.github.abhishekkaisar.glassos

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.io.File

/** Locally cached video, an optional matching still for the glass and loading fallback, and its on-screen name. */
data class AerialClip(val video: File, val poster: File?, val name: SceneName = SceneNames.humanize(video.name),
    val revision: String = "${video.length()}:${video.lastModified()}:${poster?.length()}:${poster?.lastModified()}") {
    /** The clip's file name: what favourites, hidden scenes and the play order refer to. */
    val id: String get() = video.name
}

object AerialStore {
    @Volatile var schedule: List<ScenePeriod> = emptyList()
        private set
    /** User-managed offline library, readable with GlassOS's existing photo/storage permission. */
    @Suppress("DEPRECATION")
    fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "GlassAerial")

    /** The clips in name order (the numbered names set the order), with their posters and names. Off the UI thread. */
    fun clips(ctx: Context): List<AerialClip> {
        val root = root()
        schedule = File(root, SceneSchedule.FILE).let { f ->
            try { if (f.isFile && f.length() <= 8192) SceneSchedule.parse(f.readText()) else emptyList() } catch (_: Exception) { emptyList() }
        }
        val files = try { root.listFiles() } catch (e: SecurityException) { null } ?: return emptyList()
        val meta = File(root, SceneMetadata.FILE).let { f ->
            if (f.isFile && f.length() in 1..SceneMetadata.MAX_BYTES.toLong()) SceneMetadata.parse(try { f.readText() } catch (e: Exception) { null })
            else emptyMap()
        }
        return files.filter {
            it.isFile && it.length() > 0 && it.extension.lowercase() in SceneNames.VIDEO_EXTS && SceneNames.isValidId(it.name)
        }.sortedBy { it.name }.map { f ->
            val poster = listOf("jpg", "jpeg", "png").map { File(f.parentFile, f.nameWithoutExtension + "." + it) }.firstOrNull { it.isFile }
            AerialClip(f, poster, SceneMetadata.nameFor(f.name, meta))
        }
    }
}

/**
 * The play state both home screens share (MainActivity's and the overlay's; only one of them plays at a time): the
 * play order, the clip and where it was paused, and clips that failed to decode. Kept in the "aerial" preferences,
 * so the clip and playhead survive leaving Home and restarting the app.
 */
object SceneSession {
    val playlist = ScenePlaylist()
    var position = 0
    val failed = HashSet<String>()
    var library: List<String> = emptyList()
    var revisions: Map<String, String> = emptyMap()
    /** The last clip whose name was shown, so returning to Home does not show it again. */
    var labeled: String? = null
    private var loaded = false
    private val claims = SceneClaims()
    /**
     * Who alone may play while it holds this; null: whichever view can, as before. Main thread. The screensaver's
     * view claims it ([claim]) and always yields it when it stops, is released or leaves its window. Screen
     * Mirroring holds it ([hold]) while its receiver decodes video in another process: then no view plays, the
     * screensaver included, until it yields.
     */
    val owner: Any? get() = claims.owner
    /** Every player view in the process (held weakly), so a claim or a yield reaches the others. */
    private val views = java.util.Collections.newSetFromMap(java.util.WeakHashMap<AerialVideoView, Boolean>())

    fun add(view: AerialVideoView) { views.add(view) }
    fun remove(view: AerialVideoView) { views.remove(view) }

    /**
     * [view] alone may play from now on (unless something holds the decoder). A view playing elsewhere (the home)
     * closes its player at once, keeping the playhead, so two decoders never overlap. Only the screensaver claims; the
     * home only respects the owner.
     */
    fun claim(view: AerialVideoView) {
        if (claims.claim(view, outside = false)) changed(view)
    }

    /**
     * A decoder outside the scenery views ([by], Screen Mirroring's receiver) needs the hardware: every view closes its
     * player at once, keeping the playhead, and none opens one until [by] yields.
     */
    fun hold(by: Any) {
        if (claims.claim(by, outside = true)) changed(by)
    }

    /** [by] lets go of the decoder, if it held it: the screensaver's view, or a home in front, plays again. */
    fun yield(by: Any) {
        if (claims.yield(by)) changed(by)
    }

    private fun changed(by: Any) {
        for (v in views.toList()) if (v !== by) v.ownerChanged()
    }

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = ctx.getSharedPreferences("aerial", Context.MODE_PRIVATE)
        playlist.restore(sp.getString("playlist", null))
        val clip = sp.getString("clip", null)
        // Installs from before the play order was kept have only the clip and its position.
        if (playlist.current == null && clip != null && SceneNames.isValidId(clip)) playlist.select(clip)
        position = if (clip != null && clip == playlist.current) sp.getInt("position", 0) else 0
        labeled = playlist.current
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("aerial", Context.MODE_PRIVATE).edit()
            .putString("clip", playlist.current).putInt("position", position).putString("playlist", playlist.save()).apply()
    }
}

/**
 * A silent video layer behind the app grid. Uses the platform decoder, with no audio focus or media session.
 * Only a visible, focused home owns a player, or the screensaver, which claims it ([SceneSession.claim]). Leaving
 * Home releases the decoder and saves the playhead; returning recreates it at that position, freeing hardware
 * resources for Netflix and other video apps.
 * The existing HeroView shows a still while preparing and supplies a cached blur to the glass surfaces.
 * Nothing here keeps the screen on: the TV's own screensaver and sleep settings apply as usual.
 */
class AerialVideoView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    /** The clip to show changed (or is shown again after returning to Home). */
    var onClipChanged: ((AerialClip) -> Unit)? = null
    /** Nothing can play any more (every eligible clip failed to decode). */
    var onUnavailable: (() -> Unit)? = null
    /** Another view claimed or let go of the decoder (the screensaver). */
    var onOwnerChanged: (() -> Unit)? = null
    private var clips: Map<String, AerialClip> = emptyMap()
    private var ids: List<String> = emptyList()
    private var choice = SceneChoice(emptySet(), emptySet(), false, false)
    /** Why the last filter left what it did (all hidden, all failed...), for the scene browser. */
    var reason = SceneFilter.Reason.EMPTY_LIBRARY
        private set
    private var running = false
    private var enabled = false
    private var released = false
    private var player: MediaPlayer? = null
    private var prepared = false
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private val main = Handler(Looper.getMainLooper())
    private val playlist get() = SceneSession.playlist
    private val advance = Runnable { if (canPlay()) open() }
    private val progress = PlaybackProgress()
    private val prepareTimeout = Runnable { current()?.let { if (player != null && canPlay()) fail(it, "first frame timeout") } }
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!prepared || !canPlay()) return
            try {
                if (progress.stalled(p.currentPosition, android.os.SystemClock.uptimeMillis())) {
                    current()?.let { fail(it, "playback stalled") }; return
                }
            } catch (_: IllegalStateException) { current()?.let { fail(it, "invalid playback state") }; return }
            main.postDelayed(this, 5000)
        }
    }

    init {
        surfaceTextureListener = this
        isOpaque = false
        isFocusable = false
        isClickable = false
        alpha = 0f
        visibility = View.GONE
        SceneSession.add(this)
    }

    /** The clip playing or about to play, if the aerial source is on. */
    fun current(): AerialClip? = if (enabled) playlist.current?.let { clips[it] } else null

    val isActive: Boolean get() = enabled

    fun setPlaylist(list: List<AerialClip>, on: Boolean, choice: SceneChoice) {
        SceneSession.load(context)
        this.choice = choice
        // Another Top Shelf source: stop, but leave the shared play order and position as they are.
        if (!on) { enabled = false; visibility = View.GONE; sync(); return }
        val newIds = list.map { it.id }
        val revisions = list.associate { it.id to "${it.video.path}:${it.revision}" }
        val changed = revisions.filter { (id, revision) -> SceneSession.revisions[id] != revision }.keys
        if (playlist.current in changed && playlist.current in SceneSession.revisions) { closePlayer(save = false); SceneSession.position = 0 }
        SceneSession.failed.removeAll(changed + (SceneSession.revisions.keys - revisions.keys))
        SceneSession.revisions = revisions
        SceneSession.library = newIds
        // Metadata can change without filenames changing.
        clips = list.associateBy { it.id }
        ids = newIds
        val wasEnabled = enabled
        enabled = true
        refilter(announce = !wasEnabled)
    }

    /** Re-applies the filters; switches clip only when the playing one may no longer play. */
    private fun refilter(announce: Boolean) {
        playlist.setLibrary(ids)
        playlist.setShuffle(choice.shuffle)
        val r = SceneFilter.eligible(ids, choice.favorites, choice.hidden, choice.favoritesOnly, SceneSession.failed)
        val selected = r.eligible.filter { SceneCollections.contains(choice.collection, clips[it]?.name?.collections ?: emptySet()) }
        val eligible = if (selected.isEmpty()) r.eligible else selected
        reason = if (r.eligible.isNotEmpty() && selected.isEmpty()) SceneFilter.Reason.COLLECTION_EMPTY else r.reason
        val ok = playlist.setEligible(eligible)
        if (eligible.isEmpty()) enabled = false
        visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) { sync(); return }
        if (!ok || clips[playlist.current] == null) { changeTo { playlist.next() }; return }
        if (announce) current()?.let { onClipChanged?.invoke(it) }
        sync()
    }

    fun setRunning(on: Boolean) {
        running = on
        sync()
    }

    /** Next (1) or previous (-1) clip. Returns false when there is nothing to move to. */
    fun skip(dir: Int): Boolean {
        if (!enabled) return false
        changeTo { if (dir >= 0) playlist.next() else playlist.previous() }
        return true
    }

    /** Plays [id] now (chosen in the scene browser). */
    fun play(id: String): Boolean {
        if (!enabled || clips[id] == null) return false
        if (playlist.current == id) return true
        changeTo { playlist.select(id); id }
        return true
    }

    /**
     * Moves to the clip [pick] chooses. The choice is made before anything is released: when there is nothing to move
     * to (Previous at the start of a shuffled session) while the current clip may still play, the player, clip and
     * playhead are left exactly as they are.
     */
    private fun changeTo(pick: () -> String?) {
        val before = playlist.current
        val id = pick()
        if (id == null && before != null && before == playlist.current && playlist.isEligible(before)) return
        main.removeCallbacks(advance)
        // The released player belongs to the previous clip: its playhead is not kept under the new clip's name.
        closePlayer(save = false)
        SceneSession.position = 0
        SceneSession.save(context)
        val clip = id?.let { clips[it] } ?: return
        onClipChanged?.invoke(clip)
        main.post(advance)
    }

    private fun canPlay() = !released && enabled && running && isAttachedToWindow && isAvailable &&
        windowVisibility == View.VISIBLE && hasWindowFocus() && (SceneSession.owner == null || SceneSession.owner === this) &&
        playlist.current?.let { it !in SceneSession.failed } == true

    /** Another view claimed or let go of the decoder ([SceneSession.claim]): close or open the player to match. */
    fun ownerChanged() {
        sync()
        onOwnerChanged?.invoke()
    }

    private fun sync() {
        if (canPlay()) {
            if (player == null) open()
        } else {
            main.removeCallbacks(advance)
            closePlayer()
        }
    }

    private fun open() {
        if (!canPlay() || player != null) return
        val clip = playlist.current?.let { clips[it] } ?: return
        val target = surface ?: return
        animate().cancel()
        alpha = 0f
        onClipChanged?.invoke(clip)
        val p = MediaPlayer()
        player = p
        prepared = false
        try {
            p.setVolume(0f, 0f)
            p.setSurface(target)
            p.setOnVideoSizeChangedListener { mp, w, h ->
                if (mp === player) { videoW = w; videoH = h; crop() }
            }
            p.setOnPreparedListener { mp ->
                if (mp !== player) return@setOnPreparedListener
                prepared = true
                if (!canPlay()) { closePlayer(); return@setOnPreparedListener }
                mp.setVolume(0f, 0f)
                val position = SceneSession.position
                if (position > 0 && position < mp.duration - 1000) mp.seekTo(position)
                mp.start()
                progress.reset(android.os.SystemClock.uptimeMillis())
                main.postDelayed(watchdog, 5000)
                Log.i(TAG, "playing ${clip.id} at $position ms; muted")
            }
            p.setOnInfoListener { mp, what, _ ->
                if (mp === player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && canPlay()) {
                    main.removeCallbacks(prepareTimeout)
                    animate().alpha(1f).setDuration(Effects.fade(450)).start()
                }
                false
            }
            p.setOnCompletionListener { mp -> if (mp === player) next() }
            p.setOnErrorListener { mp, what, extra ->
                if (mp === player) fail(clip, "decoder $what/$extra")
                true
            }
            p.setDataSource(clip.video.path)
            p.prepareAsync()
            main.postDelayed(prepareTimeout, 20_000)
        } catch (e: Exception) { fail(clip, e.javaClass.simpleName) }
    }

    private fun next() = changeTo { playlist.next() }

    private fun fail(clip: AerialClip, reason: String) {
        Log.w(TAG, "skipping ${clip.id}: $reason")
        SceneSession.failed.add(clip.id)
        closePlayer()
        alpha = 0f
        refilter(announce = false)
        if (!enabled) onUnavailable?.invoke()
    }

    private fun closePlayer(save: Boolean = true) {
        main.removeCallbacks(prepareTimeout)
        main.removeCallbacks(watchdog)
        val p = player ?: return
        player = null
        animate().cancel()
        if (prepared && save) {
            try { SceneSession.position = p.currentPosition.coerceAtLeast(0) } catch (_: IllegalStateException) { }
        }
        prepared = false
        p.setOnPreparedListener(null)
        p.setOnCompletionListener(null)
        p.setOnErrorListener(null)
        p.setOnInfoListener(null)
        p.setOnVideoSizeChangedListener(null)
        p.release()
        if (save) SceneSession.save(context)
        Log.i(TAG, if (save) "paused at ${SceneSession.position} ms; decoder released" else "clip changed; decoder released")
    }

    /** Preserve aspect ratio and center crop instead of stretching wide/tall clips. */
    private fun crop() {
        if (width <= 0 || height <= 0 || videoW <= 0 || videoH <= 0) return
        val scale = maxOf(width.toFloat() / videoW, height.toFloat() / videoH)
        setTransform(Matrix().apply {
            setScale(videoW * scale / width, videoH * scale / height, width / 2f, height / 2f)
        })
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(texture)
        crop()
        sync()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) { crop() }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        closePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // View constructors can dispatch this before this class's fields have been initialized.
        if (surface != null) sync()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(advance)
        closePlayer()
        SceneSession.yield(this)
        super.onDetachedFromWindow()
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        closePlayer()
        surface?.release()
        surface = null
        SceneSession.remove(this)
        SceneSession.yield(this)
    }

    companion object { private const val TAG = "GlassAerial" }
}
