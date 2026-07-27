# B 站风格统一播放器 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用纯原生 Kotlin 自定义 View 构建统一播放器，完全照搬 B站 app 交互模式，覆盖详情页半屏和全屏两种场景。

**Architecture:** `SakuraPlayerView` 继承 FrameLayout，6 层叠加（Video→Gesture→Hint→HUD→ControlBar→Panels）。ExoPlayer 负责解码，所有 UI 控件用原生 Android View 自绘。支持 INLINE 和 FULLSCREEN 两种模式。

**Tech Stack:** Kotlin, ExoPlayer (media3) 1.3.0, Android View System, ValueAnimator, HapticFeedbackConstants

**Spec:** `docs/superpowers/specs/2026-07-27-bilibili-player-design.md`

## Global Constraints

- 所有 UI 控件用原生 Android View 自绘，不使用 WebView 做播放器
- ExoPlayer (media3) 负责视频解码
- 手势响应延迟 < 100ms
- 控件显隐动画 300ms fade
- 主题色 `#FB7299` (B站粉)
- 全屏↔半屏切换不丢进度
- 在线 m3u8 和本地 mp4 统一体验
- 不做弹幕、定时关闭、画质手动选择、投屏

---

### Task 1: 项目基础设施 — 目录与接口定义

**Files:**
- Create: `app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt`
- Create: `app/src/main/java/com/sakura/player/player/PlayerConfig.kt`

**Interfaces:**
- Produces: `PlayerConfig` data class, `SakuraPlayerView` skeleton with mode enum

- [ ] **Step 1: 创建 PlayerConfig 配置类**

```kotlin
// app/src/main/java/com/sakura/player/player/PlayerConfig.kt
package com.sakura.player.player

data class PlayerConfig(
    val mode: PlayerMode,
    val title: String = "",
    val episodes: List<EpisodeItem> = emptyList(),
    val coverUrl: String = ""
)

enum class PlayerMode { INLINE, FULLSCREEN }

data class EpisodeItem(
    val index: Int,
    val name: String,
    val path: String = "",       // local file path
    val videoId: Long = 0,       // online video id
    val isLocal: Boolean = false
)
```

- [ ] **Step 2: 创建 SakuraPlayerView 骨架**

```kotlin
// app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt
package com.sakura.player.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class SakuraPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: PlayerConfig = PlayerConfig(PlayerMode.INLINE)
    var exoPlayer: ExoPlayer? = null
        private set
    private lateinit var playerView: PlayerView

    /** Callback when user requests fullscreen toggle */
    var onFullscreenRequest: (() -> Unit)? = null
    /** Callback when user switches episode */
    var onEpisodeChange: ((Int) -> Unit)? = null
    /** Callback for state changes to sync with JS */
    var onStateChanged: ((PlayerState) -> Unit)? = null

    data class PlayerState(
        val playing: Boolean,
        val position: Long,
        val duration: Long,
        val currentEp: Int,
        val speed: Float
    )

    fun setup(config: PlayerConfig) {
        this.config = config
        removeAllViews()
        buildLayers()
    }

    fun play(m3u8Url: String) {
        // Task 2
    }

    fun playLocal(uri: android.net.Uri) {
        // Task 2
    }

    fun release() {
        // Task 2
    }

    fun togglePlayPause() {
        // Task 3
    }

    fun showControls() {
        // Task 5
    }

    fun hideControls() {
        // Task 5
    }

    fun setLocked(locked: Boolean) {
        // Task 5
    }

    private fun buildLayers() {
        // Implemented across Tasks 2-7
    }
}
```

- [ ] **Step 3: 构建验证编译**

```bash
cd /data/data/com.termux/files/home/claudecode/SakuraPlayer && gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/
git commit -m "feat(player): add PlayerConfig and SakuraPlayerView skeleton"
```

---

### Task 2: ExoPlayer 视频层 (Layer 1)

**Files:**
- Modify: `app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt`
- Create: `app/src/main/java/com/sakura/player/player/PlayerLayer.kt`

**Interfaces:**
- Consumes: `PlayerConfig` from Task 1, `SakuraPlayerView` skeleton
- Produces: `PlayerLayer` with `play(m3u8Url)`, `playLocal(uri)`, `release()`, `seekTo(ms)`, `setSpeed(f)`, `pause()`, `play()`, `togglePlayPause()`

- [ ] **Step 1: 创建 PlayerLayer 封装 ExoPlayer**

```kotlin
// app/src/main/java/com/sakura/player/player/PlayerLayer.kt
package com.sakura.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerLayer(private val ctx: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    val playerView: PlayerView = PlayerView(ctx).apply {
        useController = false
        controllerAutoShow = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setBackgroundColor(0xFF000000.toInt())
    }

    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    init {
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> onReady?.invoke()
                    Player.STATE_ENDED -> onEnded?.invoke()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error.message ?: "播放错误")
            }
        })
    }

    fun play(m3u8Url: String) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(m3u8Url)))
        player.prepare()
        player.playWhenReady = true
    }

    fun playLocal(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    val isPlaying: Boolean get() = player.playWhenReady
    val currentPosition: Long get() = player.currentPosition
    val duration: Long get() = if (player.duration > 0) player.duration else 1L

    fun togglePlayPause() { player.playWhenReady = !player.playWhenReady }
    fun pause() { player.pause() }
    fun play() { player.play() }
    fun seekTo(ms: Long) { player.seekTo(ms.coerceIn(0, player.duration)) }
    fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed) }
    fun getBufferedPercent(): Int {
        val pct = player.bufferedPercentage
        return if (pct in 0..100) pct else 0
    }

    fun release() {
        player.stop()
        player.release()
    }
}
```

