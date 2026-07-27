package com.sakura.player.player.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Uses FrameLayout so the preview bubble can float above the rows as an overlay.
 */
class ControlBar(context: Context) : FrameLayout(context) {

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

    var duration: Long = 1L
        set(value) { field = value; progressTrack.duration = value }

    var trackedPosition: Long = 0L

    // Child views
    private val rowsContainer: LinearLayout
    private val progressTrack: ProgressTrack
    private val timeCurrent: TextView
    private val timeTotal: TextView
    private val btnPlay: TextView
    private val btnPrev: TextView
    private val btnNext: TextView
    private val btnFullscreen: TextView
    private val btnEpisodes: TextView
    private val btnSpeed: TextView
    private val previewBubble: TextView

    private var hideAnim: ValueAnimator? = null
    private var showFullUI: Boolean = true

    init {
        setBackgroundColor(Color.argb(180, 0, 0, 0))

        // ── Rows container (LinearLayout) ──
        rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Row 1: Progress track
        progressTrack = ProgressTrack(context)
        rowsContainer.addView(progressTrack,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        // Row 2: Time labels
        val timeRow = FrameLayout(context).apply {
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        timeCurrent = makeLabel("00:00", 12f, Color.WHITE).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        timeTotal = makeLabel("00:00", 12f, Color.argb(200, 255, 255, 255)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        timeRow.addView(timeCurrent, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })
        timeRow.addView(timeTotal, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        rowsContainer.addView(timeRow)

        // Row 3: Button row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        btnPlay = makeBtn("\u25B6", 22f)
        btnPrev = makeBtn("\u23EE", 20f)
        btnNext = makeBtn("\u23ED", 20f)
        btnSpeed = makeBtn("\u23E9", 16f)
        btnEpisodes = makeBtn("\u2261", 20f)
        btnFullscreen = makeBtn("\u26F6", 18f)

        btnPlay.setOnClickListener { onPlayPause?.invoke() }
        btnPrev.setOnClickListener { onPrev?.invoke() }
        btnNext.setOnClickListener { onNext?.invoke() }
        btnSpeed.setOnClickListener { onSpeed?.invoke() }
        btnEpisodes.setOnClickListener { onEpisodes?.invoke() }
        btnFullscreen.setOnClickListener { onFullscreen?.invoke() }

        val btnParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER }
        btnRow.addView(btnPlay, btnParams)
        btnRow.addView(btnPrev, btnParams)
        btnRow.addView(btnNext, btnParams)
        btnRow.addView(btnSpeed, btnParams)
        btnRow.addView(btnEpisodes, btnParams)
        btnRow.addView(btnFullscreen, btnParams)
        rowsContainer.addView(btnRow)

        // Add the rows container to FrameLayout (anchored at bottom)
        addView(rowsContainer, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        // ── Preview bubble (floating overlay, initially hidden) ──
        previewBubble = TextView(context).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 12f
            val p = dp(8)
            setPadding(p, p / 2, p, p / 2)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(previewBubble, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ))

        // Wire progress track callbacks
        progressTrack.onSeekStart = { onSeekStart?.invoke() }
        progressTrack.onSeek = { ms -> onSeek?.invoke(ms) }
        progressTrack.onSeekEnd = { onSeekEnd?.invoke() }
        progressTrack.onFineSeek = { delta -> onFineSeek?.invoke(delta) }
        progressTrack.onPreviewUpdate = { posMs, totalMs, xPx ->
            previewBubble.text = "${formatTime(posMs)} / ${formatTime(totalMs)}"
            previewBubble.visibility = View.VISIBLE
            previewBubble.post {
                val bw = previewBubble.width.coerceAtLeast(1)
                val bh = previewBubble.height.coerceAtLeast(1)
                previewBubble.x = (xPx - bw / 2f).coerceIn(0f, this@ControlBar.width - bw.toFloat())
                previewBubble.y = (this@ControlBar.height - rowsContainer.height - bh - dp(8)).toFloat()
            }
        }
        progressTrack.onPreviewHide = {
            previewBubble.visibility = View.GONE
        }
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
        btnPlay.text = if (isPlaying) "\u23F8" else "\u25B6"
    }

    fun setShowFullUI(show: Boolean) {
        showFullUI = show
        btnPrev.visibility = if (show) View.VISIBLE else View.GONE
        btnNext.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun show() {
        hideAnim?.cancel()
        hideAnim = ValueAnimator.ofFloat(alpha, 1f).apply {
            duration = 200; interpolator = DecelerateInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }; start()
        }
    }

    fun hide() {
        hideAnim?.cancel()
        hideAnim = ValueAnimator.ofFloat(alpha, 0f).apply {
            duration = 300; interpolator = DecelerateInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }; start()
        }
    }

    val isDragging: Boolean get() = progressTrack.isDragging

    fun showPreview(positionMs: Long, totalMs: Long) {
        previewBubble.text = "${formatTime(positionMs)} / ${formatTime(totalMs)}"
        previewBubble.visibility = View.VISIBLE
        previewBubble.post {
            val bw = previewBubble.width.coerceAtLeast(1)
            val bh = previewBubble.height.coerceAtLeast(1)
            previewBubble.x = (this.width - bw) / 2f
            previewBubble.y = (this.height - rowsContainer.height - bh - dp(8)).toFloat()
        }
    }

    fun hidePreview() {
        previewBubble.visibility = View.GONE
    }

    // ── Helpers ──

    private fun makeLabel(text: String, size: Float, color: Int): TextView {
        return TextView(context).apply { this.text = text; textSize = size; setTextColor(color) }
    }

    private fun makeBtn(text: String, size: Float): TextView {
        return TextView(context).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
    }

    fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0)
        return "${(totalSec / 60).toString().padStart(2, '0')}:${(totalSec % 60).toString().padStart(2, '0')}"
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}


