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
import com.sakura.player.player.PlayerActivity
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoViewBridge
import kotlinx.coroutines.*
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
    private lateinit var gsyPlayer: StandardGSYVideoPlayer
    private var currentIsLocal: Boolean = false
    private var currentFilePath: String = ""
    private var currentM3u8Url: String = ""
    private var currentTitle: String = ""

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
        gsyPlayer = StandardGSYVideoPlayer(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            setIsTouchWiget(true)
            // Hide title/back button for inline mode
            backButton.visibility = View.GONE
            titleTextView.visibility = View.GONE
        }

        // Disable mobile data warning dialog in GSYVideoPlayer
        disableGsyNetworkWarning()

        // Add views to root: WebView first (bottom), PlayerView on top, GSY on topmost
        rootLayout.addView(webView)
        rootLayout.addView(localPlayerView)
        rootLayout.addView(gsyPlayer)

        setContentView(rootLayout)
        Log.e("SakuraMain", "App started, WebView created, loading frontend")

        UpdateChecker.schedule(this, scope)
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
                currentIsLocal = false
                currentFilePath = ""
                currentTitle = title
                scope.launch {
                    val m3u8 = bridge.resolveM3u8Url(videoId, epIndex)
                    if (m3u8 == null) {
                        evalJs("if(window.showToast)window.showToast('无法获取播放地址')")
                        return@launch
                    }
                    currentM3u8Url = m3u8
                    positionAndSetupGsyPlayer(xPx, yPx, wPx, hPx, m3u8, true, title)
                }
            }
        }

        @JavascriptInterface fun playLocalInline(path: String, episodesJson: String,
                                                  xPx: Int, yPx: Int, wPx: Int, hPx: Int) {
            runOnUiThread {
                currentIsLocal = true
                currentFilePath = path
                currentM3u8Url = ""
                currentTitle = File(path).nameWithoutExtension
                val file = File(path)
                if (!file.exists()) {
                    evalJs("if(window.showToast)window.showToast('文件不存在: $path')")
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                positionAndSetupGsyPlayer(xPx, yPx, wPx, hPx, uri.toString(), false, currentTitle)
            }
        }

        @JavascriptInterface fun hideInlinePlayer() {
            runOnUiThread {
                gsyPlayer.onVideoPause()
                gsyPlayer.visibility = View.GONE
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
        @JavascriptInterface fun getDiscover(page: Int, callbackId: String) {
            runOnUiThread { bridge.getDiscover(page, callbackId) }
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

        gsyPlayer.setUp(url, isLive, title)
        gsyPlayer.startPlayLogic()

        // Wire fullscreen button
        gsyPlayer.postDelayed({ wireFullscreenButton() }, 300)
    }

    private fun wireFullscreenButton() {
        val btn = gsyPlayer.fullscreenButton
        if (btn == null) {
            Log.e("SakuraMain", "wireFullscreenButton: fullscreenButton is null, will retry")
            gsyPlayer.postDelayed({ wireFullscreenButton() }, 500)
            return
        }
        Log.e("SakuraMain", "wireFullscreenButton: wiring fullscreen button")
        // Clear GSY's touch listener (GSY's onTouch returns false for fullscreen,
        // but clearing it removes any risk of touch consumption)
        btn.setOnTouchListener(null)
        // Ensure the button can receive click events
        btn.isClickable = true
        btn.isEnabled = true
        btn.setOnClickListener {
            Log.e("SakuraMain", "Fullscreen button clicked: isLocal=$currentIsLocal playing=${gsyPlayer.isInPlayingState} pos=${gsyPlayer.currentPositionWhenPlaying}")
            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra("source", if (currentIsLocal) "local" else "online")
                if (currentIsLocal) putExtra("path", currentFilePath)
                else putExtra("url", currentM3u8Url)
                putExtra("title", currentTitle)
                putExtra("position", gsyPlayer.currentPositionWhenPlaying)
                putExtra("playing", gsyPlayer.isInPlayingState)
            }
            gsyPlayer.onVideoPause()
            startActivity(intent)
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
            var cls: Class<*>? = StandardGSYVideoPlayer::class.java
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

        // Restore GSY inline player state when returning from fullscreen
        if (::gsyPlayer.isInitialized) {
            val pos = JsBridge.lastFullscreenPosition
            if (pos > 0 && gsyPlayer.visibility == View.VISIBLE) {
                // Returning from fullscreen: resume surface (no re-prepare),
                // seek to saved position, restore play state.
                Log.e("SakuraMain", "Restoring from fullscreen: pos=$pos playing=${JsBridge.lastFullscreenWasPlaying}")
                gsyPlayer.onVideoResume()
                // Seek the existing prepared player directly via GSYVideoViewBridge
                // — avoid startPlayLogic() which re-prepares the entire media source.
                val player = gsyPlayer.currentPlayer as? GSYVideoViewBridge
                if (player != null) {
                    Log.e("SakuraMain", "currentPlayer ready, seeking to $pos")
                    player.seekTo(pos)
                    if (JsBridge.lastFullscreenWasPlaying) {
                        player.start()
                    }
                } else {
                    // Fallback: if currentPlayer is somehow null, re-prepare
                    Log.e("SakuraMain", "currentPlayer is null after onVideoResume, fallback to startPlayLogic")
                    gsyPlayer.seekOnStart = pos
                    gsyPlayer.startPlayLogic()
                    if (!JsBridge.lastFullscreenWasPlaying) {
                        gsyPlayer.postDelayed({ gsyPlayer.onVideoPause() }, 100)
                    }
                }
                // Re-wire the fullscreen button after restore
                gsyPlayer.postDelayed({ wireFullscreenButton() }, 500)
            } else {
                // Resume from background: just restore surface but stay paused
                gsyPlayer.onVideoResume()
                gsyPlayer.onVideoPause()
            }
            JsBridge.lastFullscreenPosition = 0
            JsBridge.lastFullscreenWasPlaying = false
        }

        // Legacy ExoPlayer resume from fullscreen (keep existing behavior)
        if (JsBridge.lastFullscreenPosition > 0 && exoPlayer != null) {
            val resumePos = JsBridge.lastFullscreenPosition
            JsBridge.lastFullscreenPosition = 0
            exoPlayer?.let {
                it.seekTo(resumePos.coerceIn(0, it.duration))
                it.play()
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
        webView.evaluateJavascript("handleBackPress(); window._shouldExit") { result ->
            if (result == "true") super.onBackPressed()
        }
    }
}
