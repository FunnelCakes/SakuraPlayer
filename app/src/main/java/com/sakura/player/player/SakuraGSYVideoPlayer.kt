package com.sakura.player.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYBaseVideoPlayer

/**
 * Direction of an episode navigation request fired by the control-bar buttons.
 */
enum class EpisodeNav { PREV, NEXT, SELECT }

/**
 * A single episode entry passed from JS and stored on the player.
 * For online episodes [path] is empty; for local episodes [path] holds the
 * absolute file path.
 */
data class PlayerEpisode(
    val index: Int,
    val name: String,
    val path: String = ""
)

/**
 * Custom GSY video player that adds the five missing features:
 *
 * 1. Long-press the video surface to play at 2x speed (released on finger-up).
 * 2. Fullscreen lock button (enabled via setNeedLockFull(true) in MainActivity).
 * 3. Prev / next episode buttons on the control bar.
 * 4. Playback-speed selector button that cycles presets and applies setSpeed().
 * 5. Episode selector button that opens a native dialog.
 *
 * The (Context, Boolean) constructor is REQUIRED: GSY instantiates the
 * fullscreen clone via reflection (getConstructor(Context.class, Boolean.class)).
 * cloneParams() is overridden so the fullscreen clone receives the episode
 * list, current index, navigation callback and speed state from the inline
 * instance.
 */
@SuppressLint("ViewConstructor")
class SakuraGSYVideoPlayer : StandardGSYVideoPlayer {

    // ==================== Episode data ====================

    /** Episode list (index/name/path) parsed from the JSON passed by JS. */
    var episodeList: List<PlayerEpisode> = emptyList()

    /** Currently playing episode index. */
    var currentEpIndex: Int = 0

    /** True when the current source is a local file (path-based). */
    var isLocal: Boolean = false

    /** Website videoId used to re-resolve m3u8 for online episode switches. */
    var currentVideoId: Long = 0

    /** Callback fired when prev/next/select episode is requested. */
    var onEpisodeNav: ((EpisodeNav, Int) -> Unit)? = null

    // ==================== Long-press 2x speed ====================

    private var longPressSpeedActive = false
    private var speedBeforeLongPress = 1f

    // ==================== Speed selector ====================

    private val speedPresets = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

    // ==================== Custom control views ====================

    private var speedBtn: TextView? = null
    private var prevBtn: View? = null
    private var nextBtn: View? = null
    private var episodeBtn: TextView? = null
    private var controlsAdded = false

    // ==================== Constructors ====================

    // fullFlag must be `Boolean?` (boxed java.lang.Boolean): GSY instantiates the
    // fullscreen clone via getConstructor(Context.class, Boolean.class) and a
    // non-null Kotlin Boolean would compile to a primitive `boolean` signature,
    // which the reflection lookup would NOT find.
    constructor(context: Context, fullFlag: Boolean?) : super(context, fullFlag)

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    // ==================== Init ====================

    override fun init(context: Context) {
        super.init(context)
        addCustomControls()
    }