/**
 * Custom progress track drawn via Canvas.
 */
class ProgressTrack(context: Context) : View(context) {

    var progress: Float = 0f
    var bufferProgress: Float = 0f
    var duration: Long = 1L
    var onSeekStart: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onSeekEnd: (() -> Unit)? = null
    var onFineSeek: ((Float) -> Unit)? = null
    var onPreviewUpdate: ((positionMs: Long, totalMs: Long, xPx: Float) -> Unit)? = null
    var onPreviewHide: (() -> Unit)? = null
    var isDragging: Boolean = false
        private set

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255); style = Paint.Style.FILL
    }
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255); style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FB7299"); style = Paint.Style.FILL
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FB7299"); style = Paint.Style.FILL
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f
    }

    private var dragFraction = 0f
    private var downY = 0f

    init {
        isClickable = true; isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val trackH = dp(4).toFloat(); val trackY = h / 2f - trackH / 2f; val cornerR = trackH / 2f

        canvas.drawRoundRect(0f, trackY, w, trackY + trackH, cornerR, cornerR, bgPaint)
        if (bufferProgress > 0f) {
            canvas.drawRoundRect(0f, trackY, w * bufferProgress.coerceIn(0f, 1f),
                trackY + trackH, cornerR, cornerR, bufferPaint)
        }
        val p = if (isDragging) dragFraction else progress
        val progressW = w * p.coerceIn(0f, 1f)
        canvas.drawRoundRect(0f, trackY, progressW, trackY + trackH, cornerR, cornerR, progressPaint)

        val dotR = if (isDragging) dp(7).toFloat() else dp(5).toFloat()
        val dotX = progressW.coerceIn(dotR, w - dotR)
        canvas.drawCircle(dotX, h / 2f, dotR, dotFillPaint)
        canvas.drawCircle(dotX, h / 2f, dotR, dotStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true; downY = event.y
                dragFraction = (event.x / w).coerceIn(0f, 1f)
                onSeekStart?.invoke()
                val posMs = (dragFraction * duration).toLong()
                onSeek?.invoke(posMs)
                onPreviewUpdate?.invoke(posMs, duration, event.x)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                dragFraction = (event.x / w).coerceIn(0f, 1f)
                val posMs = (dragFraction * duration).toLong()
                onSeek?.invoke(posMs)
                val dy = event.y - downY
                if (abs(dy) > dp(5)) {
                    onFineSeek?.invoke(-dy * 0.1f)
                    downY = event.y
                }
                onPreviewUpdate?.invoke(posMs, duration, event.x)
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false; onSeekEnd?.invoke(); onPreviewHide?.invoke()
                invalidate(); return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
