package com.sakura.player.scraper

import com.sakura.player.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

data class VideoUrl(
    val m3u8Url: String,
    val nextM3u8Url: String = "",
    val episodeName: String = ""
)

object VideoExtractor {

    suspend fun extractFromPlayPage(domain: String, videoId: Long, ep: Int, sid: Int = 1): VideoUrl = withContext(Dispatchers.IO) {
        try {
            val url = "$domain/index.php/vod/play/id/$videoId/sid/$sid/nid/$ep.html"
            val reqBuilder = Request.Builder().url(url).get()
            HttpClient.browserHeaders(domain + "/").forEach { (k, v) -> reqBuilder.header(k, v) }
            val html = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: return@withContext VideoUrl("") }

            // Parse var player_aaaa={...}
            val playerRegex = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = playerRegex.find(html)
            if (match != null) {
                val json = match.groupValues[1]
                val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                val urlNextRegex = Regex(""""url_next"\s*:\s*"([^"]+)"""")
                val m3u8 = urlRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                val next = urlNextRegex.find(json)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                return@withContext VideoUrl(m3u8, next)
            }

            // Fallback: search for direct m3u8/mp4 URLs in the page
            val m3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            val m3u8Match = m3u8Regex.find(html)
            if (m3u8Match != null) {
                return@withContext VideoUrl(m3u8Match.value)
            }

            VideoUrl("")
        } catch (e: Exception) {
            e.printStackTrace()
            VideoUrl("")
        }
    }

    suspend fun resolveMasterPlaylist(m3u8Url: String): String = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(m3u8Url).get()
            HttpClient.browserHeaders().forEach { (k, v) -> reqBuilder.header(k, v) }
            val content = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: return@withContext m3u8Url }
            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"

            if (content.contains("#EXT-X-STREAM-INF")) {
                // Master playlist: pick highest bandwidth variant
                val variants = mutableListOf<Pair<Int, String>>()
                val lines = content.lines()
                for (i in lines.indices) {
                    if (lines[i].contains("#EXT-X-STREAM-INF")) {
                        val bwRegex = Regex("BANDWIDTH=(\\d+)")
                        val bw = bwRegex.find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (i + 1 < lines.size && lines[i+1].isNotBlank() && !lines[i+1].startsWith("#")) {
                            variants.add(bw to lines[i+1])
                        }
                    }
                }
                if (variants.isNotEmpty()) {
                    variants.sortByDescending { it.first }
                    val bestUrl = variants.first().second
                    return@withContext when {
    bestUrl.startsWith("http") -> bestUrl
    bestUrl.startsWith("/") -> {
        val uri = java.net.URI(m3u8Url)
        "${uri.scheme}://${uri.host}$bestUrl"
    }
    else -> baseUrl + bestUrl
}
                }
            }
        } catch (_: Exception) {}
        m3u8Url // Return original if not a master playlist
    }

    suspend fun checkM3u8Encrypted(m3u8Url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(m3u8Url).get()
            HttpClient.browserHeaders().forEach { (k, v) -> reqBuilder.header(k, v) }
            val content = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: "" }
            content.contains("#EXT-X-KEY")
        } catch (_: Exception) {
            true // 假设加密，安全回退到 ffmpeg
        }
    }

    suspend fun parseTsSegments(m3u8Url: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(m3u8Url).get()
            HttpClient.browserHeaders().forEach { (k, v) -> reqBuilder.header(k, v) }
            val content = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: "" }
            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"

            // Check if this is a master playlist (contains #EXT-X-STREAM-INF)
            if (content.contains("#EXT-X-STREAM-INF")) {
                // Master playlist: pick highest quality variant
                val variants = mutableListOf<Pair<Int, String>>()
                val lines = content.lines()
                for (i in lines.indices) {
                    if (lines[i].contains("#EXT-X-STREAM-INF")) {
                        val bwRegex = Regex("BANDWIDTH=(\\d+)")
                        val bw = bwRegex.find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (i + 1 < lines.size && lines[i+1].isNotBlank() && !lines[i+1].startsWith("#")) {
                            variants.add(bw to lines[i+1])
                        }
                    }
                }
                if (variants.isNotEmpty()) {
                    variants.sortByDescending { it.first }
                    val bestUrl = variants.first().second
                    val fullUrl = if (bestUrl.startsWith("http")) bestUrl else baseUrl + bestUrl
                    return@withContext parseTsSegments(fullUrl) // recurse into variant playlist
                }
            }

            // Media playlist: extract .ts segments
            content.lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { if (it.startsWith("http")) it else baseUrl + it }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
