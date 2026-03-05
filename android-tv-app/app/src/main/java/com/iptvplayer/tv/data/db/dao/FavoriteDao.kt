package com.iptvplayer.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptvplayer.tv.data.db.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    fun getAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE account_id = :accountId ORDER BY added_at DESC")
    fun getByAccount(accountId: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE account_id = :accountId AND content_type = :contentType ORDER BY added_at DESC")
    fun getByAccountAndType(accountId: String, contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT id FROM favorites WHERE account_id = :accountId")
    suspend fun getAllIds(accountId: String): List<String>
}
