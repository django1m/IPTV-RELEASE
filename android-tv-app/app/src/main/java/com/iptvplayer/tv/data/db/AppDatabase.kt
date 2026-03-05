package com.iptvplayer.tv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.iptvplayer.tv.data.db.dao.FavoriteDao
import com.iptvplayer.tv.data.db.dao.WatchHistoryDao
import com.iptvplayer.tv.data.db.entity.FavoriteEntity
import com.iptvplayer.tv.data.db.entity.WatchHistoryEntity

@Database(
    entities = [FavoriteEntity::class, WatchHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
}
