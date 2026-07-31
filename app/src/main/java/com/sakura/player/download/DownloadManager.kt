package com.sakura.player.download

import android.content.Context
import android.util.Log
import com.sakura.player.scraper.VideoExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class DownloadTask(
    val id: String,
    val videoId: Long,
    val title: String,
    val epIndex: Int,
    val epName: String,
    val m3u8Url: String = "",
    val saveDir: String,
    val coverUrl: String = "",
    var status: String = "queued", // queued, downloading, paused, completed, failed
    var progress: Int = 0,
    var totalSize: Long = 0,
    var downloaded: Long = 0,
    var speed: String = "",
    var eta: String = "",
    var error: String = "",
    var job: Job? = null
)

object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val RACE_SAMPLE_SECONDS = 5L

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val semaphore = Semaphore(2) // max 2 concurrent downloads
    // Independent scope — NOT tied to Activity lifecycle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var domain: String
    private var callback: ((DownloadTask) -> Unit)? = null

    fun init(activeDomain: String) {
        domain = activeDomain
    }

    fun setDomain(newDomain: String) { domain = newDomain }

    fun setCallback(cb: (DownloadTask) -> Unit) { callback = cb }

    fun addSingle(
        videoId: Long, title: String, epIndex: Int, epName: String,
        m3u8Url: String, saveDir: String, coverUrl: String = ""
    ): String {
        val id = "${videoId}_$epIndex"
        // Clean up ALL leftover temp files (any .temp_* dir in save dir)
        val saveDirFile = File(saveDir)
        if (saveDirFile.isDirectory) {
            val tempDirs = saveDirFile.listFiles()?.filter {
                it.isDirectory && it.name.startsWith(".temp_")
            }
            if (tempDirs != null) {
                tempDirs.forEach { dir ->
                    if (dir.deleteRecursively()) {
                        Log.e(TAG, "Pre-download cleanup: removed ${dir.name}")
                    } else {
                        Log.e(TAG, "Pre-download cleanup: FAILED to remove ${dir.name}")
                    }
                }
            } else {
                Log.e(TAG, "Pre-download cleanup: listFiles() returned null for $saveDir")
            }
        }

        if (tasks.containsKey(id)) return id
        val task = DownloadTask(
            id = id, videoId = videoId, title = title,
            epIndex = epIndex, epName = epName,
            m3u8Url = m3u8Url, saveDir = saveDir, coverUrl = coverUrl
        )
        tasks[id] = task
        task.job = scope.launch(Dispatchers.IO) { startDownload(task) }
        return id
    }

    fun addBatch(
        items: List<Quadruple<Long, String, Int, String>>,
        saveDir: String, coverUrl: String = ""
    ): List<String> {
        return items.map { (vid, title, ep, epName) ->
            addSingle(vid, title, ep, epName, "", saveDir, coverUrl)
        }
    }

    fun pause(id: String) {
        tasks[id]?.let {
            it.status = "paused"
            it.job?.cancel()
        }
    }

    fun resume(id: String) {
        tasks[id]?.let { task ->
            task.status = "queued"
            task.job = scope.launch(Dispatchers.IO) { startDownload(task) }
        }
    }

    fun cancel(id: String) {
        tasks[id]?.let { task ->
            task.job?.cancel()
            // Clean up partial files
            val dir = File(task.saveDir)
            if (dir.exists()) {
                dir.listFiles()?.filter { it.name.contains("${task.videoId}_${task.epIndex}") }
                    ?.forEach { it.delete() }
            }
            tasks.remove(id)
        }
    }

    fun getAllStatus(): List<DownloadTask> = tasks.values.toList()

    private suspend fun startDownload(task: DownloadTask) {
        if (task.status == "completed" || task.status == "cancelled") return
        Log.e(TAG, "startDownload: waiting for slot... ${task.title} ep${task.epIndex}")

        try {
            semaphore.withPermit {
                // Dedup: skip if already downloaded
                try {
                    val dao = com.sakura.player.download.DownloadRecordManager.dao
                    if (task.videoId != 0L && dao.countByVideoAndEp(task.videoId, task.epIndex) > 0) {
                        Log.e(TAG, "Already downloaded (by ID): ${task.title} ep${task.epIndex}, skipping")
                        task.status = "completed"; task.progress = 100; notifyCallback(task)
                        return@withPermit
                    }
                    val safeTitle = task.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    val expectedDir = "${com.sakura.player.data.SettingsPrefs.downloadPath}/$safeTitle"
                    val dirFile = java.io.File(expectedDir)
                    if (dirFile.exists() && dirFile.isDirectory) {
                        val matching = dirFile.listFiles()?.any {
                            it.name.endsWith(".mp4") && it.name.contains("第${task.epIndex}集")
                        } ?: false
                        if (matching) {
                            Log.e(TAG, "Already downloaded (by path): ${task.title} ep${task.epIndex}, skipping")
                            task.status = "completed"; task.progress = 100; notifyCallback(task)
                            return@withPermit
                        }
                    }
                } catch (_: Exception) {}

                task.status = "downloading"
                task.progress = 0
                notifyCallback(task)
                Log.e(TAG, "Slot acquired, domain=$domain")

                if (task.m3u8Url.isNotEmpty()) {
                    // Pre-provided URL (e.g., redownload) — single CDN with all threads
                    val playPageUrl = "$domain/index.php/vod/play/id/${task.videoId}/sid/1/nid/${task.epIndex}.html"
                    TsDownloader.download(task.m3u8Url, task, playPageUrl, { notifyCallback(it) },
                        threadCount = TsDownloader.CONCURRENT_THREADS)
                } else {
                    // Extract m3u8 URLs from ALL available sids
                    val allCdnUrls = mutableListOf<Pair<String, Int>>()
                    for (sid in 1..4) {
                        try {
                            val vu = VideoExtractor.extractFromPlayPage(domain, task.videoId, task.epIndex, sid)
                            if (vu.m3u8Url.isNotEmpty()) {
                                allCdnUrls.add(vu.m3u8Url to sid)
                                Log.e(TAG, "CDN sid=$sid: ${vu.m3u8Url.take(60)}...")
                            }
                        } catch (_: Exception) { Log.e(TAG, "CDN sid=$sid unavailable") }
                    }

                    if (allCdnUrls.isEmpty()) throw Exception("无法获取视频地址 — 所有源均不可用")

                    if (allCdnUrls.size == 1) {
                        val (url, sid) = allCdnUrls[0]
                        val playPageUrl = "$domain/index.php/vod/play/id/${task.videoId}/sid/$sid/nid/${task.epIndex}.html"
                        TsDownloader.download(url, task, playPageUrl, { notifyCallback(it) },
                            threadCount = TsDownloader.CONCURRENT_THREADS)
                    } else {
                        // Multi-CDN: race all, eliminate slow ones, converge on fastest
                        raceAndConverge(task, allCdnUrls)
                    }
                }

                if (task.status != "failed") {
                    task.status = "completed"
                    task.progress = 100
                    Log.e(TAG, "Download done: ${task.title} ep${task.epIndex}")
                    try {
                        val mp4Path = "${task.saveDir}/${task.title}_第${task.epIndex}集.mp4"
                        DownloadRecordManager.upsertRecord(
                            com.sakura.player.data.DownloadRecordEntity(
                                localPath = mp4Path,
                                videoId = task.videoId,
                                title = task.title,
                                epIndex = task.epIndex,
                                coverUrl = task.coverUrl
                            )
                        )
                        if (task.coverUrl.isNotBlank()) {
                            com.sakura.player.local.LocalFileManager.downloadCover(task.videoId, task.coverUrl)
                            Log.e(TAG, "Cover downloaded for ${task.title}")
                        }
                        Log.e(TAG, "Download record saved: $mp4Path")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save download record", e)
                    }
                }
            }
        } catch (e: CancellationException) {
            task.status = "paused"
            Log.e(TAG, "Download paused: ${task.title}")
        } catch (e: Exception) {
            task.status = "failed"
            task.error = e.message ?: "下载失败"
            Log.e(TAG, "Download failed: ${task.title}", e)
        } finally {
            // Catch-all: remove any leftover temp dirs in the save directory
            try {
                val prefix = ".temp_${task.videoId}_${task.epIndex}"
                File(task.saveDir).listFiles()?.filter {
                    it.isDirectory && it.name.startsWith(prefix)
                }?.forEach {
                    if (it.deleteRecursively()) {
                        Log.e(TAG, "Post-download cleanup: removed ${it.name}")
                    } else {
                        Log.e(TAG, "Post-download cleanup: FAILED to remove ${it.name}")
                    }
                }
            } catch (_: Exception) {}
            notifyCallback(task)
            if (task.status == "completed") {
                delay(2000)
                tasks.remove(task.id)
            }
        }
    }

    /**
     * Multi-CDN race: start downloading from all CDNs in parallel with equal threads,
     * sample for [RACE_SAMPLE_SECONDS] seconds, then converge all threads onto the fastest CDN.
     * The losing CDNs' temp files are cleaned up; the winner resumes with full thread pool.
     */
    private suspend fun raceAndConverge(
        task: DownloadTask,
        cdnUrls: List<Pair<String, Int>>
    ) = withContext(Dispatchers.IO) {
        val numCdns = cdnUrls.size
        val threadsPerCdn = maxOf(4, TsDownloader.CONCURRENT_THREADS / numCdns)
        Log.e(TAG, "Multi-CDN race: $numCdns CDNs, $threadsPerCdn threads each, sample ${RACE_SAMPLE_SECONDS}s")

        // Track bytes downloaded per CDN for speed comparison
        val cdnProgress = ConcurrentHashMap<Int, Long>() // sid -> bytesDownloaded
        val raceJobs = mutableListOf<Job>()

        // Phase 1: Launch all CDNs in parallel
        for ((url, sid) in cdnUrls) {
            val raceTask = task.copy(
                id = "${task.id}_race_$sid",
                status = "downloading",
                progress = 0
            )
            val playPageUrl = "$domain/index.php/vod/play/id/${task.videoId}/sid/$sid/nid/${task.epIndex}.html"
            val job = launch(Dispatchers.IO) {
                try {
                    TsDownloader.download(url, raceTask, playPageUrl,
                        onProgress = { t ->
                            cdnProgress[sid] = t.downloaded
                            // Report progress from the leading CDN to the UI
                            if (t.progress > task.progress) {
                                task.progress = t.progress
                                task.speed = t.speed
                                notifyCallback(task)
                            }
                        },
                        threadCount = threadsPerCdn,
                        tempDirSuffix = "_sid$sid"
                    )
                    // If download completed during race, mark as winner
                    if (raceTask.status == "completed") {
                        cdnProgress[sid] = Long.MAX_VALUE
                    }
                } catch (_: CancellationException) {
                    // Expected for losers
                }
            }
            raceJobs.add(job)
        }

        // Wait for sampling period
        delay(RACE_SAMPLE_SECONDS * 1000)

        // Determine winner: most segments downloaded
        val winner = cdnProgress.maxByOrNull { it.value }?.key
            ?: cdnUrls.first().second // fallback to first if no progress yet

        val winnerEntry = cdnUrls.find { it.second == winner }!!
        Log.e(TAG, "Race winner: sid=$winner (${cdnProgress[winner]} bytes), killing ${numCdns - 1} losers")

        // Cancel losing CDNs and wait for them to fully stop
        raceJobs.forEach { it.cancel() }
        raceJobs.forEach { it.join() }

        // Clean up loser temp dirs with retry for stubborn file handles
        for ((_, sid) in cdnUrls) {
            if (sid != winner) {
                val loserTempDir = File(task.saveDir, ".temp_${task.videoId}_${task.epIndex}_sid$sid")
                var deleted = false
                repeat(3) {
                    if (!loserTempDir.exists() || loserTempDir.deleteRecursively()) {
                        deleted = true
                        return@repeat
                    }
                    delay(200)
                }
                if (deleted) {
                    Log.e(TAG, "Cleaned up loser temp dir sid=$sid")
                } else {
                    Log.e(TAG, "FAILED to clean up loser temp dir sid=$sid — may be orphaned")
                }
            }
        }

        // Rename winner's temp dir to standard name so final merge picks it up
        val winnerTempDir = File(task.saveDir, ".temp_${task.videoId}_${task.epIndex}_sid$winner")
        val standardTempDir = File(task.saveDir, ".temp_${task.videoId}_${task.epIndex}")
        if (winnerTempDir.exists() && !standardTempDir.exists()) {
            val renamed = winnerTempDir.renameTo(standardTempDir)
            if (!renamed) {
                Log.e(TAG, "Failed to rename winner temp dir — will copy instead")
                try {
                    winnerTempDir.copyRecursively(standardTempDir, overwrite = true)
                    winnerTempDir.deleteRecursively()
                    Log.e(TAG, "Winner temp dir copied successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy winner temp dir — orphaned", e)
                }
            }
        }

        // Phase 2: Winner continues with full thread pool (resumes from downloaded segments)
        task.status = "downloading"
        Log.e(TAG, "Phase 2: sid=$winner with ${TsDownloader.CONCURRENT_THREADS} threads")
        val (url, _) = winnerEntry
        val playPageUrl = "$domain/index.php/vod/play/id/${task.videoId}/sid/$winner/nid/${task.epIndex}.html"
        TsDownloader.download(url, task, playPageUrl, { notifyCallback(it) },
            threadCount = TsDownloader.CONCURRENT_THREADS)
    }

    private fun notifyCallback(task: DownloadTask) {
        callback?.let { cb ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(task) }
        }
    }
}

// Utility class for batch downloads
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
