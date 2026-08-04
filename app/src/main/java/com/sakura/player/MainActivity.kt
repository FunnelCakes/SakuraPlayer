package com.sakura.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sakura.player.bridge.JsBridge
import com.sakura.player.data.AppDatabase
import com.sakura.player.data.SettingsPrefs
import com.sakura.player.download.DownloadManager
import com.sakura.player.follow.FollowManager
import com.sakura.player.follow.UpdateChecker
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.sakura.player.player.EpisodeNav
import com.sakura.player.player.PlayerEpisode
import com.sakura.player.player.SakuraGSYVideoPlayer
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var webView: WebView
    private lateinit var bridge: JsBridge
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ExoPlayer for local file playback (old bridge, kept for compatibility)
    private lateinit var localPlayerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    @Volatile private var cachedPlayerState: String = "{}"

    // GSYVideoPlayer for inline (half-screen) playback
    private lateinit var gsyPlayer: SakuraGSYVideoPlayer

    // Whether the inline player was playing (vs. user-paused) before the app went to
    // background. Used in onResume() to restore the EXACT state: paused stays paused,
    // playing resumes (with position restored via onVideoResume()).
    private var wasPlayingBeforeBackground = true

    // Guards async m3u8 resolution for the online inline player. Cancel the previous
    // resolve coroutine before starting a new one so a stale completion can't set up
    // the wrong episode when the user taps episodes rapidly.
    private var resolveJob: Job? = null

    // Whether the inline (GSY) player is still supposed to be visible. Set true in
    // playOnlineInline/playLocalInline, false in hideInlinePlayer. Used to guard the
    // async m3u8 resolution completion: if the user backed out during loading, the
    // pending resolveJob is cancelled and this flag is false, so a stale completion
    // can never re-show the player after the detail page closed.
    @Volatile private var inlinePlayerActive = false

    // SAF directory picker for custom download path
    private val safPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist permission across reboots
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            SettingsPrefs.downloadUri = uri.toString()
            val doc = DocumentFile.fromTreeUri(this, uri)
            val path = doc?.name ?: uri.lastPathSegment ?: "SakuraAnime"
            SettingsPrefs.downloadPath = path // display name only
            val fullPath = "/storage/emulated/0/$path"
            val dlDir = File(fullPath)
            if (!dlDir.exists()) dlDir.mkdirs()
            evalJs("if(window.onPathChanged)window.onPathChanged('$fullPath')")
            Toast.makeText(this, "下载路径已设为: $fullPath", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (!granted) {
            Toast.makeText(this, "需要存储权限才能下载视频", Toast.LENGTH_LONG).show()
        }
        // Create download dir whether granted or not (app dir doesn't need it)
        createDownloadDir()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SettingsPrefs.init(this)
        com.sakura.player.local.LocalFileManager.init(this)
        val db = AppDatabase.getInstance(this)
        FollowManager.init(db)
        com.sakura.player.download.DownloadRecordManager.init(db)

        // Request necessary permissions
        checkAndRequestPermissions()

        // Create download directory (app-scoped, no permission needed)
        createDownloadDir()

        // Init download manager
        DownloadManager.init(SettingsPrefs.activeDomain)

        // Root layout: FrameLayout to hold WebView + PlayerView
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Create WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }

            bridge = JsBridge(this@MainActivity)
            bridge.setEvaluator { js -> evalJs(js) }
            addJavascriptInterface(WebAppInterface(), "Sakura")
            addJavascriptInterface(LocalPlayerBridge(), "LocalPlayer")

            loadUrl("file:///android_asset/www/index.html")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    Log.e("SakuraWebView", msg?.message() ?: "")
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setNegativeButton("取消") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                    val input = android.widget.EditText(this@MainActivity)
                    input.setText(defaultValue ?: "")
                    input.selectAll()
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("确定") { _, _ -> result?.confirm(input.text.toString()) }
                        .setNegativeButton("取消") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.e("SakuraWebView", "Page loaded: $url")
                    scope.launch {
                        try {
                            Log.e("SakuraMain", "Starting domain init...")
                            val domain = bridge.initDomain()
                            Log.e("SakuraMain", "Domain result: '$domain'")
                            if (domain.isEmpty()) {
                                evalJs("if(window.showError)window.showError('所有站点暂时无法访问')")
                            } else {
                                DownloadManager.setDomain(domain)
                                evalJs("if(window.onDomainReady)window.onDomainReady('$domain')")
                                // Sync download records for any new files (add-only, no clear)
                                evalJs("if(window.Sakura)window.Sakura.syncDownloadRecords('_cb_sync')")
                            }
                        } catch (e: Exception) {
                            Log.e("SakuraMain", "Domain init failed", e)
                        }
                    }
                }
            }
        }

        // Create PlayerView for local file ExoPlayer playback
        localPlayerView = PlayerView(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            useController = false
            controllerAutoShow = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(0xFF000000.toInt())
        }
        // Create GSYVideoPlayer for inline playback (half-screen)
        gsyPlayer = SakuraGSYVideoPlayer(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            setIsTouchWiget(true)
            setIsTouchWigetFull(true)
            // Use GSY's native OrientationUtils for the smart 180° flip. The custom
            // view-rotation approach in SakuraGSYVideoPlayer was removed; GSY now rotates
            // the whole ACTIVITY (video + UI together) between LANDSCAPE and
            // REVERSE_LANDSCAPE via setRequestedOrientation(). The Manifest's
            // configChanges="orientation|screenSize|..." keeps the activity alive across
            // that rotation, so the fullscreen window survives and playback continues.
            //
            //  - setLockLand(true) makes resolveByClick() force landscape on fullscreen
            //    entry (startWindowFullscreen() would otherwise stay portrait).
            //  - setNeedOrientationUtils(true) (default) makes resolveFullVideoShow()
            //    create the OrientationUtils for the fullscreen clone.
            //  - setRotateViewAuto(true) (default) arms GSY's orientation sensor listener.
            //  - setRotateWithSystem(false) makes GSY process the sensor even when the
            //    SYSTEM auto-rotate setting is OFF, so the video flips 180° when the phone
            //    is held upside-down in landscape regardless of that setting.
            //  - setOnlyRotateLand(true) locks the activity to landscape (the sensor's
            //    portrait branch is a no-op) while still flipping between LANDSCAPE and
            //    REVERSE_LANDSCAPE as the device tilts.
            setLockLand(true)
            setNeedOrientationUtils(true)
            setRotateViewAuto(true)
            setRotateWithSystem(false)
            setOnlyRotateLand(true)
            // Hide title/back button for inline mode
            backButton.visibility = View.GONE
            titleTextView.visibility = View.GONE
            // Prevent GSY's audio-focus-loss handler from calling releaseAllVideos()
            // (which wipes mCurrentPosition and releases the player) when another app
            // steals audio focus while this app is backgrounded. Instead GSY takes the
            // safe branch and pauses via listener().onVideoPause(), preserving position.
            setReleaseWhenLossAudio(false)
            // Show the native lock button in fullscreen (GSY default is false).
            setNeedLockFull(true)
            // Keep the JS playerState.locked flag in sync when the user locks.
            setLockClickListener { _, locked ->
                evalJs("if(window.playerState)window.playerState.locked = $locked")
                Toast.makeText(this@MainActivity, if (locked) "已锁定" else "已解锁", Toast.LENGTH_SHORT).show()
            }
        }

        // Wire prev/next/select-episode navigation from the custom control bar.
        // The fullscreen clone receives this callback via cloneParams().
        gsyPlayer.onEpisodeNav = { nav, requestedIndex ->
            handleGsyEpisodeNav(nav, requestedIndex)
        }

        // Disable mobile data warning dialog in GSYVideoPlayer
        disableGsyNetworkWarning()

        // Add views to root: WebView first (bottom), PlayerView on top, GSY on topmost
        rootLayout.addView(webView)
        rootLayout.addView(localPlayerView)
        rootLayout.addView(gsyPlayer)

        setContentView(rootLayout)
        Log.e("SakuraMain", "App started, WebView created, loading frontend")

        // Splash overlay on COLD START only. The static splashShown flag means the
        // splash renders exactly once per process: returning from background or an
        // activity recreation (rotation) never shows it again. It is added LAST so it
        // sits on top of the WebView and covers the app content while the frontend loads.
        if (!splashShown) {
            splashShown = true
            showSplash(rootLayout)
        }

        UpdateChecker.schedule(this, scope)
    }

    /**
     * Overlay a white-background FrameLayout with the app image centered (FIT_CENTER)
     * on top of the root layout. It stays visible for 1.5s, then fades out (~200ms)
     * and removes itself.
     */
    private fun showSplash(root: FrameLayout) {
        val splashView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        splashView.addView(ImageView(this).apply {
            setImageResource(R.drawable.splash_bg)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        root.addView(splashView)

        // Hide after exactly 1.5s with a short fade-out, then detach from the tree.
        splashView.postDelayed({
            splashView.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    (splashView.parent as? ViewGroup)?.removeView(splashView)
                }
                .start()
        }, 1500)
    }

    private fun createDownloadDir() {
        try {
            val dlDir = File(SettingsPrefs.downloadPath)
            if (!dlDir.exists()) {
                val created = dlDir.mkdirs()
                Log.e("SakuraMain", "Download dir created: $created at ${dlDir.absolutePath}")
                if (!created) {
                    // Fall back to app-specific directory
                    val fallback = getExternalFilesDir(null)?.absolutePath + "/Downloads"
                    SettingsPrefs.downloadPath = fallback
                    File(fallback).mkdirs()
                    Log.e("SakuraMain", "Falling back to app dir: $fallback")
                    evalJs("if(window.onPathChanged)window.onPathChanged('$fallback')")
                }
            } else {
                Log.e("SakuraMain", "Download dir exists: ${dlDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("SakuraMain", "Failed to create download dir", e)
        }
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: need MANAGE_EXTERNAL_STORAGE to write to /storage/emulated/0/
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${packageName}")
                }
                startActivity(intent)
                Toast.makeText(this, "请授予「所有文件访问」权限以下载动漫", Toast.LENGTH_LONG).show()
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // Notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ==================== LocalPlayer ExoPlayer Bridge ====================

    inner class LocalPlayerBridge {
        /**
         * Start playing a local file with ExoPlayer.
         * @param path Absolute file path (e.g., /storage/emulated/0/SakuraAnime/video.mp4)
         * @param xPx Player area left in physical pixels (= CSS px * devicePixelRatio)
         * @param yPx Player area top in physical pixels
         * @param wPx Player area width in physical pixels
         * @param hPx Player area height in physical pixels (minus control bar space)
         */
        @JavascriptInterface
        fun play(path: String, xPx: Int, yPx: Int, wPx: Int, hPx: Int) {
            runOnUiThread {
                Log.d(TAG, "LocalPlayer.play: path=$path, rect=($xPx,$yPx,${wPx}x$hPx)")
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        evalJs("if(window.showToast)window.showToast('文件不存在: $path')")
                        return@runOnUiThread
                    }

                    // Position and size PlayerView
                    val params = localPlayerView.layoutParams as FrameLayout.LayoutParams
                    params.leftMargin = xPx
                    params.topMargin = yPx
                    params.width = wPx
                    params.height = hPx
                    localPlayerView.layoutParams = params

                    // Create or reuse ExoPlayer
                    if (exoPlayer == null) {
                        exoPlayer = ExoPlayer.Builder(this@MainActivity)
                            .build()
                            .apply {
                                addListener(ExoPlayerListener())
                            }
                        localPlayerView.player = exoPlayer
                    }

                    val player = exoPlayer!!
                    player.stop()
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    player.prepare()
                    player.playWhenReady = true

                    // Show PlayerView, hide WebView video elements
                    localPlayerView.visibility = View.VISIBLE
                    evalJs("""
                        (function(){
                            var dv = document.getElementById('detail-video');
                            if (dv) { dv.style.display = 'none'; dv.pause(); dv.src = ''; }
                            var pc = document.getElementById('player-cover');
                            if (pc) pc.style.display = 'none';
                            var pl = document.getElementById('player-loading');
                            if (pl) pl.style.display = 'none';
                            if (typeof bindLocalPlayerControls === 'function') bindLocalPlayerControls();
                        })();
                    """.trimIndent())
                } catch (e: Exception) {
                    Log.e(TAG, "LocalPlayer.play failed", e)
                    evalJs("if(window.showToast)window.showToast('播放失败: ${e.message?.replace("'", "\\'")}')")
                }
            }
        }

        @JavascriptInterface
        fun pause() {
            runOnUiThread { exoPlayer?.pause() }
        }

        @JavascriptInterface
        fun resume() {
            runOnUiThread { exoPlayer?.play() }
        }

        @JavascriptInterface
        fun toggle() {
            runOnUiThread {
                exoPlayer?.let {
                    if (it.playWhenReady) it.pause() else it.play()
                }
            }
        }

        @JavascriptInterface
        fun seek(positionMs: Long) {
            runOnUiThread {
                exoPlayer?.seekTo(positionMs.coerceIn(0, exoPlayer?.duration ?: 0))
            }
        }

        @JavascriptInterface
        fun seekRelative(deltaMs: Long) {
            runOnUiThread {
                exoPlayer?.let {
                    val newPos = (it.currentPosition + deltaMs).coerceIn(0, it.duration)
                    it.seekTo(newPos)
                }
            }
        }

        @JavascriptInterface
        fun getState(): String {
            return cachedPlayerState
        }

        @JavascriptInterface
        fun release() {
            runOnUiThread {
                Log.d(TAG, "LocalPlayer.release")
                progressUpdater.removeCallbacks(progressUpdateRunnable)
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                localPlayerView.player = null
                localPlayerView.visibility = View.GONE
                cachedPlayerState = "{}"
                evalJs("if(window.onLocalPlayerReleased)window.onLocalPlayerReleased()")
            }
        }
    }

    private val progressUpdater = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateCachedPlayerState()
            if (exoPlayer?.playWhenReady == true) {
                progressUpdater.postDelayed(this, 250)
            }
        }
    }

    private inner class ExoPlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            updateCachedPlayerState()
            when (state) {
                Player.STATE_READY -> {
                    progressUpdater.removeCallbacks(progressUpdateRunnable)
                    if (exoPlayer?.playWhenReady == true) {
                        progressUpdater.post(progressUpdateRunnable)
                    }
                    evalJs("""
                        (function(){
                            var pl = document.getElementById('player-loading');
                            if (pl) pl.style.display = 'none';
                        })();
                    """.trimIndent())
                }
                Player.STATE_ENDED -> {
                    progressUpdater.removeCallbacks(progressUpdateRunnable)
                    updateCachedPlayerState()
                    evalJs("""
                        (function(){
                            var pp = document.getElementById('p-pp');
                            if (pp) pp.textContent = '\u{1F504}';
                            if (window.playerState) window.playerState.playing = false;
                        })();
                    """.trimIndent())
                }
                Player.STATE_BUFFERING -> {
                    evalJs("""
                        (function(){
                            var pl = document.getElementById('player-loading');
                            if (pl) pl.style.display = '';
                        })();
                    """.trimIndent())
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateCachedPlayerState()
            if (isPlaying) {
                progressUpdater.removeCallbacks(progressUpdateRunnable)
                progressUpdater.post(progressUpdateRunnable)
            } else {
                progressUpdater.removeCallbacks(progressUpdateRunnable)
            }
            val icon = if (isPlaying) "\u23F8" else "\u25B6"
            evalJs("""
                (function(){
                    var pp = document.getElementById('p-pp');
                    if (pp) pp.textContent = '$icon';
                    if (window.playerState) window.playerState.playing = $isPlaying;
                })();
            """.trimIndent())
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error", error)
            progressUpdater.removeCallbacks(progressUpdateRunnable)
            evalJs("""
                (function(){
                    var pl = document.getElementById('player-loading');
                    if (pl) pl.style.display = 'none';
                    if (window.showToast) window.showToast('播放错误: ${error.message?.replace("'", "\\'")?.take(50)}');
                })();
            """.trimIndent())
        }
    }

    private fun updateCachedPlayerState() {
        val p = exoPlayer
        if (p == null) {
            cachedPlayerState = "{}"
            return
        }
        cachedPlayerState = buildString {
            append("{")
            append("\"position\":${p.currentPosition},")
            append("\"duration\":${p.duration},")
            append("\"playing\":${p.playWhenReady},")
            append("\"playbackState\":${p.playbackState}")
            append("}")
        }
    }

    // ==================== JS Bridge Interface ====================

    inner class WebAppInterface {
        @JavascriptInterface fun search(keyword: String, callbackId: String) {
            runOnUiThread { bridge.search(keyword, callbackId) }
        }
        @JavascriptInterface fun getDetail(videoId: Long, isLocal: Boolean, localPath: String, callbackId: String) {
            runOnUiThread { bridge.getDetail(videoId, isLocal, localPath, callbackId) }
        }
        @JavascriptInterface fun playOnline(videoId: Long, title: String, epIndex: Int, callbackId: String) {
            runOnUiThread { bridge.playOnline(videoId, title, epIndex, callbackId) }
        }
        @JavascriptInterface fun openFullscreen(videoId: Long, title: String, epIndex: Int, callbackId: String) {
            runOnUiThread { bridge.openFullscreen(videoId, title, epIndex, callbackId) }
        }
        @JavascriptInterface fun playLocal(path: String) {
            runOnUiThread { bridge.playLocal(path) }
        }
        @JavascriptInterface fun getLocalVideoUrl(path: String, callbackId: String) {
            runOnUiThread { bridge.getLocalVideoUrl(path, callbackId) }
        }
        @JavascriptInterface fun playLocalFromUrl(contentUrl: String, title: String, epIndex: Int, positionMs: Long) {
            runOnUiThread { bridge.playLocalFromUrl(contentUrl, title, epIndex, positionMs) }
        }
        @JavascriptInterface fun browseLocalDir(path: String, callbackId: String) {
            runOnUiThread { bridge.browseLocalDir(path, callbackId) }
        }
        @JavascriptInterface fun getLocalCover(key: String, callbackId: String) {
            runOnUiThread { bridge.getLocalCover(key, callbackId) }
        }
        @JavascriptInterface fun deleteLocalFiles(pathsJson: String, callbackId: String) {
            runOnUiThread { bridge.deleteLocalFiles(pathsJson, callbackId) }
        }
        @JavascriptInterface fun renameLocalFile(path: String, newName: String, callbackId: String) {
            runOnUiThread { bridge.renameLocalFile(path, newName, callbackId) }
        }
        @JavascriptInterface fun moveLocalFiles(pathsJson: String, targetDir: String, callbackId: String) {
            runOnUiThread { bridge.moveLocalFiles(pathsJson, targetDir, callbackId) }
        }
        @JavascriptInterface fun createLocalDir(parentPath: String, name: String, callbackId: String) {
            runOnUiThread { bridge.createLocalDir(parentPath, name, callbackId) }
        }
        // ===== GSY Inline Player =====
        @JavascriptInterface fun playOnlineInline(videoId: Long, title: String, epIndex: Int,
                                                   episodesJson: String, xPx: Int, yPx: Int, wPx: Int, hPx: Int) {
            runOnUiThread {
                // Store the real episode list (previously the JS always passed '[]').
                gsyPlayer.episodeList = parseEpisodes(episodesJson)
                gsyPlayer.currentEpIndex = epIndex
                gsyPlayer.isLocal = false
                gsyPlayer.currentVideoId = videoId
                inlinePlayerActive = true
                resolveJob?.cancel()  // cancel any previous m3u8 resolution
                resolveJob = scope.launch {
                    val m3u8 = bridge.resolveM3u8Url(videoId, epIndex)
                    if (m3u8 == null) {
                        if (inlinePlayerActive) {
                            evalJs("if(window.showToast)window.showToast('无法获取播放地址')")
                            gsyPlayer.visibility = View.GONE
                        }
                        return@launch
                    }
                    // If the user backed out (hideInlinePlayer) while the m3u8 URL was being
                    // resolved, resolveJob was cancelled and inlinePlayerActive is false.
                    // Cancellation is cooperative, so guard here too: a stale completion must
                    // never re-show the player after the detail page closed.
                    if (!inlinePlayerActive) return@launch
                    positionAndSetupGsyPlayer(xPx, yPx, wPx, hPx, m3u8, true, title)
                }
            }
        }

        @JavascriptInterface fun playLocalInline(path: String, episodesJson: String,
                                                  xPx: Int, yPx: Int, wPx: Int, hPx: Int, title: String) {
            runOnUiThread {
                val file = File(path)
                if (!file.exists()) {
                    evalJs("if(window.showToast)window.showToast('文件不存在: $path')")
                    return@runOnUiThread
                }
                // A pending online m3u8 resolution must not fire after switching to local.
                resolveJob?.cancel()
                inlinePlayerActive = true
                // Store the real episode list (previously the JS always passed '[]').
                gsyPlayer.episodeList = parseEpisodes(episodesJson)
                gsyPlayer.isLocal = true
                gsyPlayer.currentVideoId = 0
                gsyPlayer.currentEpIndex = gsyPlayer.episodeList
                    .indexOfFirst { it.path == path }
                    .let { if (it >= 0) gsyPlayer.episodeList[it].index else 1 }
                val finalTitle = if (title.isBlank()) file.nameWithoutExtension else title
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                positionAndSetupGsyPlayer(xPx, yPx, wPx, hPx, uri.toString(), false, finalTitle)
            }
        }

        @JavascriptInterface fun hideInlinePlayer() {
            runOnUiThread {
                inlinePlayerActive = false
                resolveJob?.cancel()  // cancel any pending m3u8 resolution so a stale
                                      // completion can't re-show the player after backing out
                if (::gsyPlayer.isInitialized) {
                    gsyPlayer.onVideoPause()
                    gsyPlayer.visibility = View.GONE
                }
            }
        }

        // Legacy
        @JavascriptInterface fun browseDir(path: String, callbackId: String) {
            runOnUiThread { bridge.browseLocalDir(path, callbackId) }
        }
        @JavascriptInterface fun addDownload(videoId: Long, title: String, epIndex: Int, epName: String, m3u8Url: String, coverUrl: String) {
            runOnUiThread { bridge.addDownload(videoId, title, epIndex, epName, m3u8Url, coverUrl) }
        }
        @JavascriptInterface fun addBatchDownload(itemsJson: String, coverUrl: String) {
            runOnUiThread { bridge.addBatchDownload(itemsJson, coverUrl) }
        }
        @JavascriptInterface fun getDownloadStatus(callbackId: String) {
            runOnUiThread { bridge.getDownloadStatus(callbackId) }
        }
        @JavascriptInterface fun getDownloadRecord(path: String, callbackId: String) {
            runOnUiThread { bridge.getDownloadRecord(path, callbackId) }
        }
        @JavascriptInterface fun getDownloadedEps(videoId: Long, callbackId: String) {
            runOnUiThread { bridge.getDownloadedEps(videoId, callbackId) }
        }
        @JavascriptInterface fun syncDownloadRecords(callbackId: String) {
            runOnUiThread { bridge.syncDownloadRecords(callbackId) }
        }
        @JavascriptInterface fun resetAndResyncRecords(callbackId: String) {
            runOnUiThread { bridge.resetAndResyncRecords(callbackId) }
        }
        @JavascriptInterface fun redownloadLocal(pathsJson: String, callbackId: String) {
            runOnUiThread { bridge.redownloadLocal(pathsJson, callbackId) }
        }
        @JavascriptInterface fun pauseDownload(id: String) { runOnUiThread { bridge.pauseDownload(id) } }
        @JavascriptInterface fun resumeDownload(id: String) { runOnUiThread { bridge.resumeDownload(id) } }
        @JavascriptInterface fun cancelDownload(id: String) { runOnUiThread { bridge.cancelDownload(id) } }
        @JavascriptInterface fun retryDownload(id: String) { runOnUiThread { bridge.retryDownload(id) } }
        @JavascriptInterface fun addFollow(videoId: Long, title: String, coverUrl: String, totalEps: Int) {
            runOnUiThread { bridge.addFollow(videoId, title, coverUrl, totalEps) }
        }
        @JavascriptInterface fun removeFollow(videoId: Long) { runOnUiThread { bridge.removeFollow(videoId) } }
        @JavascriptInterface fun getFollows(callbackId: String) { runOnUiThread { bridge.getFollows(callbackId) } }
        @JavascriptInterface fun checkFollowUpdates() { runOnUiThread { bridge.checkFollowUpdates() } }
        @JavascriptInterface fun markWatched(followId: Long, epIndex: Int) {
            runOnUiThread { bridge.markWatched(followId, epIndex) }
        }
        @JavascriptInterface fun getSettings(callbackId: String) {
            runOnUiThread { bridge.getSettings(callbackId) }
        }
        @JavascriptInterface fun setDownloadPath(path: String) {
            runOnUiThread { bridge.setDownloadPath(path) }
        }
        @JavascriptInterface fun getDownloadsPath(): String {
            return bridge.getDownloadsPath()
        }
        @JavascriptInterface fun openDirectoryPicker() {
            runOnUiThread { safPickerLauncher.launch(null) }
        }
        @JavascriptInterface fun refreshDomain() { runOnUiThread { bridge.refreshDomain() } }
        @JavascriptInterface fun getDiscover(category: String, page: Int, callbackId: String) {
            runOnUiThread { bridge.getDiscover(category, page, callbackId) }
        }
    }

    // ==================== GSY Player Helpers ====================

    private fun positionAndSetupGsyPlayer(xPx: Int, yPx: Int, wPx: Int, hPx: Int,
                                           url: String, isLive: Boolean, title: String) {
        val params = gsyPlayer.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = xPx
        params.topMargin = yPx
        params.width = wPx
        params.height = hPx
        gsyPlayer.layoutParams = params
        gsyPlayer.visibility = View.VISIBLE

        // Reset GSY's 2000ms anti-churn guard before switching episodes. After a
        // fullscreen round-trip, resolveNormalVideoShow() stamps mSaveChangeViewTIme to
        // "now", so the first setUp() for a new episode within 2s returns false — leaving
        // mUrl pointing at the OLD episode and the subsequent startPlayLogic() re-prepares
        // that stale URL ("first click doesn't switch" bug). releaseAllVideos() calls
        // onCompletion() on the current listener, which zeroes mSaveChangeViewTIme and
        // detaches the listener, so isCurrentMediaListener() is false and setUp() always
        // applies the new URL.
        gsyPlayer.onVideoPause()
        GSYVideoManager.releaseAllVideos()

        gsyPlayer.setUp(url, isLive, title)
        gsyPlayer.startPlayLogic()

        // Wire fullscreen button to GSY's native startWindowFullscreen mechanism.
        // This removes the player from our layout and places it in a fullscreen
        // Window within the Activity, preserving all playback state automatically.
        gsyPlayer.postDelayed({
            val btn = gsyPlayer.fullscreenButton
            if (btn != null) {
                // Clear GSY's built-in touch listener (which is a no-op for fullscreen)
                btn.setOnTouchListener(null)
                btn.isClickable = true
                btn.isEnabled = true
                btn.setOnClickListener {
                    Log.e(TAG, "Fullscreen button clicked: starting GSY native fullscreen")
                    gsyPlayer.startWindowFullscreen(this@MainActivity, true, true)
                }
            }
        }, 300)
    }

    // ==================== GSY Episode Navigation ====================

    /**
     * Handle prev/next/select-episode navigation from the custom control bar.
     * Operates on the CURRENT player (inline or fullscreen clone) so playback
     * switches in place without breaking the fullscreen window.
     */
    private fun handleGsyEpisodeNav(nav: EpisodeNav, requestedIndex: Int) {
        val player = (gsyPlayer.getCurrentPlayer() as? SakuraGSYVideoPlayer) ?: gsyPlayer
        val eps = player.episodeList
        if (eps.isEmpty()) {
            Toast.makeText(this, "暂无剧集列表", Toast.LENGTH_SHORT).show()
            return
        }
        val currentIdx = eps.indexOfFirst { it.index == player.currentEpIndex }.let { if (it < 0) 0 else it }
        val target = when (nav) {
            EpisodeNav.PREV -> (currentIdx - 1).coerceAtLeast(0)
            EpisodeNav.NEXT -> (currentIdx + 1).coerceAtMost(eps.size - 1)
            EpisodeNav.SELECT -> eps.indexOfFirst { it.index == requestedIndex }.let { if (it < 0) currentIdx else it }
        }
        if (nav == EpisodeNav.PREV && target == currentIdx) {
            Toast.makeText(this, "已经是第一集", Toast.LENGTH_SHORT).show()
            return
        }
        if (nav == EpisodeNav.NEXT && target == currentIdx) {
            Toast.makeText(this, "已经是最后一集", Toast.LENGTH_SHORT).show()
            return
        }
        val ep = eps[target]
        player.currentEpIndex = ep.index
        switchGsyEpisode(player, ep)
    }

    /**
     * Resolve and play [ep]: re-resolve m3u8 for online sources, re-play the
     * file for local sources, operating on the given player instance.
     */
    private fun switchGsyEpisode(player: SakuraGSYVideoPlayer, ep: PlayerEpisode) {
        val title = ep.name.ifBlank { "第${ep.index}集" }
        if (player.isLocal) {
            val file = File(ep.path)
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在: ${ep.path}", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            switchGsyToUrl(player, uri.toString(), false, title)
        } else {
            val videoId = player.currentVideoId
            resolveJob?.cancel()
            resolveJob = scope.launch {
                val m3u8 = bridge.resolveM3u8Url(videoId, ep.index)
                if (m3u8 == null) {
                    if (inlinePlayerActive) {
                        evalJs("if(window.showToast)window.showToast('无法获取播放地址')")
                    }
                    return@launch
                }
                // Guard against re-showing the player if the user backed out (hideInlinePlayer)
                // while the m3u8 URL was being resolved.
                if (!inlinePlayerActive) return@launch
                switchGsyToUrl(player, m3u8, true, title)
            }
        }
    }

    /**
     * Switch playback in place. The releaseAllVideos() call zeroes GSY's
     * 2000ms anti-churn guard (same rationale as positionAndSetupGsyPlayer),
     * so setUp() always applies the new URL.
     */
    private fun switchGsyToUrl(player: SakuraGSYVideoPlayer, url: String, isLive: Boolean, title: String) {
        player.onVideoPause()
        GSYVideoManager.releaseAllVideos()
        player.setUp(url, isLive, title)
        player.startPlayLogic()
        updateGsyEpisodeUi(player)
    }

    /**
     * Sync the JS detail page (current episode, title, grid highlight) with
     * the episode that the native GSY player just switched to.
     */
    private fun updateGsyEpisodeUi(player: SakuraGSYVideoPlayer) {
        val epIndex = player.currentEpIndex
        evalJs("""
            (function(){
                if (window.playerState) window.playerState.currentEp = $epIndex;
                if (window.currentDetail && window.currentDetail.episodes) {
                    var ep = window.currentDetail.episodes.find(function(e){ return e.index === $epIndex; });
                    var epName = (ep && ep.name) ? ep.name : '第${'$'}{epIndex}集';
                    var base = window.currentDetail.title || '';
                    var disp = window.currentDetail.isLocal ? epName : (base ? base + ' - ' + epName : epName);
                    var dt = document.getElementById('detail-title'); if (dt) dt.textContent = disp;
                    var d2 = document.getElementById('d-title'); if (d2) d2.textContent = disp;
                }
                var btns = document.querySelectorAll('#episode-grid .ep-btn');
                btns.forEach(function(b){ b.classList.toggle('playing', parseInt(b.dataset.ep) === $epIndex); });
                if (typeof window.onGsyEpisodeChanged === 'function') window.onGsyEpisodeChanged($epIndex);
            })();
        """.trimIndent())
    }

    /**
     * Parse the episodes JSON array passed from JS into [PlayerEpisode]s.
     */
    private fun parseEpisodes(json: String): List<PlayerEpisode> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val idx = obj.optInt("index", i + 1)
                PlayerEpisode(
                    index = idx,
                    name = obj.optString("name", "第${idx}集"),
                    path = obj.optString("path", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseEpisodes failed", e)
            emptyList()
        }
    }

    // ==================== Helpers ====================

    /**
     * Disable GSYVideoPlayer's "using mobile data?" dialog.
     * GSYVideoBasePlayer.mNeedShowWifiTip is a boolean field that controls whether
     * the warning dialog is shown. It is typically set via GSYVideoOptionBuilder,
     * but we use reflection to disable it directly since we use setUp() instead.
     */
    private fun disableGsyNetworkWarning() {
        try {
            // Walk up the class hierarchy to find mNeedShowWifiTip
            var cls: Class<*>? = SakuraGSYVideoPlayer::class.java
            while (cls != null && cls != Any::class.java) {
                try {
                    val field = cls.getDeclaredField("mNeedShowWifiTip")
                    field.isAccessible = true
                    field.setBoolean(gsyPlayer, false)
                    Log.e(TAG, "Disabled GSY WiFi tip via field ${cls.simpleName}.mNeedShowWifiTip")
                    return
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            Log.e(TAG, "Could not find mNeedShowWifiTip field to disable WiFi tip")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable GSY WiFi tip", e)
        }
    }

    fun evalJs(js: String) {
        runOnUiThread {
            try { webView.evaluateJavascript(js, null) }
            catch (e: Exception) { Log.e(TAG, "evalJs failed", e) }
        }
    }

    override fun onResume() {
        super.onResume()

        // GSY lifecycle: resume surface when returning to foreground.
        // Fullscreen entry/exit is handled natively by startWindowFullscreen /
        // clearFullscreenLayout — state is preserved in GSYVideoManager automatically.
        if (::gsyPlayer.isInitialized && gsyPlayer.visibility == View.VISIBLE) {
            if (wasPlayingBeforeBackground) {
                gsyPlayer.onVideoResume()  // restores saved position and resumes playback
            } else {
                // User had manually paused before backgrounding — restore the paused
                // position WITHOUT starting playback. onVideoResume() always starts when
                // state==5, so a resume-then-repause would cause a ~100ms play flash /
                // audio blip; instead seek while paused and keep it paused.
                gsyPlayer.seekTo(gsyPlayer.currentPositionWhenPlaying)
                gsyPlayer.onVideoPause()
            }
        }

        val dlDir = File(SettingsPrefs.downloadPath)
        if (!dlDir.exists()) {
            val created = dlDir.mkdirs()
            if (created) {
                Log.e("SakuraMain", "Download dir created on resume: ${dlDir.absolutePath}")
                evalJs("if(window.onPathChanged)window.onPathChanged('${dlDir.absolutePath}')")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause the inline player and save its position (GSY's onVideoPause() stores
        // mCurrentPosition). Without this the video keeps playing in the background and
        // the position is never saved, so onResume's onVideoResume() is a no-op.
        if (::gsyPlayer.isInitialized) {
            // isInPlayingState() returns TRUE even for PAUSE state (5) — it really means
            // "not idle/completed/error". Use the raw state so a manually-paused player is
            // NOT misclassified as "was playing".
            val state = gsyPlayer.currentState
            wasPlayingBeforeBackground = (state == 2 || state == 3)  // PLAYING / BUFFERING_PLAYING
            gsyPlayer.onVideoPause()
        }
    }

    override fun onStop() {
        super.onStop()
        // Ensure the player is paused when the app is fully backgrounded. This is a
        // no-op if onPause already paused it (isInPlayingState is false), but covers
        // paths where the playback state changed between onPause and onStop.
        if (::gsyPlayer.isInitialized) {
            gsyPlayer.onVideoPause()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // Release GSY
        if (::gsyPlayer.isInitialized) {
            gsyPlayer.onVideoPause()
        }
        GSYVideoManager.releaseAllVideos()
        // Release ExoPlayer
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // If GSY fullscreen is active, exit fullscreen first
        if (::gsyPlayer.isInitialized && GSYVideoManager.backFromWindowFull(this)) {
            return
        }
        webView.evaluateJavascript("handleBackPress(); window._shouldExit") { result ->
            if (result == "true") super.onBackPressed()
        }
    }

    companion object {
        // Static process-wide guard: the splash shows only once per app launch.
        // A fresh process (cold start) starts with this false; after onCreate has
        // shown it once it stays true, so onResume (background return) and activity
        // recreation (rotation) never re-show it.
        @Volatile
        private var splashShown = false
    }
}
