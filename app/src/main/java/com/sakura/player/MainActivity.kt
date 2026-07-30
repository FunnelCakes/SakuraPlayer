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
import com.sakura.player.player.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var webView: WebView
    private lateinit var bridge: JsBridge
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ExoPlayer for local file playback
    private lateinit var localPlayerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    @Volatile private var cachedPlayerState: String = "{}"

    // SakuraPlayer — 6-layer native player for inline + fullscreen playback
    private lateinit var sakuraPlayer: SakuraPlayerView
    private lateinit var playerBridge: PlayerBridge
    private var currentM3u8Url: String = ""
    private var currentTitle: String = ""
    private var currentVideoId: Long = 0
    private var currentEpisodesJson: String = "[]"
    private var currentIsLocal: Boolean = false
    private var currentFilePath: String = ""

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

        // Create SakuraPlayerView (6-layer B站-style player) — hidden until playback starts
        sakuraPlayer = SakuraPlayerView(this).apply {
            visibility = View.GONE
            onFullscreenRequest = {
                val pos = sakuraPlayer.getPlayerState().position
                val episodesJson = buildEpisodesJson()
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    if (currentIsLocal) {
                        putExtra(PlayerActivity.EXTRA_SOURCE, "local")
                        putExtra(PlayerActivity.EXTRA_PATH, currentFilePath)
                    } else {
                        putExtra(PlayerActivity.EXTRA_SOURCE, "online")
                        putExtra(PlayerActivity.EXTRA_URL, currentM3u8Url)
                    }
                    putExtra(PlayerActivity.EXTRA_TITLE, currentTitle)
                    putExtra(PlayerActivity.EXTRA_POSITION, pos)
                    putExtra(PlayerActivity.EXTRA_EPISODES, episodesJson)
                }
                startActivity(intent)
            }
            onEpisodeChange = { idx ->
                evalJs("if(window.onPlayerEpisodeChange)window.onPlayerEpisodeChange($idx)")
            }
            onStateChanged = { state ->
                // Sync state back to JS if needed
                val json = JSONObject().apply {
                    put("playing", state.playing)
                    put("position", state.position)
                    put("duration", state.duration)
                    put("currentEp", state.currentEp)
                    put("speed", state.speed.toDouble())
                }.toString()
                evalJs("if(window.onPlayerStateChanged)window.onPlayerStateChanged($json)")
            }
        }

        // Add views to root in correct z-order: WebView (bottom), PlayerView (mid), SakuraPlayer (top)
        rootLayout.addView(webView)
        rootLayout.addView(localPlayerView)
        rootLayout.addView(sakuraPlayer)

        playerBridge = PlayerBridge(
            player = sakuraPlayer,
            context = this@MainActivity,
            jsEvaluator = { js -> evalJs(js) },
            m3u8Resolver = { videoId, title, epIndex, callback ->
                bridge.playOnline(videoId, title, epIndex, callback)
            }
        )

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
        @JavascriptInterface fun playOnlineNative(videoId: Long, title: String, epIndex: Int,
                                                   episodesJson: String, xPx: Int, yPx: Int, wPx: Int, hPx: Int) {
            runOnUiThread {
                currentIsLocal = false; currentFilePath = ""
                currentVideoId = videoId
                currentTitle = title
                currentEpisodesJson = episodesJson

                // Position SakuraPlayerView over the WebView's #player-area
                val params = sakuraPlayer.layoutParams as FrameLayout.LayoutParams
                params.leftMargin = xPx
                params.topMargin = yPx
                params.width = wPx
                params.height = hPx
                sakuraPlayer.layoutParams = params

                val episodes = parseEpisodesFromJson(episodesJson)
                val config = PlayerConfig(mode = PlayerMode.INLINE, title = title, episodes = episodes)
                sakuraPlayer.setup(config)
                sakuraPlayer.visibility = View.VISIBLE

                // Resolve m3u8 URL (with CDN race) and play in SakuraPlayer
                scope.launch(Dispatchers.IO) {
                    val m3u8Url = bridge.resolveM3u8Url(videoId, epIndex)
                    withContext(Dispatchers.Main) {
                        if (m3u8Url != null) {
                            currentM3u8Url = m3u8Url
                            sakuraPlayer.play(m3u8Url)
                        } else {
                            evalJs("if(window.showToast)window.showToast('无法获取播放地址')")
                            sakuraPlayer.visibility = View.GONE
                        }
                    }
                }
            }
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
        @JavascriptInterface fun playLocalNative(path: String, episodesJson: String,
                                                   xPx: Int, yPx: Int, wPx: Int, hPx: Int) {
            runOnUiThread {
                currentIsLocal = true
                currentFilePath = path
                currentTitle = java.io.File(path).nameWithoutExtension
                currentEpisodesJson = episodesJson
                val params = sakuraPlayer.layoutParams as FrameLayout.LayoutParams
                params.leftMargin = xPx; params.topMargin = yPx
                params.width = wPx; params.height = hPx
                sakuraPlayer.layoutParams = params

                val episodes = parseEpisodesFromJson(episodesJson)
                val title = java.io.File(path).nameWithoutExtension
                val config = PlayerConfig(mode = PlayerMode.INLINE, title = title, episodes = episodes)
                sakuraPlayer.setup(config)
                sakuraPlayer.visibility = View.VISIBLE

                // Get FileProvider URI and play
                val file = java.io.File(path)
                if (file.exists()) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "${packageName}.fileprovider", file
                    )
                    sakuraPlayer.playLocal(uri)
                } else {
                    sakuraPlayer.showError("文件不存在"); sakuraPlayer.visibility = View.GONE
                }
            }
        }
        @JavascriptInterface fun setSakuraPlayerVisible(visible: Boolean) {
            runOnUiThread {
                sakuraPlayer.visibility = if (visible) View.VISIBLE else View.GONE
                if (!visible) sakuraPlayer.release()
            }
        }
    }

    // ==================== Helpers ====================

    fun evalJs(js: String) {
        runOnUiThread {
            try { webView.evaluateJavascript(js, null) }
            catch (e: Exception) { Log.e(TAG, "evalJs failed", e) }
        }
    }

    /** Parse episode JSON array into EpisodeItem list. */
    private fun parseEpisodesFromJson(json: String): List<EpisodeItem> {
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

    /** Build episodes JSON from cached field for fullscreen handoff. */
    private fun buildEpisodesJson(): String {
        return currentEpisodesJson
    }

    override fun onResume() {
        super.onResume()

        // Resume SakuraPlayer from fullscreen position if returning from PlayerActivity
        val resumePos = JsBridge.lastFullscreenPosition
        if (resumePos > 0) {
            JsBridge.lastFullscreenPosition = 0
            sakuraPlayer.exoPlayer?.let {
                it.seekTo(resumePos.coerceIn(0, it.duration))
                it.playWhenReady = true
            }
            // Also resume legacy exoPlayer if active
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
        // Release ExoPlayer
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        // Release SakuraPlayer
        if (::sakuraPlayer.isInitialized) sakuraPlayer.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("handleBackPress(); window._shouldExit") { result ->
            if (result == "true") super.onBackPressed()
        }
    }
}
