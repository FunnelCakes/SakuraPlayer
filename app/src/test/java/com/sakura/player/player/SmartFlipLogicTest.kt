package com.sakura.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the smart 180° flip decision logic in [SmartFlipLogic].
 *
 * The logic is a pure function so it can be tested on the JVM without any Android
 * framework. Scenarios mirror the real feature:
 *   - In landscape fullscreen, when the device is held ~180° from the display's
 *     current landscape rotation, the view must be rotated 180° so the content stays
 *     upright relative to the user.
 *   - In normal landscape the view must be at 0°.
 *   - In portrait / non-fullscreen the view must never be flipped.
 *   - While locked the current rotation must be left as-is.
 */
class SmartFlipLogicTest {

    private fun flip(
        deviceOrientation: Int,
        displayRotation: Int,
        landscapeFullscreen: Boolean = true,
        locked: Boolean = false,
        current: Float = 0f
    ): Float = SmartFlipLogic.targetRotation(
        deviceOrientationDegrees = deviceOrientation,
        displayRotationDegrees = displayRotation,
        isLandscapeFullscreen = landscapeFullscreen,
        isLocked = locked,
        currentRotation = current
    )

    // ===== Reverse landscape (display ROTATION_90 as "normal") =====

    @Test
    fun `reverse landscape flips 180 when display is rotated 90`() {
        // Display rendered for 90°; device physically held at 270° (upside-down) → flip.
        assertEquals(180f, flip(deviceOrientation = 270, displayRotation = 90))
    }

    @Test
    fun `normal landscape stays 0 when display is rotated 90`() {
        assertEquals(0f, flip(deviceOrientation = 90, displayRotation = 90))
    }

    // ===== Reverse landscape (display ROTATION_270 as "normal") =====

    @Test
    fun `reverse landscape flips 180 when display is rotated 270`() {
        assertEquals(180f, flip(deviceOrientation = 90, displayRotation = 270))
    }

    @Test
    fun `normal landscape stays 0 when display is rotated 270`() {
        assertEquals(0f, flip(deviceOrientation = 270, displayRotation = 270))
    }

    // ===== Device-independent: any 180° swing inverts =====

    @Test
    fun `physical orientation 180 degrees from display rotation always flips`() {
        for (display in intArrayOf(0, 90, 180, 270)) {
            val opposite = (display + 180) % 360
            assertEquals(
                "display=$display opposite=$opposite",
                180f,
                flip(deviceOrientation = opposite, displayRotation = display)
            )
        }
    }

    @Test
    fun `physical orientation matching display rotation never flips`() {
        for (display in intArrayOf(0, 90, 180, 270)) {
            assertEquals(
                "display=$display",
                0f,
                flip(deviceOrientation = display, displayRotation = display)
            )
        }
    }

    // ===== Sensor noise tolerance =====

    @Test
    fun `sensor noise around normal orientation stays upright`() {
        assertEquals(0f, flip(deviceOrientation = 105, displayRotation = 90))
        assertEquals(0f, flip(deviceOrientation = 75, displayRotation = 90))
    }

    @Test
    fun `sensor noise around reverse orientation stays flipped`() {
        assertEquals(180f, flip(deviceOrientation = 255, displayRotation = 90))
        assertEquals(180f, flip(deviceOrientation = 285, displayRotation = 90))
    }

    // ===== Only landscape fullscreen =====

    @Test
    fun `portrait or inline never flips`() {
        assertEquals(0f, flip(deviceOrientation = 270, displayRotation = 90, landscapeFullscreen = false))
        assertEquals(0f, flip(deviceOrientation = 90, displayRotation = 90, landscapeFullscreen = false))
        assertEquals(0f, flip(deviceOrientation = 0, displayRotation = 90, landscapeFullscreen = false))
    }

    // ===== Locked =====

    @Test
    fun `locked keeps current rotation as-is even when upside down`() {
        assertEquals(0f, flip(deviceOrientation = 270, displayRotation = 90, locked = true, current = 0f))
        assertEquals(180f, flip(deviceOrientation = 90, displayRotation = 90, locked = true, current = 180f))
    }

    @Test
    fun `unlocked engages flip when upside down`() {
        assertEquals(180f, flip(deviceOrientation = 270, displayRotation = 90, locked = false, current = 0f))
    }

    // ===== Unknown sensor =====

    @Test
    fun `unknown sensor reading leaves rotation as-is`() {
        assertEquals(0f, flip(deviceOrientation = -1, displayRotation = 90, current = 0f))
        assertEquals(180f, flip(deviceOrientation = -1, displayRotation = 90, current = 180f))
    }

    // ===== Angular distance helper =====

    @Test
    fun `angular distance wraps around 360`() {
        assertEquals(0, SmartFlipLogic.angularDistance(0, 360))
        assertEquals(180, SmartFlipLogic.angularDistance(0, 180))
        assertEquals(180, SmartFlipLogic.angularDistance(90, 270))
        assertEquals(90, SmartFlipLogic.angularDistance(0, 90))
        assertEquals(10, SmartFlipLogic.angularDistance(350, 0))
    }
}
