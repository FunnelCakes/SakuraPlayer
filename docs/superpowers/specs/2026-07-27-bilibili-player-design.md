# SakuraPlayer — B 站风格播放器设计

## 概述

用纯原生 Kotlin 自定义 View 重建播放器，完全照搬 B站 Android app 的交互模式。覆盖详情页半屏和全屏两种场景，统一组件 `SakuraPlayerView`。

## 架构

`SakuraPlayerView` 继承 `FrameLayout`，6 层叠加：

```
Layer 1: ExoPlayer PlayerView     — 视频画面
Layer 2: GestureOverlay           — 透明触摸层，拦截所有手势
Layer 3: CenterHint               — 中间浮动提示 (▶⏸ 2x)
Layer 4: SideHUD                  — 亮度🔆 / 音量🔊 指示条
Layer 5: ControlBar               — 底部控制栏 (进度条+按钮)
Layer 6: SlidePanels              — 选集/倍速 底部滑出面板
```

两种模式：
- `INLINE`：嵌入 MainActivity FrameLayout，覆盖 WebView
- `FULLSCREEN`：PlayerActivity 全屏，自动横屏

**技术选型**：ExoPlayer (media3) 负责解码和视频渲染。所有 UI 控件用原生 Android View，自定义绘制。不使用 WebView。

## 手势系统

### 分区
```
┌────────────┬──────────────────┬────────────┐
│  左侧 25%   │     中间 50%      │  右侧 25%   │
│  ↕ 亮度     │  ↔ 快进快退       │  ↕ 音量     │
└────────────┴──────────────────┴────────────┘
```

### 手势表

| 手势 | 区域 | 行为 |
|------|------|------|
| 单击 | 全屏 | 显示/隐藏控件 (300ms fade) |
| 双击 | 全屏任意位置 | 播放/暂停，中间显示 ▶/⏸ 图标 |
| 长按 500ms | 全屏 | 2x 倍速播放，松手恢复，显示 "2x 快放中" |
| 左区 ↕ 滑动 | 左侧 25% | 调节亮度，HUD 显示百分比 |
| 右区 ↕ 滑动 | 右侧 25% | 调节音量，HUD 显示百分比 |
| ↔ 水平滑动 | 全屏 | 快进/快退，顶部显示目标时间预览 |
| 进度条 ↔ 拖拽 | 底部 | 精细 seek，上方弹出时间预览气泡 |
| 进度条 ↕ 滑动 | 底部 | 微调 seek（上=前进，下=后退） |

### 冲突解决
1. 锁定状态 → 仅响应解锁按钮
2. 进度条拖拽中 → 全局手势暂停
3. 面板打开 → 手势传递给面板
4. 竖滑检测：|dy| > |dx| 且 |dy| > 10px → 亮度/音量
5. 水平检测：|dx| > |dy| 且 |dx| > 10px → 快进快退

### 触觉反馈
每次手势生效时调用 `view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)`

## 控件层

### ControlBar (底部)

```
┌──────────────────────────────────────────────┐
│  ┌───────────────────────────────────────┐   │
│  │  ▓▓▓▓▓▓░░░░░░░░░░░  ●               │   │
│  │  ├播放──┤├缓冲──┤      ↑拖拽圆点       │   │
│  └───────────────────────────────────────┘   │
│  00:12                           24:06       │
│                                               │
│  ▶⏸          ⏮ ⏭       ⏩    ≡    ...       │
│  播放      上集下集    倍速  选集  更多       │
└──────────────────────────────────────────────┘
```

按钮可见性：
- ⏮ ⏭ (上集/下集)：仅 FULLSCREEN 模式显示
- 进度条时间：始终显示
- 3 秒无操作自动渐隐 (alpha 1→0, 300ms)

### 进度条拖拽
- 手指按下圆点 → 圆点 scale 1.4×，弹出 `01:23 / 24:06` 预览气泡
- 水平拖动 → 实时更新 ExoPlayer seek + 气泡时间
- 松手 → 圆点回弹，确认 seek 位置
- 点击轨道任意位置 → 直接跳转

