package com.sakura.player.player.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Layer 5: Bottom control bar with progress track, time labels, and action buttons.
 *
 * Layout (top to bottom):
 *   - ProgressTrack (custom view with Canvas-drawn track + draggable dot)
 *   - Time label row: current time (left) / total time (right)
 *   - Button row: play/pause, prev, next (fullscreen only), speed, episodes, fullscreen
 *
 * Auto-hides via alpha animation. Preview bubble appears during progress drag.
 */
class ControlBar(context: Context) : LinearLayout(context) {

    // ── Public callbacks ──
    var onPlayPause: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onFullscreen: (() -> Unit)? = null
    var onEpisodes: (() -> Unit)? = null
    var onSpeed: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onSeekStart: (() -> Unit)? = null
    var onSeekEnd: (() -> Unit)? = null
    var onFineSeek: ((Float) -> Unit)? = null

    /** Pass the video duration (ms) into ProgressTrack so it can compute positions. */
    var duration: Long = 1L
        set(value) {
            field = value
            progressTrack.duration = value
        }

    /** Update the seek position tracked inside the progress bar. */
    var trackedPosition: Long = 0L

    // ── Views ──
    private val progressTrack: ProgressTrack
    private val timeCurrent: TextView
    private val timeTotal: TextView
    private val btnPlay: TextView
    private val btnPrev: TextView
    private val btnNext: TextView
    private val btnFullscreen: TextView
    private val btnEpisodes: TextView
    private val btnSpeed: TextView
    private val previewBubble: BubbleView

    private val handler = Handler(Looper.getMainLooper())
    private var hideAnim: ValueAnimator? = null
    private var showFullUI: Boolean = true

    init {
        orientation = VERTICAL

        // Dark translucent background
        setBackgroundColor(Color.argb(180, 0, 0, 0))

        // ── Row 1: Progress track (40dp) ──
        progressTrack = ProgressTrack(context).apply {
            onSeekStart = { onSeekStart?.invoke() }
            onSeek = { fraction -> onSeek?.invoke(fraction) }
            onSeekEnd = { onSeekEnd?.invoke() }
            onFineSeek = { delta -> onFineSeek?.invoke(delta) }
            onPreviewUpdate = { positionMs, totalMs, xPos ->
                previewBubble.text = "${formatTime(positionMs)} / ${formatTime(totalMs)}"
                previewBubble.visibility = View.VISIBLE
                previewBubble.x = xPos - previewBubble.measuredWidth / 2f
                previewBubble.y = -previewBubble.measuredHeight - 8f
            }
            onPreviewHide = {
                previewBubble.visibility = View.GONE
            }
        }
        addView(
            progressTrack,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
        )

        // ── Row 2: Time labels ──
        val timeRow = FrameLayout(context).apply {
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        timeCurrent = makeLabel("00:00", 12f, Color.WHITE).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        timeTotal = makeLabel("00:00", 12f, Color.argb(200, 255, 255, 255)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        timeRow.addView(
            timeCurrent,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        )
        timeRow.addView(
            timeTotal,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
        addView(timeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 3: Button row (48dp) ──
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        btnPlay = makeBtn("\u25B6", 22f)  // ▶
        btnPrev = makeBtn("\u23EE", 20f)  // ⏮
        btnNext = makeBtn("\u23ED", 20f)  // ⏭
        btnSpeed = makeBtn("\u23E9", 16f) // ⏩
        btnEpisodes = makeBtn("\u2261", 20f) // ≡
        btnFullscreen = makeBtn("\u26F6", 18f) // ⛶

        btnPlay.setOnClickListener { onPlayPause?.invoke() }
        btnPrev.setOnClickListener { onPrev?.invoke() }
        btnNext.setOnClickListener { onNext?.invoke() }
        btnSpeed.setOnClickListener { onSpeed?.invoke() }
        btnEpisodes.setOnClickListener { onEpisodes?.invoke() }
        btnFullscreen.setOnClickListener { onFullscreen?.invoke() }

        // Equal-weight layout for buttons
        val btnParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            gravity = Gravity.CENTER
        }
        btnRow.addView(btnPlay, btnParams)
        btnRow.addView(btnPrev, btnParams)
        btnRow.addView(btnNext, btnParams)
        btnRow.addView(btnSpeed, btnParams)
        btnRow.addView(btnEpisodes, btnParams)
        btnRow.addView(btnFullscreen, btnParams)
        addView(btnRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Preview bubble (overlay, initially hidden) ──
        previewBubble = BubbleView(context)
        previewBubble.visibility = View.GONE
        addView(previewBubble)
    }

    // ── Public API ──

    fun updateTime(current: Long, duration: Long) {
        timeCurrent.text = formatTime(current)
        timeTotal.text = formatTime(duration)
    }

    fun updateProgress(fraction: Float, bufferFraction: Float) {
        progressTrack.progress = fraction
        progressTrack.bufferProgress = bufferFraction
    }

    fun updatePlayPause(isPlaying: Boolean) {
        btnPlay.text = if (isPlaying) "\u23F8" else "\u25B6"  // ⏸ or ▶
    }

    /** Show/hide prev/next buttons based on player mode (FULLSCREEN = show, INLINE = hide). */
    fun setShowFullUI(show: Boolean) {
        showFullUI = show
        btnPrev.visibility = if (show) View.VISIBLE else View.GONE
        btnNext.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun show() {
        hideAnim?.cancel()
        val anim = ValueAnimator.ofFloat(alpha, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }
        }
        hideAnim = anim
        anim.start()
    }

    fun hide() {
        hideAnim?.cancel()
        val anim = ValueAnimator.ofFloat(alpha, 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }
        }
        hideAnim = anim
        anim.start()
    }

    /** Whether the user is currently dragging the progress dot. */
    val isDragging: Boolean get() = progressTrack.isDragging

    /** Show preview bubble for gesture-based seek (positionMs, totalMs). */
    fun showPreview(positionMs: Long, totalMs: Long) {
        previewBubble.text = "${formatTime(positionMs)} / ${formatTime(totalMs)}"
        previewBubble.visibility = View.VISIBLE
        post {
            previewBubble.x = (width - previewBubble.measuredWidth) / 2f
            previewBubble.y = -previewBubble.measuredHeight - 8f
        }
    }

    /** Hide the gesture-seek preview bubble. */
    fun hidePreview() {
        previewBubble.visibility = View.GONE
    }

    // ── Internal helpers ──

    private fun makeLabel(text: String, size: Float, color: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }
    }

    private fun makeBtn(text: String, size: Float): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
    }

    fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }

