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

    // --- Structural fallback (no CUE/DATERANGE markers) ---
    // Real yinghua14 mirror playlists inject ads from a wholly separate stream directory
    // (/20260727/<adId>/10137kb/hls/) with short, irregular durations, bounded by
    // #EXT-X-DISCONTINUITY. Content stays on the media playlist's own directory with
    // uniform ~3.75s segments.

    private val mediaUrl = "https://play.modujx11.com/20250716/gY1jhK5d/1026kb/hls/index.m3u8"
    private val contentSeg = "https://bf.modujx11.com/20250716/gY1jhK5d/1026kb/hls/content.ts"
    private val adSeg0 = "https://bf.modujx15.com/20260727/wRbpF6Qd/10137kb/hls/ad0.ts"
    private val adSeg1 = "https://bf.modujx15.com/20260727/wRbpF6Qd/10137kb/hls/ad1.ts"

    @Test
    fun `discontinuity block on different content path with short durations is skipped`() {
        val content = """
            #EXTM3U
            #EXTINF:3.753122,
            $contentSeg
            #EXT-X-DISCONTINUITY
            #EXTINF:3.333,
            $adSeg0
            #EXTINF:1.667,
            $adSeg1
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $contentSeg
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, mediaUrl)
        assertEquals(2, info.segments.size)
        assertTrue(info.segments.all { it.contains("modujx11.com") })
    }

    @Test
    fun `discontinuity block on different path with normal durations is kept`() {
        val content = """
            #EXTM3U
            #EXTINF:3.753122,
            $contentSeg
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $adSeg0
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $contentSeg
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, mediaUrl)
        assertEquals(3, info.segments.size)
    }

    @Test
    fun `playlist where no block matches media dir keeps every segment`() {
        // Guard: if the media URL's directory matches nothing (nonstandard playlist URL),
        // the heuristic must not drop every block.
        val content = """
            #EXTM3U
            #EXTINF:1.667,
            $adSeg0
            #EXT-X-DISCONTINUITY
            #EXTINF:1.667,
            $adSeg1
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, mediaUrl)
        assertEquals(2, info.segments.size)
    }

    @Test
    fun `real yinghua ep8 playlist keeps 379 content segments and skips 27 ad segments`() {
        // Faithful reproduction of the downloaded ep8 media playlist structure:
        // 406 segments TOTAL; ad blocks occupy slots 24-32, 96-104, 397-405 (3x9 = 27 ads on
        // modujx15), the remaining 379 slots are content on modujx11, ad blocks bounded by
        // #EXT-X-DISCONTINUITY.
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n")
        fun contentSeg(i: Int) = "https://bf.modujx11.com/20250716/gY1jhK5d/1026kb/hls/c$i.ts"
        fun adSeg(i: Int) = "https://bf.modujx15.com/20260727/wRbpF6Qd/10137kb/hls/a$i.ts"
        val adDurations = listOf(3.333, 1.667, 1.667, 2.933, 1.667, 1.667, 1.667, 1.667, 1.3)
        val isAdSlot = Array(406) { false }
        for (start in listOf(24, 96, 397)) for (j in 0 until 9) isAdSlot[start + j] = true
        var contentIdx = 0
        var adIdx = 0
        var prevAd = false
        for (i in 0 until 406) {
            val isAd = isAdSlot[i]
            if (isAd != prevAd) {
                sb.append("#EXT-X-DISCONTINUITY\n")
                prevAd = isAd
            }
            if (isAd) {
                val j = adIdx % 9
                sb.append("#EXTINF:${adDurations[j]},\n${adSeg(j)}\n")
                adIdx++
            } else {
                sb.append("#EXTINF:3.753122,\n${contentSeg(contentIdx)}\n")
                contentIdx++
            }
        }
        sb.append("#EXT-X-ENDLIST\n")
        val info = TsDownloader.parseM3u8Media(sb.toString(), mediaUrl)
        assertEquals(379, info.segments.size)
        assertEquals(0, info.segments.count { it.contains("modujx15.com") })
        assertEquals(379, info.segments.count { it.contains("modujx11.com") })
    }
}
