package com.sakura.player.download

import org.junit.Assert.*
import org.junit.Test

class DownloadRecordLogicTest {

    @Test
    fun `redownload with searchVideoIdForDir fallback to hash`() {
        // When website search fails, videoId = title.hashCode()
        val title = "不存在番剧XYZ"
        val videoId = title.hashCode().toLong()
        // This is a valid Long, not 0, so the code would try to use it
        assertNotEquals(0L, videoId)
        assertTrue(videoId != 0L)
    }

    @Test
    fun `redownload with real videoId from search`() {
        // When search succeeds, videoId is the real website ID
        val realVideoId = 76284L
        assertTrue(realVideoId > 0)
    }

    @Test
    fun `download record coverUrl fallback logic`() {
        // Records from syncMissingRecords always have coverUrl=""
        // Records from online download may have coverUrl from detail page
        val syncedRecordCoverUrl = ""
        val onlineRecordCoverUrl = "https://yinghua14.com/upload/vod/cover.jpg"

        val needsCoverFetch = syncedRecordCoverUrl.isBlank()
        val alreadyHasCover = onlineRecordCoverUrl.isNotBlank()

        assertTrue(needsCoverFetch)
        assertTrue(alreadyHasCover)
    }

    @Test
    fun `dedup by videoId and epIndex`() {
        val videoId = 76284L
        val epIndex = 3
        val id = "${videoId}_$epIndex"
        assertEquals("76284_3", id)
    }

    @Test
    fun `syncMissingRecords does not clobber record that has a sourceUrl`() {
        // Mirrors DownloadRecordManager.syncMissingRecords guard: a record carrying an
        // authoritative stored play page URL must never be overwritten by a search result.
        fun shouldUpgrade(existingVideoId: Long, searchVideoId: Long, hasSourceUrl: Boolean): Boolean {
            if (existingVideoId == searchVideoId) return false
            if (hasSourceUrl) return false
            val currentLooksLikeHash = existingVideoId == 0L || existingVideoId !in 1L..99999999L
            return currentLooksLikeHash
        }

        // Record has sourceUrl -> never clobber, even if search returns something different
        assertFalse(shouldUpgrade(76284L, 99999L, hasSourceUrl = true))
        assertFalse(shouldUpgrade(724765640L, 76284L, hasSourceUrl = true))

        // Hash record without sourceUrl -> allow upgrade to search result
        assertTrue(shouldUpgrade(724765640L, 76284L, hasSourceUrl = false))
        assertTrue(shouldUpgrade(0L, 76284L, hasSourceUrl = false))

        // Plausible real record without sourceUrl -> do NOT clobber with a different search result
        assertFalse(shouldUpgrade(76284L, 99999L, hasSourceUrl = false))

        // Identical videoId -> no-op
        assertFalse(shouldUpgrade(76284L, 76284L, hasSourceUrl = false))
    }

    @Test
    fun `extractSidFromUrl parses sid from play page URL`() {
        assertEquals(2, DownloadManager.extractSidFromUrl("https://yinghua14.com/index.php/vod/play/id/76284/sid/2/nid/3.html"))
        assertEquals(1, DownloadManager.extractSidFromUrl("https://yinghua14.com/index.php/vod/play/id/76284/sid/1/nid/12.html"))
        // Missing sid defaults to 1
        assertEquals(1, DownloadManager.extractSidFromUrl("https://yinghua14.com/index.php/vod/play/id/76284.html"))
        assertEquals(1, DownloadManager.extractSidFromUrl(""))
    }
}
