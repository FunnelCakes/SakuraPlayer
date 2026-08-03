package com.sakura.player.download

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TsDownloader.parseM3u8Media — the pure m3u8 media-playlist parser
 * that must skip advertisement segments so ads never get merged into the final MP4.
 */
class M3u8ParserTest {

    private val url = "https://example.com/video/playlist.m3u8"

    @Test
    fun `plain playlist returns all segments`() {
        val content = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(3, info.segments.size)
        assertEquals("https://example.com/video/seg0.ts", info.segments[0])
        assertEquals("https://example.com/video/seg1.ts", info.segments[1])
        assertEquals("https://example.com/video/seg2.ts", info.segments[2])
    }

    @Test
    fun `segments between CUE-OUT and CUE-IN are skipped`() {
        val content = """
            #EXTM3U
            #EXT-X-CUE-OUT:30
            #EXTINF:6.0,
            ad0.ts
            #EXTINF:6.0,
            ad1.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(1, info.segments.size)
        assertEquals("https://example.com/video/seg0.ts", info.segments[0])
    }

    @Test
    fun `segments after CUE-OUT with no CUE-IN are all skipped`() {
        val content = """
            #EXTM3U
            #EXT-X-CUE-OUT:30
            #EXTINF:6.0,
            ad0.ts
            #EXTINF:6.0,
            ad1.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertTrue(info.segments.isEmpty())
    }

    @Test
    fun `CUE-OUT-CONT continues ad state`() {
        val content = """
            #EXTM3U
            #EXT-X-CUE-OUT:30
            #EXTINF:6.0,
            ad0.ts
            #EXT-X-CUE-OUT-CONT:12
            #EXTINF:6.0,
            ad1.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(1, info.segments.size)
        assertEquals("https://example.com/video/seg0.ts", info.segments[0])
    }

    @Test
    fun `DATERANGE with ad class and SCTE35-OUT skips until SCTE35-IN`() {
        val content = """
            #EXTM3U
            #EXT-X-DATERANGE:ID="splice-1",CLASS="com.example.advertisement",SCTE35-OUT=0xFC00
            #EXTINF:6.0,
            ad0.ts
            #EXT-X-DATERANGE:ID="splice-1",SCTE35-IN=0xFC00
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(1, info.segments.size)
        assertEquals("https://example.com/video/seg0.ts", info.segments[0])
    }

    @Test
    fun `DATERANGE interstitial class skips following segments`() {
        val content = """
            #EXTM3U
            #EXT-X-DATERANGE:ID="ad-7",CLASS="com.example.interstitial"
            #EXTINF:6.0,
            interstitial0.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(1, info.segments.size)
        assertEquals("https://example.com/video/seg0.ts", info.segments[0])
    }

    @Test
    fun `non-ad DATERANGE does not skip segments`() {
        val content = """
            #EXTM3U
            #EXT-X-DATERANGE:ID="intro",CLASS="com.example.chapter"
            #EXTINF:6.0,
            intro.ts
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(2, info.segments.size)
        assertEquals("https://example.com/video/intro.ts", info.segments[0])
    }

    @Test
    fun `absolute and root-relative segment URLs resolve correctly`() {
        val content = """
            #EXTM3U
            #EXTINF:6.0,
            https://cdn.example.com/other/seg0.ts
            #EXTINF:6.0,
            /root/seg1.ts
            #EXTINF:6.0,
            seg2.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals("https://cdn.example.com/other/seg0.ts", info.segments[0])
        assertEquals("https://example.com/root/seg1.ts", info.segments[1])
        assertEquals("https://example.com/video/seg2.ts", info.segments[2])
    }

    @Test
    fun `AES key and IV are extracted`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000000
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(1, info.segments.size)
        assertEquals("key.bin", info.keyUrl)
        assertNotNull(info.iv)
        assertEquals(16, info.iv!!.size)
    }
}
