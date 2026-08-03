# 樱花播放器 (SakuraPlayer)

樱花动漫在线播放/下载器。Kotlin + WebView + GSYVideoPlayer（ExoPlayer 内核），B站风格 UI，支持在线播放、m3u8 下载、本地文件管理。

## 功能特性

- **在线播放**：搜索/发现页 → 详情 → 点选集 → GSYVideoPlayer 全屏播放，B站风格手势
- **多 CDN 竞速下载**：同时探测多个 CDN，自适应采样选出最快节点，全程用户无感
- **离线缓存**：m3u8 TS 分片多线程并行下载 + AES-128 解密 + 合并
- **本地管理**：文件夹浏览、封面、选集播放、重新下载
- **追番系统**：收藏追番，更新检查
- **播放器交互**（GSYVideoPlayer 定制）：
  - 长按 2x 倍速
  - 锁屏
  - 倍速下拉菜单（0.5x ~ 3.0x）
  - 选集下拉菜单（全屏显示）
  - 上/下一集切换
  - 顶部下拉查看状态栏
  - 切后台保留播放进度与暂停状态

## 技术栈

- Kotlin + Gradle + AGP
- GSYVideoPlayer (ExoPlayer/media3 内核)
- OkHttp, Jsoup, Room
- WebView 前端：原生 JS/CSS

## 截图

（待补充）

## 安装

下载 [最新 Release](https://github.com/FunnelCakes/SakuraPlayer/releases) 中的 APK 安装即可。

## 构建

```bash
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release.apk
```

## 数据源

基于樱花动漫 (AppleCMS) 站点。站点域名可能变更，应用内可手动刷新域名。

## 许可证

作者: 奶球
ALL RIGHTS RESERVED.

> 仅供个人学习交流使用，请勿用于商业用途。动漫版权归原制作方所有。
