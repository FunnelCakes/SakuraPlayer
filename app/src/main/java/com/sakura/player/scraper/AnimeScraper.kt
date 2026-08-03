package com.sakura.player.scraper

import com.sakura.player.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class AnimeResult(
    val videoId: Long,
    val title: String,
    val coverUrl: String,
    val episodeInfo: String = "",
    val tags: List<String> = emptyList(),
    val isLocal: Boolean = false
)

data class AnimeDetail(
    val videoId: Long,
    val title: String,
    val coverUrl: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val episodes: List<EpisodeInfo> = emptyList(),
    val totalEps: Int = 0,
    val sourceIds: List<Int> = emptyList() // Available sid values for fallback
)

data class EpisodeInfo(
    val index: Int,
    val name: String,
    val playUrl: String,
    val sourceId: Int = 1 // sid parameter, for multi-source fallback
)

object AnimeScraper {

    suspend fun search(domain: String, keyword: String, page: Int = 1): List<AnimeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AnimeResult>()
        val seenIds = mutableSetOf<Long>()
        try {
            val url = "$domain/index.php/vod/search.html?wd=$keyword&page=$page"
            val reqBuilder = Request.Builder().url(url).get()
            HttpClient.browserHeaders(domain + "/").forEach { (k, v) -> reqBuilder.header(k, v) }
            val html = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: return@withContext emptyList<AnimeResult>() }
            val doc = Jsoup.parse(html)

            // yinghua14.com style: .stui-vodlist__media
            doc.select(".stui-vodlist__media li, .stui-vodlist__media .stui-vodlist__box").forEach { item ->
                val linkEl = item.selectFirst("a.stui-vodlist__thumb") ?: item.selectFirst("a.v-thumb")
                val titleEl = item.selectFirst(".title a") ?: item.selectFirst("h4.title a")
                val href = linkEl?.attr("href") ?: return@forEach
                val videoId = extractId(href) ?: return@forEach
                if (!seenIds.add(videoId)) return@forEach // dedup
                val title = titleEl?.text()?.trim() ?: linkEl.attr("title")
                val cover = linkEl.attr("data-original").ifEmpty { linkEl.attr("src") }
                results.add(AnimeResult(
                    videoId = videoId,
                    title = title,
                    coverUrl = if (cover.startsWith("http")) cover else "$domain$cover",
                    episodeInfo = item.select(".pic-text").text().trim()
                ))
            }