- [ ] **Step 2: 集成 PlayerLayer 到 SakuraPlayerView**

在 `SakuraPlayerView.buildLayers()` 中添加 Layer 1:

```kotlin
private lateinit var playerLayer: PlayerLayer

private fun buildLayers() {
    // Layer 1: Video surface
    playerLayer = PlayerLayer(context)
    playerLayer.onReady = { onReadyCallback() }
    playerLayer.onEnded = { onEndedCallback() }
    playerLayer.onError = { msg -> onErrorCallback(msg) }
    addView(playerLayer.playerView, LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
    ))
}
```

替换骨架方法:

```kotlin
fun play(m3u8Url: String) { playerLayer.play(m3u8Url) }
fun playLocal(uri: android.net.Uri) { playerLayer.playLocal(uri) }
fun release() { playerLayer.release() }
fun togglePlayPause() { playerLayer.togglePlayPause() }
```

- [ ] **Step 3: 构建验证编译**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/
git commit -m "feat(player): add PlayerLayer with ExoPlayer integration"
```

---

### Task 3: 手势系统 (Layer 2)

**Files:**
- Create: `app/src/main/java/com/sakura/player/player/gesture/GestureOverlay.kt`
- Modify: `app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt`

**Interfaces:**
- Consumes: `PlayerLayer` (via `SakuraPlayerView`), `PlayerMode` from `PlayerConfig`
- Produces: `GestureOverlay` with `GestureListener` interface (onSingleTap, onDoubleTap, onLongPress, onBrightnessChange, onVolumeChange, onSeek, onSeekEnd)

- [ ] **Step 1: 创建 GestureOverlay**

```kotlin
// app/src/main/java/com/sakura/player/player/gesture/GestureOverlay.kt
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
                        GestureType.SEEK -> initialSeekPos = 0
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
```

- [ ] **Step 2: 集成 GestureOverlay 到 SakuraPlayerView**

在 `buildLayers()` 添加 Layer 2:

```kotlin
private lateinit var gestureOverlay: GestureOverlay

// Layer 2: Gesture overlay (transparent)
gestureOverlay = GestureOverlay(context).apply {
    listener = object : GestureOverlay.GestureListener {
        override fun onSingleTap() { toggleControlVisibility() }
        override fun onDoubleTap() { playerLayer.togglePlayPause(); showCenterHint(if (playerLayer.isPlaying) PAUSE else PLAY) }
        override fun onLongPressStart() { playerLayer.setSpeed(2f); showSpeedHint("2x 快放中") }
        override fun onLongPressEnd() { playerLayer.setSpeed(currentSpeed); hideSpeedHint() }
        override fun onBrightnessChange(delta: Float) { adjustBrightness(delta) }
        override fun onVolumeChange(delta: Float) { adjustVolume(delta) }
        override fun onSeek(deltaSeconds: Float) { seekPreview(deltaSeconds) }
        override fun onSeekEnd() { commitSeek() }
        override fun onProgressFineSeek(delta: Float) { fineSeek(delta) }
    }
}
addView(gestureOverlay, LayoutParams(MATCH_PARENT, MATCH_PARENT))
```

- [ ] **Step 3: 构建验证编译**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL (with stub method warnings if not yet implemented)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/gesture/ app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt
git commit -m "feat(player): add GestureOverlay with B站-style gesture detection"
```

---

### Task 4: CenterHint + SideHUD (Layers 3 & 4)

**Files:**
- Create: `app/src/main/java/com/sakura/player/player/control/CenterHint.kt`
- Create: `app/src/main/java/com/sakura/player/player/control/SideHUD.kt`
- Modify: `app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt`

**Interfaces:**
- Consumes: `SakuraPlayerView` from Task 3
- Produces: `CenterHint.show(type)`, `CenterHint.hide()`, `SideHUD.show(type, value)`, `SideHUD.hide()`

- [ ] **Step 1: 创建 CenterHint**

```kotlin
// app/src/main/java/com/sakura/player/player/control/CenterHint.kt
package com.sakura.player.player.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

class CenterHint(context: Context) : FrameLayout(context) {

    private val iconView: TextView
    private val labelView: TextView
    private var fadeAnim: ValueAnimator? = null

    enum class Type { PLAY, PAUSE, REWIND, FORWARD }

    init {
        // Large centered icon (e.g., ▶ / ⏸)
        iconView = TextView(context).apply {
            textSize = 48f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        addView(iconView, LayoutParams(120, 120).apply {
            gravity = Gravity.CENTER
        })

        labelView = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = 140
        })
    }

    fun show(type: Type) {
        iconView.text = when (type) {
            Type.PLAY -> "\u25B6"      // ▶
            Type.PAUSE -> "\u23F8"     // ⏸
            Type.REWIND -> "\u23EE"    // ⏮
            Type.FORWARD -> "\u23ED"   // ⏭
        }
        iconView.alpha = 1f
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 300
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { iconView.alpha = it.animatedValue as Float }
            start()
        }
    }

    fun showSpeedHint(text: String) {
        labelView.text = text
        labelView.alpha = 1f
    }

    fun hideSpeedHint() {
        labelView.alpha = 0f
    }
}
```

