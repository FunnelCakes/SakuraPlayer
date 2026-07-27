# SakuraPlayer 项目状态文档

## 项目概述
樱花动漫播放/下载器 Android APK。Kotlin + WebView + ExoPlayer，B站风格 UI，4 标签页布局，支持在线播放、m3u8 下载、本地文件管理。

## 技术栈
- Kotlin + Gradle 9.6.1 + AGP 8.7.3 + Kotlin 2.0
- OkHttp 4.12.0, Jsoup 1.17.2, Room 2.6.1, ExoPlayer (media3) 1.3.0
- WebView 前端：原生 JS/CSS，无框架
- 樱花动漫源站：yinghua14.com (AppleCMS)

## 目录结构
```
SakuraPlayer/app/src/main/
├── java/com/sakura/player/
│   ├── MainActivity.kt          # WebView 容器 + WebAppInterface (50+ JS桥接方法) + LocalPlayerBridge (ExoPlayer) + 权限管理
│   ├── AnimeService.kt          # 前台下载服务 + WakeLock + 通知回调
│   ├── bridge/JsBridge.kt       # 核心桥接层：搜索/详情/播放/下载/本地文件/下载记录/封面同步
│   ├── scraper/
│   │   ├── AnimeScraper.kt      # 网站搜索/详情/推荐 爬虫（Jsoup）
│   │   ├── VideoExtractor.kt    # m3u8 URL 提取（从播放页解析 var player_aaaa）
│   │   └── DomainFinder.kt      # 域名探活
│   ├── download/
│   │   ├── DownloadManager.kt   # 下载队列管理（Semaphore(2) 并发，独立协程作用域）
│   │   ├── TsDownloader.kt      # m3u8 解析 + 8线程 TS 分片下载 + AES-128 解密 + 合并
│   │   ├── CdnProber.kt         # CDN 嗅探器（TLS 1.2 → TLS 1.3 → HTTP 三级探测）
│   │   ├── DownloadNotif.kt     # 下载通知管理
│   │   ├── DownloadRecordManager.kt  # 下载记录 CRUD + 路径变更同步 + 增量同步
│   │   └── FfmpegDownloader.kt  # FFmpeg 备选方案（保留未用）
│   ├── local/LocalFileManager.kt # 本地文件扫描/封面/CRUD/排序
│   ├── player/PlayerActivity.kt # 全屏播放器（本地=ExoPlayer, 在线=WebView fullplayer.html）
│   ├── network/HttpClient.kt    # 统一 HTTP 客户端（共享 CookieJar，三套连接规格）
│   ├── data/
│   │   ├── AppDatabase.kt       # Room DB：follows + watch_history + download_records (v2)
│   │   └── SettingsPrefs.kt     # SharedPreferences：下载路径、活跃域名
│   └── follow/
│       ├── FollowManager.kt     # 追番 CRUD
│       └── UpdateChecker.kt     # 追番更新检查
├── assets/www/
│   ├── index.html               # SPA：4 标签页 + 4 覆盖层 + toast + 播放器控件
│   ├── fullplayer.html          # 独立全屏播放器页面（PlayerActivity 加载）
│   ├── css/style.css            # B站粉(#FB7299)主题
│   └── js/
│       ├── app.js               # 核心：$选择器、callNativeLegacy、标签切换、覆盖层管理、handleBackPress、下载列表渲染
│       ├── pages/
│       │   ├── home.js          # 首页：本地库 + 追番列表
│       │   ├── detail.js        # 详情页：在线/本地播放、选集、播放器控件、ExoPlayer桥接、追番/下载/重下
│       │   ├── local.js         # 本地文件浏览器：2列网格、选择模式、文件夹导航、封面懒加载、重下
│       │   └── search.js        # 搜索页
│       ├── player.js            # 未使用的 SakuraPlayer 类（死代码）
│       └── mock-bridge.js       # 浏览器测试用 Mock 桥接
├── res/
│   └── xml/
│       ├── file_paths.xml       # FileProvider 路径配置（external-path path="."）
│       └── network_security_config.xml  # 允许明文流量
└── AndroidManifest.xml          # 权限、Activity、Service、FileProvider
```

