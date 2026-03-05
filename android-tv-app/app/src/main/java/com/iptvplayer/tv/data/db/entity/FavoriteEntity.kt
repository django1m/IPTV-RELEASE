package com.iptvplayer.tv.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["account_id", "content_type"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "content_id") val contentId: Int,
    @ColumnInfo(name = "content_type") val contentType: String,
    val name: String,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "account_id") val accountId: String,
    val extension: String? = null
)
