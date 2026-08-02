package com.sakura.player.bridge

import org.junit.Assert.*
import org.junit.Test

class JsBridgeLogicTest {

    @Test
    fun `cover URL on detail with empty cover returns empty string`() {
        // Simulates buildLocalDetail logic: coverPath="" → coverUrl=""
        val coverPath = ""
        val coverUrl = if (coverPath.isNotEmpty()) "file://$coverPath" else ""
        assertEquals("", coverUrl)
    }

    @Test
    fun `cover URL on detail with valid cover returns file URL`() {
        val coverPath = "/storage/emulated/0/SakuraAnime/Test/cover.jpg"
        val coverUrl = if (coverPath.isNotEmpty()) "file://$coverPath" else ""
        assertEquals("file://$coverPath", coverUrl)
    }

    @Test
    fun `searchVideoIdForDir matching score with containment`() {
        val dirName = "进击的巨人最终季"
        val searchTitle = "进击的巨人 最终季"
        val contains = searchTitle.contains(dirName) || dirName.contains(searchTitle)
        val score = if (contains) 1000 + searchTitle.commonPrefixWith(dirName).length
                    else searchTitle.commonPrefixWith(dirName).length
        // "进击的巨人" is prefix = 5 chars, containment gives 1000+5=1005
        assertTrue("score=$score", score >= 2)
    }

    @Test
    fun `searchVideoIdForDir matching score with prefix only`() {
        val dirName = "咒术回战"
        val searchTitle = "咒术回战 第二季"
        val contains = searchTitle.contains(dirName) || dirName.contains(searchTitle)
        val score = if (contains) 1000 + searchTitle.commonPrefixWith(dirName).length
                    else searchTitle.commonPrefixWith(dirName).length
        // "咒术回战" is contained in searchTitle → 1000 + 4 = 1004
        assertTrue("score=$score", score >= 2)
    }

    @Test
    fun `searchVideoIdForDir matching score with no match`() {
        val dirName = "XYZ"
        val searchTitle = "ABC"
        val contains = searchTitle.contains(dirName) || dirName.contains(searchTitle)
        val score = if (contains) 1000 + searchTitle.commonPrefixWith(dirName).length
                    else searchTitle.commonPrefixWith(dirName).length
        // no containment, no common prefix → 0
        assertEquals(0, score)
        assertTrue("score=$score should be < 2", score < 2)
    }

    @Test
    fun `extractVideoIdFromUrl parses real ID from stored play page URL`() {
        // Mirrors JsBridge.extractVideoIdFromUrl regex
        fun extract(url: String): Long? {
            if (url.isBlank()) return null
            return Regex("""/vod/play/id/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        }

        assertEquals(76284L, extract("https://yinghua14.com/index.php/vod/play/id/76284/sid/2/nid/3.html"))
        assertEquals(12345L, extract("http://mirror.example/index.php/vod/play/id/12345/sid/1/nid/12.html"))
        assertNull(extract(""))
        assertNull(extract("https://example.com/vod/detail/id/76284.html"))
        assertNull(extract("not-a-url"))
    }

    @Test
    fun `redownload resolution prefers real videoId from sourceUrl over stored hash`() {
        // Mirrors JsBridge.redownloadLocal when-branch priority
        fun extractVideoIdFromUrl(url: String): Long? {
            if (url.isBlank()) return null
            return Regex("""/vod/play/id/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        }
        fun resolve(sourceUrl: String, storedVideoId: Long): Pair<Long, Boolean> {
            val videoIdFromUrl = extractVideoIdFromUrl(sourceUrl)
            val realVideoId = when {
                videoIdFromUrl != null -> videoIdFromUrl
                storedVideoId in 1L..99999999L && storedVideoId != 0L -> storedVideoId
                else -> -1L // searchVideoIdForDir fallback
            }
            return realVideoId to (videoIdFromUrl != null)
        }

        // sourceUrl carries the real ID even when stored videoId is a hash -> use sourceUrl
        val (id1, fromUrl1) = resolve(
            "https://yinghua14.com/index.php/vod/play/id/76284/sid/1/nid/3.html",
            724765640L // hash for "尼古喵喵"
        )
        assertEquals(76284L, id1)
        assertTrue(fromUrl1)

        // No sourceUrl, stored videoId is a plausible real ID -> keep it
        val (id2, fromUrl2) = resolve("", 76284L)
        assertEquals(76284L, id2)
        assertFalse(fromUrl2)

        // No sourceUrl, stored videoId looks like a hash (out of range) -> fall to search
        val (id3, fromUrl3) = resolve("", 724765640L)
        assertEquals(-1L, id3) // would be searchVideoIdForDir
        assertFalse(fromUrl3)

        // No sourceUrl, stored videoId is 0 -> fall to search
        val (id4, _) = resolve("", 0L)
        assertEquals(-1L, id4)
    }

    @Test
    fun `hash fallback value is not a plausible real videoId for Chinese titles`() {
        // Guards the redownload heuristic: hash values must NOT look like real website IDs.
        // If this ever fails, the in-range heuristic is no longer safe and sourceUrl becomes mandatory.
        val dirName = "尼古喵喵"
        val hash = dirName.hashCode().toLong()
        assertFalse("hash=$hash should be out of plausible website-id range", hash in 1L..99999999L)
    }

    @Test
    fun `episode index extraction from filename`() {
        val patterns = listOf(
            Regex("""第\s*(\d+)"""),
            Regex("""[Ee][Pp]?\s*(\d+)"""),
            Regex("""[Ss]\d+[Ee](\d+)"""),
            Regex("""(\d+)""")
        )
        fun extractEp(name: String): Int {
            for (p in patterns) {
                val m = p.find(name) ?: continue
                return m.groupValues[1].toIntOrNull() ?: continue
            }
            return 0
        }

        assertEquals(1, extractEp("第01集"))
        assertEquals(12, extractEp("EP12"))
        assertEquals(5, extractEp("S01E05"))
        assertEquals(3, extractEp("第3集.mp4"))
        assertEquals(0, extractEp("no_episode_number"))
    }
}