    /**
     * Build the speed / prev / next / episode-selector buttons and add them to
     * the bottom control bar (just before the fullscreen button).
     */
    private fun addCustomControls() {
        if (controlsAdded) return
        controlsAdded = true

        val bottom = mBottomContainer ?: return
        val ctx = context ?: return

        try {
            prevBtn = ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_media_previous)
                contentDescription = "上一集"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onEpisodeNav?.invoke(EpisodeNav.PREV, currentEpIndex)
                }
            }

            nextBtn = ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_media_next)
                contentDescription = "下一集"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onEpisodeNav?.invoke(EpisodeNav.NEXT, currentEpIndex)
                }
            }

            speedBtn = TextView(ctx).apply {
                text = formatSpeed(getSpeed())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { cycleSpeed() }
            }

            episodeBtn = TextView(ctx).apply {
                text = "\u2261\u9009\u96c6" // ≡选集
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { showEpisodeDialog() }
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            val fullscreenIndex = bottom.indexOfChild(mFullscreenButton)
            val insertIndex = if (fullscreenIndex >= 0) fullscreenIndex else bottom.childCount

            bottom.addView(prevBtn, insertIndex, lp)
            bottom.addView(nextBtn, insertIndex + 1, lp)
            bottom.addView(speedBtn, insertIndex + 2, lp)
            bottom.addView(episodeBtn, insertIndex + 3, lp)
        } catch (e: Exception) {
            // Never crash the player if the standard layout changes underneath us.
            e.printStackTrace()
        }
    }

    // ==================== Long-press 2x speed ====================

    /**
     * GSY calls this when the GestureDetector detects a long-press (gated by
     * setIsTouchWiget / setIsTouchWigetFull, both enabled in MainActivity).
     * Speed up to 2x. NOTE: setSpeedPlaying() is a no-op in the Exo2 player
     * manager, so we must use setSpeed(float).
     */
    override fun touchLongPress(e: MotionEvent?) {
        super.touchLongPress(e)
        if (longPressSpeedActive) return
        val state = currentState
        // Only when actually playing/buffering.
        if (state != CURRENT_STATE_PLAYING && state != CURRENT_STATE_PLAYING_BUFFERING_START) return
        longPressSpeedActive = true
        speedBeforeLongPress = getSpeed()
        setSpeed(2f)
        Toast.makeText(context, "2x快放", Toast.LENGTH_SHORT).show()
    }

    /**
     * Reset the speed back to the pre-long-press value when the finger lifts.
     */
    override fun touchSurfaceUp() {
        if (longPressSpeedActive) {
            longPressSpeedActive = false
            setSpeed(speedBeforeLongPress)
            updateSpeedBtn()
        }
        super.touchSurfaceUp()
    }

    // ==================== Top-edge status bar reserve ====================

    /**
     * Pixels reserved at the top of the SCREEN for the system status-bar swipe.
     *
     * Touches that START inside this strip are passed through to the system
     * instead of being consumed by GSY's GestureDetector, which would otherwise
     * turn a status-bar pull-down into a brightness/volume/seek gesture. This is
     * what B站 does by reserving the top ~100px of the screen.
     */
    private val statusBarReservePx: Int by lazy {
        // Read the real status-bar height (may be tall on notch/cutout devices).
        val statusBarHeight = try {
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        } catch (e: Exception) {
            0
        }
        // Reserve the status bar plus a comfortable margin (>= 60dp) so the
        // top-edge swipe reliably wins over GSY's gesture detector.
        maxOf(statusBarHeight + dp(16), dp(60))
    }

    /**
     * Pass touches that start in the screen's top status-bar strip through to
     * the system so the notification shade / status bar can be pulled down
     * (mainly in fullscreen).
     *
     * rawY is screen-absolute, so this only reserves the real top of the screen:
     * in inline mode the player is usually not at the screen top, so normal GSY
     * gestures keep working everywhere on the inline surface.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN &&
            ev.rawY < statusBarReservePx &&
            !isTopControlAt(ev.x, ev.y)
        ) {
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * True when (x, y) -- player-local coordinates -- lands on a tappable top
     * control that must keep working (the fullscreen back button and the lock
     * button), so reserving the top strip does not break them.
     */
    private fun isTopControlAt(x: Float, y: Float): Boolean {
        val controls = listOfNotNull<View>(mBackButton, mLockScreen)
        if (controls.isEmpty()) return false
        val playerLoc = IntArray(2)
        getLocationInWindow(playerLoc)
        for (c in controls) {
            if (c.visibility != View.VISIBLE) continue
            val loc = IntArray(2)
            c.getLocationInWindow(loc)
            val left = loc[0] - playerLoc[0]
            val top = loc[1] - playerLoc[1]
            if (x >= left && x <= left + c.width && y >= top && y <= top + c.height) {
                return true
            }
        }
        return false
    }

    // ==================== Speed selector ====================

    private fun cycleSpeed() {
        val cur = getSpeed().coerceIn(0.25f, 3f)
        val idx = nearestPresetIndex(cur)
        val next = speedPresets[(idx + 1) % speedPresets.size]
        setSpeed(next)
        updateSpeedBtn()
        Toast.makeText(context, "倍速 ${formatSpeed(next)}", Toast.LENGTH_SHORT).show()
    }

    private fun nearestPresetIndex(speed: Float): Int {
        var best = 0
        var bestDiff = Float.MAX_VALUE
        for (i in speedPresets.indices) {
            val diff = kotlin.math.abs(speedPresets[i] - speed)
            if (diff < bestDiff) {
                bestDiff = diff
                best = i
            }
        }
        return best
    }

    private fun updateSpeedBtn() {
        speedBtn?.text = formatSpeed(getSpeed())
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed % 1.0f == 0.0f) {
            String.format("%.1fx", speed)
        } else {
            String.format("%.2fx", speed).trimEnd('0').trimEnd('.') + "x"
        }
    }

    // ==================== Episode selector ====================

    private fun showEpisodeDialog() {
        val ctx = context ?: return
        if (episodeList.isEmpty()) {
            Toast.makeText(ctx, "暂无剧集列表", Toast.LENGTH_SHORT).show()
            return
        }
        val names = episodeList.map { it.name.ifBlank { "第${it.index}集" } }.toTypedArray()
        val currentIdx = episodeList.indexOfFirst { it.index == currentEpIndex }.let { if (it < 0) 0 else it }

        android.app.AlertDialog.Builder(ctx)
            .setTitle("选集")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                dialog.dismiss()
                if (which in episodeList.indices) {
                    val ep = episodeList[which]
                    currentEpIndex = ep.index
                    onEpisodeNav?.invoke(EpisodeNav.SELECT, ep.index)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== Fullscreen clone params ====================

    /**
     * Copy the episode data and navigation callback to the fullscreen clone
     * (and back, when exiting fullscreen). Without this the fullscreen window
     * would have an empty episode list and dead prev/next buttons.
     */
    override fun cloneParams(from: GSYBaseVideoPlayer?, to: GSYBaseVideoPlayer?) {
        super.cloneParams(from, to)
        if (from is SakuraGSYVideoPlayer && to is SakuraGSYVideoPlayer) {
            to.episodeList = from.episodeList
            to.currentEpIndex = from.currentEpIndex
            to.isLocal = from.isLocal
            to.currentVideoId = from.currentVideoId
            to.onEpisodeNav = from.onEpisodeNav
            to.updateSpeedBtn()
        }
    }

    // ==================== Helpers ====================

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
