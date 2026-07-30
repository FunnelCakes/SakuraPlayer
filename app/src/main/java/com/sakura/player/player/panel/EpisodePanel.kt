package com.sakura.player.player.panel

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sakura.player.player.EpisodeItem

/**
 * Bottom-sheet episode selector. Mirrors B站's episode list panel.
 * Compact layout for half-screen (INLINE), full layout for fullscreen.
 * Completely non-interactive when hidden — visibility=GONE, no animation tricks.
 */
class EpisodePanel(context: Context) : FrameLayout(context) {

    var onEpisodeSelected: ((Int) -> Unit)? = null
    private val contentView: LinearLayout
    private val episodeList: LinearLayout
    private var _showing = false
    val isShowing: Boolean get() = _showing

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
            text = "选集"; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }
        contentView.addView(title)

        episodeList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        contentView.addView(episodeList)

        // Tap overlay background → dismiss
        setOnClickListener {
            if (_showing) hide()
        }

        addView(contentView)
    }

    fun show(episodes: List<EpisodeItem>, currentIndex: Int, compact: Boolean = false) {
        _showing = true
        val p = if (compact) 12 else 16
        contentView.setPadding(p, p * 2, p, p * 2)

        episodeList.removeAllViews()
        for (ep in episodes) {
            val row = TextView(context).apply {
                text = ep.name
                textSize = if (compact) 13f else 15f
                setTextColor(if (ep.index == currentIndex) Color.parseColor("#FB7299") else Color.WHITE)
                setPadding(p, if (compact) 8 else 12, p, if (compact) 8 else 12)
                setOnClickListener {
                    onEpisodeSelected?.invoke(ep.index)
                    hide()
                }
            }
            episodeList.addView(row)
        }
        visibility = View.VISIBLE
    }

    fun hide() {
        _showing = false
        visibility = View.GONE
    }

    fun toggle(episodes: List<EpisodeItem>, currentIndex: Int, compact: Boolean = false) {
        if (_showing) hide() else show(episodes, currentIndex, compact)
    }
}
