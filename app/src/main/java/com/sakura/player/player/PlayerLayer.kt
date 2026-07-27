package com.sakura.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerLayer(private val ctx: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    val playerView: PlayerView = PlayerView(ctx).apply {
        useController = false
        controllerAutoShow = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setBackgroundColor(0xFF000000.toInt())
    }

    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    init {
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> onReady?.invoke()
                    Player.STATE_ENDED -> onEnded?.invoke()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error.message ?: "播放错误")
            }
        })
    }

    fun play(m3u8Url: String) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(m3u8Url)))
        player.prepare()
        player.playWhenReady = true
    }

    fun playLocal(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    val isPlaying: Boolean get() = player.playWhenReady
    val currentPosition: Long get() = player.currentPosition
    val duration: Long get() = if (player.duration > 0) player.duration else 1L

    fun togglePlayPause() { player.playWhenReady = !player.playWhenReady }
    fun pause() { player.pause() }
    fun play() { player.play() }
    fun seekTo(ms: Long) { player.seekTo(ms.coerceIn(0, player.duration)) }
    fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed) }
    fun getBufferedPercent(): Int {
        val pct = player.bufferedPercentage
        return if (pct in 0..100) pct else 0
    }

    fun release() {
        player.stop()
        player.release()
    }
}
