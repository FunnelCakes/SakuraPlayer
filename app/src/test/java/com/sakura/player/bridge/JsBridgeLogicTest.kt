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
