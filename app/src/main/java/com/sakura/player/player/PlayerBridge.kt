package com.sakura.player.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PlayerBridge — manages JS to native player communication.
 *
 * Bridges the gap between the WebView's JavaScript layer and the native
 * SakuraPlayerView (ExoPlayer-based). Handles online playback (m3u8
 * resolution via JsBridge), local file playback (via FileProvider),
 * state queries, and episode list management.
 */
class PlayerBridge(
    private val player: SakuraPlayerView,
    private val context: Context,
    private val jsEvaluator: (String) -> Unit,
    private val m3u8Resolver: (Long, String, Int, String) -> Unit
) {
    private val TAG = "PlayerBridge"
    private var currentEpIndex: Int = 0

    /** Called from JS to play an online episode — delegates m3u8 resolution. */
    fun playOnline(videoId: Long, title: String, epIndex: Int, callback: String) {
        currentEpIndex = epIndex
        m3u8Resolver(videoId, title, epIndex, callback)
    }

    /** Called from JS to play a local file. Gets content:// URI via FileProvider. */
    fun playLocal(path: String) {
        try {
            val file = File(path)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                player.playLocal(uri)
            } else {
                Log.e(TAG, "playLocal: file not found $path")
            }
        } catch (e: Exception) {
            Log.e(TAG, "playLocal failed", e)
        }
    }

    /** JS queries current player state — returns JSON via callback. */
    fun getPlayerState(callback: String) {
        val state = player.getPlayerState()
        val json = JSONObject().apply {
            put("playing", state.playing)
            put("position", state.position)
            put("duration", state.duration)
            put("currentEp", currentEpIndex)
            put("speed", state.speed)
        }.toString()
        jsEvaluator("$callback(null, $json)")
    }

    /** Update player with episode list from detail page JSON. */
    fun setEpisodes(episodesJson: String) {
        try {
            val arr = JSONArray(episodesJson)
            val episodes = mutableListOf<EpisodeItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                episodes.add(
                    EpisodeItem(
                        index = obj.getInt("index"),
                        name = obj.getString("name"),
                        path = obj.optString("path", ""),
                        videoId = obj.optLong("videoId", 0),
                        isLocal = obj.optBoolean("isLocal", false)
                    )
                )
            }
            player.updateEpisodes(episodes)
        } catch (e: Exception) {
            Log.e(TAG, "setEpisodes parse error", e)
        }
    }
}
