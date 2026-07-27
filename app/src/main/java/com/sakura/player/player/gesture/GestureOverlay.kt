package com.sakura.player.player.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GestureOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var listener: GestureListener? = null
    var locked: Boolean = false
    var seekingEnabled: Boolean = true

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Touch state
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var initialBrightness = 0f
    private var initialVolume = 0
    private var initialSeekPos = 0L
    private var seekAccum = 0f

    private var gestureType: GestureType? = null
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null
    private var tapCount = 0
    private var lastTapTime = 0L

    private enum class GestureType { BRIGHTNESS, VOLUME, SEEK, PROGRESS_FINE }

    interface GestureListener {
        fun onSingleTap()
        fun onDoubleTap()
        fun onLongPressStart()
        fun onLongPressEnd()
        fun onBrightnessChange(delta: Float)    // -1.0 .. 1.0
        fun onVolumeChange(delta: Float)         // -1.0 .. 1.0
        fun onSeek(deltaSeconds: Float)          // relative seek in seconds
        fun onSeekEnd()
        fun onProgressFineSeek(deltaSeconds: Float)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                gestureType = null; longPressFired = false; seekAccum = 0f

                // Long press detection
                longPressRunnable = Runnable {
                    if (gestureType == null) {
                        longPressFired = true
                        listener?.onLongPressStart()
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                handler.postDelayed(longPressRunnable!!, 500)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                val tdx = event.x - downX
                val tdy = event.y - downY
                val adx = abs(tdx); val ady = abs(tdy)

                if (gestureType == null && (adx > touchSlop || ady > touchSlop)) {
                    gestureType = when {
                        adx > ady -> GestureType.SEEK
                        downX < width * 0.25f -> GestureType.BRIGHTNESS
                        downX > width * 0.75f -> GestureType.VOLUME
                        else -> GestureType.SEEK
                    }
                    if (gestureType != GestureType.SEEK) {
                        handler.removeCallbacks(longPressRunnable!!)
                    }
                    when (gestureType) {
                        GestureType.BRIGHTNESS -> initialBrightness = getSystemBrightness()
                        GestureType.VOLUME -> initialVolume = getSystemVolume()
                        GestureType.SEEK -> initialSeekPos = 0L
                        else -> {}
                    }
                }

                when (gestureType) {
                    GestureType.BRIGHTNESS -> {
                        val delta = -tdy / (height * 0.5f)
                        listener?.onBrightnessChange(delta.coerceIn(-1f, 1f))
                    }
                    GestureType.VOLUME -> {
                        val delta = -tdy / (height * 0.5f)
                        listener?.onVolumeChange(delta.coerceIn(-1f, 1f))
                    }
                    GestureType.SEEK -> {
                        seekAccum += dx * 0.15f
                        listener?.onSeek(seekAccum)
                    }
                    null -> {}
                    else -> {}
                }
                lastX = event.x; lastY = event.y
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable!!)
                if (longPressFired) {
                    listener?.onLongPressEnd()
                } else if (gestureType == null) {
                    // Tap detection
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) {
                        tapCount++
                        if (tapCount >= 2) {
                            handler.removeCallbacksAndMessages(null)
                            listener?.onDoubleTap()
                            tapCount = 0
                        }
                    } else {
                        tapCount = 1
                        handler.postDelayed({
                            if (tapCount == 1) listener?.onSingleTap()
                            tapCount = 0
                        }, 300)
                    }
                    lastTapTime = now
                }
                if (gestureType == GestureType.SEEK) {
                    listener?.onSeekEnd()
                }
                gestureType = null
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable!!)
                if (longPressFired) listener?.onLongPressEnd()
                gestureType = null
            }
        }
        return true
    }

    private fun getSystemBrightness(): Float = 0.5f  // placeholder
    private fun getSystemVolume(): Int = 50           // placeholder
}
