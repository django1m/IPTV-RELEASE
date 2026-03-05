package com.iptvplayer.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptvplayer.tv.data.db.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {

    @Query("SELECT * FROM watch_history WHERE account_id = :accountId AND progress_percent > :minProgress AND is_completed = 0 ORDER BY last_watched_at DESC")
    fun getContinueWatching(accountId: String, minProgress: Float = 0.02f): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE account_id = :accountId ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatched(accountId: String, limit: Int = 20): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("UPDATE watch_history SET is_completed = 1 WHERE id = :id")
    suspend fun markCompleted(id: String)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM watch_history WHERE account_id = :accountId")
    suspend fun deleteByAccount(accountId: String)

    @Query("DELETE FROM watch_history WHERE id NOT IN (SELECT id FROM watch_history ORDER BY last_watched_at DESC LIMIT :keepCount)")
    suspend fun trimOldEntries(keepCount: Int = 100)
}