- [ ] **Step 2: 创建 SideHUD**

```kotlin
// app/src/main/java/com/sakura/player/player/control/SideHUD.kt
package com.sakura.player.player.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class SideHUD(context: Context, private val alignLeft: Boolean) : FrameLayout(context) {

    private val iconView: TextView
    private val barView: BarView
    private val labelView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    enum class Type { BRIGHTNESS, VOLUME }

    init {
        layoutParams = LayoutParams(80, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (alignLeft) Gravity.START else Gravity.END)
            setMargins(if (alignLeft) 16 else 0, 0, if (alignLeft) 0 else 16, 0)
        }
        setBackgroundColor(Color.argb(180, 0, 0, 0))
        val padding = 12
        setPadding(padding, padding, padding, padding)

        iconView = TextView(context).apply {
            textSize = 18f; gravity = Gravity.CENTER
        }
        addView(iconView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        barView = BarView(context)
        addView(barView, LayoutParams(6, 100).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8
        })

        labelView = TextView(context).apply {
            textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = 4
        })

        alpha = 0f
    }

    fun show(type: Type, value: Int) {
        when (type) {
            Type.BRIGHTNESS -> { iconView.text = "\u2600"; barView.level = value }
            Type.VOLUME -> { iconView.text = if (value == 0) "\uD83D\uDD07" else "\uD83D\uDD0A"; barView.level = value }
        }
        labelView.text = "$value%"
        alpha = 1f
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable { animate().alpha(0f).setDuration(300).start() }
        handler.postDelayed(hideRunnable!!, 800)
    }

    private class BarView(context: Context) : View(context) {
        var level: Int = 50
        private val bgPaint = Paint().apply { color = Color.argb(80, 255, 255, 255) }
        private val fillPaint = Paint().apply { color = Color.parseColor("#FB7299") }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, 3f, 3f, bgPaint)
            val fillH = h * level / 100
            canvas.drawRoundRect(0f, h - fillH, w, h, 3f, 3f, fillPaint)
        }
    }
}
```

- [ ] **Step 3: 集成到 SakuraPlayerView**

在 `buildLayers()` 添加 Layers 3 & 4:

```kotlin
private lateinit var centerHint: CenterHint
private lateinit var brightnessHud: SideHUD
private lateinit var volumeHud: SideHUD

// Layer 3: Center hint
centerHint = CenterHint(context)
addView(centerHint, LayoutParams(MATCH_PARENT, MATCH_PARENT))

// Layer 4: Side HUDs
brightnessHud = SideHUD(context, true)   // left side
volumeHud = SideHUD(context, false)       // right side
addView(brightnessHud)
addView(volumeHud)
```

实现 bridge 方法:

```kotlin
private fun showCenterHint(type: CenterHint.Type) = centerHint.show(type)
private fun showSpeedHint(text: String) = centerHint.showSpeedHint(text)
private fun hideSpeedHint() = centerHint.hideSpeedHint()
private fun showBrightnessHud(value: Int) = brightnessHud.show(SideHUD.Type.BRIGHTNESS, value)
private fun showVolumeHud(value: Int) = volumeHud.show(SideHUD.Type.VOLUME, value)
```

