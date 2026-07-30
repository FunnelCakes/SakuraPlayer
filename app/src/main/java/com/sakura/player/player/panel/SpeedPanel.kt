package com.sakura.player.player.panel

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
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
        visibility = View.GONE
        isClickable = false; isFocusable = false

        contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }

        val titleText = TextView(context).apply {
            text = "播放速度"; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }
        contentView.addView(titleText)

        speeds.forEachIndexed { i, speed ->
            val row = TextView(context).apply {
                text = speedLabels[i]; textSize = 14f
                setPadding(8, 10, 8, 10)
                setOnClickListener { onSpeedSelected?.invoke(speed); hide() }
            }
            contentView.addView(row)
        }
        addView(contentView)
    }

    fun show(currentSpeed: Float, compact: Boolean = false) {
        val pad = if (compact) 10 else 16
        val rowPadV = if (compact) 6 else 10
        val rowTextSize = if (compact) 12f else 14f
        contentView.setPadding(pad, pad * 2, pad, pad * 2)

        for (i in speeds.indices) {
            val row = contentView.getChildAt(i + 1) as? TextView ?: continue
            row.textSize = rowTextSize
            row.setPadding(pad, rowPadV, pad, rowPadV)
            row.setTextColor(if (speeds[i] == currentSpeed) Color.parseColor("#FB7299") else Color.WHITE)
        }
        isClickable = true; isFocusable = true
        visibility = View.VISIBLE
        post {
            translationY = contentView.height.toFloat()
            animate().translationY(0f).setDuration(250)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        isShowing = true
    }

    fun hide() {
        isClickable = false; isFocusable = false; isShowing = false
        val targetY = contentView.height.toFloat()
        animate().translationY(targetY).setDuration(150)
            .withEndAction { visibility = View.GONE }.start()
        postDelayed({ visibility = View.GONE }, 200)
    }

    fun toggle(currentSpeed: Float, compact: Boolean = false) {
        if (isShowing) hide() else show(currentSpeed, compact)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return isShowing && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        if (event.action == MotionEvent.ACTION_UP && !isChildHit(contentView, event)) {
            hide(); return true
        }
        return super.onTouchEvent(event)
    }

    private fun isChildHit(child: View, event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        return x >= child.left && x <= child.right && y >= child.top && y <= child.bottom
    }
}
