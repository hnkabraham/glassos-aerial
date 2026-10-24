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

/** Locally cached video and an optional matching still for the glass and loading fallback. */
data class AerialClip(val video: File, val poster: File?)

object AerialStore {
    /** User-managed offline library, readable with GlassOS's existing photo/storage permission. */
    @Suppress("DEPRECATION")
    fun clips(ctx: Context): List<AerialClip> {
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "GlassAerial")
        return root.listFiles()?.filter {
            it.isFile && it.length() > 0 && it.extension.lowercase() in setOf("mp4", "mov", "m4v")
        }?.sortedBy { it.name }?.map { f ->
            AerialClip(f, File(f.parentFile, f.nameWithoutExtension + ".jpg").takeIf { it.isFile })
        } ?: emptyList()
    }
}

/**
 * A silent video layer behind the app grid. Uses the platform decoder, with no audio focus or media session.
 * Only a visible, focused home owns a player. Leaving Home releases the decoder and saves the playhead;
 * returning recreates it at that position, freeing hardware resources for Netflix and other video apps.
 * The existing HeroView shows a still while preparing and supplies a cached blur to the glass surfaces.
 */
class AerialVideoView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    var onClipChanged: ((AerialClip) -> Unit)? = null
    private var clips: List<AerialClip> = emptyList()
    private var index = 0
    private var position = 0
    private var running = false
    private var enabled = false
    private var released = false
    private var player: MediaPlayer? = null
    private var prepared = false
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val state = ctx.getSharedPreferences("aerial", Context.MODE_PRIVATE)
    private val advance = Runnable { if (canPlay()) open() }

    init {
        surfaceTextureListener = this
        isOpaque = false
        isFocusable = false
        isClickable = false
        alpha = 0f
        visibility = View.GONE
    }

    fun setPlaylist(list: List<AerialClip>, on: Boolean) {
        val changed = clips.map { it.video.path } != list.map { it.video.path }
        if (changed) {
            closePlayer()
            clips = list
            failed.clear()
            val saved = state.getString("clip", null)
            index = list.indexOfFirst { it.video.name == saved }.coerceAtLeast(0)
            position = if (list.getOrNull(index)?.video?.name == saved) state.getInt("position", 0) else 0
        }
        enabled = on && clips.isNotEmpty()
        visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) clips.getOrNull(index)?.let { onClipChanged?.invoke(it) }
        sync()
    }

    fun setRunning(on: Boolean) {
        running = on
        sync()
    }

    private fun canPlay() = !released && enabled && running && isAttachedToWindow && isAvailable &&
        windowVisibility == View.VISIBLE && hasWindowFocus() && clips.any { it.video.path !in failed }

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
        val clip = clips.getOrNull(index) ?: return
        if (clip.video.path in failed) { next(); return }
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
                if (position > 0 && position < mp.duration - 1000) mp.seekTo(position)
                mp.start()
                Log.i(TAG, "playing ${clip.video.name} at $position ms; muted")
            }
            p.setOnInfoListener { mp, what, _ ->
                if (mp === player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && canPlay()) {
                    animate().alpha(1f).setDuration(450).start()
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
        } catch (e: Exception) { fail(clip, e.javaClass.simpleName) }
    }

    private fun next() {
        closePlayer()
        position = 0
        index = (index + 1) % clips.size
        savePosition()
        main.removeCallbacks(advance)
        main.post(advance)
    }

    private fun fail(clip: AerialClip, reason: String) {
        Log.w(TAG, "skipping ${clip.video.name}: $reason")
        failed.add(clip.video.path)
        closePlayer()
        alpha = 0f
        if (clips.any { it.video.path !in failed }) next()
    }

    private fun savePosition() {
        val clip = clips.getOrNull(index) ?: return
        state.edit().putString("clip", clip.video.name).putInt("position", position).apply()
    }

    private fun closePlayer() {
        val p = player ?: return
        player = null
        animate().cancel()
        if (prepared) {
            try { position = p.currentPosition.coerceAtLeast(0) } catch (_: IllegalStateException) { }
        }
        prepared = false
        p.setOnPreparedListener(null)
        p.setOnCompletionListener(null)
        p.setOnErrorListener(null)
        p.setOnInfoListener(null)
        p.setOnVideoSizeChangedListener(null)
        p.release()
        savePosition()
        Log.i(TAG, "paused at $position ms; decoder released")
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
        super.onDetachedFromWindow()
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        closePlayer()
        surface?.release()
        surface = null
    }

    companion object { private const val TAG = "GlassAerial" }
}