- [ ] **Step 4: 构建验证**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/control/ app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt
git commit -m "feat(player): add CenterHint and SideHUD for gesture feedback"
```

---

### Task 5: ControlBar (Layer 5)

**Files:**
- Create: `app/src/main/java/com/sakura/player/player/control/ControlBar.kt`
- Modify: `app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt`

**Interfaces:**
- Consumes: `PlayerConfig.mode` (to show/hide prev/next buttons), `PlayerLayer` state
- Produces: `ControlBar` with callbacks: `onPlayPause`, `onPrev`, `onNext`, `onFullscreen`, `onEpisodes`, `onSpeed`, `onSeek(ms)`, `onSeekStart`, `onSeekEnd`, `onFineSeek(delta)`

- [ ] **Step 1: 创建 ControlBar**

```kotlin
// app/src/main/java/com/sakura/player/player/control/ControlBar.kt
package com.sakura.player.player.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class ControlBar(context: Context) : FrameLayout(context) {

    // Callbacks
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

    // Views
    private val timeCurrent: TextView
    private val timeTotal: TextView
    private val btnPlay: TextView
    private val btnPrev: TextView
    private val btnNext: TextView
    private val btnFullscreen: TextView
    private val btnEpisodes: TextView
    private val btnSpeed: TextView
    private val progressTrack: ProgressTrack
    private val previewBubble: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var hideAnim: ValueAnimator? = null
    private var showFullUI: Boolean = true

    init {
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        setBackgroundColor(Color.argb(180, 0, 0, 0))
        setPadding(12, 8, 12, 16)
        // Built with programmatic layout — detailed in implementation

        // Preview bubble (shown during drag)
        previewBubble = TextView(context).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(8, 4, 8, 4)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(previewBubble)

        // Custom progress track
        progressTrack = ProgressTrack(context)
        progressTrack.onSeekStart = { onSeekStart?.invoke() }
        progressTrack.onSeek = { fraction -> onSeek?.invoke(fraction) }
        progressTrack.onSeekEnd = { onSeekEnd?.invoke() }
        progressTrack.onFineSeek = { delta -> onFineSeek?.invoke(delta) }
        addView(progressTrack, LayoutParams(MATCH_PARENT, 40).apply {
            gravity = Gravity.TOP
        })

        // Time labels
        timeCurrent = textView("00:00", 12f, Color.WHITE, Gravity.START)
        timeTotal = textView("00:00", 12f, Color.argb(200, 255, 255, 255), Gravity.END)
        addView(timeCurrent); addView(timeTotal)

        // Button row
        val btnRow = FrameLayout(context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, 48).apply { gravity = Gravity.BOTTOM }
        }
        btnPlay = textView("\u25B6", 22f, Color.WHITE, Gravity.START)
        btnPrev = textView("\u23EE", 20f, Color.WHITE, Gravity.CENTER)
        btnNext = textView("\u23ED", 20f, Color.WHITE, Gravity.CENTER)
        btnSpeed = textView("\u23E9", 16f, Color.WHITE, Gravity.CENTER)
        btnEpisodes = textView("\u2261", 20f, Color.WHITE, Gravity.CENTER)
        btnFullscreen = textView("\u26F6", 18f, Color.WHITE, Gravity.END)

        btnPlay.setOnClickListener { onPlayPause?.invoke() }
        btnPrev.setOnClickListener { onPrev?.invoke() }
        btnNext.setOnClickListener { onNext?.invoke() }
        btnSpeed.setOnClickListener { onSpeed?.invoke() }
        btnEpisodes.setOnClickListener { onEpisodes?.invoke() }
        btnFullscreen.setOnClickListener { onFullscreen?.invoke() }

        btnRow.addView(btnPlay); btnRow.addView(btnPrev); btnRow.addView(btnNext)
        btnRow.addView(btnSpeed); btnRow.addView(btnEpisodes); btnRow.addView(btnFullscreen)
        addView(btnRow)
    }

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
        btnPrev.visibility = if (show) VISIBLE else GONE
        btnNext.visibility = if (show) VISIBLE else GONE
    }

    fun show() {
        animate().alpha(1f).setDuration(200).start()
    }

    fun hide() {
        animate().alpha(0f).setDuration(300).start()
    }

    fun showPreview(ms: Long, duration: Long) {
        previewBubble.text = "${formatTime(ms)} / ${formatTime(duration)}"
        previewBubble.visibility = VISIBLE
        previewBubble.x = (width * (ms.toFloat() / duration)).coerceIn(20f, width - 120f)
        previewBubble.y = -80f
    }

    fun hidePreview() {
        previewBubble.visibility = GONE
    }

    private fun textView(text: String, size: Float, color: Int, gravity: Int): TextView {
        return TextView(context).apply {
            this.text = text; textSize = size; setTextColor(color)
            this.gravity = gravity or Gravity.CENTER_VERTICAL
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60; val sec = totalSec % 60
        return "${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    }
}

/** Custom progress bar with drag and vertical fine-seek */
class ProgressTrack(context: Context) : View(context) {
    var progress: Float = 0f       // 0..1
    var bufferProgress: Float = 0f // 0..1
    var onSeekStart: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null // fraction in ms
    var onSeekEnd: (() -> Unit)? = null
    var onFineSeek: ((Float) -> Unit)? = null

    private val bgPaint = Paint().apply { color = Color.argb(60, 255, 255, 255) }
    private val bufferPaint = Paint().apply { color = Color.argb(40, 255, 255, 255) }
    private val progressPaint = Paint().apply { color = Color.parseColor("#FB7299") }
    private val dotPaint = Paint().apply {
        color = Color.parseColor("#FB7299")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val dotStrokePaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; isAntiAlias = true
        strokeWidth = 3f
    }

    private var dragging = false
    private var dragFraction = 0f
    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val trackH = 4f; val trackY = h / 2 - trackH / 2
        val dotR = 10f

        // Background track
        canvas.drawRoundRect(0f, trackY, w, trackY + trackH, 2f, 2f, bgPaint)
        // Buffer
        canvas.drawRoundRect(0f, trackY, w * bufferProgress, trackY + trackH, 2f, 2f, bufferPaint)
        // Progress
        val p = if (isDragging) dragFraction else progress
        canvas.drawRoundRect(0f, trackY, w * p, trackY + trackH, 2f, 2f, progressPaint)
        // Dot
        val dotX = w * p
        canvas.drawCircle(dotX, h / 2, if (isDragging) dotR * 1.4f else dotR, dotPaint)
        canvas.drawCircle(dotX, h / 2, if (isDragging) dotR * 1.4f else dotR, dotStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragFraction = (event.x / width).coerceIn(0f, 1f)
                onSeekStart?.invoke()
                onSeek?.invoke((dragFraction * 1000).toLong()) // approximate ms
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                dragFraction = (event.x / width).coerceIn(0f, 1f)
                onSeek?.invoke((dragFraction * 1000).toLong())
                // Detect vertical component for fine-seek
                val dy = event.y - (height / 2f)
                if (abs(dy) > 5f) {
                    onFineSeek?.invoke(-dy * 0.1f)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                onSeekEnd?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
```

- [ ] **Step 2: 集成到 SakuraPlayerView**

在 `buildLayers()` 添加 Layer 5:

```kotlin
private lateinit var controlBar: ControlBar

// Layer 5: Control bar
controlBar = ControlBar(context).apply {
    onPlayPause = { playerLayer.togglePlayPause() }
    onPrev = { /* implement in Task 7 */ }
    onNext = { /* implement in Task 7 */ }
    onFullscreen = { onFullscreenRequest?.invoke() }
    onEpisodes = { showEpisodePanel() }
    onSpeed = { showSpeedPanel() }
    onSeekStart = { gestureOverlay.seekingEnabled = false }
    onSeek = { fraction -> playerLayer.seekTo(fraction) }
    onSeekEnd = {
        gestureOverlay.seekingEnabled = true
        playerLayer.seekTo((dragTargetMs))  // stored during drag
    }
    onFineSeek = { delta -> playerLayer.seekTo(playerLayer.currentPosition + delta.toLong()) }
    setShowFullUI(config.mode == PlayerMode.FULLSCREEN)
}
addView(controlBar, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
    gravity = Gravity.BOTTOM
})
```

启动进度更新循环:

```kotlin
private val progressHandler = Handler(Looper.getMainLooper())
private val progressRunnable = object : Runnable {
    override fun run() {
        if (playerLayer.isPlaying && !isDragging) {
            val pos = playerLayer.currentPosition
            val dur = playerLayer.duration
            controlBar.updateTime(pos, dur)
            controlBar.updateProgress(pos.toFloat() / dur, playerLayer.getBufferedPercent() / 100f)
        }
        progressHandler.postDelayed(this, 250)
    }
}
```

- [ ] **Step 3: 构建验证**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/control/ControlBar.kt app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt
git commit -m "feat(player): add ControlBar with progress drag and fine-seek"
```

---

### Task 6: 滑出面板 (Layer 6)

**Files:**
- Create: `app/src/main/java/com/sakura/player/player/panel/EpisodePanel.kt`
- Create: `app/src/main/java/com/sakura/player/player/panel/SpeedPanel.kt`
- Modify: `app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt`

**Interfaces:**
- Consumes: `EpisodeItem` list from `PlayerConfig`, current episode index
- Produces: `EpisodePanel.show(items, currentIndex)`, `SpeedPanel.show(currentSpeed)`, both with `onSelect` callbacks

- [ ] **Step 1: 创建 EpisodePanel**

```kotlin
// app/src/main/java/com/sakura/player/player/panel/EpisodePanel.kt
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
        setOnClickListener { hide() }
        visibility = View.GONE

        contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            setPadding(16, 32, 16, 32)
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        }

        val titleText = TextView(context).apply {
            text = "选集"; textSize = 16f; setTextColor(Color.WHITE)
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
        visibility = View.VISIBLE
        translationY = contentView.height.toFloat()
        animate().translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator()).start()
        isShowing = true
    }

    fun hide() {
        animate().translationY(contentView.height.toFloat()).setDuration(200)
            .withEndAction { visibility = View.GONE }.start()
        isShowing = false
    }

    fun toggle(episodes: List<EpisodeItem>, currentIndex: Int) {
        if (isShowing) hide() else show(episodes, currentIndex)
    }
}
```

- [ ] **Step 2: 创建 SpeedPanel**

```kotlin
// app/src/main/java/com/sakura/player/player/panel/SpeedPanel.kt
package com.sakura.player.player.panel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
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
        setOnClickListener { hide() }
        visibility = View.GONE

        contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            setPadding(16, 32, 16, 32)
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        }

        val titleText = TextView(context).apply {
            text = "播放速度"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        contentView.addView(titleText)

        speeds.forEachIndexed { i, speed ->
            val row = TextView(context).apply {
                text = speedLabels[i]; textSize = 14f
                setPadding(8, 14, 8, 14)
                setOnClickListener {
                    onSpeedSelected?.invoke(speed); hide()
                }
            }
            contentView.addView(row)
        }
        addView(contentView)
    }

    fun show(currentSpeed: Float) {
        // Highlight current speed
        for (i in speeds.indices) {
            val row = contentView.getChildAt(i + 1) as? TextView ?: continue
            row.setTextColor(if (speeds[i] == currentSpeed) Color.parseColor("#FB7299") else Color.WHITE)
        }
        visibility = View.VISIBLE
        translationY = contentView.height.toFloat()
        animate().translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator()).start()
        isShowing = true
    }

    fun hide() {
        animate().translationY(contentView.height.toFloat()).setDuration(200)
            .withEndAction { visibility = View.GONE }.start()
        isShowing = false
    }
}
```

- [ ] **Step 3: 集成到 SakuraPlayerView**

在 `buildLayers()` 添加 Layer 6:

```kotlin
private lateinit var episodePanel: EpisodePanel
private lateinit var speedPanel: SpeedPanel
private var currentSpeed: Float = 1.0f

// Layer 6: Panels
episodePanel = EpisodePanel(context).apply {
    onEpisodeSelected = { idx -> onEpisodeChange?.invoke(idx) }
}
speedPanel = SpeedPanel(context).apply {
    onSpeedSelected = { speed ->
        currentSpeed = speed
        playerLayer.setSpeed(speed)
    }
}
addView(episodePanel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
addView(speedPanel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
```

实现 bridge 方法:

```kotlin
private fun showEpisodePanel() {
    episodePanel.toggle(config.episodes, currentEpisodeIndex)
}
private fun showSpeedPanel() {
    speedPanel.show(currentSpeed)
}
```

- [ ] **Step 4: 构建验证**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/panel/ app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt
git commit -m "feat(player): add EpisodePanel and SpeedPanel"
```

---

### Task 7: 自动隐藏 + 锁定 + 亮度音量控制

**Files:**
- Modify: `app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt`

**Interfaces:**
- Consumes: All layers from Tasks 2-6
- Produces: Complete `SakuraPlayerView` with auto-hide, lock, brightness/volume control

- [ ] **Step 1: 实现控件自动隐藏逻辑**

在 `SakuraPlayerView` 中添加:

```kotlin
private val hideHandler = Handler(Looper.getMainLooper())
private val hideControlsRunnable = Runnable { hideControls() }
private var controlsVisible = true
private var isLocked = false
private var isDragging = false

fun toggleControlVisibility() {
    if (isLocked) return
    if (controlsVisible) hideControls() else showControls()
}

fun showControls() {
    controlsVisible = true
    controlBar.show()
    if (config.mode == PlayerMode.FULLSCREEN) {
        // Show lock button
    }
    resetHideTimer()
}

fun hideControls() {
    controlsVisible = false
    controlBar.hide()
    // Hide lock button (with delay in fullscreen)
    hideHandler.removeCallbacks(hideControlsRunnable)
}

fun setLocked(locked: Boolean) {
    isLocked = locked
    gestureOverlay.locked = locked
    if (locked) {
        hideControls()
    } else {
        showControls()
    }
}

private fun resetHideTimer() {
    hideHandler.removeCallbacks(hideControlsRunnable)
    if (playerLayer.isPlaying && !isDragging && !isLocked) {
        hideHandler.postDelayed(hideControlsRunnable, 3000)
    }
}
```

- [ ] **Step 2: 实现亮度和音量控制**

```kotlin
private var systemBrightness = 0.5f
private var systemVolume = 50

private fun adjustBrightness(delta: Float) {
    systemBrightness = (systemBrightness + delta).coerceIn(0f, 1f)
    // Apply via WindowManager.LayoutParams
    try {
        val lp = (context as? android.app.Activity)?.window?.attributes
        if (lp != null) {
            lp.screenBrightness = systemBrightness
            (context as android.app.Activity).window.attributes = lp
        }
    } catch (_: Exception) {}
    showBrightnessHud((systemBrightness * 100).roundToInt())
}

private fun adjustVolume(delta: Float) {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val newVol = (curVol + (delta * maxVol * 0.3f)).roundToInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVol, 0)
        showVolumeHud((newVol * 100 / maxVol))
    } catch (_: Exception) {}
}
```

- [ ] **Step 3: 实现 seek 预览和确认**

```kotlin
private var pendingSeekPos: Long = 0

private fun seekPreview(deltaSeconds: Float) {
    val pos = playerLayer.currentPosition + (deltaSeconds * 1000).toLong()
    pendingSeekPos = pos.coerceIn(0, playerLayer.duration)
    controlBar.showPreview(pendingSeekPos, playerLayer.duration)
}

private fun commitSeek() {
    playerLayer.seekTo(pendingSeekPos)
    controlBar.hidePreview()
    resetHideTimer()
}

private fun fineSeek(delta: Float) {
    val newPos = playerLayer.currentPosition + (delta * 100).toLong()
    playerLayer.seekTo(newPos.coerceIn(0, playerLayer.duration))
}
```

- [ ] **Step 4: 完善 GestureOverlay 回调集成**

更新 `GestureOverlay` 的 listener 绑定，将之前 Task 3 的 stub 替换为 Task 7 的实现:

```kotlin
gestureOverlay.listener = object : GestureOverlay.GestureListener {
    override fun onSingleTap() { toggleControlVisibility() }
    override fun onDoubleTap() {
        playerLayer.togglePlayPause()
        showCenterHint(if (playerLayer.isPlaying) CenterHint.Type.PLAY else CenterHint.Type.PAUSE)
    }
    override fun onLongPressStart() {
        previousSpeed = currentSpeed
        currentSpeed = 2f; playerLayer.setSpeed(2f)
        centerHint.showSpeedHint("2x 快放中")
    }
    override fun onLongPressEnd() {
        currentSpeed = previousSpeed; playerLayer.setSpeed(currentSpeed)
        centerHint.hideSpeedHint()
    }
    override fun onBrightnessChange(delta: Float) { adjustBrightness(delta) }
    override fun onVolumeChange(delta: Float) { adjustVolume(delta) }
    override fun onSeek(deltaSeconds: Float) { seekPreview(deltaSeconds) }
    override fun onSeekEnd() { commitSeek() }
    override fun onProgressFineSeek(delta: Float) { fineSeek(delta) }
}
```

- [ ] **Step 5: 构建验证**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/SakuraPlayerView.kt
git commit -m "feat(player): add auto-hide, lock, brightness/volume, seek preview"
```

---

### Task 8: PlayerBridge — JS ↔ 原生通信

**Files:**
- Create: `app/src/main/java/com/sakura/player/player/PlayerBridge.kt`
- Modify: `app/src/main/java/com/sakura/player/MainActivity.kt`
- Modify: `app/src/main/java/com/sakura/player/bridge/JsBridge.kt`

**Interfaces:**
- Consumes: `SakuraPlayerView`, `JsBridge`
- Produces: `PlayerBridge` — manages JS ↔ native player communication

- [ ] **Step 1: 创建 PlayerBridge**

```kotlin
// app/src/main/java/com/sakura/player/player/PlayerBridge.kt
package com.sakura.player.player

import android.util.Log
import com.sakura.player.bridge.JsBridge

class PlayerBridge(
    private val player: SakuraPlayerView,
    private val jsEvaluator: (String) -> Unit
) {
    private val TAG = "PlayerBridge"

    /** Called from JS to play an online episode */
    fun playOnline(videoId: Long, title: String, epIndex: Int, callback: String) {
        // Delegate to JsBridge's playOnline for m3u8 extraction + CDN race
        // Then route result to SakuraPlayerView
    }

    /** Called from JS to play a local file */
    fun playLocal(path: String) {
        // Get content:// URI and play via ExoPlayer
    }

    /** JS queries current player state */
    fun getPlayerState(callback: String) {
        val state = player.exoPlayer?.let {
            SakuraPlayerView.PlayerState(
                playing = it.playWhenReady,
                position = it.currentPosition,
                duration = it.duration,
                currentEp = currentEpIndex,
                speed = it.playbackParameters?.speed ?: 1f
            )
        }
        val json = """{"playing":${state?.playing},"position":${state?.position},"duration":${state?.duration}}"""
        jsEvaluator("$callback(null, $json)")
    }

    /** Update player with episode list from detail page */
    fun setEpisodes(episodesJson: String) {
        // Parse JSON array of episodes and update PlayerConfig
    }

    private var currentEpIndex: Int = 0
}
```

- [ ] **Step 2: 修改 MainActivity 集成 SakuraPlayerView**

在 `MainActivity` 中添加:

```kotlin
private lateinit var sakuraPlayer: SakuraPlayerView
private lateinit var playerBridge: PlayerBridge

// In onCreate, after WebView setup:
sakuraPlayer = SakuraPlayerView(this).apply {
    visibility = View.GONE  // hidden until playback starts
    onFullscreenRequest = {
        val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
            putExtra("source", "current_state")
            // Pass current player state to fullscreen activity
        }
        startActivity(intent)
    }
    onEpisodeChange = { idx ->
        // Notify JS of episode change
        evalJs("if(window.onPlayerEpisodeChange)window.onPlayerEpisodeChange($idx)")
    }
}
rootLayout.addView(sakuraPlayer)

playerBridge = PlayerBridge(sakuraPlayer, { js -> evalJs(js) })
```

- [ ] **Step 3: 构建验证**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/PlayerBridge.kt app/src/main/java/com/sakura/player/MainActivity.kt
git commit -m "feat(player): add PlayerBridge for JS-native communication"
```

---

### Task 9: 重写 PlayerActivity + 全屏集成

**Files:**
- Rewrite: `app/src/main/java/com/sakura/player/player/PlayerActivity.kt`
- Modify: `app/src/main/java/com/sakura/player/MainActivity.kt`

**Interfaces:**
- Consumes: Complete `SakuraPlayerView` from Tasks 2-7
- Produces: Fullscreen playback with correct restore on back

- [ ] **Step 1: 重写 PlayerActivity**

```kotlin
// app/src/main/java/com/sakura/player/player/PlayerActivity.kt
package com.sakura.player.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sakura.player.bridge.JsBridge
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private lateinit var player: SakuraPlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        player = SakuraPlayerView(this)
        setContentView(player)

        val source = intent.getStringExtra("source") ?: "online"
        val title = intent.getStringExtra("title") ?: ""
        val position = intent.getLongExtra("position", 0)
        val episodesJson = intent.getStringExtra("episodes") ?: "[]"

        val config = PlayerConfig(
            mode = PlayerMode.FULLSCREEN,
            title = title,
            episodes = parseEpisodes(episodesJson)
        )
        player.setup(config)

        when (source) {
            "local" -> {
                val path = intent.getStringExtra("path") ?: ""
                val file = File(path)
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    player.playLocal(uri)
                } else finish()
            }
            "url" -> {
                val url = intent.getStringExtra("url") ?: ""
                player.playLocal(Uri.parse(url))
            }
            "online" -> {
                val url = intent.getStringExtra("url") ?: ""
                player.play(url)
            }
        }
        if (position > 0) player.exoPlayer?.seekTo(position)

        player.onFullscreenRequest = { finish() } // exit fullscreen
    }

    private fun parseEpisodes(json: String): List<EpisodeItem> {
        // JSONArray parsing
        val list = mutableListOf<EpisodeItem>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(EpisodeItem(
                    index = obj.getInt("index"),
                    name = obj.getString("name"),
                    path = obj.optString("path", ""),
                    videoId = obj.optLong("videoId", 0),
                    isLocal = obj.optBoolean("isLocal", false)
                ))
            }
        } catch (_: Exception) {}
        return list
    }

    override fun onDestroy() {
        val finalPos = player.exoPlayer?.currentPosition ?: 0
        if (finalPos > 0) JsBridge.lastFullscreenPosition = finalPos
        player.release()
        super.onDestroy()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }
}
```

- [ ] **Step 2: 更新 MainActivity 的全屏启动逻辑**

修改 `MainActivity` 中的 `sakuraPlayer.onFullscreenRequest`:

```kotlin
sakuraPlayer.onFullscreenRequest = {
    val pos = sakuraPlayer.exoPlayer?.currentPosition ?: 0
    val episodesJson = buildEpisodesJson()
    val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
        putExtra("source", "online")
        putExtra("url", currentM3u8Url)
        putExtra("title", currentTitle)
        putExtra("position", pos)
        putExtra("episodes", episodesJson)
    }
    startActivity(intent)
}
```

- [ ] **Step 3: 构建验证**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sakura/player/player/PlayerActivity.kt app/src/main/java/com/sakura/player/MainActivity.kt
git commit -m "feat(player): rewrite PlayerActivity with SakuraPlayerView fullscreen"
```

