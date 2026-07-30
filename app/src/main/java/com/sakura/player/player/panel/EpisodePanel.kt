package com.sakura.player.player.panel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
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
            text = "选集"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        contentView.addView(titleText)

        episodeList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentView.addView(episodeList)
        addView(contentView)
    }

    fun show(episodes: List<EpisodeItem>, currentIndex: Int) {
        episodeList.removeAllViews()
        episodes.forEach { ep ->
            val row = TextView(context).apply {
                text = ep.name
                textSize = 14f
                setTextColor(if (ep.index == currentIndex) Color.parseColor("#FB7299") else Color.WHITE)
                setPadding(8, 16, 8, 16)
                setOnClickListener {
                    onEpisodeSelected?.invoke(ep.index)
                    hide()
                }
            }
            episodeList.addView(row)
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
        postDelayed({ visibility = View.GONE }, 300) // safety: always hide after animation
        isShowing = false
    }

    fun toggle(episodes: List<EpisodeItem>, currentIndex: Int) {
        if (isShowing) hide() else show(episodes, currentIndex)
    }

    /** Block all touch when invisible. */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        return isShowing && super.onTouchEvent(event)
    }
}
