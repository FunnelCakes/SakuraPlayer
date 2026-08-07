package com.sakura.player.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYBaseVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer

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
 * 3. Prev / next episode buttons on the control bar (FULLSCREEN ONLY — hidden in
 *    the inline / half-screen player via [updateControlsVisibility]).
 * 4. Playback-speed selector button that opens a GSY-styled popup menu of presets
 *    and applies setSpeed() (visible in both inline and fullscreen).
 * 5. Episode selector button that opens a GSY-styled popup menu (FULLSCREEN ONLY).
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
     * the bottom control bar (just before the fullscreen button). Prev / next /
     * episode are only relevant in fullscreen, so their visibility is applied by
     * [updateControlsVisibility] (they stay hidden in the inline half-screen bar).
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
                setOnClickListener { showSpeedMenu() }
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

            updateControlsVisibility()
        } catch (e: Exception) {
            // Never crash the player if the standard layout changes underneath us.
            e.printStackTrace()
        }
    }

    /**
     * Show prev / next / episode-selector ONLY in the fullscreen clone, and keep
     * the speed selector visible in both inline and fullscreen. GSY's
     * [isIfCurrentIsFullscreen] is true on the fullscreen window and false on the
     * inline (half-screen) player, so hiding is driven by that flag.
     *
     * Called from [addCustomControls] for the initial state, from
     * [setStateAndUi] on every UI-state transition, and from
     * [resolveNormalVideoShow] AFTER the flag flips back to false on fullscreen
     * exit (so the inline player re-hides its fullscreen-only buttons).
     */
    private fun updateControlsVisibility() {
        val isFullscreen = isIfCurrentIsFullscreen()
        prevBtn?.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        nextBtn?.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        episodeBtn?.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        speedBtn?.visibility = View.VISIBLE
    }

    // ==================== UI state / fullscreen hooks ====================

    /**
     * Refresh fullscreen-only button visibility on every UI-state transition
     * (GSY calls this on prepare/play/pause/complete/error and on fullscreen
     * enter/exit). Safe to call before controls exist.
     */
    override fun setStateAndUi(state: Int) {
        super.setStateAndUi(state)
        updateControlsVisibility()
    }

    /**
     * GSY calls this on the inline player when leaving fullscreen. The base
     * implementation flips [mIfCurrentIsFullscreen] to false at the very end, so
     * we refresh our button visibility AFTER calling super to re-hide the
     * fullscreen-only prev/next/episode controls.
     */
    override fun resolveNormalVideoShow(oldF: View?, vp: ViewGroup?, gsyVideoPlayer: GSYVideoPlayer?) {
        super.resolveNormalVideoShow(oldF, vp, gsyVideoPlayer)
        updateControlsVisibility()
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
        // An interrupted touch (physical back / home / lock / notification shade /
        // incoming call / overlay) is torn down with ACTION_CANCEL and never reaches
        // GSY's touchSurfaceUp(), so the HUD dialogs would otherwise leak. Dismiss
        // them here, on the way out, for both the inline player and the fullscreen
        // clone (whichever receives the cancelled touch).
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            dismissGestureDialogs()
        }
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

    // ==================== HUD dialog cleanup (seek-bubble leak fix) ====================

    /**
     * Dismiss the seek-preview (progress), volume and brightness HUD dialogs if any
     * are showing. GSY only dismisses these from touchSurfaceUp() on the surface
     * container's ACTION_UP, so every other teardown path (detach, ACTION_CANCEL,
     * activity pause) must call this to avoid leaking the seek bubble — a Dialog
     * window owned by the Activity that outlives the player view. The three dismiss
     * methods are protected on StandardGSYVideoPlayer and are no-ops when the
     * corresponding dialog is null / not showing.
     */
    fun dismissGestureDialogs() {
        try { dismissProgressDialog() } catch (_: Exception) {}
        try { dismissVolumeDialog() } catch (_: Exception) {}
        try { dismissBrightnessDialog() } catch (_: Exception) {}
    }

    /**
     * GSY's own onDetachedFromWindow() dismisses the volume/brightness dialogs but
     * omits the seek progress dialog. On fullscreen exit (physical back, back/shrink
     * button, rotation) the fullscreen clone is removed from the window mid-gesture,
     * so the seek bubble would otherwise survive the detach. Dismiss all three here
     * before super runs its cleanup.
     */
    override fun onDetachedFromWindow() {
        dismissGestureDialogs()
        super.onDetachedFromWindow()
    }

    /**
     * Belt-and-braces for the Home / lock / app-background path: MainActivity pauses
     * the inline player on onPause/onStop. Dismissing the HUD dialogs here guarantees
     * a mid-gesture bubble is not re-shown when the app resumes. (The fullscreen clone
     * is additionally covered by MainActivity's own pause cleanup.)
     */
    override fun onVideoPause() {
        dismissGestureDialogs()
        super.onVideoPause()
    }

    // ==================== Speed selector ====================

    /**
     * Open the GSY-styled popup menu listing every speed preset. The current
     * speed is highlighted; selecting a preset applies it via setSpeed() and
     * closes the menu. Works in both inline and fullscreen (this button is kept
     * visible in the inline bar — see [updateControlsVisibility]).
     */
    private fun showSpeedMenu() {
        val ctx = context ?: return
        val anchor = speedBtn ?: return
        val cur = getSpeed().coerceIn(0.25f, 3f)
        val idx = nearestPresetIndex(cur)
        showGsyDropdown(
            anchor = anchor,
            title = "播放速度",
            items = speedPresets.map { formatSpeed(it) },
            currentIndex = idx
        ) { which ->
            val speed = speedPresets[which]
            setSpeed(speed)
            updateSpeedBtn()
            Toast.makeText(ctx, "倍速 ${formatSpeed(speed)}", Toast.LENGTH_SHORT).show()
        }
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

    /**
     * Open the GSY-styled popup menu listing every episode from [episodeList].
     * The current episode is highlighted; selecting one switches playback via the
     * existing [EpisodeNav.SELECT] path and closes the menu. The episode button is
     * only visible in fullscreen (see [updateControlsVisibility]), but the menu
     * itself works on either instance.
     */
    private fun showEpisodeDialog() {
        val ctx = context ?: return
        val anchor = episodeBtn ?: return
        if (episodeList.isEmpty()) {
            Toast.makeText(ctx, "暂无剧集列表", Toast.LENGTH_SHORT).show()
            return
        }
        val names = episodeList.map { it.name.ifBlank { "第${it.index}集" } }
        val currentIdx = episodeList.indexOfFirst { it.index == currentEpIndex }.let { if (it < 0) 0 else it }

        showGsyDropdown(
            anchor = anchor,
            title = "选集",
            items = names,
            currentIndex = currentIdx
        ) { which ->
            if (which in episodeList.indices) {
                val ep = episodeList[which]
                currentEpIndex = ep.index
                onEpisodeNav?.invoke(EpisodeNav.SELECT, ep.index)
            }
        }
    }

    // ==================== GSY-styled popup menus ====================

    /** B站 / GSY pink accent used to highlight the currently-selected menu row. */
    private val accentPink: Int = 0xFFFB7299.toInt()

    /** The single currently-visible dropdown menu (speed or episode). */
    private var activeMenu: PopupWindow? = null

    private fun dismissActiveMenu() {
        activeMenu?.let { popup ->
            if (popup.isShowing) popup.dismiss()
        }
        activeMenu = null
    }

    /**
     * Show a GSY-consistent dropdown menu anchored immediately ABOVE [anchor] (the
     * tapped control-bar button), like the B站 / YouTube player settings dropdowns.
     * The panel is a semi-transparent dark rounded card with a bold title, white
     * rows, and the current row highlighted in the B站 pink accent (#FB7299) with a
     * subtle tinted background. Rows are scrollable so long episode lists stay
     * on-screen. Selecting a row applies [onSelect] and closes the dropdown;
     * tapping outside or pressing back also dismisses it.
     */
    private fun showGsyDropdown(
        anchor: View,
        title: String,
        items: List<String>,
        currentIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val ctx = context ?: return
        if (items.isEmpty()) return
        dismissActiveMenu()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRectDrawable(0xF0242424.toInt(), dp(14).toFloat())
        }

        // Title header.
        content.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        })

        // List rows (click listeners attached after the PopupWindow exists).
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, label ->
            val selected = index == currentIndex
            val row = TextView(ctx).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(12))
                isClickable = true
                isFocusable = true
                setTextColor(if (selected) accentPink else 0xFFFFFFFF.toInt())
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                if (selected) {
                    background = roundedRectDrawable(0x33FB7299.toInt(), dp(8).toFloat())
                }
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        content.addView(
            MaxHeightScrollView(ctx, dp(360)).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val popupWidth = dp(280)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        var popupHeight = content.measuredHeight
        if (popupHeight <= 0) popupHeight = dp(200)

        val popup = PopupWindow(content, popupWidth, popupHeight, true).apply {
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        // Selecting a row applies it and closes the dropdown.
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            row.setOnClickListener {
                popup.dismiss()
                activeMenu = null
                onSelect(i)
            }
        }

        // showAtLocation positions in SCREEN coordinates (Gravity.TOP|LEFT means
        // offsets from the screen's top-left), so pair it with getLocationOnScreen.
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)

        // Place the dropdown immediately ABOVE the button: the popup's bottom edge
        // sits `gap` above the button's top, horizontally centered on the button.
        val gap = dp(6)
        var x = anchorLoc[0] + anchor.width / 2 - popupWidth / 2
        var y = anchorLoc[1] - popupHeight - gap

        // Keep it fully on-screen.
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels
        val margin = dp(8)
        if (x < margin) x = margin
        if (x + popupWidth > screenWidth - margin) x = screenWidth - popupWidth - margin
        if (y < margin) y = margin
        if (y + popupHeight > screenHeight - margin) y = screenHeight - popupHeight - margin

        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
        activeMenu = popup
    }

    /** Build a rounded-rectangle background drawable for the menu panel / rows. */
    private fun roundedRectDrawable(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    /**
     * A [ScrollView] whose measured height is capped so a very long episode list
     * cannot push the popup menu off-screen.
     */
    private inner class MaxHeightScrollView(
        ctx: Context,
        private val maxHeightPx: Int
    ) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limited = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, limited)
        }
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
