package com.sakura.player.player.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Layer 4: Side HUD for brightness (left) and volume (right) indicators.
 *
 * Displays a vertical progress bar with an icon and percentage label.
 * Automatically hides 800ms after the last [show] call via a fade-out animation.
 */
class SideHUD(context: Context, private val alignLeft: Boolean) : FrameLayout(context) {

    private val iconView: TextView
    private val barView: BarView
    private val labelView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var fadeAnim: ValueAnimator? = null

    enum class Type { BRIGHTNESS, VOLUME }

    init {
        layoutParams = LayoutParams(80, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or
                (if (alignLeft) Gravity.START else Gravity.END)
            setMargins(
                if (alignLeft) 16 else 0, 0,
                if (alignLeft) 0 else 16, 0
            )
        }

        // Semi-transparent black rounded background
        background = GradientDrawable().apply {
            setColor(Color.argb(180, 0, 0, 0))
            cornerRadius = 12f
        }

        val padding = 12
        setPadding(padding, padding, padding, padding)

        // Icon at the top
        iconView = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }
        addView(
            iconView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        // Vertical progress bar
        barView = BarView(context)
        addView(
            barView,
            LayoutParams(6, 100).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 8
            }
        )

        // Percentage label
        labelView = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        addView(
            labelView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 4
            }
        )

        alpha = 0f
        isClickable = false
        isFocusable = false
    }

    /**
     * Show the HUD with the given type and current value (0–100).
     * Resets the auto-hide timer to 800ms on each call.
     */
    fun show(type: Type, value: Int) {
        when (type) {
            Type.BRIGHTNESS -> {
                iconView.text = "\u2600" // ☀
                barView.level = value
            }
            Type.VOLUME -> {
                iconView.text = if (value == 0) "\uD83D\uDD07" else "\uD83D\uDD0A"
                barView.level = value
            }
        }
        labelView.text = "$value%"

        // Cancel any in-progress fade-out
        fadeAnim?.cancel()
        alpha = 1f

        // Reset the auto-hide timer
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable {
            fadeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addUpdateListener { alpha = it.animatedValue as Float }
                start()
            }
        }
        handler.postDelayed(hideRunnable!!, 800)
    }

    /**
     * Custom vertical progress bar drawn with Canvas.
     * Background: semi-transparent white. Fill: B站 pink (#FB7299).
     * Fill grows from bottom to top.
     */
    private class BarView(context: Context) : View(context) {
        var level: Int = 50

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 255, 255)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FB7299")
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // Background bar
            canvas.drawRoundRect(0f, 0f, w, h, 3f, 3f, bgPaint)

            // Fill from bottom
            val fillH = h * level.coerceIn(0, 100) / 100
            canvas.drawRoundRect(0f, h - fillH, w, h, 3f, 3f, fillPaint)
        }
    }
}
