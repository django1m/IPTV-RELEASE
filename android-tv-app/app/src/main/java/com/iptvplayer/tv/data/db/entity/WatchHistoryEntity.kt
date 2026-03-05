package com.iptvplayer.tv.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_history",
    indices = [
        Index(value = ["account_id", "last_watched_at"])
    ]
)
data class WatchHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "content_id") val contentId: Int,
    @ColumnInfo(name = "content_type") val contentType: String,
    val name: String,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    @ColumnInfo(name = "watched_position_ms") val watchedPositionMs: Long,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long,
    @ColumnInfo(name = "progress_percent") val progressPercent: Float,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean = false,
    @ColumnInfo(name = "series_id") val seriesId: Int? = null,
    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null,
    @ColumnInfo(name = "episode_id") val episodeId: String? = null,
    val extension: String? = null
)
