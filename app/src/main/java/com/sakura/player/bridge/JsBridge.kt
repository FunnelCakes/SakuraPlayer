package com.sakura.player.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import com.sakura.player.AnimeService
import com.sakura.player.data.SettingsPrefs
import com.sakura.player.download.DownloadManager
import com.sakura.player.download.DownloadNotif
import com.sakura.player.download.DownloadRecordManager
import com.sakura.player.download.DownloadTask
import com.sakura.player.download.TsDownloader
import com.sakura.player.data.DownloadRecordEntity
import com.sakura.player.follow.FollowManager
import com.sakura.player.scraper.AnimeScraper
import com.sakura.player.scraper.AnimeResult
import com.sakura.player.scraper.AnimeDetail
import com.sakura.player.scraper.EpisodeInfo
import com.sakura.player.scraper.VideoUrl
import com.sakura.player.scraper.DomainFinder
import com.sakura.player.scraper.VideoExtractor
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class JsBridge(private val ctx: Context) {
    private val TAG = "JsBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var domain: String
    private var jsEvaluator: ((String) -> Unit)? = null

    fun setEvaluator(eval: (String) -> Unit) { jsEvaluator = eval }

    suspend fun initDomain(): String {
        domain = SettingsPrefs.activeDomain
        if (domain.isEmpty()) {
            domain = DomainFinder.findDomain()
            SettingsPrefs.activeDomain = domain
        }
        return domain
    }

    // Call JS function in WebView
    fun callJs(function: String, vararg args: String) {
        // This will be set from MainActivity
    }

    // ==================== Search ====================

    fun search(keyword: String, callback: String) {
        scope.launch {
            try {
                val online = AnimeScraper.search(domain, keyword)
                val local = searchLocal(keyword)
                val merged = mergeResults(local, online)
                val json = JSONArray()
                merged.forEach { json.put(it.toJson()) }
                evalJs("$callback(null, ${json})")
            } catch (e: Exception) {
                evalJs("$callback('${e.message?.replace("'", "\\'")}', null)")
            }
        }
    }

    private fun searchLocal(keyword: String): List<AnimeResult> {
        val results = mutableListOf<AnimeResult>()
        val root = File(SettingsPrefs.downloadPath)
        if (!root.exists()) return results

        val kw = keyword.lowercase()
        root.listFiles()?.filter { it.isDirectory }?.forEach { ipDir ->
            val nameMatch = ipDir.name.lowercase().contains(kw)
            if (nameMatch) {
                // Find cover image
                val cover = findCover(ipDir)
                results.add(AnimeResult(
                    videoId = ipDir.name.hashCode().toLong(),
                    title = ipDir.name,
                    coverUrl = "file://$cover",
                    episodeInfo = "${countEpisodes(ipDir)} 集",
                    isLocal = true
                ))
            }
        }
        return results
    }

    private fun findCover(dir: File): String {
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".jpg") || f.name.endsWith(".png") || f.name == "cover.jpg") {
                return f.absolutePath
            }
        }
        // Check subdirectories
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            sub.listFiles()?.forEach { f ->
                if (f.name.endsWith(".jpg") || f.name.endsWith(".png")) {
                    return f.absolutePath
                }
            }
        }
        return ""
    }

    private fun countEpisodes(dir: File): Int {
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".mp4")) count++
            else if (f.isDirectory) {
                f.listFiles()?.forEach { if (it.name.endsWith(".mp4")) count++ }
            }
        }
        return count
    }

    private fun mergeResults(
        local: List<AnimeResult>,
        online: List<AnimeResult>
    ): List<AnimeResult> = local + online

    // ==================== Detail ====================

    fun getDetail(videoId: Long, isLocal: Boolean, localPath: String, callback: String) {
        scope.launch {
            try {
                if (isLocal) {
                    val json = buildLocalDetail(localPath)
                    evalJs("$callback(null, $json)")
                } else {
                    val detail = AnimeScraper.getDetail(domain, videoId)
                    val json = JSONObject().apply {
                        put("videoId", detail.videoId)
                        put("title", detail.title)
                        put("coverUrl", detail.coverUrl)
                        put("description", detail.description)
                        put("tags", JSONArray(detail.tags))
                        put("totalEps", detail.totalEps)
                        put("sourceIds", JSONArray(detail.sourceIds))
                        put("isLocal", false)
                        val eps = JSONArray()
                        detail.episodes.forEach { ep ->
                            eps.put(JSONObject().apply {
                                put("index", ep.index)
                                put("name", ep.name)
                            })
                        }
                        put("episodes", eps)
                    }
                    evalJs("$callback(null, $json)")
                }
            } catch (e: Exception) {
                evalJs("$callback('${e.message?.replace("'", "\\'")}', null)")
            }
        }
    }

    private fun buildLocalDetail(path: String): JSONObject {
        val dir = File(path)
        val coverPath = findCover(dir)
        val json = JSONObject().apply {
            put("videoId", path.hashCode().toLong())
            put("title", dir.name)
            put("isLocal", true)
            put("coverUrl", if (coverPath.isNotEmpty()) "file://$coverPath" else "")
        }

        val eps = JSONArray()
        val mp4Files = mutableListOf<File>()

        // Collect mp4 files from dir and subdirs
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            sub.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedBy { it.name }?.forEach { mp4Files.add(it) }
        }
        dir.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedBy { it.name }?.forEach { mp4Files.add(it) }

        mp4Files.forEachIndexed { idx, f ->
            eps.put(JSONObject().apply {
                put("index", idx + 1)
                put("name", f.nameWithoutExtension)
                put("path", f.absolutePath)
            })
        }
        json.put("episodes", eps)
        json.put("totalEps", eps.length())
        return json
    }

    // ==================== Play ====================

    fun playOnline(videoId: Long, title: String, epIndex: Int, callback: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val detail = AnimeScraper.getDetail(domain, videoId)
                val trySids = if (detail.sourceIds.isNotEmpty()) detail.sourceIds else listOf(1)

                // Collect m3u8 URLs from all available CDNs
                val entries = mutableListOf<Pair<Int, String>>() // sid -> url
                for (sid in trySids) {
                    try {
                        val vu = VideoExtractor.extractFromPlayPage(domain, videoId, epIndex, sid)
                        if (vu.m3u8Url.isNotEmpty()) {
                            entries.add(sid to vu.m3u8Url)
                        }
                    } catch (_: Exception) {}
                }

                if (entries.isEmpty()) {
                    evalJs("$callback('无法获取播放地址', null)")
                    return@launch
                }

                // Single CDN or multi-CDN race
                val bestUrl = if (entries.size == 1) {
                    entries[0].second
                } else {
                    raceStreamingCdns(entries)
                }

                evalJs("$callback(null, {m3u8Url:'$bestUrl'})")
            } catch (e: Exception) {
                evalJs("$callback('${e.message?.replace("'", "\\'")}', null)")
            }
        }
    }

    /** Race multiple CDNs: probe each for 1.5s, return the fastest one's m3u8 URL */
    private suspend fun raceStreamingCdns(
        entries: List<Pair<Int, String>>, sampleMs: Long = 1500
    ): String {
        val speeds = java.util.concurrent.ConcurrentHashMap<Int, Double>() // sid -> MB/s
        val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        for ((sid, url) in entries) {
            probeScope.launch {
                val speed = TsDownloader.probeSpeed(url)
                if (speed > 0) speeds[sid] = speed
                Log.e(TAG, "CDN sid=$sid probe: ${String.format("%.2f", speed)} MB/s")
            }
        }

        // Adaptive wait: end as soon as any CDN returns a valid speed, max 1.5s
        var waited = 0L
        while (speeds.isEmpty() && waited < sampleMs) {
            delay(100)
            waited += 100
        }
        probeScope.cancel()

        val best = speeds.maxByOrNull { it.value }?.key ?: entries[0].first
        val bestSpeed = String.format("%.2f", speeds[best] ?: 0.0)
        Log.e(TAG, "Streaming race winner: sid=$best ($bestSpeed MB/s)")
        return entries.find { it.first == best }!!.second
    }

    /** Resolve m3u8 URL for inline player (suspend, called from MainActivity) */
    suspend fun resolveM3u8Url(videoId: Long, epIndex: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val detail = AnimeScraper.getDetail(domain, videoId)
                val trySids = if (detail.sourceIds.isNotEmpty()) detail.sourceIds else listOf(1)
                for (sid in trySids) {
                    val vu = VideoExtractor.extractFromPlayPage(domain, videoId, epIndex, sid)
                    if (vu.m3u8Url.isNotEmpty()) return@withContext vu.m3u8Url
                }
                null
            } catch (_: Exception) { null }
        }
    }

    fun openFullscreen(videoId: Long, title: String, epIndex: Int, callback: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val detail = AnimeScraper.getDetail(domain, videoId)
                var m3u8Url = ""
                val trySids = if (detail.sourceIds.isNotEmpty()) detail.sourceIds else listOf(1)
                for (sid in trySids) {
                    val vu = VideoExtractor.extractFromPlayPage(domain, videoId, epIndex, sid)
                    if (vu.m3u8Url.isNotEmpty()) { m3u8Url = vu.m3u8Url; break }
                }
                if (m3u8Url.isNotEmpty()) {
                    // Route through JS to use inline GSY player
                    evalJs("""
                        if (window._onOpenFullscreenResolved)
                            window._onOpenFullscreenResolved('${m3u8Url}', ${videoId}, '${title.replace("'", "\\'")}', ${epIndex});
                    """.trimIndent())
                    evalJs("$callback(null, {})")
                } else {
                    evalJs("$callback('无法获取播放地址', null)")
                }
            } catch (e: Exception) {
                evalJs("$callback('${e.message?.replace("'", "\\'")}', null)")
            }
        }
    }

    fun playLocal(path: String) {
        // Route through JS to use inline GSY player
        evalJs("""
            var detail = window.currentDetail;
            if (detail && detail.episodes) {
                var ep = detail.episodes.find(function(e) { return e.path === '${path.replace("'", "\\'")}'; });
                if (ep && typeof playEpisode === 'function') { playEpisode(ep.index); }
            }
        """.trimIndent())
    }

    fun playLocalFromPath(path: String) {
        playLocal(path)
    }

    fun getLocalVideoUrl(path: String, callback: String) {
        val file = File(path)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            evalJs("$callback(null, \"${uri}\")")
        } else {
            evalJs("$callback('文件不存在', null)")
        }
    }

    fun playLocalFromUrl(contentUrl: String, title: String, epIndex: Int, positionMs: Long = 0) {
        // Route through JS to use inline GSY player
        val safeTitle = title.replace("'", "\\'")
        evalJs("""
            (function() {
                var detail = window.currentDetail;
                if (detail && detail.episodes && typeof playEpisode === 'function') {
                    playEpisode(${epIndex});
                }
            })();
        """.trimIndent())
    }

    // ==================== Local File Manager ====================

    fun browseLocalDir(path: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val dirPath = if (path.isEmpty()) SettingsPrefs.downloadPath else path
                val items = com.sakura.player.local.LocalFileManager.scanDir(dirPath)
                val parent = File(dirPath).parent ?: ""
                val json = JSONObject().apply {
                    put("path", dirPath)
                    put("name", File(dirPath).name.ifEmpty { "樱花动漫" })
                    put("parentPath", parent)
                    val arr = JSONArray()
                    // Pre-resolve parent dir cover for files
                    var parentCoverKey = ""
                    val parentRecords = DownloadRecordManager.getRecordsUnder(if (dirPath.endsWith("/")) dirPath else "$dirPath/")
                    if (parentRecords.isNotEmpty()) {
                        parentCoverKey = "v_${parentRecords.first().videoId}"
                    }
                    items.forEach { item ->
                        var coverKey = item.coverKey
                        // For dirs: try internal cover files, then download cache
                        if (coverKey.isBlank() && item.isDir) {
                            val prefix = if (item.path.endsWith("/")) item.path else "${item.path}/"
                            val records = DownloadRecordManager.getRecordsUnder(prefix)
                            if (records.isNotEmpty()) {
                                coverKey = "v_${records.first().videoId}"
                            } else if (parentCoverKey.isNotEmpty()) {
                                coverKey = parentCoverKey
                            }
                        }
                        // For files: use parent directory's cover
                        if (coverKey.isBlank() && !item.isDir) {
                            if (parentCoverKey.isNotEmpty()) {
                                coverKey = parentCoverKey
                            } else {
                                // Fallback: scan parent directory for local cover images
                                coverKey = com.sakura.player.local.LocalFileManager.findCoverForDir(dirPath)
                            }
                        }
                        arr.put(JSONObject().apply {
                            put("name", item.name)
                            put("path", item.path)
                            put("isDir", item.isDir)
                            put("coverKey", coverKey)
                            put("episodeCount", item.episodeCount)
                            put("duration", item.duration)
                            put("size", item.size)
                        })
                    }
                    put("items", arr)
                }
                evalJs("$callback(null, $json)")
            } catch (e: Exception) {
                evalJs("$callback('${e.message?.replace("'", "\\'")}', null)")
            }
        }
    }

    fun getLocalCover(key: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            val path = com.sakura.player.local.LocalFileManager.getCoverPath(key)
            if (path.isBlank()) {
                evalJs("$callback(null, \"\")")
            } else {
                val file = File(path)
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    evalJs("$callback(null, \"$uri\")")
                } else {
                    evalJs("$callback(null, \"\")")
                }
            }
        }
    }

    fun deleteLocalFiles(pathsJson: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            val arr = JSONArray(pathsJson)
            val paths = mutableListOf<String>()
            for (i in 0 until arr.length()) paths.add(arr.getString(i))
            val ok = com.sakura.player.local.LocalFileManager.deleteItems(paths)
            if (ok) {
                for (p in paths) {
                    DownloadRecordManager.deleteRecord(p)
                    DownloadRecordManager.deleteRecordsUnder("$p/")
                }
            }
            evalJs("$callback(null, $ok)")
        }
    }

    fun renameLocalFile(path: String, newName: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            val parent = File(path).parentFile
            val newPath = if (parent != null) File(parent, newName).absolutePath else newName
            val ok = com.sakura.player.local.LocalFileManager.renameItem(path, newName)
            if (ok) {
                DownloadRecordManager.handlePathChange(path, newPath)
            }
            evalJs("$callback(null, $ok)")
        }
    }

    fun moveLocalFiles(pathsJson: String, targetDir: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            val arr = JSONArray(pathsJson)
            val paths = mutableListOf<String>()
            for (i in 0 until arr.length()) paths.add(arr.getString(i))
            val ok = com.sakura.player.local.LocalFileManager.moveItems(paths, targetDir)
            if (ok) {
                for (p in paths) {
                    val srcFile = File(p)
                    val newPath = File(targetDir, srcFile.name).absolutePath
                    DownloadRecordManager.handlePathChange(p, newPath)
                }
            }
            evalJs("$callback(null, $ok)")
        }
    }

    fun createLocalDir(parentPath: String, name: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            val ok = com.sakura.player.local.LocalFileManager.createDir(parentPath, name)
            evalJs("$callback(null, $ok)")
        }
    }

    // ==================== Download ====================

    fun addDownload(videoId: Long, title: String, epIndex: Int, epName: String,
                    m3u8Url: String, coverUrl: String = "") {
        val safeTitle = title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val saveDir = "${SettingsPrefs.downloadPath}/$safeTitle"
        Log.e("SakuraDownload", "addDownload called: vid=$videoId title=$title ep=$epIndex dir=$saveDir coverUrl=$coverUrl")
        // Download cover first so it's ready before the video finishes
        if (coverUrl.isNotBlank()) {
            com.sakura.player.local.LocalFileManager.downloadCover(videoId, coverUrl)
        }
        val did = DownloadManager.addSingle(videoId, title, epIndex, epName, m3u8Url, saveDir, coverUrl)
        Log.e("SakuraDownload", "Download task created: $did")
        val intent = Intent(ctx, AnimeService::class.java).apply {
            putExtra("action", "start")
        }
        ctx.startService(intent)
    }

    fun addBatchDownload(itemsJson: String, coverUrl: String = "") {
        val arr = JSONArray(itemsJson)
        val batch = mutableListOf<com.sakura.player.download.Quadruple<Long, String, Int, String>>()
        var saveDir = ""
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val title = obj.getString("title")
            val safeTitle = title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            saveDir = "${SettingsPrefs.downloadPath}/$safeTitle"
            batch.add(com.sakura.player.download.Quadruple(
                obj.getLong("videoId"), title, obj.getInt("epIndex"), obj.getString("epName")
            ))
        }
        if (batch.isNotEmpty()) {
            Log.e("SakuraDownload", "addBatchDownload: ${batch.size} items, dir=$saveDir")
            // Download cover upfront before starting downloads
            if (coverUrl.isNotBlank()) {
                val firstVid = batch[0].first // videoId of first item
                com.sakura.player.local.LocalFileManager.downloadCover(firstVid, coverUrl)
            }
            DownloadManager.addBatch(batch, saveDir, coverUrl)
        }
        val intent = Intent(ctx, AnimeService::class.java).apply {
            putExtra("action", "start")
        }
        ctx.startService(intent)
    }

    fun getDownloadStatus(callback: String) {
        scope.launch {
            val tasks = DownloadManager.getAllStatus()
            val json = JSONArray()
            tasks.forEach { json.put(taskToJson(it)) }
            evalJs("$callback(null, $json)")
        }
    }

    fun pauseDownload(id: String) = DownloadManager.pause(id)
    fun resumeDownload(id: String) = DownloadManager.resume(id)
    fun cancelDownload(id: String) = DownloadManager.cancel(id)
    fun retryDownload(id: String) = DownloadManager.retry(id)

    private fun taskToJson(t: DownloadTask): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("videoId", t.videoId)
        put("title", t.title)
        put("epIndex", t.epIndex)
        put("epName", t.epName)
        put("status", t.status)
        put("progress", t.progress)
        put("speed", t.speed)
        put("eta", t.eta)
        put("error", t.error)
    }

    // ==================== Download Records ====================

    fun getDownloadRecord(path: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            val record = DownloadRecordManager.getRecord(path)
            if (record != null) {
                val json = JSONObject().apply {
                    put("videoId", record.videoId)
                    put("title", record.title)
                    put("epIndex", record.epIndex)
                    put("localPath", record.localPath)
                    put("coverUrl", record.coverUrl)
                }
                evalJs("$callback(null, $json)")
            } else {
                evalJs("$callback(null, null)")
            }
        }
    }

    fun getDownloadedEps(videoId: Long, callback: String) {
        scope.launch(Dispatchers.IO) {
            val eps = DownloadRecordManager.dao.getDownloadedEpisodeIndices(videoId)
            val json = JSONArray(eps)
            evalJs("$callback(null, $json)")
        }
    }

    fun syncDownloadRecords(callback: String) {
        scope.launch(Dispatchers.IO) {
            val count = DownloadRecordManager.syncMissingRecords(SettingsPrefs.downloadPath,
                searchVideoId = { name -> searchVideoIdForDir(name) })
            evalJs("$callback(null, $count)")
        }
    }

    fun resetAndResyncRecords(callback: String) {
        scope.launch(Dispatchers.IO) {
            DownloadRecordManager.clearAllRecords()
            val count = DownloadRecordManager.syncMissingRecords(SettingsPrefs.downloadPath,
                searchVideoId = { name -> searchVideoIdForDir(name) })
            evalJs("$callback(null, $count)")
        }
    }

    /** Try to find the real website videoId for a directory name, and download cover */
    private suspend fun searchVideoIdForDir(dirName: String): Long {
        try {
            val keyword = dirName.take(15).trim()
            val results = AnimeScraper.search(domain, keyword)
            // Score: containment > prefix length > 0, require minimum 2-char overlap
            val best = results.maxByOrNull { r ->
                val contains = r.title.contains(dirName) || dirName.contains(r.title)
                if (contains) 1000 + r.title.commonPrefixWith(dirName).length
                else r.title.commonPrefixWith(dirName).length
            }
            if (best != null) {
                val score = if (best.title.contains(dirName) || dirName.contains(best.title)) 1000
                    else best.title.commonPrefixWith(dirName).length
                if (score >= 2) {
                    Log.e(TAG, "Matched '$dirName' -> videoId=${best.videoId} (${best.title}), coverUrl='${best.coverUrl}'")
                    var coverUrl = best.coverUrl
                    if (coverUrl.isBlank()) {
                        try {
                            val detail = AnimeScraper.getDetail(domain, best.videoId)
                            coverUrl = detail.coverUrl
                            Log.e(TAG, "Got cover from detail page: '${coverUrl}'")
                        } catch (e: Exception) {
                            Log.e(TAG, "Detail fetch failed: ${e.message}")
                        }
                    }
                    if (coverUrl.isNotBlank()) {
                        Log.e(TAG, "Downloading cover for videoId=${best.videoId}: $coverUrl")
                        com.sakura.player.local.LocalFileManager.downloadCover(best.videoId, coverUrl)
                    } else {
                        Log.e(TAG, "No coverUrl found for videoId=${best.videoId}")
                    }
                    return best.videoId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$dirName': ${e.message}")
        }
        return dirName.hashCode().toLong()
    }

    /** Extract the real website videoId from a stored play page URL (e.g. .../vod/play/id/76284/sid/1/nid/3.html) */
    private fun extractVideoIdFromUrl(url: String): Long? {
        if (url.isBlank()) return null
        val match = Regex("""/vod/play/id/(\d+)""").find(url)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }

    fun redownloadLocal(pathsJson: String, callback: String) {
        scope.launch(Dispatchers.IO) {
            val arr = JSONArray(pathsJson)
            val allRecords = mutableListOf<DownloadRecordEntity>()

            for (i in 0 until arr.length()) {
                val path = arr.getString(i)
                if (path.isBlank()) continue
                val f = File(path)
                if (f.isDirectory) {
                    allRecords.addAll(DownloadRecordManager.getRecordsUnder("$path/"))
                } else {
                    DownloadRecordManager.getRecord(path)?.let { allRecords.add(it) }
                }
            }

            for (record in allRecords) {
                // Resolve the real website videoId. Priority:
                //   1. The stored play page URL (sourceUrl) — authoritative, carries the real ID
                //   2. The stored videoId when it is a plausible real website ID
                //   3. A fresh website search (records synced from disk have no sourceUrl)
                val videoIdFromUrl = record.sourceUrl.let { extractVideoIdFromUrl(it) }
                val realVideoId = when {
                    videoIdFromUrl != null -> videoIdFromUrl
                    record.videoId in 1L..99999999L && record.videoId != 0L -> record.videoId
                    else -> searchVideoIdForDir(record.title)
                }
                Log.e(TAG, "redownload: ${record.title} -> videoId=$realVideoId (was ${record.videoId})" +
                        (if (videoIdFromUrl != null) ", from sourceUrl=${record.sourceUrl}" else ""))
                // Delete DB record first so dedup check doesn't block re-download
                DownloadRecordManager.deleteRecord(record.localPath)
                // Delete old file (best-effort)
                File(record.localPath).delete()
                val dir = File(record.localPath).parent ?: SettingsPrefs.downloadPath
                DownloadManager.addSingle(
                    videoId = realVideoId,
                    title = record.title,
                    epIndex = record.epIndex,
                    epName = "第${record.epIndex}集",
                    m3u8Url = "",
                    saveDir = dir,
                    coverUrl = record.coverUrl,
                    playPageUrl = record.sourceUrl
                )
            }

            val intent = Intent(ctx, AnimeService::class.java).apply {
                putExtra("action", "start")
            }
            ctx.startService(intent)

            evalJs("$callback(null, ${allRecords.size})")
        }
    }

    // ==================== Follow ====================

    fun addFollow(videoId: Long, title: String, coverUrl: String, totalEps: Int) {
        scope.launch {
            FollowManager.addFollow(videoId, title, coverUrl, totalEps)
        }
    }

    fun removeFollow(videoId: Long) {
        scope.launch {
            FollowManager.removeFollow(videoId)
        }
    }

    fun getFollows(callback: String) {
        scope.launch {
            val follows = FollowManager.getFollows()
            val json = JSONArray()
            follows.forEach { f ->
                json.put(JSONObject().apply {
                    put("videoId", f.videoId)
                    put("title", f.title)
                    put("coverUrl", f.coverUrl)
                    put("status", f.status)
                    put("totalEps", f.totalEps)
                    put("watchedEps", f.watchedEps)
                    put("hasUpdate", f.hasUpdate)
                })
            }
            evalJs("$callback(null, $json)")
        }
    }

    fun checkFollowUpdates() {
        scope.launch {
            FollowManager.checkUpdates(domain) { videoId, newEps ->
                // Notify frontend via callback
                evalJs("if(window.onFollowUpdate)window.onFollowUpdate($videoId,$newEps)")
            }
        }
    }

    fun markWatched(followId: Long, epIndex: Int) {
        scope.launch {
            FollowManager.markWatched(followId, epIndex)
        }
    }

    // ==================== Settings ====================

    fun getSettings(callback: String) {
        scope.launch {
            val json = JSONObject().apply {
                put("downloadPath", SettingsPrefs.downloadPath)
                put("activeDomain", domain)
            }
            evalJs("$callback(null, $json)")
        }
    }

    fun setDownloadPath(path: String) {
        SettingsPrefs.downloadPath = path
    }

    fun getDownloadsPath(): String = SettingsPrefs.downloadPath

    fun refreshDomain() {
        scope.launch {
            domain = DomainFinder.findDomain()
            if (domain.isNotEmpty()) {
                SettingsPrefs.activeDomain = domain
                DownloadManager.setDomain(domain)
            }
            evalJs("if(window.onDomainRefresh)window.onDomainRefresh('$domain')")
        }
    }

    // ==================== Discover ====================

    fun getDiscover(category: String, page: Int, callback: String) {
        scope.launch {
            try {
                val results = if (category == "recommend" || category.isBlank()) {
                    AnimeScraper.getHomeRecommend(domain)
                } else {
                    AnimeScraper.getCategoryList(domain, category, page)
                }
                val json = JSONArray()
                results.forEach { json.put(it.toJson()) }
                evalJs("$callback(null, $json)")
            } catch (e: Exception) {
                evalJs("$callback('${e.message?.replace("'", "\\'")}', null)")
            }
        }
    }

    // ==================== Helpers ====================

    private fun AnimeResult.toJson(): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("coverUrl", coverUrl)
        put("episodeInfo", episodeInfo)
        put("tags", JSONArray(tags))
        put("isLocal", isLocal)
    }

    private fun evalJs(js: String) {
        jsEvaluator?.invoke(js)
    }
}
