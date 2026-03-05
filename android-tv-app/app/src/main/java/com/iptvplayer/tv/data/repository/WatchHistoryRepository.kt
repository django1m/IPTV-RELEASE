package com.iptvplayer.tv.data.repository

import com.iptvplayer.tv.data.db.dao.WatchHistoryDao
import com.iptvplayer.tv.data.db.entity.WatchHistoryEntity
import com.iptvplayer.tv.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WatchHistoryRepository(private val watchHistoryDao: WatchHistoryDao) {

    companion object {
        private const val MAX_HISTORY_ITEMS = 100
        private const val COMPLETED_THRESHOLD = 0.90f
    }

    fun getContinueWatching(accountId: String): Flow<List<WatchHistory>> {
        return watchHistoryDao.getContinueWatching(accountId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getRecentlyWatched(accountId: String, limit: Int = 20): Flow<List<WatchHistory>> {
        return watchHistoryDao.getRecentlyWatched(accountId, limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun updateProgress(
        contentType: ContentType,
        contentId: Int,
        name: String,
        imageUrl: String?,
        accountId: String,
        currentPositionMs: Long,
        totalDurationMs: Long,
        seriesId: Int? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeId: String? = null,
        extension: String? = null
    ) {
        if (totalDurationMs <= 0) return

        val progressPercent = currentPositionMs.toFloat() / totalDurationMs.toFloat()
        val isCompleted = progressPercent >= COMPLETED_THRESHOLD

        val id = if (episodeId != null) {
            "${contentType.name}_${seriesId}_$episodeId"
        } else {
            "${contentType.name}_$contentId"
        }

        watchHistoryDao.upsert(
            WatchHistoryEntity(
                id = id,
                contentId = contentId,
                contentType = contentType.name,
                name = name,
                imageUrl = imageUrl,
                accountId = accountId,
                lastWatchedAt = System.currentTimeMillis(),
                watchedPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                progressPercent = progressPercent,
                isCompleted = isCompleted,
                seriesId = seriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeId = episodeId,
                extension = extension
            )
        )

        // Trim old entries to keep DB size bounded
        watchHistoryDao.trimOldEntries(MAX_HISTORY_ITEMS)
    }

    suspend fun getLastPosition(contentType: ContentType, contentId: Int, episodeId: String? = null): Long? {
        val id = if (episodeId != null) {
            "${contentType.name}_${contentId}_$episodeId"
        } else {
            "${contentType.name}_$contentId"
        }
        return watchHistoryDao.getById(id)?.watchedPositionMs
    }

    suspend fun markAsCompleted(historyId: String) {
        watchHistoryDao.markCompleted(historyId)
    }

    suspend fun removeFromHistory(historyId: String) {
        watchHistoryDao.deleteById(historyId)
    }

    suspend fun clearHistory(accountId: String) {
        watchHistoryDao.deleteByAccount(accountId)
    }
}

// Extension function for mapping Entity to Domain
private fun WatchHistoryEntity.toDomain() = WatchHistory(
    id = id,
    contentId = contentId,
    contentType = ContentType.valueOf(contentType),
    name = name,
    imageUrl = imageUrl,
    accountId = accountId,
    lastWatchedAt = lastWatchedAt,
    watchedPositionMs = watchedPositionMs,
    totalDurationMs = totalDurationMs,
    progressPercent = progressPercent,
    isCompleted = isCompleted,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeId = episodeId,
    extension = extension
)