---

### Task 10: WebView JS 适配 + 端到端集成

**Files:**
- Modify: `app/src/main/assets/www/js/pages/detail.js`
- Modify: `app/src/main/java/com/sakura/player/MainActivity.kt`

**Interfaces:**
- Consumes: Complete player system from Tasks 1-9
- Produces: JS side triggers native player instead of WebView `<video>`

- [ ] **Step 1: 修改 detail.js — 在线播放切换到原生播放器**

在 `playEpisode()` 中，当不是本地文件时，调用原生播放器而非 WebView video:

```javascript
// detail.js playEpisode() 修改:
if (window.currentDetail.isLocal) {
    // 本地播放 — 使用 ExoPlayer (现有逻辑保持)
    startLocalPlayer(ep.path);
} else {
    // 在线播放 — 使用新的原生 SakuraPlayer
    var episodesJson = buildEpisodesJson();
    window.Sakura.playOnlineNative(
        window.currentDetail.videoId,
        window.currentDetail.title,
        epIndex,
        episodesJson
    );
}

function buildEpisodesJson() {
    if (!window.currentDetail || !window.currentDetail.episodes) return '[]';
    return JSON.stringify(window.currentDetail.episodes.map(function(ep) {
        return {
            index: ep.index,
            name: ep.name,
            path: ep.path || '',
            videoId: window.currentDetail.videoId || 0,
            isLocal: window.currentDetail.isLocal || false
        };
    }));
}
```