## 核心架构

### 桥接模式
```
JS → window.Sakura.method(args, callbackId)
  → MainActivity.WebAppInterface.@JavascriptInterface
    → runOnUiThread { bridge.method(args, callbackId) }
      → JsBridge.method() [协程 IO]
        → evalJs("callbackId(null, result)")
          → webView.evaluateJavascript()
            → window['_legacy_cb_N'](null, data)
              → callNativeLegacy Promise resolve
```

callNativeLegacy (app.js:55) 创建临时全局回调函数 `window._legacy_cb_N`，等待原生 evalJs 调用。

### 返回键优先级
1. 选择模式 → exitSelectMode
2. 覆盖层打开 → closeOverlay（详情页、搜索页）
3. 本地文件浏览器 → 返回上级文件夹（localHistory.pop）
4. 非首页标签 → switchTab('home')
5. 首页 → 双击退出

### 本地播放架构
- 半屏内嵌：ExoPlayer PlayerView 覆盖 WebView (MainActivity.FrameLayout)
- 全屏：PlayerActivity 用原生 ExoPlayer
- 同步：半屏→全屏通过 Intent 传递 currentPosition；全屏→半屏通过 JsBridge.lastFullscreenPosition + MainActivity.onResume 恢复
- 控件通信：LocalPlayerBridge (window.LocalPlayer) with getState() 轮询 (400ms) + play/pause/seek/release

### 下载架构
- TsDownloader: 8 线程并发 TS 分片下载 + AES-128-CBC 解密 + 合并
- CdnProber: 下载前三级探测 (TLS 1.2 → TLS 1.3 → HTTP)
- HttpClient: 三套连接规格 (tls12/tls13/http) + 共享 CookieJar
- DownloadManager: Semaphore(2) 最大2并发 + 自动重试 (sid 1-4)
- 完成时：写 File → insert download_records → downloadCover → cleanup temp

## 已知问题
1. **在线播放可能失效** — 最后一次 detail.js 修改 (bindPlayer 提前) 后未做端到端验证
2. **CDN TLS 指纹复杂** — vv.jisuzyv.com (TLS 1.3 RST)、vodcnd16.trjho.com (需TLS 1.2+Referer)、不同番用不同 CDN
3. **封面文件存在但 JS 端可能不显示** — content:// URI 转换正确，但上次用户反馈视频文件封面仍不显示
4. **detail.js IIFE hook 复杂** — closeOverlay/switchTab 两个覆盖，多次修改后可能有残留 bug
5. **player.js 整个文件是死代码** — 213行永不使用
6. **mock-bridge.js 需随桥接方法同步更新** — 新增的 getDownloadedEps、syncDownloadRecords 等方法可能遗漏
7. **下载去重依赖 videoId 匹配** — sync 用网站搜索匹配 videoId，搜不到的用 hash fallback

## 数据库 (v2)
- follows: videoId, title, coverUrl, status, totalEps, watchedEps, lastCheckTime, hasUpdate
- watch_history: id, followId, epIndex, watchedAt
- download_records: localPath(PK), videoId, title, epIndex, coverUrl, updatedAt

## 关键待验证点
1. 在线播放：搜索番剧 → 点结果 → 详情页出现 → 点剧集 → 视频加载播放
2. 本地播放：文件浏览器 → 点MP4 → 详情页出现 → 视频播放 + 选集列表 → 全屏
3. 封面：文件夹封面 + 视频文件封面 都正确显示
4. 批量下载去重：已下载集数自动跳过
5. 重新下载：DB 记录先删 → 文件删 → 重新下载启动
6. 文件操作同步 DB：删除/移动/重命名后 download_records 自动更新
7. 返回键：详情覆盖层先关，再关文件夹，再切标签，再退出
