package com.sakura.player.download

import android.util.Log
import com.sakura.player.network.HttpClient
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TsDownloader {
    private const val TAG = "TsDownloader"
    const val CONCURRENT_THREADS = 16

    data class M3u8Info(
        val segments: List<String>,
        val keyUrl: String?,
        val iv: ByteArray?
    )

    /**
     * Whether a temp dir should be deleted after a download attempt ends.
     *
     * Paused downloads must keep their partial segments so resume() can continue
     * where it left off. Every other terminal state (completed, failed, retry via
     * queued, cancel) should clean up the temp dir.
     */
    internal fun shouldCleanupTempDir(status: String): Boolean = status != "paused"

    /**
     * Probe a CDN's speed by downloading the first 64KB of the first TS segment.
     * Returns speed in MB/s, or 0.0 if unreachable.
     */
    suspend fun probeSpeed(m3u8Url: String, referer: String = "https://yinghua14.com/"): Double {
        return withContext(Dispatchers.IO) {
            try {
                val probe = CdnProber.probe(m3u8Url, referer)
                if (probe.strategy == CdnProber.DownloadStrategy.UNREACHABLE) return@withContext 0.0

                val client = when (probe.strategy) {
                    CdnProber.DownloadStrategy.TLS_1_2 -> HttpClient.client
                    CdnProber.DownloadStrategy.TLS_1_3 -> HttpClient.clientTls13
                    CdnProber.DownloadStrategy.HTTP_ONLY -> HttpClient.clientHttp
                    else -> HttpClient.client
                }

                val info = parseM3u8WithKey(probe.workingUrl, referer, client)
                if (info.segments.isEmpty()) return@withContext 0.0

                val firstSeg = info.segments[0]
                val startNs = System.nanoTime()
                val req = Request.Builder().url(firstSeg)
                    .header("Range", "bytes=0-65535") // 64KB probe
                HttpClient.browserHeaders(referer).forEach { (k, v) -> req.header(k, v) }

                client.newCall(req.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext 0.0
                    val bytes = resp.body?.bytes()?.size ?: 0
                    val elapsed = (System.nanoTime() - startNs) / 1_000_000_000.0
                    if (elapsed > 0 && bytes > 0) (bytes / 1_000_000.0) / elapsed else 0.0
                }
            } catch (_: Exception) { 0.0 }
        }
    }

    suspend fun download(
        m3u8Url: String,
        task: DownloadTask,
        referer: String = "https://yinghua14.com/",
        onProgress: (DownloadTask) -> Unit,
        threadCount: Int = CONCURRENT_THREADS,
        tempDirSuffix: String = ""
    ) = withContext(Dispatchers.IO) {

        // Step 1: Probe CDN to determine best connection strategy
        val probe = CdnProber.probe(m3u8Url, referer)
        Log.e(TAG, "CDN probe: strategy=${probe.strategy}, diagnosis=${probe.diagnostics}")

        if (probe.strategy == CdnProber.DownloadStrategy.UNREACHABLE) {
            task.status = "failed"
            task.error = "CDN 不可达: ${probe.diagnostics}"
            return@withContext
        }

        // Step 2: Select client based on strategy
        val downloadClient = when (probe.strategy) {
            CdnProber.DownloadStrategy.TLS_1_2 -> HttpClient.client
            CdnProber.DownloadStrategy.TLS_1_3 -> HttpClient.clientTls13
            CdnProber.DownloadStrategy.HTTP_ONLY -> HttpClient.clientHttp
            else -> HttpClient.client
        }
        Log.e(TAG, "Using client: ${downloadClient.hashCode()} for strategy ${probe.strategy}")

        // Step 3: Parse m3u8 using the determined client
        val info = try {
            parseM3u8WithKey(probe.workingUrl, referer, downloadClient)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse m3u8", e)
            task.status = "failed"
            task.error = e.message ?: "解析视频失败"
            return@withContext
        }
        Log.e(TAG, "Parsed ${info.segments.size} segments, encrypted=${info.keyUrl != null}")
        if (info.segments.isEmpty()) {
            task.status = "failed"
            task.error = "m3u8中未找到视频分片"
            return@withContext
        }

        var aesKey: ByteArray? = null
        if (info.keyUrl != null) {
            try {
                val keyUrl = when {
                    info.keyUrl.startsWith("http") -> info.keyUrl
                    info.keyUrl.startsWith("/") -> {
                        val uri = java.net.URI(m3u8Url)
                        "${uri.scheme}://${uri.host}${info.keyUrl}"
                    }
                    else -> m3u8Url.substringBeforeLast("/") + "/" + info.keyUrl.trimStart('/')
                }
                Log.e(TAG, "Downloading AES key from $keyUrl")
                val keyReqBuilder = Request.Builder().url(keyUrl).get()
                HttpClient.browserHeaders(referer).forEach { (k, v) -> keyReqBuilder.header(k, v) }
                aesKey = downloadClient.newCall(keyReqBuilder.build()).execute().use { it.body?.bytes() }
                Log.e(TAG, "AES key: ${aesKey?.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get AES key", e)
                task.status = "failed"
                task.error = "无法获取解密密钥"
                return@withContext
            }
        }

        val tempDir = File(task.saveDir, ".temp_${task.videoId}_${task.epIndex}$tempDirSuffix")
        tempDir.mkdirs()

        try {
            val startTime = System.currentTimeMillis()
            val completed = AtomicInteger(0)
            val total = info.segments.size
            Log.e(TAG, "Downloading $total segments ($threadCount threads)")

            // Count already-downloaded segments for resume
            val existingCount = (0 until total).count { i ->
                val f = File(tempDir, "${i.toString().padStart(6, '0')}.ts")
                f.exists() && f.length() > 0
            }
            if (existingCount > 0) {
                completed.set(existingCount)
                task.progress = ((existingCount * 100) / total).coerceIn(1, 100)
                onProgress(task)
                Log.e(TAG, "Resuming: $existingCount/$total segments already downloaded (${task.progress}%)")
            }

            coroutineScope {
                // Work-stealing: threads compete for next segment via atomic counter
                // avoids idle threads when segment sizes are uneven
                val nextIdx = java.util.concurrent.atomic.AtomicInteger(0)
                (0 until threadCount).map {
                    launch(Dispatchers.IO) {
                        while (true) {
                            ensureActive()  // Cooperative cancellation check
                            val i = nextIdx.getAndIncrement()
                            if (i >= total) break
                            if (task.status != "downloading") return@launch

                            val segFile = File(tempDir, "${i.toString().padStart(6, '0')}.ts")

                            // Skip already-downloaded segments (already counted in completed)
                            if (segFile.exists() && segFile.length() > 0) continue

                            var success = false
                            var retries = 3

                            while (retries > 0 && !success) {
                                try {
                                    ensureActive()  // Check for cancellation before each HTTP request
                                    val segReqBuilder = Request.Builder().url(info.segments[i]).get()
                                    HttpClient.browserHeaders(referer).forEach { (k, v) -> segReqBuilder.header(k, v) }
                                    downloadClient.newCall(segReqBuilder.build()).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            val raw = resp.body?.bytes() ?: return@use
                                            val out = if (aesKey != null) {
                                                val ivBytes = info.iv ?: ByteArray(16).apply {
                                                    for (j in 0..15) this[j] = ((i shr ((15 - j % 8) * 8)) and 0xFF).toByte()
                                                }
                                                decryptAes128(raw, aesKey, ivBytes)
                                            } else raw
                                            FileOutputStream(segFile).use { it.write(out) }
                                            success = true
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    retries--
                                    if (retries > 0) delay(500)
                                }
                            }

                            if (!success) continue  // skip failed segment, don't count it

                            val done = completed.incrementAndGet()
                            task.progress = ((done * 100) / total).coerceIn(1, 100)
                            // Update downloaded bytes with actual segment size for CDN race tracking
                            task.downloaded += segFile.length()
                            // Track speed every ~20 segments
                            if (done % 20 == 0) {
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                                if (elapsed > 0) task.speed = "${(task.downloaded / elapsed / 1024).toInt()}KB/s"
                            }
                            if (done % 50 == 0 || done == 1) {
                                Log.e(TAG, "Progress: $done/$total (${task.progress}%)")
                            }
                            onProgress(task)
                        }
                    }
                }.joinAll()
            }

            if (task.status != "downloading") return@withContext

            // Verify segments: count how many have actual content
            var successCount = 0
            for (i in 0 until total) {
                val seg = File(tempDir, "${i.toString().padStart(6, '0')}.ts")
                if (seg.exists() && seg.length() > 0) successCount++
            }
            Log.e(TAG, "Segments with content: $successCount/$total (tempDir=$tempDir)")
            if (successCount == 0) {
                // Diagnostic: check if directory exists and list some files
                val dirFiles = tempDir.listFiles()?.take(5)?.joinToString { "${it.name}(${it.length()}b)" } ?: "null"
                Log.e(TAG, "Temp dir exists=${tempDir.exists()}, files sample: $dirFiles")
                task.status = "failed"
                task.error = "所有分片下载失败，可能是视频源无法访问"
                return@withContext
            }
            if (successCount < total * 0.8) {
                task.status = "failed"
                task.error = "分片下载不完整 (${successCount}/${total})，视频源可能不稳定"
                return@withContext
            }

            Log.e(TAG, "Merging $total segments...")
            mergeSegments(tempDir, task, total)
            Log.e(TAG, "Download done: ${task.title} ep${task.epIndex}")
        } finally {
            // Clean up temp dir unless paused. Pausing cancels this coroutine, which
            // propagates CancellationException through here; the partial segments must
            // survive so resume() can pick up where it left off.
            if (shouldCleanupTempDir(task.status)) {
                if (tempDir.exists()) {
                    tempDir.deleteRecursively()
                    Log.e(TAG, "Temp dir cleaned up: $tempDir")
                }
            } else {
                Log.e(TAG, "Paused — preserving temp dir for resume: $tempDir")
            }
        }
    }

    class ParseError(msg: String) : Exception(msg)

    private suspend fun parseM3u8WithKey(
        url: String,
        referer: String,
        client: OkHttpClient
    ): M3u8Info = withContext(Dispatchers.IO) {
        try {
            Log.e(TAG, "Fetching m3u8: $url")
            val headers = HttpClient.browserHeaders(referer)
            val reqBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val resp = client.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) throw ParseError("HTTP ${resp.code}")
            val content = resp.body?.string() ?: ""
            resp.close()
            if (content.isBlank()) throw ParseError("m3u8响应为空")
            val baseUrl = url.substringBeforeLast("/") + "/"

            // Master playlist -> pick highest bandwidth
            Log.e(TAG, "M3U8 content first 200 chars: ${content.take(200)}")
            if (content.contains("#EXT-X-STREAM-INF")) {
                Log.e(TAG, "Detected master playlist, parsing variants...")
                val variants = mutableListOf<Pair<Int, String>>()
                val lines = content.lines()
                for (i in lines.indices) {
                    if (lines[i].contains("#EXT-X-STREAM-INF")) {
                        val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        Log.e(TAG, "Found variant line: ${lines[i]}, bw=$bw")
                        if (i + 1 < lines.size && lines[i+1].isNotBlank() && !lines[i+1].startsWith("#")) {
                            variants.add(bw to lines[i+1])
                            Log.e(TAG, "Added variant: $bw -> ${lines[i+1]}")
                        }
                    }
                }
                if (variants.isNotEmpty()) {
                    variants.sortByDescending { it.first }
                    val sub = variants.first().second
                    val full = when {
                        sub.startsWith("http") -> sub
                        sub.startsWith("/") -> {
                            val uri = java.net.URI(url)
                            "${uri.scheme}://${uri.host}$sub"
                        }
                        else -> baseUrl + sub
                    }
                    Log.e(TAG, "Recursing into variant: $full")
                    return@withContext parseM3u8WithKey(full, referer, client)
                }
                Log.e(TAG, "No variants found, falling through to media parsing")
            }

            // Media playlist (pure parser handles ad-skipping via CUE-OUT/CUE-IN,
            // DATERANGE markers and returns resolved segment URLs)
            return@withContext parseM3u8Media(content, url)
        } catch (e: Exception) {
            val msg = if (e is ParseError) e.message else (e.message ?: "未知错误")
            Log.e(TAG, "parseM3u8 failed: $msg", e)
            throw Exception("解析视频失败: $msg")
        }
    }

    /** Structural fallback: a DISCONTINUITY block whose median duration is below this is
     *  considered an ad (content on yinghua mirrors is uniformly ~3.75s; ads are ~1.3-3.3s). */
    private const val AD_BLOCK_MAX_MEDIAN_DURATION = 3.0

    private data class SegmentEntry(val url: String, val duration: Double?)

    /** Path directory (up to the last '/') of an absolute segment URL, or null if unparseable. */
    private fun segmentPathDir(segmentUrl: String): String? {
        return try {
            val path = java.net.URI(segmentUrl).path ?: return null
            path.substringBeforeLast('/').takeIf { it.isNotBlank() && it != "/" }
        } catch (_: Exception) { null }
    }

    /**
     * Parse an HLS media playlist, skipping advertisement segments.
     *
     * Ads are detected two ways:
     *  1. Standard HLS ad markers:
     *     - #EXT-X-CUE-OUT / #EXT-X-CUE-IN (classic SCTE-35 splice markers)
     *     - #EXT-X-DATERANGE with an ad/interstitial CLASS or SCTE35-OUT / SCTE35-IN
     *  2. Structural fallback for CDNs that inject ads WITHOUT marker tags (confirmed on
     *     yinghua14.com mirrors, where ad segments come from a wholly separate stream path,
     *     e.g. /20260727/<adId>/10137kb/hls/, and have short, irregular durations). A
     *     #EXT-X-DISCONTINUITY-bounded block is treated as an ad block when BOTH:
     *       - none of its segment URLs share the media playlist's content directory, AND
     *       - the block's median segment duration is below [AD_BLOCK_MAX_MEDIAN_DURATION].
     *     The "at least one block matches the media directory" guard prevents a nonstandard
     *     playlist URL from causing every block to be misclassified as an ad.
     *
     * While inside an ad block, segment URIs are NOT collected so ads never get downloaded
     * or merged into the final MP4. This is a pure function (no network or Android
     * dependencies) so it can be unit tested on the JVM.
     */
    internal fun parseM3u8Media(content: String, url: String): M3u8Info {
        val baseUrl = url.substringBeforeLast("/") + "/"
        val mediaUri = try { java.net.URI(url) } catch (_: Exception) { null }
        val contentDir = mediaUri?.path?.substringBeforeLast('/')?.takeIf { it.isNotBlank() && it != "/" }

        var keyUrl: String? = null
        var iv: ByteArray? = null
        var currentDuration: Double? = null
        var inAd = false
        var adSegmentsSkippedByMarker = 0

        // Blocks are separated by #EXT-X-DISCONTINUITY; used by the structural fallback.
        val blocks = mutableListOf<MutableList<SegmentEntry>>()
        blocks.add(mutableListOf())
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY") -> {
                    Regex("URI=\"([^\"]+)\"").find(trimmed)?.let { keyUrl = it.groupValues[1] }
                    Regex("IV=0x([0-9a-fA-F]+)").find(trimmed)?.let {
                        val hex = it.groupValues[1]
                        // IV must be 32 hex chars (16 bytes); guard against short/malformed values
                        if (hex.length >= 32) {
                            iv = ByteArray(16) { j -> hex.substring(j * 2, j * 2 + 2).toInt(16).toByte() }
                        }
                    }
                }
                trimmed.startsWith("#EXTINF") -> {
                    currentDuration = Regex("#EXTINF:\\s*([\\d.]+)").find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
                }
                trimmed.startsWith("#EXT-X-CUE-OUT") -> {
                    inAd = true
                    Log.e(TAG, "Ad marker: CUE-OUT (ad starts)")
                }
                trimmed.startsWith("#EXT-X-CUE-IN") -> {
                    inAd = false
                    Log.e(TAG, "Ad marker: CUE-IN (ad ends)")
                }
                trimmed.startsWith("#EXT-X-DATERANGE") -> {
                    val lower = trimmed.lowercase()
                    if (lower.contains("scte35-in")) {
                        inAd = false
                        Log.e(TAG, "Ad marker: DATERANGE SCTE35-IN (ad ends)")
                    } else {
                        val cls = Regex("CLASS=\"([^\"]*)\"").find(trimmed)?.groupValues?.get(1)?.lowercase() ?: ""
                        val isAdClass = cls.contains("ad") || cls.contains("interstitial") || cls.contains("advertisement")
                        if (isAdClass || lower.contains("scte35-out")) {
                            inAd = true
                            Log.e(TAG, "Ad marker: DATERANGE ad class/SCTE35-OUT (ad starts)")
                        }
                    }
                }
                trimmed.startsWith("#EXT-X-DISCONTINUITY") -> {
                    blocks.add(mutableListOf())
                }
                trimmed.startsWith("#") -> {
                    // Ignore all other tags
                }
                trimmed.isNotBlank() -> {
                    if (inAd) {
                        adSegmentsSkippedByMarker++
                        currentDuration = null
                        continue
                    }
                    val segUrl = when {
                        trimmed.startsWith("http") -> trimmed
                        trimmed.startsWith("/") -> {
                            val u = mediaUri
                            if (u != null) "${u.scheme}://${u.host}$trimmed" else baseUrl + trimmed.trimStart('/')
                        }
                        else -> baseUrl + trimmed
                    }
                    blocks.last().add(SegmentEntry(segUrl, currentDuration))
                    currentDuration = null
                }
            }
        }

        // Structural fallback: drop DISCONTINUITY-bounded blocks that come from a different
        // stream directory AND have abnormally short segments (the confirmed ad signature).
        val contentDirMatches = blocks.count { block -> block.any { segmentPathDir(it.url) == contentDir } }
        val segments = mutableListOf<String>()
        var adSegmentsSkippedByBlock = 0
        if (contentDir != null && contentDirMatches > 0) {
            for (block in blocks) {
                val noSegmentMatchesContent = block.none { segmentPathDir(it.url) == contentDir }
                val medianDuration = block.mapNotNull { it.duration }.let { durs ->
                    if (durs.isEmpty()) null else durs.sorted().let { s ->
                        val mid = s.size / 2
                        if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
                    }
                }
                if (noSegmentMatchesContent && medianDuration != null && medianDuration < AD_BLOCK_MAX_MEDIAN_DURATION) {
                    adSegmentsSkippedByBlock += block.size
                    Log.e(TAG, "Ad block skipped via content-path + duration heuristic (n=${block.size})")
                } else {
                    segments.addAll(block.map { it.url })
                }
            }
        } else {
            for (block in blocks) segments.addAll(block.map { it.url })
        }

        if (adSegmentsSkippedByMarker > 0) {
            Log.e(TAG, "Skipped $adSegmentsSkippedByMarker ad segment(s) via CUE/DATERANGE markers")
        }
        if (adSegmentsSkippedByBlock > 0) {
            Log.e(TAG, "Skipped $adSegmentsSkippedByBlock ad segment(s) via content-path/duration heuristic")
        }
        return M3u8Info(segments, keyUrl, iv)
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun mergeSegments(tempDir: File, task: DownloadTask, total: Int) {
        val dir = File(task.saveDir)
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, "${task.title}_第${task.epIndex}集.mp4")
        RandomAccessFile(outFile, "rw").use { raf ->
            for (i in 0 until total) {
                val seg = File(tempDir, "${i.toString().padStart(6, '0')}.ts")
                if (seg.exists() && seg.length() > 0) {
                    seg.inputStream().use { input ->
                        val buf = ByteArray(262144) // 256KB buffer for faster merge
                        var r: Int
                        while (input.read(buf).also { r = it } != -1) raf.write(buf, 0, r)
                    }
                }
            }
        }
        Log.e(TAG, "Merged file size: ${outFile.length()} bytes")
    }
}