- [ ] **Step 2: 在 MainActivity WebAppInterface 添加新桥接方法**

```kotlin
@JavascriptInterface
fun playOnlineNative(videoId: Long, title: String, epIndex: Int, episodesJson: String) {
    runOnUiThread {
        // Configure player
        val config = PlayerConfig(
            mode = PlayerMode.INLINE,
            title = title,
            episodes = parseEpisodesFromJson(episodesJson)
        )
        sakuraPlayer.setup(config)
        sakuraPlayer.visibility = View.VISIBLE

        // Use JsBridge to get m3u8 (with CDN race), then play
        bridge.playOnline(videoId, title, epIndex, "_cb_playnative")
    }
}
```

添加全局回调:

```kotlin
// In WebAppInterface or MainActivity
window._cb_playnative = { err, data ->
    if (!err && data) {
        val parsed = JSON.parse(data)
        sakuraPlayer.play(parsed.m3u8Url)
    }
}
```

- [ ] **Step 3: 适配 MainActivity onResume 进度恢复**

```kotlin
override fun onResume() {
    super.onResume()
    val resumePos = JsBridge.lastFullscreenPosition
    if (resumePos > 0) {
        JsBridge.lastFullscreenPosition = 0
        sakuraPlayer.exoPlayer?.seekTo(resumePos)
        sakuraPlayer.exoPlayer?.playWhenReady = true
    }
}
```

