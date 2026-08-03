package com.sakura.player

import org.junit.Assert.*
import org.junit.Test

/**
 * Mirrors the inline-player m3u8 resolution guard in MainActivity. When the user backs
 * out during the async m3u8 resolution (hideInlinePlayer), the pending resolveJob is
 * cancelled AND inlinePlayerActive is set false, so a stale completion can never
 * re-show the GSY player after the detail page closed.
 */
class InlinePlayerBackGuardTest {

    // State shared by the mirrored MainActivity snippets
    private class PlayerState {
        var inlinePlayerActive = false
        var resolveJobCancelled = false
        var setupCalls = 0
        var visible = false
    }

    private fun playOnlineInline(state: PlayerState) {
        // begin async m3u8 resolution
        state.inlinePlayerActive = true
        state.resolveJobCancelled = false
    }

    private fun onResolutionComplete(state: PlayerState) {
        // The fix: a late completion is skipped when the inline player was hidden
        // (cancellation is cooperative, so this guard catches the back-during-loading race).
        if (!state.inlinePlayerActive) return
        state.setupCalls++
        state.visible = true
    }

    private fun hideInlinePlayer(state: PlayerState) {
        state.inlinePlayerActive = false
        state.resolveJobCancelled = true
        state.visible = false
    }

    @Test
    fun `stale m3u8 completion does not re-show the player after backing out during loading`() {
        val s = PlayerState()
        playOnlineInline(s)
        // user presses back while m3u8 is still resolving
        hideInlinePlayer(s)
        // the resolve coroutine had already fetched the URL and its completion fires late
        onResolutionComplete(s)

        assertEquals(0, s.setupCalls)
        assertFalse(s.visible)
    }

    @Test
    fun `resolution completion still sets up the player when the user did not back out`() {
        val s = PlayerState()
        playOnlineInline(s)
        onResolutionComplete(s)

        assertEquals(1, s.setupCalls)
        assertTrue(s.visible)
    }

    @Test
    fun `backing out after playback started hides the player`() {
        val s = PlayerState()
        playOnlineInline(s)
        onResolutionComplete(s)  // player set up and playing
        hideInlinePlayer(s)      // then user backs out

        assertFalse(s.visible)
        assertTrue(s.resolveJobCancelled)
    }
}
