package com.sakura.player.follow

import com.sakura.player.data.AppDatabase
import com.sakura.player.data.FollowEntity
import com.sakura.player.data.SettingsPrefs
import com.sakura.player.scraper.AnimeScraper

object FollowManager {
    private lateinit var db: AppDatabase

    fun init(database: AppDatabase) {
        db = database
    }

    val dao get() = db.animeDao()

    suspend fun addFollow(videoId: Long, title: String, coverUrl: String, totalEps: Int) {
        dao.upsertFollow(FollowEntity(
            videoId = videoId,
            title = title,
            coverUrl = coverUrl,
            totalEps = totalEps,
            lastCheckTime = System.currentTimeMillis()
        ))
    }

    suspend fun removeFollow(videoId: Long) {
        dao.deleteFollow(videoId)
    }

    data class FollowInfo(
        val videoId: Long,
        val title: String,
        val coverUrl: String,
        val status: String,
        val totalEps: Int,
        val watchedEps: Int,
        val hasUpdate: Boolean
    )

    suspend fun getFollows(): List<FollowInfo> {
        return dao.getAllFollows().map { f ->
            FollowInfo(
                videoId = f.videoId,
                title = f.title,
                coverUrl = f.coverUrl,
                status = f.status,
                totalEps = f.totalEps,
                watchedEps = f.watchedEps,
                hasUpdate = f.hasUpdate
            )
        }
    }

    suspend fun markWatched(videoId: Long, epIndex: Int) {
        dao.insertWatchHistory(com.sakura.player.data.WatchHistoryEntity(
            followId = videoId, epIndex = epIndex
        ))
        dao.updateWatchedEps(videoId, epIndex)
    }

    suspend fun checkUpdates(domain: String, onUpdate: (videoId: Long, newEps: Int) -> Unit) {
        val follows = dao.getAllFollows()
        for (follow in follows) {
            try {
                val detail = AnimeScraper.getDetail(domain, follow.videoId)
                if (detail.totalEps > follow.totalEps) {
                    val newEps = detail.totalEps - follow.totalEps
                    dao.updateEpsCount(follow.videoId, detail.totalEps)
                    dao.setHasUpdate(follow.videoId, true)
                    onUpdate(follow.videoId, newEps)
                }
            } catch (_: Exception) {}
        }
        SettingsPrefs.followLastCheck = System.currentTimeMillis()
    }
}
