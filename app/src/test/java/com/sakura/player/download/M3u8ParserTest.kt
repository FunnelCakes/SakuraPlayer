package com.sakura.player.download

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Unit tests for TsDownloader.parseM3u8Media — the pure m3u8 media-playlist parser
 * that must skip advertisement segments so ads never get merged into the final MP4.
 *
 * Also tests the layered ad-detection scheme:
 *   Layer 1 parse-time scoring (CUE markers, repeated filenames, dir mismatch,
 *   bitrate mismatch, short duration) -> probable-ad / suspicious / content,
 *   Layer 2 SPS probe verification via TsDownloader.extractSpsProfile.
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
        // All content: score 0, no block suspicious/probable.
        assertEquals(1, info.blocks.size)
        assertEquals(0, info.blocks[0].score)
        assertFalse(info.blocks[0].isSuspicious)
        assertFalse(info.blocks[0].isProbableAd)
        assertEquals(0, info.baselineBlockIndex)
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

    // --- Structural fallback / layered scoring (no CUE/DATERANGE markers) ---
    // Real yinghua14 mirror playlists inject ads from a wholly separate stream directory
    // (/20260727/<adId>/10137kb/hls/) with short, irregular durations, bounded by
    // #EXT-X-DISCONTINUITY. Content stays on the media playlist's own directory with
    // uniform ~3.75s segments.

    private val mediaUrl = "https://play.modujx11.com/20250716/gY1jhK5d/1026kb/hls/index.m3u8"
    private val contentSeg = "https://bf.modujx11.com/20250716/gY1jhK5d/1026kb/hls/content.ts"
    private val contentSeg2 = "https://bf.modujx11.com/20250716/gY1jhK5d/1026kb/hls/content2.ts"
    // Different CDN dir but SAME bitrate marker (1026kb) + normal duration -> only +3 (suspicious).
    private val suspiciousSeg = "https://bf.modujx15.com/20260727/wRbpF6Qd/1026kb/hls/suspicious.ts"
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
            $contentSeg2
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, mediaUrl)
        assertEquals(2, info.segments.size)
        assertTrue(info.segments.all { it.contains("modujx11.com") })
        // Layer-1 scoring: ad block = dir mismatch(+3) + bitrate(+2) + short duration(+2) = 7 -> probable ad.
        assertEquals(3, info.blocks.size)
        assertFalse(info.blocks[0].isProbableAd)
        assertTrue(info.blocks[1].isProbableAd)
        assertTrue(info.blocks[1].score >= 4)
        assertFalse(info.blocks[2].isProbableAd)
        assertEquals(0, info.baselineBlockIndex)
    }

    @Test
    fun `discontinuity block on different path with normal durations is suspicious and kept`() {
        // Different CDN directory but SAME bitrate marker and normal duration:
        // only dir mismatch fires (+3) -> "suspicious" (still included in the flat list).
        val content = """
            #EXTM3U
            #EXTINF:3.753122,
            $contentSeg
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $suspiciousSeg
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $contentSeg2
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, mediaUrl)
        assertEquals(3, info.segments.size)
        assertEquals(3, info.blocks.size)
        assertTrue(info.blocks[1].isSuspicious)
        assertFalse(info.blocks[1].isProbableAd)
        assertEquals(3, info.blocks[1].score)
        assertFalse(info.blocks[0].isSuspicious)
        assertEquals(0, info.baselineBlockIndex)
    }

    @Test
    fun `playlist where no block matches media dir keeps every segment`() {
        // Guard: if the media URL's directory matches nothing (nonstandard playlist URL),
        // the directory/bitrate heuristics must not fire, so nothing is auto-dropped.
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
        assertFalse(info.blocks[0].isProbableAd)
        assertFalse(info.blocks[1].isProbableAd)
    }

    @Test
    fun `repeated filenames across blocks push score into probable ad`() {
        // Two ad blocks share the same two filenames. Each ad block accumulates
        // dir mismatch(+3) + bitrate mismatch(+2) + repeated filenames(+3) = 8 -> probable ad.
        // A single such block (no cross-block repeat) would only reach 5 anyway, but this
        // verifies the repeated-filename signal contributes.
        val content = """
            #EXTM3U
            #EXTINF:3.753122,
            $contentSeg
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $adSeg0
            #EXTINF:3.753133,
            $adSeg1
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $adSeg0
            #EXTINF:3.753133,
            $adSeg1
            #EXT-X-DISCONTINUITY
            #EXTINF:3.753133,
            $contentSeg2
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, mediaUrl)
        assertEquals(2, info.segments.size)
        val adBlocks = info.blocks.filter { it.isProbableAd }
        assertEquals(2, adBlocks.size)
        assertTrue(adBlocks.all { it.score >= 7 })
        assertTrue(adBlocks.all { it.segments.size == 2 })
        assertEquals(0, info.baselineBlockIndex)
    }

    @Test
    fun `CUE-OUT marker block is marked probable ad`() {
        val content = """
            #EXTM3U
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-CUE-OUT:30
            #EXTINF:6.0,
            ad0.ts
            #EXTINF:6.0,
            ad1.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()
        val info = TsDownloader.parseM3u8Media(content, url)
        assertEquals(2, info.segments.size)
        assertEquals("https://example.com/video/seg0.ts", info.segments[0])
        assertEquals("https://example.com/video/seg1.ts", info.segments[1])
        val adBlock = info.blocks.firstOrNull { it.score >= 4 }
        assertNotNull(adBlock)
        assertTrue(adBlock!!.isProbableAd)
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
        // Layered scoring marks exactly the 3 ad blocks as probable ad; content is normal.
        assertEquals(3, info.blocks.count { it.isProbableAd })
        assertEquals(0, info.blocks.count { it.isSuspicious && !it.isProbableAd })
        assertTrue(info.blocks.filter { it.isProbableAd }.all { it.score >= 4 })
        assertEquals(0, info.baselineBlockIndex)
    }

    @Test
    fun `real yinghua ep9 playlist reproduction`() {
        // Variant with different ad positions and stream paths; same verified signatures.
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n")
        fun contentSeg(i: Int) = "https://bf.modujx11.com/20250801/aBcDeFgH/1026kb/hls/c$i.ts"
        fun adSeg(i: Int) = "https://bf.modujx15.com/20260802/XyZwVqRs/10137kb/hls/a$i.ts"
        val adDurations = listOf(3.333, 1.667, 1.667, 2.933, 1.667, 1.667, 1.667, 1.667, 1.3)
        val isAdSlot = Array(406) { false }
        for (start in listOf(30, 118, 383)) for (j in 0 until 9) isAdSlot[start + j] = true
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
        val mediaUrl9 = "https://play.modujx11.com/20250801/aBcDeFgH/1026kb/hls/index.m3u8"
        val info = TsDownloader.parseM3u8Media(sb.toString(), mediaUrl9)
        assertEquals(379, info.segments.size)
        assertEquals(0, info.segments.count { it.contains("modujx15.com") })
        assertEquals(3, info.blocks.count { it.isProbableAd })
        assertTrue(info.blocks.filter { it.isProbableAd }.all { it.score >= 4 })
        assertEquals(0, info.baselineBlockIndex)
    }

    // --- Layer 2: SPS probe verification over raw TS bytes ---

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Wrap an Annex-B NAL payload (start codes already embedded) into 188-byte TS packets. */
    private fun buildTs(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < payload.size) {
            val len = minOf(184, payload.size - off)
            val pkt = ByteArray(188)
            pkt[0] = 0x47.toByte()          // sync byte
            pkt[1] = 0x40.toByte()          // payload unit start indicator
            pkt[2] = 0x00.toByte()          // PID low
            pkt[3] = 0x10.toByte()          // payload only
            payload.copyInto(pkt, 4, off, off + len)
            out.write(pkt)
            off += len
        }
        return out.toByteArray()
    }

    private fun spsTs(spsHex: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 0, 0, 1)) // Annex-B start code
        payload.write(hexToBytes(spsHex))
        return buildTs(payload.toByteArray())
    }

    @Test
    fun `extractSpsProfile returns compact signature for Main L3_1 and High L4_0`() {
        // Real SPS bytes captured from yinghua content (Main@L3.1) and ad (High@L4.0) TS segments.
        val mainSps = "674d401f95a014016ec04400000fa00002ed6380"
        val highSps = "67640028acd940780227e5c05a808080a0000003"
        assertEquals("H264_Main_L3.1", TsDownloader.extractSpsProfile(spsTs(mainSps)))
        assertEquals("H264_High_L4.0", TsDownloader.extractSpsProfile(spsTs(highSps)))
    }

    @Test
    fun `extractSpsProfile skips non-SPS NALs before the SPS`() {
        val sps = "674d401f95a014016ec04400000fa00002ed6380"
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 0, 0, 1))
        payload.write(byteArrayOf(0x09, 0xF0.toByte())) // AUD NAL (type 9)
        payload.write(byteArrayOf(0, 0, 0, 1))
        payload.write(hexToBytes(sps))
        val ts = buildTs(payload.toByteArray())
        assertEquals("H264_Main_L3.1", TsDownloader.extractSpsProfile(ts))
    }

    @Test
    fun `extractSpsProfile returns null for empty or non-TS input`() {
        assertNull(TsDownloader.extractSpsProfile(ByteArray(0)))
        // 188 bytes of sync bytes with no payload start codes -> no SPS.
        assertNull(TsDownloader.extractSpsProfile(ByteArray(188) { 0x47.toByte() }))
    }
}
