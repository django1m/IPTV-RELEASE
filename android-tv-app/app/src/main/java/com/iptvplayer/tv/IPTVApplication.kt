package com.iptvplayer.tv

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.db.dao.FavoriteDao
import com.iptvplayer.tv.data.db.dao.WatchHistoryDao
import com.iptvplayer.tv.data.db.entity.FavoriteEntity
import com.iptvplayer.tv.data.db.entity.WatchHistoryEntity
import com.iptvplayer.tv.data.model.ContentType
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class IPTVApplication : Application() {

    @Inject lateinit var favoriteDao: FavoriteDao
    @Inject lateinit var watchHistoryDao: WatchHistoryDao
    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        ContentCache.init(this)
        ContentCache.setHttpCache(okHttpClient.cache)
        migrateDataStoreToRoom()
    }

    private fun migrateDataStoreToRoom() {
        val prefs = getSharedPreferences("migration_flags", MODE_PRIVATE)
        if (prefs.getBoolean("datastore_to_room_done", false)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                migrateFavorites()
                migrateWatchHistory()
                prefs.edit().putBoolean("datastore_to_room_done", true).apply()
            } catch (e: Exception) {
                // Migration is best-effort; mark as done to avoid retrying
                prefs.edit().putBoolean("datastore_to_room_done", true).apply()
                e.printStackTrace()
            }
        }
    }

    private suspend fun migrateFavorites() {
        val file = File(filesDir, "datastore/favorites.preferences_pb")
        if (!file.exists()) return

        val json = Json { ignoreUnknownKeys = true }
        val dataStore = PreferenceDataStoreFactory.create { file }
        val data = dataStore.data.first()
        val favoritesJson = data[stringPreferencesKey("favorites_list")] ?: return

        @Serializable
        data class MigFav(
            val id: String,
            val contentId: Int,
            val contentType: ContentType,
            val name: String,
            val imageUrl: String? = null,
            val addedAt: Long,
            val accountId: String,
            val extension: String? = null
        )

        val favorites = json.decodeFromString<List<MigFav>>(favoritesJson)
        favorites.forEach { f ->
            favoriteDao.insert(
                FavoriteEntity(
                    id = f.id,
                    contentId = f.contentId,
                    contentType = f.contentType.name,
                    name = f.name,
                    imageUrl = f.imageUrl,
                    addedAt = f.addedAt,
                    accountId = f.accountId,
                    extension = f.extension
                )
            )
        }
    }

    private suspend fun migrateWatchHistory() {
        val file = File(filesDir, "datastore/watch_history.preferences_pb")
        if (!file.exists()) return

        val json = Json { ignoreUnknownKeys = true }
        val dataStore = PreferenceDataStoreFactory.create { file }
        val data = dataStore.data.first()
        val historyJson = data[stringPreferencesKey("history_list")] ?: return

        @Serializable
        data class MigHist(
            val id: String,
            val contentId: Int,
            val contentType: ContentType,
            val name: String,
            val imageUrl: String? = null,
            val accountId: String,
            val lastWatchedAt: Long,
            val watchedPositionMs: Long,
            val totalDurationMs: Long,
            val progressPercent: Float,
            val isCompleted: Boolean = false,
            val seriesId: Int? = null,
            val seasonNumber: Int? = null,
            val episodeNumber: Int? = null,
            val episodeId: String? = null,
            val extension: String? = null
        )

        val history = json.decodeFromString<List<MigHist>>(historyJson)
        history.forEach { h ->
            watchHistoryDao.upsert(
                WatchHistoryEntity(
                    id = h.id,
                    contentId = h.contentId,
                    contentType = h.contentType.name,
                    name = h.name,
                    imageUrl = h.imageUrl,
                    accountId = h.accountId,
                    lastWatchedAt = h.lastWatchedAt,
                    watchedPositionMs = h.watchedPositionMs,
                    totalDurationMs = h.totalDurationMs,
                    progressPercent = h.progressPercent,
                    isCompleted = h.isCompleted,
                    seriesId = h.seriesId,
                    seasonNumber = h.seasonNumber,
                    episodeNumber = h.episodeNumber,
                    episodeId = h.episodeId,
                    extension = h.extension
                )
            )
        }
    }
}
