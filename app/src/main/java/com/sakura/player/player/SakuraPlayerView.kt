package com.sakura.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import com.sakura.player.player.control.CenterHint
import com.sakura.player.player.control.ControlBar
import com.sakura.player.player.control.SideHUD
import com.sakura.player.player.gesture.GestureOverlay
import com.sakura.player.player.panel.EpisodePanel
import com.sakura.player.player.panel.SpeedPanel

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

    // Track drag state during progress-bar seeking
    private var isDragging = false
    private var dragTargetMs: Long = 0L

    // Progress update loop (runs every 250ms while playing and not dragging)
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (playerLayer.isPlaying && !isDragging) {
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

    fun setup(config: PlayerConfig) {
        this.config = config
        removeAllViews()
        buildLayers()
    }

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
        playerLayer.release()
    }

    fun togglePlayPause() { playerLayer.togglePlayPause() }

    fun showControls() {
        controlBar.show()
    }

    fun hideControls() {
        controlBar.hide()
    }

    fun setLocked(locked: Boolean) {
        gestureOverlay.locked = locked
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

    private fun showEpisodePanel() {
        episodePanel.toggle(config.episodes, currentEpisodeIndex)
    }

    private fun showSpeedPanel() {
        speedPanel.toggle(currentSpeed)
    }

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
            // Error handling will be added in future tasks
        }
        addView(playerLayer.playerView, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))

        // Layer 2: Gesture overlay (transparent)
        gestureOverlay = GestureOverlay(context).apply {
            listener = object : GestureOverlay.GestureListener {
                override fun onSingleTap() {
                    // Task 7: toggle control visibility
                }
                override fun onDoubleTap() {
                    playerLayer.togglePlayPause()
                }
                override fun onLongPressStart() {
                    playerLayer.setSpeed(2f)
                }
                override fun onLongPressEnd() {
                    playerLayer.setSpeed(1f)
                }
                override fun onBrightnessChange(delta: Float) {
                    // Task 7: adjust screen brightness
                }
                override fun onVolumeChange(delta: Float) {
                    // Task 7: adjust system volume
                }
                override fun onSeek(deltaSeconds: Float) {
                    // Task 7: show seek preview
                }
                override fun onSeekEnd() {
                    // Task 7: commit seek position
                }
                override fun onProgressFineSeek(deltaSeconds: Float) {
                    // Task 7: fine seek adjustment
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

        // ── Layer 5: Control bar (progress track + time labels + buttons) ──
        controlBar = ControlBar(context).apply {
            onPlayPause = { playerLayer.togglePlayPause() }
            onPrev = {
                // Implemented in Task 7 (playlist manager)
            }
            onNext = {
                // Implemented in Task 7 (playlist manager)
            }
            onFullscreen = { onFullscreenRequest?.invoke() }
            onEpisodes = { showEpisodePanel() }
            onSpeed = { showSpeedPanel() }

            onSeekStart = {
                this@SakuraPlayerView.isDragging = true
                gestureOverlay.seekingEnabled = false
            }
            onSeek = { fraction ->
                dragTargetMs = fraction
                playerLayer.seekTo(fraction)
            }
            onSeekEnd = {
                this@SakuraPlayerView.isDragging = false
                gestureOverlay.seekingEnabled = true
                playerLayer.seekTo(dragTargetMs)
            }
            onFineSeek = { delta ->
                // delta is in seconds from ProgressTrack
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

        // ── Layer 6: Slide-out panels (episode & speed selectors) ──
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
}
