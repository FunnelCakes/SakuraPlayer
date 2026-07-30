package com.sakura.player.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sakura.player.bridge.JsBridge
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.utils.OrientationUtils
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private val TAG = "PlayerActivity"
    private lateinit var gsyPlayer: StandardGSYVideoPlayer
    private var orientationUtils: OrientationUtils? = null
    private var shouldBePlaying = true // Track play state from intent

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_URL = "url"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DIR = "dir"
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_POSITION = "position"
        const val EXTRA_PLAYING = "playing"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "online"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val position = intent.getLongExtra(EXTRA_POSITION, 0)
        shouldBePlaying = intent.getBooleanExtra(EXTRA_PLAYING, true)

        // Create GSY player
        gsyPlayer = StandardGSYVideoPlayer(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
            setIsTouchWiget(true)
            // Hide back button (we use system back)
            backButton.visibility = View.GONE
        }

        setContentView(gsyPlayer)

        // Set up video source
        val url: String = when (source) {
            "local" -> {
                val path = intent.getStringExtra(EXTRA_PATH) ?: ""
                val file = File(path)
                if (file.exists()) {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file).toString()
                } else ""
            }
            "url" -> intent.getStringExtra(EXTRA_URL) ?: ""
            "online" -> intent.getStringExtra(EXTRA_URL) ?: ""
            else -> ""
        }

        if (url.isEmpty()) {
            finish()
            return
        }

        val isLive = url.contains(".m3u8")
        gsyPlayer.setUp(url, isLive, title)

        if (position > 0) {
            gsyPlayer.seekOnStart = position
        }

        gsyPlayer.startPlayLogic()

        if (!shouldBePlaying) {
            gsyPlayer.postDelayed({ gsyPlayer.onVideoPause() }, 100)
        }

        hideSystemUI()

        // Wire GSY's back button and fullscreen/shrink button to finish().
        // GSY's internal handlers try to call stopWindowFullscreen() which doesn't
        // work because we're in a separate PlayerActivity (not using GSY's
        // view-manipulation fullscreen). Delay to let GSY inflate controls.
        gsyPlayer.postDelayed({
            // Back button (← arrow, top-left)
            gsyPlayer.backButton.visibility = View.VISIBLE
            gsyPlayer.backButton.setOnClickListener { finish() }

            // Fullscreen/shrink button (bottom-right, replaces enlarge button)
            gsyPlayer.fullscreenButton?.setOnClickListener { finish() }
            // Clear GSY's internal touch listener so it doesn't consume events
            gsyPlayer.fullscreenButton?.setOnTouchListener(null)
            gsyPlayer.backButton?.setOnTouchListener(null)
        }, 800)
    }

    override fun onPause() {
        super.onPause()
        gsyPlayer.onVideoPause()
    }

    override fun onResume() {
        super.onResume()
        gsyPlayer.onVideoResume()
        // Respect the play state: if video should be paused, pause it.
        // The onCreate already set up the correct state, but onResume
        // fires after onCreate and calls onVideoResume() which auto-starts.
        // Only pause if the user was NOT playing before entering fullscreen.
        if (!shouldBePlaying) {
            gsyPlayer.onVideoPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Save state for MainActivity to resume inline player
        JsBridge.lastFullscreenPosition = gsyPlayer.currentPositionWhenPlaying
        JsBridge.lastFullscreenWasPlaying = gsyPlayer.isInPlayingState
        GSYVideoManager.releaseAllVideos()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onBackPressed() {
        if (orientationUtils != null) {
            orientationUtils?.backToProtVideo()
            return
        }
        if (GSYVideoManager.backFromWindowFull(this)) {
            return
        }
        super.onBackPressed()
    }

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
