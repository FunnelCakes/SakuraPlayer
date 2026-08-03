package com.sakura.player.player

/**
 * Pure decision logic for the "smart 180° flip" feature.
 *
 * In landscape fullscreen, when the phone is held 180° from the display's current
 * landscape rotation, the video/UI appear upside-down to the user. Rotating the
 * player view 180° reorients the content so it is upright relative to the user.
 *
 * Extracted into a framework-free object so it can be unit-tested on the JVM.
 */
object SmartFlipLogic {

    /** Rotation value for the normal (upright) state. */
    const val ROTATION_NORMAL = 0f

    /** Rotation value for the flipped (upside-down device) state. */
    const val ROTATION_FLIPPED = 180f

    /**
     * Decide the target rotation for the player view.
     *
     * @param deviceOrientationDegrees orientation reported by
     *   [android.view.OrientationEventListener]: 0-359 degrees, or -1 when unknown.
     * @param displayRotationDegrees   the display's current rotation mapped to degrees
     *   (0 / 90 / 180 / 270) — i.e. the rotation the screen is currently rendered in.
     * @param isLandscapeFullscreen    true when the player is the fullscreen player AND the
     *   activity is in landscape (the only mode where a flip makes sense).
     * @param isLocked                 true when the player controls are locked — the flip is
     *   then left as-is (never engaged/disengaged by orientation while locked).
     * @param currentRotation          the view's current rotation, returned unchanged when the
     *   sensor is unknown or the player is locked ("stays as-is").
     */
    fun targetRotation(
        deviceOrientationDegrees: Int,
        displayRotationDegrees: Int,
        isLandscapeFullscreen: Boolean,
        isLocked: Boolean,
        currentRotation: Float
    ): Float {
        // Outside landscape fullscreen the content is never flipped.
        if (!isLandscapeFullscreen) return ROTATION_NORMAL

        // No reliable sensor reading / locked controls: leave the current rotation as-is.
        if (deviceOrientationDegrees < 0 || isLocked) return currentRotation

        // The content appears upside-down when the physical device orientation is ~180°
        // away from the rotation the display is currently rendered in.
        val diff = angularDistance(deviceOrientationDegrees, displayRotationDegrees)
        return if (diff > 90) ROTATION_FLIPPED else ROTATION_NORMAL
    }

    /**
     * Smallest angular distance between [a] and [b] in degrees, in [0, 180].
     */
    fun angularDistance(a: Int, b: Int): Int {
        val d = ((a - b) % 360 + 360) % 360
        return minOf(d, 360 - d)
    }
}
