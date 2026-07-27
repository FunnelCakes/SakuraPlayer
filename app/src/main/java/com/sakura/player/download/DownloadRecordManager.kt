package com.sakura.player.download

import android.util.Log
import com.sakura.player.data.AppDatabase
import com.sakura.player.data.DownloadRecordEntity
import java.io.File

object DownloadRecordManager {
    private const val TAG = "DownloadRecordMgr"
    private lateinit var db: AppDatabase

    fun init(database: AppDatabase) {
        db = database
    }

    val dao get() = db.animeDao()

    suspend fun getRecord(path: String): DownloadRecordEntity? {
        return dao.getDownloadRecord(path)
    }

    suspend fun getRecordsUnder(dir: String): List<DownloadRecordEntity> {
        val prefix = if (dir.endsWith("/")) dir else "$dir/"
        return dao.getDownloadRecordsUnder(prefix)
    }

    suspend fun upsertRecord(record: DownloadRecordEntity) {
        dao.upsertDownloadRecord(record)
    }

    suspend fun deleteRecord(path: String) {
        dao.deleteDownloadRecord(path)
    }

    suspend fun clearAllRecords() {
        // Delete all records to force re-sync
        try {
            val all = dao.getDownloadRecordsUnder("")
            all.forEach { dao.deleteDownloadRecord(it.localPath) }
            Log.e(TAG, "Cleared ${all.size} download records")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear records", e)
        }
    }

    suspend fun deleteRecordsUnder(dir: String) {
        val prefix = if (dir.endsWith("/")) dir else "$dir/"
        dao.deleteDownloadRecordsUnder(prefix)
    }

    suspend fun handlePathChange(oldPath: String, newPath: String) {
        // Exact file match
        val record = dao.getDownloadRecord(oldPath)
        if (record != null) {
            dao.upsertDownloadRecord(record.copy(
                localPath = newPath,
                updatedAt = System.currentTimeMillis()
            ))
            if (oldPath != newPath) {
                dao.deleteDownloadRecord(oldPath)
            }
        }
        // Children under old path (directory case)
        val oldPrefix = if (oldPath.endsWith("/")) oldPath else "$oldPath/"
        val recordsUnder = dao.getDownloadRecordsUnder(oldPrefix)
        for (r in recordsUnder) {
            val updatedPath = newPath + r.localPath.removePrefix(oldPath.trimEnd('/'))
            dao.upsertDownloadRecord(r.copy(
                localPath = updatedPath,
                updatedAt = System.currentTimeMillis()
            ))
            if (r.localPath != updatedPath) {
                dao.deleteDownloadRecord(r.localPath)
            }
        }
    }

    /** Scan download directory and insert missing records. Tries to find real videoId from website search.
     *  Also updates existing records whose videoId differs from the fresh search result (e.g. hash-based -> real). */
    suspend fun syncMissingRecords(downloadPath: String, searchVideoId: suspend (String) -> Long = { 0L }): Int {
        val root = File(downloadPath)
        if (!root.exists()) return 0

        var inserted = 0
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            // Try to find real videoId via search callback, fall back to hash (unknown source)
            val videoId = searchVideoId(dir.name)
            val title = dir.name

            dir.listFiles()?.filter { it.name.endsWith(".mp4") || it.name.endsWith(".mkv") }?.forEach { file ->
                val existing = dao.getDownloadRecord(file.absolutePath)
                if (existing == null) {
                    val patterns = listOf(
                        Regex("""第\s*(\d+)"""),
                        Regex("""[Ee][Pp]?\s*(\d+)"""),
                        Regex("""[Ss]\d+[Ee](\d+)"""),
                        Regex("""(\d+)""")
                    )
                    var epIndex = 0
                    for (p in patterns) {
                        val m = p.find(file.nameWithoutExtension)
                        if (m != null) {
                            epIndex = m.groupValues[1].toIntOrNull() ?: 0
                            break
                        }
                    }
                    if (epIndex > 0) {
                        dao.upsertDownloadRecord(DownloadRecordEntity(
                            localPath = file.absolutePath,
                            videoId = videoId,
                            title = title,
                            epIndex = epIndex,
                            coverUrl = ""
                        ))
                        inserted++
                    }
                } else if (existing.videoId != videoId) {
                    // Update record if videoId changed (e.g. old hash-based -> real from search)
                    dao.upsertDownloadRecord(existing.copy(
                        videoId = videoId,
                        title = title,
                        updatedAt = System.currentTimeMillis()
                    ))
                    inserted++
                }
            }
        }
        Log.e(TAG, "Synced $inserted download records from $downloadPath")
        return inserted
    }
}
