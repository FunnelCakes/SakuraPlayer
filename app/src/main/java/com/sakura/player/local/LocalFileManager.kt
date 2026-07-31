package com.sakura.player.local

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class LocalItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val coverKey: String,
    val episodeCount: Int,
    val duration: String,
    val size: String
)

object LocalFileManager {
    private const val TAG = "LocalFileManager"
    private lateinit var coversDir: String

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun init(ctx: Context) {
        coversDir = ctx.filesDir.absolutePath + "/covers"
        File(coversDir).mkdirs()
    }

    /** Scan directory and return items suitable for 2-column grid */
    fun scanDir(path: String): List<LocalItem> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val items = mutableListOf<LocalItem>()
        val children = dir.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { extractEpNumber(it.name) }
        ) ?: return emptyList()

        for (f in children) {
            if (f.isHidden || f.name.startsWith(".")) continue
            if (f.isFile && !f.name.endsWith(".mp4") && !f.name.endsWith(".mkv")) continue

            val coverKey = if (f.isDirectory) {
                // IP root: try videoId from folder name; nested: first child
                findCoverKey(f, path)
            } else ""
            val epCount = if (f.isDirectory) countVideos(f) else 1
            val dur = if (f.isFile) "" else "" // duration from MediaMetadataRetriever would be too slow
            val sz = if (f.isFile) formatSize(f.length()) else ""

            items.add(LocalItem(
                name = f.name,
                path = f.absolutePath,
                isDir = f.isDirectory,
                coverKey = coverKey,
                episodeCount = epCount,
                duration = dur,
                size = sz
            ))
        }
        return items
    }

    private fun findCoverKey(dir: File, rootPath: String): String {
        // Check if there's a downloaded cover for this directory
        val coverFiles = dir.listFiles()?.filter {
            it.isFile && (it.name == "cover.jpg" || it.name == "cover.png" || it.name.endsWith(".jpg"))
        }
        if (coverFiles != null && coverFiles.isNotEmpty()) {
            return coverFiles.first().absolutePath
        }
        // Check subdirectories for any cover
        dir.listFiles()?.filter { it.isDirectory && !it.isHidden }?.forEach { sub ->
            val subCover = findCoverKey(sub, rootPath)
            if (subCover.isNotEmpty()) return subCover
        }
        return ""
    }

    private fun countVideos(dir: File): Int {
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isHidden) {
                when {
                    f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".mkv")) -> count++
                    f.isDirectory -> count += countVideos(f)
                }
            }
        }
        return count
    }

    /** Download cover image from URL and cache it */
    fun downloadCover(videoId: Long, coverUrl: String, saveDir: String = "") {
        if (coverUrl.isBlank()) return
        try {
            val cacheFile = File(coversDir, "v_${videoId}.jpg")
            if (cacheFile.exists()) return
            Log.e(TAG, "Downloading cover: $coverUrl")
            val req = Request.Builder().url(coverUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", "https://yinghua14.com/")
                .get().build()
            val resp = com.sakura.player.network.HttpClient.client.newCall(req).execute()
            if (resp.isSuccessful && resp.body != null) {
                cacheFile.outputStream().use { fos ->
                    resp.body!!.byteStream().copyTo(fos)
                }
                Log.e(TAG, "Cover cached: v_${videoId}.jpg (${cacheFile.length()} bytes)")
            } else {
                Log.e(TAG, "Cover download HTTP ${resp.code} for $coverUrl")
            }
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "Cover download failed for $videoId: ${e.message}", e)
        }
    }

    /** Find any cover image file (.jpg/.png) directly inside a directory. */
    fun findCoverForDir(dirPath: String): String {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return ""
        dir.listFiles()?.forEach { f ->
            if (!f.isHidden && f.isFile && (f.name == "cover.jpg" || f.name.endsWith(".jpg") || f.name.endsWith(".png"))) {
                return f.absolutePath
            }
        }
        // Also check child directories
        dir.listFiles()?.filter { it.isDirectory && !it.isHidden }?.forEach { sub ->
            val subCover = findCoverForDir(sub.absolutePath)
            if (subCover.isNotEmpty()) return subCover
        }
        return ""
    }

    fun getCoverPath(coverKey: String): String {
        if (coverKey.isBlank()) return ""
        if (coverKey.startsWith("/") && File(coverKey).exists()) return coverKey
        if (coverKey.startsWith("v_")) {
            // Try with .jpg extension first (downloadCover saves as v_{id}.jpg)
            val jpgFile = File(coversDir, "$coverKey.jpg")
            if (jpgFile.exists()) return jpgFile.absolutePath
            val f = File(coversDir, coverKey)
            return if (f.exists()) f.absolutePath else ""
        }
        return ""
    }

    fun deleteItems(paths: List<String>): Boolean {
        var ok = true
        for (p in paths) {
            val f = File(p)
            if (f.exists()) {
                val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (!deleted) ok = false
            }
        }
        return ok
    }

    fun renameItem(path: String, newName: String): Boolean {
        val f = File(path)
        if (!f.exists()) return false
        val parent = f.parentFile ?: return false
        val dest = File(parent, newName)
        return f.renameTo(dest)
    }

    fun moveItems(paths: List<String>, targetDir: String): Boolean {
        val target = File(targetDir)
        if (!target.exists() || !target.isDirectory) return false
        var ok = true
        for (p in paths) {
            val src = File(p)
            if (!src.exists()) { ok = false; continue }
            // Prevent moving into self or subdirectory
            if (src.isDirectory && target.absolutePath.startsWith(src.absolutePath)) {
                ok = false; continue
            }
            val dest = File(target, src.name)
            if (!src.renameTo(dest)) ok = false
        }
        return ok
    }

    fun createDir(parentPath: String, name: String): Boolean {
        val dir = File(parentPath, name)
        return if (dir.exists()) false else dir.mkdirs()
    }

    /** Extract episode number from filename for numeric sorting */
    private fun extractEpNumber(name: String): Int {
        // Try patterns like: 第01集, EP01, E01, 01, S01E01
        val patterns = listOf(
            Regex("""第\s*(\d+)"""),
            Regex("""[Ee][Pp]?\s*(\d+)"""),
            Regex("""[Ss]\d+[Ee](\d+)"""),
            Regex("""(\d+)""")
        )
        for (p in patterns) {
            val m = p.find(name)
            if (m != null) return m.groupValues[1].toIntOrNull() ?: continue
        }
        return Int.MAX_VALUE // put files without episode numbers at the end
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "${bytes / 1024} KB"
        bytes < 1073741824 -> "${bytes / 1048576} MB"
        else -> "${bytes / 1073741824} GB"
    }
}
