package com.sakura.player.player

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sakura.player.bridge.JsBridge
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_URL = "url"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DIR = "dir"
        const val EXTRA_VIDEO_ID = "videoId"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "online"

        if (source == "local" || source == "url") {
            setupLocalPlayer(source)
        } else {
            setupWebViewPlayer()
        }
    }

    private fun setupLocalPlayer(source: String) {
        val uri = when (source) {
            "local" -> {
                val path = intent.getStringExtra(EXTRA_PATH) ?: ""
                val file = File(path)
                if (file.exists()) {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } else {
                    Uri.EMPTY
                }
            }
            "url" -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                Uri.parse(url)
            }
            else -> Uri.EMPTY
        }

        if (uri == Uri.EMPTY) {
            finish()
            return
        }

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 3000
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(0xFF000000.toInt())
        }
        setContentView(playerView!!)

        val positionMs = intent.getLongExtra("position", 0)

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            if (positionMs > 0) {
                seekTo(positionMs)
            }
        }
        playerView!!.player = exoPlayer

        hideSystemUI()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewPlayer() {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            setBackgroundColor(0xFF000000.toInt())

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }

            // Hide system bars on user interaction
            setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    // System bars visible -- hide after 3s
                    handler.postDelayed({ hideSystemUI() }, 3000)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    super.onShowCustomView(view, callback)
                    hideSystemUI()
                }
            }
        }

        setContentView(webView)

        val url = intent.getStringExtra(EXTRA_URL) ?: ""

        val videoParam = if (url.isNotEmpty()) url else ""
        webView.loadUrl("file:///android_asset/www/fullplayer.html?url=${Uri.encode(videoParam)}")
        hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
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

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        val finalPos = exoPlayer?.currentPosition ?: 0
        if (finalPos > 0) {
            JsBridge.lastFullscreenPosition = finalPos
        }
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        playerView?.player = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