            // yhdmw2.com style: .hl-list-item
            if (results.isEmpty()) {
                doc.select(".hl-list-item").forEach { item ->
                    val linkEl = item.selectFirst("a.hl-item-thumb")
                    val titleEl = item.selectFirst(".hl-item-title a")
                    val href = linkEl?.attr("href") ?: return@forEach
                    val videoId = extractId(href) ?: return@forEach
                    if (!seenIds.add(videoId)) return@forEach
                    val cover = linkEl.attr("data-original").ifEmpty { linkEl.attr("src") }
                    results.add(AnimeResult(
                        videoId = videoId,
                        title = titleEl?.text()?.trim() ?: linkEl.attr("title"),
                        coverUrl = if (cover.startsWith("http")) cover else "$domain$cover",
                        episodeInfo = item.select(".hl-pic-text").text().trim()
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results
    }

    suspend fun getDetail(domain: String, videoId: Long): AnimeDetail = withContext(Dispatchers.IO) {
        try {
            val url = "$domain/index.php/vod/detail/id/$videoId.html"
            val reqBuilder = Request.Builder().url(url).get()
            HttpClient.browserHeaders(domain + "/").forEach { (k, v) -> reqBuilder.header(k, v) }
            val html = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: return@withContext AnimeDetail(videoId, "", "") }
            val doc = Jsoup.parse(html)

            val title = doc.select(".stui-content__detail .title").text().trim()
                .ifEmpty { doc.select("h1.title").text().trim() }
            val cover = doc.select(".stui-content__thumb img").attr("data-original")
                .ifEmpty { doc.select(".stui-content__thumb img").attr("src") }
            val desc = doc.select(".stui-content__detail .desc").text().trim()
                .ifEmpty { doc.select(".detail-desc").text().trim() }
            val tags = doc.select(".stui-content__detail .tag a").map { it.text().trim() }

            // Parse playlist episodes from all sources for fallback support
            val episodes = mutableListOf<EpisodeInfo>()
            val sourceIds = mutableListOf<Int>()

            // Collect unique sid values from playlist blocks
            val playlistBlocks = doc.select(".stui-content__playlist")
            val blockSids = mutableListOf<Int>()
            playlistBlocks.forEach { block ->
                // Try to extract source ID from links in this block
                val firstLink = block.selectFirst("li a[href*='/play/']")
                if (firstLink != null) {
                    val href = firstLink.attr("href")
                    val sidMatch = Regex("/sid/(\\d+)/").find(href)
                    val sid = sidMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    if (!blockSids.contains(sid)) {
                        blockSids.add(sid)
                    }
                }
            }

            // Use primary source (first block) for episode list
            if (playlistBlocks.isNotEmpty()) {
                val firstBlock = playlistBlocks.first()
                firstBlock.select("li a").forEachIndexed { idx, a ->
                    val href = a.attr("href")
                    if (href.contains("/play/")) {
                        val sidMatch = Regex("/sid/(\\d+)/").find(href)
                        val sid = sidMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        episodes.add(EpisodeInfo(
                            index = idx + 1,
                            name = a.text().trim(),
                            playUrl = if (href.startsWith("http")) href else "$domain$href",
                            sourceId = sid
                        ))
                    }
                }
            }

            // Collect all available source IDs for fallback
            if (blockSids.isNotEmpty()) {
                sourceIds.addAll(blockSids)
            } else if (episodes.isNotEmpty()) {
                sourceIds.add(episodes.first().sourceId)
            }

            // Fallback: no episodes found by blocks, try nav
            if (episodes.isEmpty()) {
                doc.select(".stui-player__nav li a").forEachIndexed { idx, a ->
                    val href = a.attr("href")
                    if (href.contains("/play/")) {
                        val sidMatch = Regex("/sid/(\\d+)/").find(href)
                        val sid = sidMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        episodes.add(EpisodeInfo(
                            index = idx + 1,
                            name = a.text().trim().ifEmpty { "第${idx + 1}集" },
                            playUrl = if (href.startsWith("http")) href else "$domain$href",
                            sourceId = sid
                        ))
                    }
                }
            }

            AnimeDetail(
                videoId = videoId,
                title = title.ifEmpty { "未知" },
                coverUrl = if (cover.startsWith("http")) cover else "$domain$cover",
                description = desc,
                tags = tags,
                episodes = episodes,
                totalEps = episodes.size,
                sourceIds = sourceIds
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnimeDetail(videoId, "", "")
        }
    }

    suspend fun getHomeRecommend(domain: String): List<AnimeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AnimeResult>()
        try {
            val reqBuilder = Request.Builder().url(domain).get()
            HttpClient.browserHeaders(domain + "/").forEach { (k, v) -> reqBuilder.header(k, v) }
            val html = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: return@withContext emptyList<AnimeResult>() }
            val doc = Jsoup.parse(html)
            results.addAll(parseCardList(doc, domain))
        } catch (_: Exception) {}
        results
    }

    /**
     * Fetch a category-specific list from an AppleCMS type page.
     * Category IDs on the source site: recommend handled by getHomeRecommend,
     * 20 = 日本动漫, 21 = 国产动漫, 22 = 欧美动漫, 23 = 动漫电影.
     */
    suspend fun getCategoryList(domain: String, catId: String, page: Int): List<AnimeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AnimeResult>()
        try {
            val url = "$domain/index.php/vod/type/id/$catId/page/$page.html"
            val reqBuilder = Request.Builder().url(url).get()
            HttpClient.browserHeaders(domain + "/").forEach { (k, v) -> reqBuilder.header(k, v) }
            val html = HttpClient.client.newCall(reqBuilder.build()).execute().use { it.body?.string() ?: return@withContext emptyList<AnimeResult>() }
            val doc = Jsoup.parse(html)
            results.addAll(parseCardList(doc, domain))
        } catch (_: Exception) {}
        results
    }

    /** Shared AppleCMS card-grid parsing (.stui-vodlist__box), used by home & category pages. */
    private fun parseCardList(doc: Document, domain: String): List<AnimeResult> {
        val results = mutableListOf<AnimeResult>()
        doc.select(".stui-vodlist__box").forEach { item ->
            val linkEl = item.selectFirst("a.stui-vodlist__thumb")
            val titleEl = item.selectFirst(".title a")
            val href = linkEl?.attr("href") ?: return@forEach
            val videoId = extractId(href) ?: return@forEach
            val cover = linkEl.attr("data-original").ifEmpty { linkEl.attr("src") }
            results.add(AnimeResult(
                videoId = videoId,
                title = titleEl?.text()?.trim() ?: linkEl.attr("title"),
                coverUrl = if (cover.startsWith("http")) cover else "$domain$cover",
                episodeInfo = item.select(".pic-text").text().trim()
            ))
        }
        return results
    }

    private fun extractId(href: String): Long? {
        val patterns = listOf(
            Regex("/id/(\\d+)\\.html"),
            Regex("/detail/id/(\\d+)\\.html"),
            Regex("/vod/(\\d+)/"),
            Regex("/html/(\\d+)\\.html")
        )
        for (p in patterns) {
            p.find(href)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        }
        return null
    }
}
