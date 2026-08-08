package com.sakura.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for fix #3 of the download-queue stall: deduplicate identical
 * m3u8 URLs before the multi-CDN race. Without this, several sids that resolve to
 * the exact same CDN URL are raced against themselves, wasting bandwidth and
 * multiplying the number of losers that can get stuck mid-call.
 */
class DownloadDedupeTest {

    @Test
    fun `identical m3u8 URLs are deduplicated keeping first sid`() {
        val urls = listOf(
            "https://cdn.example.com/playlist.m3u8" to 1,
            "https://cdn.example.com/playlist.m3u8" to 2,
            "https://cdn.example.com/playlist.m3u8" to 3
        )
        val deduped = DownloadManager.dedupeCdnUrls(urls)
        assertEquals(1, deduped.size)
        assertEquals(1, deduped[0].second)
        assertEquals("https://cdn.example.com/playlist.m3u8", deduped[0].first)
    }

    @Test
    fun `distinct URLs are all kept`() {
        val urls = listOf(
            "https://cdn-a.example.com/a.m3u8" to 1,
            "https://cdn-b.example.com/b.m3u8" to 2
        )
        val deduped = DownloadManager.dedupeCdnUrls(urls)
        assertEquals(2, deduped.size)
    }

    @Test
    fun `duplicate later entry is dropped, distinct url preserved`() {
        val urls = listOf(
            "https://cdn.example.com/x.m3u8" to 1,
            "https://cdn.example.com/y.m3u8" to 2,
            "https://cdn.example.com/x.m3u8" to 3
        )
        val deduped = DownloadManager.dedupeCdnUrls(urls)
        assertEquals(2, deduped.size)
        assertEquals("https://cdn.example.com/x.m3u8", deduped[0].first)
        assertEquals(1, deduped[0].second)
        assertEquals("https://cdn.example.com/y.m3u8", deduped[1].first)
        assertEquals(2, deduped[1].second)
    }

    @Test
    fun `empty input produces empty output`() {
        assertTrue(DownloadManager.dedupeCdnUrls(emptyList()).isEmpty())
    }
}