### CenterHint
- 暂停/播放切换时显示 ▶/⏸ 图标 (300ms 后自动消失)
- 长按 2x 时显示 "2x 快放中"
- 点击屏幕任意位置显示/隐藏控件时无提示

### SideHUD
- 亮度 HUD：左侧，竖向进度条 + 🔆 图标 + 百分比
- 音量 HUD：右侧，竖向进度条 + 🔊 图标 + 百分比
- 手势触发时显示，松手 800ms 后渐隐

## 滑出面板

### 选集面板
- 触发：点击 ControlBar `≡` 按钮
- 从底部滑入 (translateY, 300ms)
- 当前播放集数粉色高亮 (`#FB7299`)
- 点击其他集 → 切换播放，面板关闭
- 遮罩点击 → 关闭

### 倍速面板
- 触发：点击 ControlBar `⏩` 按钮，或长按播放器
- 选项：0.5x · 0.75x · 1.0x · 1.25x · 1.5x · 2.0x · 3.0x
- 当前速度粉色高亮
- 选中后立即生效

## 锁定模式
- 触发：点击浮动 🔓 按钮
- 锁定后：所有控件隐藏，手势禁用，仅显示 🔒 按钮
- 🔒 按钮 2s 后自动半透明 (alpha 0.4)
- 点击 🔒 解锁，恢复所有控件

## 全屏切换
- INLINE → FULLSCREEN：点击 ⛶ 按钮，启动 PlayerActivity，传递当前位置
- FULLSCREEN → INLINE：按返回键或下滑手势，回传位置给 MainActivity
- FULLSCREEN 模式自动横屏 (SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
- 系统栏完全隐藏 (IMMERSIVE_STICKY)

## 与现有系统集成

### 在线播放 (m3u8)
1. JS 调用 `playOnline` → JsBridge 竞速返回最快 m3u8
2. ExoPlayer 直接加载 m3u8 URL (media3 原生支持 HLS)
3. 不再需要 WebView `<video>` 标签

### 本地播放 (mp4)
1. JS 调用 `getLocalVideoUrl` → 获取 content:// URI
2. ExoPlayer 加载本地文件
3. 选集列表通过 `browseLocalDir` 获取同目录下所有 mp4

### JS 桥接
保留现有桥接方法，新增：
- `playEpisode(videoId, epIndex)` — JS 通知原生切换剧集
- `getPlayerState()` → JSON — JS 查询当前播放状态
- 进度同步：`onPlayerStateChanged` → evalJs 回调

## 文件结构

```
app/src/main/java/com/sakura/player/
├── player/
│   ├── SakuraPlayerView.kt      — 主 ViewGroup，组装 6 层
│   ├── PlayerActivity.kt        — 重写，全屏模式宿主
│   ├── gesture/
│   │   └── GestureOverlay.kt    — 手势检测与分发
│   ├── control/
│   │   ├── ControlBar.kt        — 底部控制栏
│   │   ├── CenterHint.kt        — 中间浮动提示
│   │   └── SideHUD.kt           — 亮度/音量指示
│   ├── panel/
│   │   ├── EpisodePanel.kt      — 选集面板
│   │   └── SpeedPanel.kt        — 倍速面板
│   └── PlayerBridge.kt          — JS ↔ 原生通信接口
```

## 关键行为矩阵

| 状态 | 单击 | 双击 | 左滑 | 右滑 | 横滑 | 长按 |
|------|------|------|------|------|------|------|
| 正常播放 | 显隐控件 | 暂停 | 调亮度 | 调音量 | seek | 2x |
| 暂停中 | 显隐控件 | 播放 | — | — | — | — |
| 锁定 | — | — | — | — | — | — |
| 面板打开 | 关面板 | — | — | — | — | — |
| 拖拽进度条 | — | — | — | — | seek | — |

## 排除项
- 弹幕系统（不做）
- 定时关闭（不做）
- 画质手动选择（自动选最高）
- 投屏（不做）

## 验收标准
1. 手势响应延迟 < 100ms
2. 控件显隐动画流畅 60fps
3. 全屏↔半屏切换不丢进度
4. 在线 m3u8 和本地 mp4 播放体验一致
5. B站用户上手零学习成本
