package com.sakura.player.player

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
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
    private var currentSpeed: Float = 1.0f
    private var currentEpisodeIndex = 0

    /** Callback when user requests fullscreen toggle */
    var onFullscreenRequest: (() -> Unit)? = null
    /** Callback when user switches episode */
    var onEpisodeChange: ((Int) -> Unit)? = null
    /** Callback for state changes to sync with JS */
    var onStateChanged: ((PlayerState) -> Unit)? = null

    data class PlayerState(
        val playing: Boolean,
        val position: Long,
        val duration: Long,
        val currentEp: Int,
        val speed: Float
    )

    // ── Auto-hide controls ──
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var controlsVisible = true
    private var isLocked = false
    private var isDragging = false

    // ── Gesture state tracking ──
    private var brightnessGestureActive = false
    private var volumeGestureActive = false
    private var gestureInitialBrightness = 0.5f
    private var gestureInitialVolume = 0

    // ── Seek preview state ──
    private var pendingSeekPos: Long = 0
    private var previousSpeed: Float = 1.0f

    // ── Progress update loop (every 250ms while playing and not dragging) ──
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::playerLayer.isInitialized && playerLayer.isPlaying && !isDragging) {
                val pos = playerLayer.currentPosition
                val dur = playerLayer.duration
                controlBar.duration = dur
                controlBar.updateTime(pos, dur)
                controlBar.updateProgress(
                    pos.toFloat() / dur,
                    playerLayer.getBufferedPercent() / 100f
                )
            }
            progressHandler.postDelayed(this, 250)
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
        playerLayer.play(m3u8Url)
        progressHandler.post(progressRunnable)
    }

    fun playLocal(uri: android.net.Uri) {
        playerLayer.playLocal(uri)
        progressHandler.post(progressRunnable)
    }

    fun release() {
        progressHandler.removeCallbacks(progressRunnable)
        hideHandler.removeCallbacks(hideControlsRunnable)
        if (::playerLayer.isInitialized) playerLayer.release()
    }

    fun togglePlayPause() { playerLayer.togglePlayPause() }

    /** Get player state snapshot for JS bridge queries. */
    fun getPlayerState(): PlayerState {
        return PlayerState(
            playing = if (::playerLayer.isInitialized) playerLayer.isPlaying else false,
            position = if (::playerLayer.isInitialized) playerLayer.currentPosition else 0,
            duration = if (::playerLayer.isInitialized) playerLayer.duration else 1,
            currentEp = currentEpisodeIndex,
            speed = currentSpeed
        )
    }

    /** Update episode list without full rebuild. */
    fun updateEpisodes(episodes: List<EpisodeItem>) {
        config = config.copy(episodes = episodes)
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
        if (locked) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideControlsRunnable)
        if (::playerLayer.isInitialized && playerLayer.isPlaying && !isDragging && !isLocked) {
            hideHandler.postDelayed(hideControlsRunnable, 3000)
        }
    }

    // ── Brightness control (via WindowManager.LayoutParams) ──

    private fun getSystemBrightness(): Float {
        return try {
            val lp = (context as? android.app.Activity)?.window?.attributes
            lp?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE.toFloat()
        } catch (_: Exception) {
            0.5f
        }
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
            if (lp != null) {
                lp.screenBrightness = newBrightness
                (context as android.app.Activity).window.attributes = lp
            }
        } catch (_: Exception) {}
        showBrightnessHud((newBrightness * 100).roundToInt())
    }

    // ── Volume control (via AudioManager) ──

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

    // ── Seek preview ──

    private fun seekPreview(deltaSeconds: Float) {
        val pos = playerLayer.currentPosition + (deltaSeconds * 1000).toLong()
        pendingSeekPos = pos.coerceIn(0, playerLayer.duration)
        controlBar.showPreview(pendingSeekPos, playerLayer.duration)
    }

    private fun commitSeek() {
        playerLayer.seekTo(pendingSeekPos)
        controlBar.hidePreview()
        resetHideTimer()
    }

    private fun fineSeek(delta: Float) {
        val newPos = playerLayer.currentPosition + (delta * 100).toLong()
        playerLayer.seekTo(newPos.coerceIn(0, playerLayer.duration))
    }

    // ── Bridge methods: Layer 3 CenterHint ──

    private fun showCenterHint(type: CenterHint.Type) = centerHint.show(type)

    private fun showSpeedHint(text: String) = centerHint.showSpeedHint(text)

    private fun hideSpeedHint() = centerHint.hideSpeedHint()

    // ── Bridge methods: Layer 4 SideHUD ──

    private fun showBrightnessHud(value: Int) =
        brightnessHud.show(SideHUD.Type.BRIGHTNESS, value)

    private fun showVolumeHud(value: Int) =
        volumeHud.show(SideHUD.Type.VOLUME, value)

    // ── Panel toggles ──

    private fun showEpisodePanel() {
        episodePanel.toggle(config.episodes, currentEpisodeIndex)
    }

    private fun showSpeedPanel() {
        speedPanel.toggle(currentSpeed)
    }

    // ── Layer construction ──

    private fun buildLayers() {
        // Layer 1: Video surface
        playerLayer = PlayerLayer(context)
        playerLayer.onReady = {
            onStateChanged?.invoke(PlayerState(
                playing = true,
                position = playerLayer.currentPosition,
                duration = playerLayer.duration,
                currentEp = 0,
                speed = 1f
            ))
        }
        playerLayer.onEnded = {
            onStateChanged?.invoke(PlayerState(
                playing = false,
                position = playerLayer.currentPosition,
                duration = playerLayer.duration,
                currentEp = 0,
                speed = 1f
            ))
        }
        playerLayer.onError = { msg ->
            android.util.Log.e("SakuraPlayerView", "Player error: $msg")
        }
        addView(playerLayer.playerView, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))

        // Layer 2: Gesture overlay (transparent)
        gestureOverlay = GestureOverlay(context).apply {
            listener = object : GestureOverlay.GestureListener {
                override fun onSingleTap() { toggleControlVisibility() }
                override fun onDoubleTap() {
                    playerLayer.togglePlayPause()
                    showCenterHint(if (playerLayer.isPlaying) CenterHint.Type.PLAY else CenterHint.Type.PAUSE)
                }
                override fun onLongPressStart() {
                    previousSpeed = currentSpeed
                    currentSpeed = 2f
                    playerLayer.setSpeed(2f)
                    showSpeedHint("2x 快放中")
                }
                override fun onLongPressEnd() {
                    currentSpeed = previousSpeed
                    playerLayer.setSpeed(currentSpeed)
                    hideSpeedHint()
                }
                override fun onBrightnessChange(delta: Float) { adjustBrightness(delta) }
                override fun onVolumeChange(delta: Float) { adjustVolume(delta) }
                override fun onSeek(deltaSeconds: Float) { seekPreview(deltaSeconds) }
                override fun onSeekEnd() { commitSeek() }
                override fun onProgressFineSeek(delta: Float) { fineSeek(delta) }
                override fun onGestureEnd() {
                    brightnessGestureActive = false
                    volumeGestureActive = false
                }
            }
        }
        addView(gestureOverlay, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))

        // Layer 3: Center hint (play/pause icon, speed label)
        centerHint = CenterHint(context)
        addView(centerHint, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))

        // Layer 4: Side HUDs for brightness (left) and volume (right)
        brightnessHud = SideHUD(context, true)
        volumeHud = SideHUD(context, false)
        addView(brightnessHud)
        addView(volumeHud)

        // Layer 5: Control bar (progress track + time labels + buttons)
        controlBar = ControlBar(context).apply {
            onPlayPause = { playerLayer.togglePlayPause() }
            onPrev = {
                // Navigate to previous episode
                if (currentEpisodeIndex > 0 && config.episodes.isNotEmpty()) {
                    currentEpisodeIndex--
                    onEpisodeChange?.invoke(currentEpisodeIndex)
                }
            }
            onNext = {
                // Navigate to next episode
                val maxIdx = config.episodes.size - 1
                if (currentEpisodeIndex < maxIdx) {
                    currentEpisodeIndex++
                    onEpisodeChange?.invoke(currentEpisodeIndex)
                }
            }
            onFullscreen = { onFullscreenRequest?.invoke() }
            onEpisodes = { showEpisodePanel() }
            onSpeed = { showSpeedPanel() }

            onSeekStart = {
                this@SakuraPlayerView.isDragging = true
                if (::gestureOverlay.isInitialized) gestureOverlay.seekingEnabled = false
            }
            onSeek = { fraction ->
                this@SakuraPlayerView.dragTargetMs = fraction
                playerLayer.seekTo(fraction)
            }
            onSeekEnd = {
                this@SakuraPlayerView.isDragging = false
                if (::gestureOverlay.isInitialized) gestureOverlay.seekingEnabled = true
                playerLayer.seekTo(dragTargetMs)
                resetHideTimer()
            }
            onFineSeek = { delta ->
                val newPos = dragTargetMs + (delta * 1000).toLong()
                dragTargetMs = newPos
                playerLayer.seekTo(newPos)
            }

            setShowFullUI(config.mode == PlayerMode.FULLSCREEN)
        }
        addView(
            controlBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        )

        // Layer 6: Slide-out panels (episode & speed selectors)
        episodePanel = EpisodePanel(context).apply {
            onEpisodeSelected = { idx ->
                currentEpisodeIndex = idx
                onEpisodeChange?.invoke(idx)
            }
        }
        speedPanel = SpeedPanel(context).apply {
            onSpeedSelected = { speed ->
                currentSpeed = speed
                playerLayer.setSpeed(speed)
            }
        }
        addView(episodePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(speedPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // Track drag state during progress-bar seeking
    private var dragTargetMs: Long = 0L
}
