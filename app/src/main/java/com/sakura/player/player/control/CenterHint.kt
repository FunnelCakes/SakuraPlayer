package com.sakura.player.player.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Layer 3: Center floating hint overlay.
 *
 * Displays a large play/pause/rewind/forward icon that fades out after a
 * brief hold, plus an optional speed label (e.g. "2x").
 */
class CenterHint(context: Context) : FrameLayout(context) {

    private val iconView: TextView
    private val labelView: TextView
    private var fadeAnim: ValueAnimator? = null

    enum class Type { PLAY, PAUSE, REWIND, FORWARD }

    init {
        // Large centered icon -- initially invisible
        iconView = TextView(context).apply {
            textSize = 48f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 2f, Color.argb(120, 0, 0, 0))
            gravity = Gravity.CENTER
            alpha = 0f
        }
        addView(
            iconView,
            LayoutParams(120, 120).apply {
                gravity = Gravity.CENTER
            }
        )

        // Speed label (e.g. "2x") positioned just below the icon
        labelView = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setShadowLayer(3f, 0f, 1f, Color.argb(100, 0, 0, 0))
            gravity = Gravity.CENTER
            alpha = 0f
        }
        addView(
            labelView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 140
            }
        )

        // Pass through touch events so the gesture overlay beneath can still receive them
        isClickable = false
        isFocusable = false
    }

    /**
     * Show a centered icon with a brief hold time, then animate it away.
     */
    fun show(type: Type) {
        iconView.text = when (type) {
            Type.PLAY -> "\u25B6"      // ▶
            Type.PAUSE -> "\u23F8"     // ⏸
            Type.REWIND -> "\u23EE"    // ⏮
            Type.FORWARD -> "\u23ED"   // ⏭
        }
        iconView.alpha = 1f

        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 300
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { iconView.alpha = it.animatedValue as Float }
            start()
        }
    }

    /**
     * Show a text hint below the icon, e.g. "2x" for speed.
     */
    fun showSpeedHint(text: String) {
        labelView.text = text
        labelView.alpha = 1f
    }

    /**
     * Hide the speed hint label.
     */
    fun hideSpeedHint() {
        labelView.alpha = 0f
    }
}
