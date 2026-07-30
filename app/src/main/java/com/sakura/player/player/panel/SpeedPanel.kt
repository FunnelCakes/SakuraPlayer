package com.sakura.player.player.panel

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class SpeedPanel(context: Context) : FrameLayout(context) {

    var onSpeedSelected: ((Float) -> Unit)? = null
    private val contentView: LinearLayout
    private var _showing = false
    val isShowing: Boolean get() = _showing

    private val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val speedLabels = listOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x")

    init {
        setBackgroundColor(Color.argb(100, 0, 0, 0))
        visibility = View.GONE

        contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }

        val title = TextView(context).apply {
            text = "播放速度"; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }
        contentView.addView(title)

        for (i in speeds.indices) {
            val row = TextView(context).apply {
                text = speedLabels[i]; textSize = 15f
                setPadding(8, 12, 8, 12)
                setOnClickListener {
                    onSpeedSelected?.invoke(speeds[i])
                    hide()
                }
            }
            contentView.addView(row)
        }

        setOnClickListener {
            if (_showing) hide()
        }

        addView(contentView)
    }

    fun show(currentSpeed: Float, compact: Boolean = false) {
        _showing = true
        val p = if (compact) 12 else 16
        contentView.setPadding(p, p * 2, p, p * 2)

        for (i in speeds.indices) {
            val row = contentView.getChildAt(i + 1) as? TextView ?: continue
            row.textSize = if (compact) 13f else 15f
            row.setPadding(p, if (compact) 8 else 12, p, if (compact) 8 else 12)
            row.setTextColor(if (speeds[i] == currentSpeed) Color.parseColor("#FB7299") else Color.WHITE)
        }
        visibility = View.VISIBLE
    }

    fun hide() {
        _showing = false
        visibility = View.GONE
    }

    fun toggle(currentSpeed: Float, compact: Boolean = false) {
        if (_showing) hide() else show(currentSpeed, compact)
    }
}
