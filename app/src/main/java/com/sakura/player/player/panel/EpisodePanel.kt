package com.sakura.player.player.panel

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.sakura.player.player.EpisodeItem

class EpisodePanel(context: Context) : FrameLayout(context) {

    var onEpisodeSelected: ((Int) -> Unit)? = null
    private val contentView: LinearLayout
    private val episodeList: LinearLayout
    private var isShowing = false

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
            text = "选集"; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }
        contentView.addView(titleText)

        episodeList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentView.addView(episodeList)
        addView(contentView)
    }

    fun show(episodes: List<EpisodeItem>, currentIndex: Int, compact: Boolean = false) {
        val pad = if (compact) 10 else 16
        val rowPadV = if (compact) 6 else 10
        val rowTextSize = if (compact) 12f else 14f
        contentView.setPadding(pad, pad * 2, pad, pad * 2)

        episodeList.removeAllViews()
        episodes.forEach { ep ->
            val row = TextView(context).apply {
                text = ep.name; textSize = rowTextSize
                setTextColor(if (ep.index == currentIndex) Color.parseColor("#FB7299") else Color.WHITE)
                setPadding(pad, rowPadV, pad, rowPadV)
                setOnClickListener {
                    onEpisodeSelected?.invoke(ep.index); hide()
                }
            }
            episodeList.addView(row)
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

    fun toggle(episodes: List<EpisodeItem>, currentIndex: Int, compact: Boolean = false) {
        if (isShowing) hide() else show(episodes, currentIndex, compact)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return isShowing && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        // Tap overlay background to dismiss
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
