package com.sakura.player.player.panel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*

class SpeedPanel(context: Context) : FrameLayout(context) {

    var onSpeedSelected: ((Float) -> Unit)? = null
    private val contentView: LinearLayout
    private var isShowing = false

    private val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val speedLabels = listOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x")

    init {
        setBackgroundColor(Color.argb(100, 0, 0, 0))
        setOnClickListener { if (isShowing) hide() }
        visibility = View.GONE
        isClickable = false
        isFocusable = false

        contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            setPadding(16, 32, 16, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
        }

        val titleText = TextView(context).apply {
            text = "播放速度"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        contentView.addView(titleText)

        speeds.forEachIndexed { i, speed ->
            val row = TextView(context).apply {
                text = speedLabels[i]
                textSize = 14f
                setPadding(8, 14, 8, 14)
                setOnClickListener {
                    onSpeedSelected?.invoke(speed)
                    hide()
                }
            }
            contentView.addView(row)
        }
        addView(contentView)
    }

    fun show(currentSpeed: Float) {
        // Highlight current speed
        for (i in speeds.indices) {
            val row = contentView.getChildAt(i + 1) as? TextView ?: continue
            row.setTextColor(if (speeds[i] == currentSpeed) Color.parseColor("#FB7299") else Color.WHITE)
        }
        isClickable = true; isFocusable = true
        visibility = View.VISIBLE
        // Ensure layout is measured before animating
        post {
            translationY = contentView.height.toFloat()
            animate().translationY(0f).setDuration(300)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        isShowing = true
    }

    fun hide() {
        isClickable = false; isFocusable = false
        val targetY = contentView.height.toFloat()
        animate().translationY(targetY).setDuration(200)
            .withEndAction { visibility = View.GONE }.start()
        postDelayed({ visibility = View.GONE }, 300)
        isShowing = false
    }

    fun toggle(currentSpeed: Float) {
        if (isShowing) hide() else show(currentSpeed)
    }
}
