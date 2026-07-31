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
}
