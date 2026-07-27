package com.sakura.player.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class SakuraPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: PlayerConfig = PlayerConfig(PlayerMode.INLINE)
    var exoPlayer: ExoPlayer? = null
        private set
    private lateinit var playerView: PlayerView

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

    fun play(m3u8Url: String) {
        // Task 2
    }

    fun playLocal(uri: android.net.Uri) {
        // Task 2
    }

    fun release() {
        // Task 2
    }

    fun togglePlayPause() {
        // Task 3
    }

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
        // Implemented across Tasks 2-7
    }
}
