package com.sakura.player.player.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

class SideHUD(context: Context, private val alignLeft: Boolean) : FrameLayout(context) {

    private val iconView: TextView
    private val barView: BarView
    private val labelView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    enum class Type { BRIGHTNESS, VOLUME }

    init {
        // Container styling
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (alignLeft) Gravity.START else Gravity.END)
        }

        background = GradientDrawable().apply {
            setColor(Color.argb(160, 0, 0, 0))
            cornerRadius = 16f
        }
        val p = 16; setPadding(p, p, p, p)

        // Icon
        iconView = TextView(context).apply {
            textSize = 24f; gravity = Gravity.CENTER
        }
        addView(iconView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Progress bar (taller, centered)
        barView = BarView(context)
        addView(barView, LayoutParams(6, 120).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 10
        })

        // Percentage label
        labelView = TextView(context).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        addView(labelView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 6 })

        alpha = 0f; isClickable = false; isFocusable = false
    }

    fun show(type: Type, value: Int) {
        when (type) {
            Type.BRIGHTNESS -> { iconView.text = "\u2600"; barView.level = value }
            Type.VOLUME -> {
                iconView.text = when { value == 0 -> "\uD83D\uDD07"; value < 50 -> "\uD83D\uDD09"; else -> "\uD83D\uDD0A" }
                barView.level = value
            }
        }
        labelView.text = "$value%"
        alpha = 1f
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable { animate().alpha(0f).setDuration(300).start() }
        handler.postDelayed(hideRunnable!!, 800)
    }

    private class BarView(context: Context) : View(context) {
        var level: Int = 50
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255) }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FB7299") }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, 3f, 3f, bgPaint)
            val fillH = h * level.coerceIn(0, 100) / 100
            canvas.drawRoundRect(0f, h - fillH, w, h, 3f, 3f, fillPaint)
        }
    }
}