- [ ] **Step 4: 构建并安装测试**

```bash
gradle assembleDebug --no-daemon 2>&1 | tail -5
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/www/js/pages/detail.js app/src/main/java/com/sakura/player/MainActivity.kt
git commit -m "feat(player): wire JS to native SakuraPlayer, replace WebView video"
```

---

### Task 11: 最终验证 & 清理

**Files:**
- Clean up old code that's no longer needed

- [ ] **Step 1: 删除 fullplayer.html (不再需要)**

```bash
# Only if player is fully working
git rm app/src/main/assets/www/fullplayer.html
```

- [ ] **Step 2: 清理 detail.js 中旧的 bindPlayer 和 WebView video 逻辑**

保留 `resetPlayer`, `startLocalPlayer`, `bindLocalPlayerControls` (本地 ExoPlayer 半屏仍需要)。
移除 `bindPlayer` 函数（Task 10 已不再调用）。

- [ ] **Step 3: 端到端验证清单**
  - [ ] 在线播放：搜索 → 详情 → 点播 → 手势操作
  - [ ] 本地播放：文件浏览器 → 点 mp4 → 播放
  - [ ] 进度同步：半屏 ↔ 全屏切换不丢进度
  - [ ] 控件显隐：3s 自动隐藏
  - [ ] 锁定：锁定后手势失效

- [ ] **Step 4: 最终 Commit**

```bash
git add . && git commit -m "feat(player): complete B站-style unified player"
```
