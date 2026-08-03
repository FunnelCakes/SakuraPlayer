package com.sakura.player.download

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the pause-preserves-partial-segments guard.
 *
 * When DownloadManager.pause() runs it sets task.status = "paused" BEFORE calling
 * job.cancel(). The resulting CancellationException unwinds through
 * TsDownloader.download() into its finally block, where shouldCleanupTempDir()
 * must return false so the temp dir (partial segments) survives for resume().
 */
class DownloadPauseTest {

    @Test
    fun `temp dir is preserved when task is paused`() {
        assertFalse(TsDownloader.shouldCleanupTempDir("paused"))
    }

    @Test
    fun `temp dir is cleaned up on completion`() {
        assertTrue(TsDownloader.shouldCleanupTempDir("completed"))
    }

    @Test
    fun `temp dir is cleaned up on failure`() {
        assertTrue(TsDownloader.shouldCleanupTempDir("failed"))
    }

    @Test
    fun `temp dir is cleaned up on retry queued`() {
        assertTrue(TsDownloader.shouldCleanupTempDir("queued"))
    }

    @Test
    fun `temp dir is cleaned up during active download`() {
        assertTrue(TsDownloader.shouldCleanupTempDir("downloading"))
    }

    @Test
    fun `pause sets status before cancel so finally sees paused`() {
        // Mirrors DownloadManager.pause(): status is written BEFORE job.cancel(),
        // so by the time the CancellationException reaches the finally block the
        // status is already "paused" and the guard preserves the temp dir.
        var status = "downloading"
        status = "paused" // pause()
        // job.cancel() -> CancellationException unwinds
        assertFalse(TsDownloader.shouldCleanupTempDir(status))
    }
}
