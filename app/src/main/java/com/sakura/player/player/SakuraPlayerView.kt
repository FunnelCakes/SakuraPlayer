package com.sakura.player.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.sakura.player.player.gesture.GestureOverlay

class SakuraPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: PlayerConfig = PlayerConfig(PlayerMode.INLINE)
    private lateinit var playerLayer: PlayerLayer
    private lateinit var gestureOverlay: GestureOverlay

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

    fun setup(config: PlayerConfig) {
        this.config = config
        removeAllViews()
        buildLayers()
    }

    fun play(m3u8Url: String) { playerLayer.play(m3u8Url) }

    fun playLocal(uri: android.net.Uri) { playerLayer.playLocal(uri) }

    fun release() { playerLayer.release() }

    fun togglePlayPause() { playerLayer.togglePlayPause() }

    fun showControls() {
        // Task 5
    }

    fun hideControls() {
        // Task 5
    }

    fun setLocked(locked: Boolean) {
        // Task 5
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
    }
}
