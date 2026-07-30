package com.sakura.player.player

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.sakura.player.player.control.CenterHint
import com.sakura.player.player.control.ControlBar
import com.sakura.player.player.control.SideHUD
import com.sakura.player.player.gesture.GestureOverlay
import com.sakura.player.player.panel.EpisodePanel
import com.sakura.player.player.panel.SpeedPanel
import kotlin.math.roundToInt

class SakuraPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: PlayerConfig = PlayerConfig(PlayerMode.INLINE)
    private lateinit var playerLayer: PlayerLayer
    private lateinit var gestureOverlay: GestureOverlay
    private lateinit var centerHint: CenterHint
    private lateinit var brightnessHud: SideHUD
    private lateinit var volumeHud: SideHUD
    private lateinit var controlBar: ControlBar
    private lateinit var episodePanel: EpisodePanel
    private lateinit var speedPanel: SpeedPanel
    private lateinit var loadingView: TextView
    private var currentSpeed: Float = 1.0f
    private var currentEpisodeIndex = 0

    var onFullscreenRequest: (() -> Unit)? = null
    var onEpisodeChange: ((Int) -> Unit)? = null
    var onStateChanged: ((PlayerState) -> Unit)? = null

    data class PlayerState(
        val playing: Boolean, val position: Long, val duration: Long,
        val currentEp: Int, val speed: Float
    )

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var controlsVisible = true
    private var isLocked = false
    private var isDragging = false
    private var brightnessGestureActive = false
    private var volumeGestureActive = false
    private var gestureInitialBrightness = 0.5f
    private var gestureInitialVolume = 0
    private var pendingSeekPos: Long = 0
    private var previousSpeed: Float = 1.0f
    private var dragTargetMs: Long = 0L

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::playerLayer.isInitialized && !isDragging) {
                val pos = playerLayer.currentPosition
                val dur = playerLayer.duration
                val playing = playerLayer.isPlaying
                controlBar.duration = dur
                controlBar.updateTime(pos, dur)
                controlBar.updateProgress(pos.toFloat() / dur, playerLayer.getBufferedPercent() / 100f)
                controlBar.updatePlayPause(playing)
                if (playing) progressHandler.postDelayed(this, 250)
                else progressHandler.postDelayed(this, 500)
            } else {
                progressHandler.postDelayed(this, 250)
            }
        }
    }

    // ── Public API ──

    fun setup(config: PlayerConfig) {
        this.config = config
        removeAllViews()
        buildLayers()
    }

    val exoPlayer get() = if (::playerLayer.isInitialized) playerLayer.player else null

    fun play(m3u8Url: String) {
        showLoading()
        playerLayer.play(m3u8Url)
        progressHandler.post(progressRunnable)
    }

    fun playLocal(uri: android.net.Uri) {
        showLoading()
        playerLayer.playLocal(uri)
        progressHandler.post(progressRunnable)
    }

    fun release() {
        progressHandler.removeCallbacks(progressRunnable)
        hideHandler.removeCallbacks(hideControlsRunnable)
        if (::playerLayer.isInitialized) playerLayer.release()
    }

    fun pause() {
        if (::playerLayer.isInitialized) playerLayer.pause()
        progressHandler.removeCallbacks(progressRunnable)
    }

    fun resume() {
        if (::playerLayer.isInitialized) {
            playerLayer.play()
            progressHandler.post(progressRunnable)
        }
    }

    fun togglePlayPause() {
        playerLayer.togglePlayPause()
        controlBar.updatePlayPause(playerLayer.isPlaying)
    }

    fun getPlayerState(): PlayerState {
        return PlayerState(
            playing = if (::playerLayer.isInitialized) playerLayer.isPlaying else false,
            position = if (::playerLayer.isInitialized) playerLayer.currentPosition else 0,
            duration = if (::playerLayer.isInitialized) playerLayer.duration else 1,
            currentEp = currentEpisodeIndex, speed = currentSpeed
        )
    }

    fun updateEpisodes(episodes: List<EpisodeItem>) {
        config = config.copy(episodes = episodes)
    }

    fun showLoading() {
        loadingView.visibility = View.VISIBLE
    }

    fun hideLoading() {
        loadingView.visibility = View.GONE
    }

    fun showError(msg: String) {
        loadingView.text = msg
        loadingView.visibility = View.VISIBLE
    }

    // ── Control visibility ──

    fun toggleControlVisibility() {
        if (isLocked) return
        if (controlsVisible) hideControls() else showControls()
    }

    fun showControls() {
        if (!::controlBar.isInitialized) return
        controlsVisible = true
        controlBar.show()
        resetHideTimer()
    }

    fun hideControls() {
        if (!::controlBar.isInitialized) return
        controlsVisible = false
        controlBar.hide()
        hideHandler.removeCallbacks(hideControlsRunnable)
    }

    fun setLocked(locked: Boolean) {
        isLocked = locked
        if (::gestureOverlay.isInitialized) gestureOverlay.locked = locked
        if (locked) hideControls() else showControls()
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideControlsRunnable)
        if (::playerLayer.isInitialized && playerLayer.isPlaying && !isDragging && !isLocked) {
            hideHandler.postDelayed(hideControlsRunnable, 3000)
        }
    }

    // ── Brightness ──

    private fun getSystemBrightness(): Float {
        return try {
            val lp = (context as? android.app.Activity)?.window?.attributes
            lp?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE.toFloat()
        } catch (_: Exception) { 0.5f }
    }

    private fun adjustBrightness(delta: Float) {
        if (!brightnessGestureActive) {
            brightnessGestureActive = true
            gestureInitialBrightness = getSystemBrightness()
            if (gestureInitialBrightness < 0f) gestureInitialBrightness = 0.5f
        }
        val newBrightness = (gestureInitialBrightness + delta).coerceIn(0.01f, 1f)
        try {
            val lp = (context as? android.app.Activity)?.window?.attributes
            if (lp != null) { lp.screenBrightness = newBrightness; (context as android.app.Activity).window.attributes = lp }
        } catch (_: Exception) {}
        showBrightnessHud((newBrightness * 100).roundToInt())
    }

    // ── Volume ──

    private fun getSystemVolume(): Int {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) { 7 }
    }

    private fun adjustVolume(delta: Float) {
        if (!volumeGestureActive) {
            volumeGestureActive = true
            gestureInitialVolume = getSystemVolume()
        }
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val newVol = (gestureInitialVolume + (delta * maxVol)).roundToInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            showVolumeHud(if (maxVol > 0) (newVol * 100 / maxVol) else 0)
        } catch (_: Exception) {}
    }

    // ── Seek: visual-only during drag, commit on release (hanime-style) ──

    private fun seekPreviewGesture(deltaSeconds: Float) {
        val pos = playerLayer.currentPosition + (deltaSeconds * 1000).toLong()
        pendingSeekPos = pos.coerceIn(0, playerLayer.duration)
        controlBar.showPreview(pendingSeekPos, playerLayer.duration)
        // Update progress bar visually during swipe too
        if (playerLayer.duration > 0) {
            val frac = pendingSeekPos.toFloat() / playerLayer.duration
            controlBar.updateProgress(frac, playerLayer.getBufferedPercent() / 100f)
        }
    }

    private fun commitSeek() {
        playerLayer.seekTo(pendingSeekPos)
        controlBar.hidePreview()
        resetHideTimer()
    }

    // Progress bar drag: visual-only, seek on release
    private fun onBarSeekStart() {
        isDragging = true
        if (::gestureOverlay.isInitialized) gestureOverlay.seekingEnabled = false
    }

    private fun onBarSeek(posMs: Long) {
        dragTargetMs = posMs
        // Visual update only, no ExoPlayer seekTo during drag
        val dur = playerLayer.duration
        if (dur > 0) {
            controlBar.updateProgress(posMs.toFloat() / dur, playerLayer.getBufferedPercent() / 100f)
            controlBar.updateTime(posMs, dur)
        }
    }

    private fun onBarSeekEnd() {
        isDragging = false
        if (::gestureOverlay.isInitialized) gestureOverlay.seekingEnabled = true
        playerLayer.seekTo(dragTargetMs)
        resetHideTimer()
    }

    // ── Bridge methods ──

    private fun showCenterHint(type: CenterHint.Type) = centerHint.show(type)
    private fun showSpeedHint(text: String) = centerHint.showSpeedHint(text)
    private fun hideSpeedHint() = centerHint.hideSpeedHint()
    private fun showBrightnessHud(value: Int) = brightnessHud.show(SideHUD.Type.BRIGHTNESS, value)
    private fun showVolumeHud(value: Int) = volumeHud.show(SideHUD.Type.VOLUME, value)

    private fun showEpisodePanel() {
        val compact = config.mode == PlayerMode.INLINE
        episodePanel.toggle(config.episodes, currentEpisodeIndex, compact)
    }

    private fun showSpeedPanel() {
        val compact = config.mode == PlayerMode.INLINE
        speedPanel.toggle(currentSpeed, compact)
    }

    // ── Layer construction ──

    private fun buildLayers() {
        // Layer 0: Loading/error overlay (bottom of z-order, behind video)
        loadingView = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 16f
            gravity = Gravity.CENTER
            text = "加载中..."
            visibility = View.GONE
        }
        addView(loadingView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Layer 1: Video surface
        playerLayer = PlayerLayer(context)
        playerLayer.onReady = {
            hideLoading()
            controlBar.updatePlayPause(true)
            onStateChanged?.invoke(PlayerState(true, playerLayer.currentPosition, playerLayer.duration, 0, 1f))
        }
        playerLayer.onEnded = {
            controlBar.updatePlayPause(false)
            onStateChanged?.invoke(PlayerState(false, playerLayer.currentPosition, playerLayer.duration, 0, 1f))
        }
        playerLayer.onError = { msg ->
            hideLoading()
            showError("播放失败，请尝试切换播放源\n$msg")
            android.util.Log.e("SakuraPlayerView", "Player error: $msg")
        }
        addView(playerLayer.playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Layer 2: Gesture overlay
        gestureOverlay = GestureOverlay(context).apply {
            listener = object : GestureOverlay.GestureListener {
                override fun onSingleTap() { toggleControlVisibility() }
                override fun onDoubleTap() {
                    playerLayer.togglePlayPause()
                    controlBar.updatePlayPause(playerLayer.isPlaying)
                    showCenterHint(if (playerLayer.isPlaying) CenterHint.Type.PLAY else CenterHint.Type.PAUSE)
                }
                override fun onLongPressStart() {
                    previousSpeed = currentSpeed; currentSpeed = 2f
                    playerLayer.setSpeed(2f); showSpeedHint("2x 快放中")
                }
                override fun onLongPressEnd() {
                    currentSpeed = previousSpeed; playerLayer.setSpeed(currentSpeed); hideSpeedHint()
                }
                override fun onBrightnessChange(delta: Float) { adjustBrightness(delta) }
                override fun onVolumeChange(delta: Float) { adjustVolume(delta) }
                override fun onSeek(deltaSeconds: Float) { seekPreviewGesture(deltaSeconds) }
                override fun onSeekEnd() { commitSeek() }
                override fun onProgressFineSeek(delta: Float) {}
                override fun onGestureEnd() { brightnessGestureActive = false; volumeGestureActive = false }
            }
        }
        addView(gestureOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Layer 3: Center hint (highest z above gesture)
        centerHint = CenterHint(context).apply { setWillNotDraw(false) }
        centerHint.elevation = 10f
        addView(centerHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Layer 4: Side HUDs — vertically centered, flush to edges
        brightnessHud = SideHUD(context, true)
        volumeHud = SideHUD(context, false)
        addView(brightnessHud)
        addView(volumeHud)

        // Layer 5: Control bar
        controlBar = ControlBar(context).apply {
            onPlayPause = {
                playerLayer.togglePlayPause()
                updatePlayPause(playerLayer.isPlaying)
            }
            onPrev = {
                val idx = currentEpisodeIndex - 1
                if (idx >= 0 && config.episodes.isNotEmpty()) {
                    currentEpisodeIndex = idx; onEpisodeChange?.invoke(idx)
                }
            }
            onNext = {
                val idx = currentEpisodeIndex + 1
                if (idx < config.episodes.size) {
                    currentEpisodeIndex = idx; onEpisodeChange?.invoke(idx)
                }
            }
            onFullscreen = { onFullscreenRequest?.invoke() }
            onEpisodes = { showEpisodePanel() }
            onSpeed = { showSpeedPanel() }
            onSeekStart = { onBarSeekStart() }
            onSeek = { posMs -> onBarSeek(posMs) }
            onSeekEnd = { onBarSeekEnd() }
            onFineSeek = { delta -> playerLayer.seekTo((dragTargetMs + delta * 1000).toLong().coerceIn(0, playerLayer.duration)) }
            setShowFullUI(config.mode == PlayerMode.FULLSCREEN)
        }
        addView(controlBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // Layer 6: Panels
        episodePanel = EpisodePanel(context).apply {
            onEpisodeSelected = { idx -> currentEpisodeIndex = idx; onEpisodeChange?.invoke(idx) }
        }
        speedPanel = SpeedPanel(context).apply {
            onSpeedSelected = { speed -> currentSpeed = speed; playerLayer.setSpeed(speed) }
        }
        addView(episodePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(speedPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
