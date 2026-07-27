package com.sakura.player.download

import android.util.Log
import java.io.File

object FfmpegDownloader {
    private const val TAG = "FfmpegDownloader"

    suspend fun download(
        m3u8Url: String,
        task: DownloadTask,
        onProgress: (DownloadTask) -> Unit
    ) {
        val dir = File(task.saveDir)
        if (!dir.exists()) dir.mkdirs()
        val outPath = "$dir/${task.title}_第${task.epIndex}集.mp4"

        try {
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", m3u8Url,
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                "-progress", "pipe:1",
                "-nostats",
                outPath
            ).redirectErrorStream(true).start()

            process.inputStream.bufferedReader().use { reader ->
                var duration = 0L
                val outTimeRegex = Regex("""out_time_us=(\d+)""")
                val totalDurationRegex = Regex("""duration=(\d+)""")

                reader.forEachLine { line ->
                    outTimeRegex.find(line)?.let {
                        val current = it.groupValues[1].toLongOrNull() ?: 0
                        if (duration > 0) {
                            task.progress = ((current * 100) / duration).toInt().coerceIn(0, 100)
                            onProgress(task)
                        }
                    }
                    totalDurationRegex.find(line)?.let {
                        duration = it.groupValues[1].toLongOrNull() ?: 0
                    }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                task.status = "failed"
                task.error = "FFmpeg 退出码: $exitCode"
            }
        } catch (e: Exception) {
            task.status = "failed"
            task.error = "FFmpeg 不可用: ${e.message}"
            Log.e(TAG, "FFmpeg download failed", e)
        }
    }
}
