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

    internal data class M3u8Info(
        val segments: List<String>,
        val keyUrl: String?,
        val iv: ByteArray?,
        /**
         * Layered ad-detection block structure. Each [BlockInfo] is a
         * #EXT-X-DISCONTINUITY-bounded (or marker-bounded) group of segments with a
         * Layer-1 confidence score. The flat [segments] list excludes probable-ad
         * blocks but still includes "suspicious" blocks (those needing a Layer-2 SPS
         * probe).
         */
        val blocks: List<BlockInfo> = emptyList(),
        /**
         * Index into [blocks] of the first content block, whose first segment's H.264
         * SPS is used as the dynamic baseline for Layer-2 probe comparison.
         */
        val baselineBlockIndex: Int = -1
    )

    /** One DISCONTINUITY/marker-bounded group of segments plus its Layer-1 score. */
    internal data class BlockInfo(
        val segments: List<SegmentEntry>,
        val score: Int,
        val isProbableAd: Boolean,
        val isSuspicious: Boolean,
        /** Index of this block's first segment in the flat [M3u8Info.segments] list, or -1 if the block is filtered out. */
        val flatStartIndex: Int
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

            // Layered ad detection: probable-ad blocks were filtered at parse time;
            // this finalizes the list by probing each "suspicious" block's first segment
            // against the content baseline SPS (Layer 2 + Layer 3).
            val finalSegments = buildDownloadSegmentList(info, downloadClient, referer, aesKey, info.iv)
            val total = finalSegments.size
            Log.e(TAG, "Downloading $total segments after layered ad detection ($threadCount threads)")
            if (total == 0) {
                task.status = "failed"
                task.error = "m3u8中未找到可下载的视频分片（可能全部为广告）"
                return@withContext
            }

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
                                    val segReqBuilder = Request.Builder().url(finalSegments[i]).get()
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

    /**
     * A DISCONTINUITY block whose median duration is below this value gets +2 score
     * (content on yinghua mirrors is uniformly ~3.75s; ads are ~1.3-3.3s).
     */
    private const val AD_BLOCK_MAX_MEDIAN_DURATION = 3.0

    /** A block scoring at or above this is auto-skipped at parse time (Layer 1). */
    private const val AD_PROBABLE_AD_THRESHOLD = 4

    internal data class SegmentEntry(val url: String, val duration: Double?)

    /** Path directory (up to the last '/') of an absolute segment URL, or null if unparseable. */
    private fun segmentPathDir(segmentUrl: String): String? {
        return try {
            val path = java.net.URI(segmentUrl).path ?: return null
            path.substringBeforeLast('/').takeIf { it.isNotBlank() && it != "/" }
        } catch (_: Exception) { null }
    }

    /** Filename (last path segment, query stripped) of an absolute segment URL. */
    private fun segmentFileName(segmentUrl: String): String {
        return try {
            val path = java.net.URI(segmentUrl).path ?: segmentUrl
            path.substringAfterLast('/').substringBefore('?')
        } catch (_: Exception) { segmentUrl }
    }

    /** Bitrate marker path segment like "1026kb" or "10137kb", or null if absent. */
    private fun bitrateMarker(segmentUrl: String): String? {
        val dir = segmentPathDir(segmentUrl) ?: return null
        return Regex("/(\\d+kb)/").find(dir)?.groupValues?.get(1)
    }

    private fun blockMedianDuration(block: List<SegmentEntry>): Double? {
        val durs = block.mapNotNull { it.duration }
        if (durs.isEmpty()) return null
        val s = durs.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }

    /** True if any segment filename in this block also appears in a different block. */
    private fun hasRepeatedFilenames(block: List<SegmentEntry>, blockIndex: Int, allBlocks: List<List<SegmentEntry>>): Boolean {
        if (block.isEmpty()) return false
        val names = block.map { segmentFileName(it.url) }.toSet()
        for ((i, other) in allBlocks.withIndex()) {
            if (i == blockIndex) continue
            if (other.any { segmentFileName(it.url) in names }) return true
        }
        return false
    }

    /**
     * Layer 1 parse-time ad scoring. Returns a per-block confidence score from
     * independent signals (each additive, never cancels):
     *   - CUE/DATERANGE ad marker on the block            -> +5 (auto-ad)
     *   - segment filenames repeated across other blocks  -> +3
     *   - URL directory differs from the content directory-> +3
     *   - URL path bitrate marker differs from content    -> +2
     *   - median duration below 3.0s                      -> +2
     * Score >= [AD_PROBABLE_AD_THRESHOLD] => probable ad (skipped at download).
     * Score 1..3 => suspicious (needs a Layer-2 SPS probe).
     * Score 0    => normal content.
     *
     * The directory/bitrate heuristics only fire when at least one block actually lives
     * in the media playlist's content directory; otherwise a nonstandard playlist URL
     * could cause every block to be misclassified.
     */
    private fun scoreBlock(
        block: List<SegmentEntry>,
        blockIndex: Int,
        allBlocks: List<List<SegmentEntry>>,
        contentDir: String?,
        contentBitrate: String?,
        applyDirHeuristics: Boolean,
        adViaMarker: Boolean
    ): Int {
        var score = 0
        if (adViaMarker) score += 5
        if (block.isNotEmpty() && applyDirHeuristics && block.none { segmentPathDir(it.url) == contentDir }) score += 3
        if (block.isNotEmpty() && applyDirHeuristics && contentBitrate != null &&
            block.any { bitrateMarker(it.url) != null && bitrateMarker(it.url) != contentBitrate }) score += 2
        if (hasRepeatedFilenames(block, blockIndex, allBlocks)) score += 3
        val median = blockMedianDuration(block)
        if (median != null && median < AD_BLOCK_MAX_MEDIAN_DURATION) score += 2
        return score
    }

    /**
     * Parse an HLS media playlist, skipping advertisement segments.
     *
     * Implements the layered ad-detection scheme:
     *   Layer 1 (parse-time, zero bandwidth): every #EXT-X-DISCONTINUITY-bounded block
     *     is scored via [scoreBlock]. Blocks scoring >= 4 are dropped from the flat
     *     [M3u8Info.segments] list; blocks scoring 1..3 are kept but flagged "suspicious"
     *     so the download flow can run a Layer-2 probe. Blocks scoring 0 are content.
     *   Layer 3 (dynamic baseline): [M3u8Info.baselineBlockIndex] points at the first
     *     content block; the download flow extracts its SPS as the comparison baseline.
     *
     * This is a pure function (no network or Android dependencies) so it can be unit
     * tested on the JVM.
     */
    internal fun parseM3u8Media(content: String, url: String): M3u8Info {
        val baseUrl = url.substringBeforeLast("/") + "/"
        val mediaUri = try { java.net.URI(url) } catch (_: Exception) { null }
        val contentDir = mediaUri?.path?.substringBeforeLast('/')?.takeIf { it.isNotBlank() && it != "/" }

        var keyUrl: String? = null
        var iv: ByteArray? = null
        var currentDuration: Double? = null

        // Marker-based ad tracking. CUE-OUT / DATERANGE-ad opens a dedicated ad block;
        // CUE-IN / DATERANGE-in closes it and opens a fresh content block, so marker ads
        // never pollute a block that also holds legitimate content.
        var inAd = false
        var adSegmentsSkippedByMarker = 0

        val blocks = mutableListOf<MutableList<SegmentEntry>>()
        val blockAdMarkers = mutableListOf<Boolean>()
        blocks.add(mutableListOf())
        blockAdMarkers.add(false)

        fun startAdBlock() {
            blocks.add(mutableListOf())
            blockAdMarkers.add(true)
            inAd = true
        }

        fun endAdBlock() {
            inAd = false
            blocks.add(mutableListOf())
            blockAdMarkers.add(false)
        }

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
                    if (!inAd) startAdBlock()
                    Log.e(TAG, "Ad marker: CUE-OUT (ad starts)")
                }
                trimmed.startsWith("#EXT-X-CUE-IN") -> {
                    if (inAd) endAdBlock()
                    Log.e(TAG, "Ad marker: CUE-IN (ad ends)")
                }
                trimmed.startsWith("#EXT-X-DATERANGE") -> {
                    val lower = trimmed.lowercase()
                    if (lower.contains("scte35-in")) {
                        if (inAd) endAdBlock()
                        Log.e(TAG, "Ad marker: DATERANGE SCTE35-IN (ad ends)")
                    } else {
                        val cls = Regex("CLASS=\"([^\"]*)\"").find(trimmed)?.groupValues?.get(1)?.lowercase() ?: ""
                        val isAdClass = cls.contains("ad") || cls.contains("interstitial") || cls.contains("advertisement")
                        if (isAdClass || lower.contains("scte35-out")) {
                            if (!inAd) startAdBlock()
                            Log.e(TAG, "Ad marker: DATERANGE ad class/SCTE35-OUT (ad starts)")
                        }
                    }
                }
                trimmed.startsWith("#EXT-X-DISCONTINUITY") -> {
                    blocks.add(mutableListOf())
                    blockAdMarkers.add(false)
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

        // Layer 1 scoring: compute a confidence score for every block.
        val contentDirMatches = blocks.count { block -> block.any { segmentPathDir(it.url) == contentDir } }
        val applyDirHeuristics = contentDir != null && contentDirMatches > 0
        val contentBitrate = contentDir?.let { Regex("/(\\d+kb)/").find(it)?.groupValues?.get(1) }
        val scores = blocks.mapIndexed { idx, block ->
            scoreBlock(block, idx, blocks, contentDir, contentBitrate, applyDirHeuristics, blockAdMarkers[idx])
        }

        // Build the flat kept-segment list (probable-ad blocks dropped, suspicious kept).
        val segments = mutableListOf<String>()
        val blockInfos = mutableListOf<BlockInfo>()
        var adSegmentsSkippedByBlock = 0
        var flatIndex = 0
        for ((idx, block) in blocks.withIndex()) {
            val score = scores[idx]
            val isProbableAd = score >= AD_PROBABLE_AD_THRESHOLD
            if (isProbableAd) {
                adSegmentsSkippedByBlock += block.size
                Log.e(TAG, "Ad block skipped via layered scoring (score=$score, n=${block.size})")
                blockInfos.add(BlockInfo(block, score, true, score in 1..3, -1))
            } else {
                blockInfos.add(BlockInfo(block, score, false, score in 1..3, flatIndex))
                flatIndex += block.size
                segments.addAll(block.map { it.url })
            }
        }

        // Layer 3: the dynamic baseline is the first normal-content block.
        val baselineBlockIndex = blocks.indices.firstOrNull { scores[it] == 0 && blocks[it].isNotEmpty() }
            ?: blocks.indices.firstOrNull { scores[it] < AD_PROBABLE_AD_THRESHOLD && blocks[it].isNotEmpty() }
            ?: -1

        if (adSegmentsSkippedByMarker > 0) {
            Log.e(TAG, "Skipped $adSegmentsSkippedByMarker ad segment(s) via CUE/DATERANGE markers")
        }
        if (adSegmentsSkippedByBlock > 0) {
            Log.e(TAG, "Skipped $adSegmentsSkippedByBlock ad segment(s) via layered scoring")
        }
        return M3u8Info(segments, keyUrl, iv, blockInfos, baselineBlockIndex)
    }

    // ---------------------------------------------------------------------------
    // Layer 2: SPS probe verification.
    //
    // extractSpsProfile is a pure function over TS bytes: it walks MPEG-TS packets,
    // collects their payloads, finds the first H.264 SPS NAL (type 7) and returns a
    // compact "H264_<Profile>_L<level>" signature used for baseline + comparison.
    // ---------------------------------------------------------------------------

    internal fun extractSpsProfile(tsBytes: ByteArray): String? {
        if (tsBytes.isEmpty()) return null
        val payloads = java.io.ByteArrayOutputStream(tsBytes.size)
        var i = 0
        while (i + 188 <= tsBytes.size) {
            if (tsBytes[i] != 0x47.toByte()) { i++; continue }
            val adapt = tsBytes[i + 3].toInt() and 0x30
            when (adapt) {
                0x30 -> { // adaptation field + payload
                    val alen = tsBytes[i + 4].toInt() and 0xFF
                    val p = i + 5 + alen
                    if (p < i + 188) payloads.write(tsBytes, p, (i + 188) - p)
                }
                0x10 -> payloads.write(tsBytes, i + 4, 184) // payload only
                else -> {} // adaptation-only / reserved: no payload
            }
            i += 188
        }
        return parseSpsFromAnnexB(payloads.toByteArray())
    }

    private fun parseSpsFromAnnexB(data: ByteArray): String? {
        var i = 0
        while (i + 4 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                var nalStart = -1
                if (data[i + 2] == 1.toByte()) {
                    nalStart = i + 3
                } else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    nalStart = i + 4
                }
                if (nalStart >= 0) {
                    val nalType = data[nalStart].toInt() and 0x1F
                    if (nalType == 7) {
                        return parseSpsPayload(data, nalStart + 1)
                    }
                    // Advance to the next start code to keep the scan linear.
                    var j = nalStart + 1
                    while (j + 4 <= data.size) {
                        if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() &&
                            (data[j + 2] == 1.toByte() ||
                                (j + 3 < data.size && data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()))) {
                            break
                        }
                        j++
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        return null
    }

    private fun parseSpsPayload(data: ByteArray, offset: Int): String? {
        if (offset + 3 >= data.size) return null
        val profileIdc = data[offset].toInt() and 0xFF
        val levelIdc = data[offset + 2].toInt() and 0xFF
        val profileName = when (profileIdc) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High10"
            122 -> "High422"
            244 -> "High444"
            else -> "Profile$profileIdc"
        }
        return "H264_${profileName}_L${levelIdc / 10.0}"
    }

    /**
     * Download one segment's (decrypted) bytes. Returns null on any failure. Used by the
     * Layer-2 SPS probe so the download flow can verify "suspicious" blocks without
     * touching the temp dir.
     */
    private suspend fun downloadSegmentBytes(
        url: String,
        client: OkHttpClient,
        referer: String,
        aesKey: ByteArray?,
        iv: ByteArray?,
        index: Int
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val segReqBuilder = Request.Builder().url(url).get()
            HttpClient.browserHeaders(referer).forEach { (k, v) -> segReqBuilder.header(k, v) }
            client.newCall(segReqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val raw = resp.body?.bytes() ?: return@withContext null
                if (aesKey != null) {
                    val ivBytes = iv ?: ByteArray(16).apply {
                        for (j in 0..15) this[j] = ((index shr ((15 - j % 8) * 8)) and 0xFF).toByte()
                    }
                    try { decryptAes128(raw, aesKey, ivBytes) } catch (_: Exception) { null }
                } else raw
            }
        } catch (_: Exception) { null }
    }

    /**
     * Layer 2 + 3: decide the final download list from the parsed block structure.
     *
     *  - probable-ad blocks are already excluded by [M3u8Info.segments]; this rebuilds
     *    the list from [M3u8Info.blocks] so suspicious blocks can be dropped too.
     *  - The first content block's SPS becomes the baseline (Layer 3).
     *  - For each suspicious block we download ONLY its first segment, parse its SPS,
     *    and compare with the baseline. Mismatch => confirmed ad, block skipped.
     *    Match (or an inconclusive probe) => the block is kept (conservative — never
     *    drops legitimate content).
     */
    private suspend fun buildDownloadSegmentList(
        info: M3u8Info,
        client: OkHttpClient,
        referer: String,
        aesKey: ByteArray?,
        iv: ByteArray?
    ): List<String> {
        if (info.blocks.isEmpty()) return info.segments

        val suspiciousBlocks = info.blocks.filter { it.isSuspicious }
        val needsBaseline = suspiciousBlocks.isNotEmpty()
        if (!needsBaseline) return info.segments

        // Layer 3: establish the dynamic baseline from the first content block.
        val baseBlock = if (info.baselineBlockIndex in info.blocks.indices) info.blocks[info.baselineBlockIndex] else null
        if (baseBlock == null || baseBlock.segments.isEmpty()) return info.segments
        val baselineSps = probeSps(baseBlock.segments[0].url, client, referer, aesKey, iv, baseBlock.flatStartIndex)
        Log.e(TAG, "Baseline SPS (block ${info.baselineBlockIndex}): $baselineSps")
        if (baselineSps == null) {
            // No baseline available; cannot verify suspicious blocks — keep everything.
            Log.e(TAG, "No baseline SPS available; keeping all suspicious blocks (conservative)")
            return info.segments
        }

        val result = mutableListOf<String>()
        var skipped = 0
        for (block in info.blocks) {
            if (block.isProbableAd) {
                skipped += block.segments.size
                continue
            }
            if (block.isSuspicious) {
                if (block.segments.isEmpty()) continue
                val sps = probeSps(block.segments[0].url, client, referer, aesKey, iv, block.flatStartIndex)
                if (sps != null && sps != baselineSps) {
                    skipped += block.segments.size
                    Log.e(TAG, "Suspicious block confirmed ad via SPS (sps=$sps, baseline=$baselineSps, n=${block.segments.size})")
                    continue
                }
                Log.e(TAG, "Suspicious block kept after SPS probe (sps=$sps, baseline=$baselineSps, n=${block.segments.size})")
            }
            result.addAll(block.segments.map { it.url })
        }
        if (skipped > 0) Log.e(TAG, "Layered ad detection: skipped $skipped segments after probing")
        return result
    }

    private suspend fun probeSps(
        url: String,
        client: OkHttpClient,
        referer: String,
        aesKey: ByteArray?,
        iv: ByteArray?,
        index: Int
    ): String? {
        val bytes = downloadSegmentBytes(url, client, referer, aesKey, iv, index) ?: return null
        return extractSpsProfile(bytes)
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
