package com.sakura.player.player

import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sakura.player.bridge.JsBridge
import org.json.JSONArray
import java.io.File

/**
 * Fullscreen player activity using SakuraPlayerView (ExoPlayer + B站-style gesture UI).
 *
 * Supports three playback sources:
 *   - "online": direct m3u8 URL
 *   - "local":  file path, converted to FileProvider content:// URI
 *   - "url":    already-resolved content:// URI
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: SakuraPlayerView

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_URL = "url"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"
        const val EXTRA_EPISODES = "episodes"
        const val EXTRA_VIDEO_ID = "videoId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force landscape, keep screen on
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        player = SakuraPlayerView(this)
        setContentView(player)

        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "online"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val position = intent.getLongExtra(EXTRA_POSITION, 0)
        val episodesJson = intent.getStringExtra(EXTRA_EPISODES) ?: "[]"

        val config = PlayerConfig(
            mode = PlayerMode.FULLSCREEN,
            title = title,
            episodes = parseEpisodes(episodesJson)
        )
        player.setup(config)

        // Resolve and play
        when (source) {
            "local" -> {
                val path = intent.getStringExtra(EXTRA_PATH) ?: ""
                val file = File(path)
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        file
                    )
                    player.playLocal(uri)
                } else {
                    finish()
                }
            }
            "url" -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                player.playLocal(Uri.parse(url))
            }
            "online" -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                if (url.isNotEmpty()) {
                    player.play(url)
                }
            }
        }

        // Restore position if provided
        if (position > 0) {
            player.exoPlayer?.seekTo(position)
        }

        // Exit fullscreen on back
        player.onFullscreenRequest = { finish() }
    }

    private fun parseEpisodes(json: String): List<EpisodeItem> {
        val list = mutableListOf<EpisodeItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    EpisodeItem(
                        index = obj.getInt("index"),
                        name = obj.getString("name"),
                        path = obj.optString("path", ""),
                        videoId = obj.optLong("videoId", 0),
                        isLocal = obj.optBoolean("isLocal", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    override fun onDestroy() {
        // Save playback position for resume in MainActivity
        val finalPos = player.exoPlayer?.currentPosition ?: 0
        if (finalPos > 0) {
            JsBridge.lastFullscreenPosition = finalPos
        }
        player.release()
        super.onDestroy()
    }

    /** Hide system bars in immersive sticky mode for true fullscreen. */
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }
}
