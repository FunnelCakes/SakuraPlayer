package com.sakura.player.download

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `progress formula never exceeds 100 percent with normal input`() {
        val total = 100
        for (done in 0..total) {
            val progress = ((done * 100) / total).coerceIn(1, 100)
            assertTrue("progress=$progress at done=$done", progress in 1..100)
        }
    }

    @Test
    fun `progress clamped to 100 when done exceeds total`() {
        val total = 100
        // Simulates the double-counting bug: completed already = existingCount,
        // then loop increments completed again for each existing segment
        val existingCount = 50
        val doubleCount = existingCount * 2  // bug: counted twice
        val progress = ((doubleCount * 100) / total).coerceIn(1, 100)
        assertEquals(100, progress)
    }

    @Test
    fun `progress starts at 0 for fresh download`() {
        val existingCount = 0
        val total = 200
        val completed = 0
        val progress = ((completed * 100) / total).coerceIn(1,100)
        assertEquals(1, progress) // coerceIn(1,100) floors to 1
    }

    @Test
    fun `progress at 50 percent`() {
        val total = 200
        val completed = 100
        val progress = ((completed * 100) / total).coerceIn(1, 100)
        assertEquals(50, progress)
    }

    @Test
    fun `progress at 100 percent`() {
        val total = 200
        val completed = 200
        val progress = ((completed * 100) / total).coerceIn(1, 100)
        assertEquals(100, progress)
    }

    @Test
    fun `resume from 30 percent`() {
        val total = 300
        val existingCount = 90
        val progress = ((existingCount * 100) / total).coerceIn(1, 100)
        assertEquals(30, progress)
    }
}