    // ── Bubble overlay view ──
    private class BubbleView(context: Context) : TextView(context) {
        init {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 12f
            val p = dp(8, context)
            setPadding(p, p / 2, p, p / 2)
            gravity = Gravity.CENTER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // Position based on x / y after measurement
        }
    }

    companion object {
        private fun dp(value: Int, ctx: Context): Int {
            return (value * ctx.resources.displayMetrics.density).roundToInt()
        }
    }
}


/**
 * Custom progress track drawn entirely via Canvas.
 *
 * Draws (bottom to top):
 *   1. Background track — semi-transparent white rounded rect
 *   2. Buffer bar — lighter gray rounded rect (shows loaded range)
 *   3. Progress bar — B站 pink (#FB7299) rounded rect
 *   4. Draggable dot — pink fill + white stroke circle; scales 1.4x when dragging
 *
 * Touch handling:
 *   - Horizontal drag on the track: seek (calls [onSeek] with position in ms)
 *   - Vertical movement > 5px during drag: fine-seek (calls [onFineSeek] with delta)
 *   - Dot scales up on press (isDragging = true)
 */
class ProgressTrack(context: Context) : View(context) {

    /** Current playback progress 0..1. */
    var progress: Float = 0f

    /** Buffered range 0..1. */
    var bufferProgress: Float = 0f

    /** Video duration in ms, used to convert drag fraction to position. */
    var duration: Long = 1L

    var onSeekStart: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null        // position in ms
    var onSeekEnd: (() -> Unit)? = null
    var onFineSeek: ((Float) -> Unit)? = null   // delta in seconds
    var onPreviewUpdate: ((positionMs: Long, totalMs: Long, xPx: Float) -> Unit)? = null
    var onPreviewHide: (() -> Unit)? = null

    /** Whether the user is currently touching / dragging the track. */
    var isDragging: Boolean = false
        private set

    // ── Paints ──
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FB7299")
        style = Paint.Style.FILL
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FB7299")
        style = Paint.Style.FILL
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private var dragFraction = 0f
    private var downY = 0f

    init {
        // Allow touch interaction on the entire view
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val trackH = dp(4).toFloat()
        val trackY = h / 2f - trackH / 2f
        val cornerR = trackH / 2f

        // 1. Background track (full width)
        canvas.drawRoundRect(0f, trackY, w, trackY + trackH, cornerR, cornerR, bgPaint)

        // 2. Buffer bar
        if (bufferProgress > 0f) {
            val bufferW = w * bufferProgress.coerceIn(0f, 1f)
            canvas.drawRoundRect(0f, trackY, bufferW, trackY + trackH, cornerR, cornerR, bufferPaint)
        }

        // 3. Progress bar
        val p = if (isDragging) dragFraction else progress
        val progressW = w * p.coerceIn(0f, 1f)
        canvas.drawRoundRect(0f, trackY, progressW, trackY + trackH, cornerR, cornerR, progressPaint)

        // 4. Draggable dot
        val dotR = if (isDragging) dp(7).toFloat() else dp(5).toFloat()
        val dotX = progressW.coerceIn(dotR, w - dotR)
        val dotY = h / 2f

        canvas.drawCircle(dotX, dotY, dotR, dotFillPaint)
        canvas.drawCircle(dotX, dotY, dotR, dotStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Start drag at touch position
                isDragging = true
                downY = event.y
                dragFraction = (event.x / w).coerceIn(0f, 1f)
                onSeekStart?.invoke()
                val posMs = (dragFraction * duration).toLong()
                onSeek?.invoke(posMs)

                // Show preview
                onPreviewUpdate?.invoke(posMs, duration, event.x)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                dragFraction = (event.x / w).coerceIn(0f, 1f)
                val posMs = (dragFraction * duration).toLong()
                onSeek?.invoke(posMs)

                // Vertical movement = fine-seek (delta in seconds)
                val dy = event.y - downY
                if (abs(dy) > dp(5)) {
                    // Moving finger down = negative delta (seek backward a bit)
                    // Scale so ~100px vertical = ~10s fine adjustment
                    val delta = -dy * 0.1f
                    onFineSeek?.invoke(delta)
                    downY = event.y // reset anchor to avoid accumulating
                }

                // Update preview
                onPreviewUpdate?.invoke(posMs, duration, event.x)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                onSeekEnd?.invoke()
                onPreviewHide?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
