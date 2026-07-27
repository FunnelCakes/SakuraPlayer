# 本地动漫文件管理器 - 最终需求

## 1. 整体设计

- 根目录 = 下载路径（默认 `/storage/emulated/0/SakuraAnime/`）
- 纵向 2 列卡片网格，仿华为文件管理器风格
- 顶部路径面包屑：`樱花动漫 > 进击的巨人 > 最终季`
- 点击面包屑节点直接跳转
- 网格第一个位置固定为"新建文件夹"卡片

## 2. 封面图

### 2.1 缓存路径
`/storage/emulated/0/Android/data/com.sakura.player/files/covers/`

### 2.2 封面键值
- 下载的视频：`v_{videoId}.jpg`（videoId 来自搜索结果/下载记录）
- 手动放入的文件：没有封面，显示默认占位符

### 2.3 封面来源（优先级）
1. 下载时爬樱花动漫详情页获取封面 URL → 下载到 covers 缓存
2. 本地文件夹内 `cover.jpg` / `cover.png`
3. 下载历史记录中的封面 URL → 缓存
4. 默认占位符 🎬

### 2.4 文件夹封面
取文件夹内第一个有封面的子项（递归），优先级同上

## 3. 浏览与导航

- 根视图：显示根目录下所有文件夹 + mp4/mkv 文件
- **第一个格子永远是"新建文件夹"卡片**（虚线边框 + "+" 图标），点击弹出输入框创建
- 点击文件夹 → 进入子目录
- 点击 mp4 → 直接播放
- 返回键：选择模式 → 退出选择模式 → 上级目录 → 根目录保持 → 回到首页

## 4. 卡片展示（2列网格，第一个格子 = 新建文件夹）

每张卡片：16:10 封面 + 名称 + 角标
- 新建文件夹卡片：第 1 格，虚线边框 + "+" 图标，不在选择模式中出现勾选框
- 文件夹角标：右下角 "12 集"
- 视频文件角标：右下角时长 "24:30"
- 封面图懒加载：仅当前可见卡片请求封面

## 4.1 文件夹封面
- **IP 根文件夹**：取对应动漫的封面（从下载记录 `videoId` → `covers/v_{videoId}.jpg`）
- **嵌套子文件夹（季/篇）**：取其下第一个子文件夹的封面，没有子文件夹则取第一个视频关联的封面，都没有则默认占位符

## 5. 文件操作（华为文件管理风格）

### 5.1 选择模式
- 长按任意卡片 → 进入选择模式
- 所有卡片右上角出现勾选框
- 点击卡片 → 勾选/取消
- 点击全选 → 或取消全选
- 底栏切换为操作栏

### 5.2 底栏操作栏
选择模式下底栏显示：
- 全选/取消全选 | 移动 | 重命名（仅单选） | 删除 | 取消

### 5.3 删除
- 弹出确认对话框 → 确认后物理删除 → 刷新列表

### 5.4 重命名
- 仅单选可用
- 弹出输入框预填当前名 → 确认后重命名 → 刷新列表

### 5.5 移动
- 弹出目录树选择器（从根目录开始浏览）
- 选目标文件夹 → 确认 → 移动文件 → 刷新列表
- 不能移动到自身或子目录

### 5.6 新建文件夹
- 网格第一个格子：虚线边框 + "+" 图标
- 点击 → 弹出输入框 → 输入文件夹名 → 确认 → 创建到当前目录 → 刷新列表
- 选择模式下该卡片隐藏勾选框（不能选中）

## 6. Kotlin 接口

```kotlin
data class LocalItem(
    val name: String,        // 显示名
    val path: String,        // 完整路径
    val isDir: Boolean,      // 是否文件夹
    val coverKey: String,    // 封面缓存键（v_{videoId} 或文件路径的 md5）
    val videoCount: Int,     // 文件夹内的视频数（仅文件夹）
    val duration: String,    // 时长 "24:30"（仅视频文件）
    val size: String         // 文件大小（仅视频文件）
)

// 浏览目录
fun browseLocalDir(path: String): List<LocalItem>

// 获取封面路径（异步生成/返回缓存）
fun getCoverPath(coverKey: String): String

// 删除
fun deleteLocalItems(paths: List<String>): Boolean

// 重命名
fun renameLocalItem(path: String, newName: String): Boolean

// 移动
fun moveLocalItems(paths: List<String>, targetDir: String): Boolean

// 新建文件夹
fun createLocalDir(parentPath: String, name: String): Boolean
```

## 7. 封面生成

- 下载动漫时：`details.coverUrl` → OkHttp 下载 → 保存为 `covers/v_{videoId}.jpg`
- 关联到视频：视频文件保存时记录 `videoId` → `coverKey = "v_{videoId}"`
- 本地已有文件扫描时：从下载记录数据库匹配 videoId → 找到 coverKey
