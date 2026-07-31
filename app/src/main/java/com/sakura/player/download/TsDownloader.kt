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
            val completed = AtomicInteger(0)
            val total = info.segments.size
            Log.e(TAG, "Downloading $total segments ($CONCURRENT_THREADS threads)")

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
            // Always clean up temp dir, even on cancellation or failure
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
                Log.e(TAG, "Temp dir cleaned up: $tempDir")
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

            // Media playlist
            var keyUrl: String? = null
            var iv: ByteArray? = null
            val segments = mutableListOf<String>()
            val lines = content.lines()

            for (i in lines.indices) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-KEY")) {
                    Regex("URI=\"([^\"]+)\"").find(line)?.let { keyUrl = it.groupValues[1] }
                    Regex("IV=0x([0-9a-fA-F]+)").find(line)?.let {
                        val hex = it.groupValues[1]
                        iv = ByteArray(16) { j -> hex.substring(j*2, j*2+2).toInt(16).toByte() }
                    }
                }
                if (!line.startsWith("#") && line.isNotBlank()) {
                    val segUrl = when {
                        line.startsWith("http") -> line
                        line.startsWith("/") -> {
                            val uri = java.net.URI(url)
                            "${uri.scheme}://${uri.host}$line"
                        }
                        else -> baseUrl + line
                    }
                    segments.add(segUrl)
                }
            }
            M3u8Info(segments, keyUrl, iv)
        } catch (e: Exception) {
            val msg = if (e is ParseError) e.message else (e.message ?: "未知错误")
            Log.e(TAG, "parseM3u8 failed: $msg", e)
            throw Exception("解析视频失败: $msg")
        }
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
