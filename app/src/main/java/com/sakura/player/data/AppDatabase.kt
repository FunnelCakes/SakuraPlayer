package com.sakura.player.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "follows")
data class FollowEntity(
    @PrimaryKey val videoId: Long,
    val title: String,
    val coverUrl: String,
    val status: String = "following", // following, completed, dropped
    val totalEps: Int = 0,
    val watchedEps: Int = 0,
    val lastCheckTime: Long = 0,
    val hasUpdate: Boolean = false
)

@Entity(tableName = "download_records")
data class DownloadRecordEntity(
    @PrimaryKey val localPath: String,
    val videoId: Long,
    val title: String,
    val epIndex: Int,
    val coverUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val followId: Long,
    val epIndex: Int,
    val watchedAt: Long = System.currentTimeMillis()
)

@Dao
interface AnimeDao {
    @Query("SELECT * FROM follows ORDER BY lastCheckTime DESC")
    suspend fun getAllFollows(): List<FollowEntity>

    @Query("SELECT * FROM follows WHERE videoId = :videoId")
    suspend fun getFollow(videoId: Long): FollowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFollow(follow: FollowEntity)

    @Query("DELETE FROM follows WHERE videoId = :videoId")
    suspend fun deleteFollow(videoId: Long)

    @Query("UPDATE follows SET status = :status WHERE videoId = :videoId")
    suspend fun updateFollowStatus(videoId: Long, status: String)

    @Query("UPDATE follows SET totalEps = :totalEps, lastCheckTime = :time WHERE videoId = :videoId")
    suspend fun updateEpsCount(videoId: Long, totalEps: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE follows SET watchedEps = :watchedEps WHERE videoId = :videoId")
    suspend fun updateWatchedEps(videoId: Long, watchedEps: Int)

    @Query("UPDATE follows SET hasUpdate = :hasUpdate WHERE videoId = :videoId")
    suspend fun setHasUpdate(videoId: Long, hasUpdate: Boolean)

    @Insert
    suspend fun insertWatchHistory(history: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history WHERE followId = :followId ORDER BY watchedAt DESC")
    suspend fun getWatchHistory(followId: Long): List<WatchHistoryEntity>

    @Query("SELECT CAST(COALESCE(MAX(epIndex), 0) AS INTEGER) FROM watch_history WHERE followId = :followId")
    suspend fun getMaxWatchedEp(followId: Long): Int

    // Download records
    @Query("SELECT * FROM download_records WHERE localPath = :path")
    suspend fun getDownloadRecord(path: String): DownloadRecordEntity?

    @Query("SELECT * FROM download_records WHERE localPath LIKE :prefix || '%'")
    suspend fun getDownloadRecordsUnder(prefix: String): List<DownloadRecordEntity>

    @Query("SELECT COUNT(*) FROM download_records WHERE videoId = :videoId AND epIndex = :epIndex")
    suspend fun countByVideoAndEp(videoId: Long, epIndex: Int): Int

    @Query("SELECT epIndex FROM download_records WHERE videoId = :videoId")
    suspend fun getDownloadedEpisodeIndices(videoId: Long): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownloadRecord(record: DownloadRecordEntity)

    @Query("DELETE FROM download_records WHERE localPath = :path")
    suspend fun deleteDownloadRecord(path: String)

    @Query("DELETE FROM download_records WHERE localPath LIKE :prefix || '%'")
    suspend fun deleteDownloadRecordsUnder(prefix: String)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS download_records (
                localPath TEXT NOT NULL PRIMARY KEY,
                videoId INTEGER NOT NULL,
                title TEXT NOT NULL,
                epIndex INTEGER NOT NULL,
                coverUrl TEXT NOT NULL DEFAULT '',
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

@Database(entities = [FollowEntity::class, WatchHistoryEntity::class, DownloadRecordEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "sakura_anime.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
