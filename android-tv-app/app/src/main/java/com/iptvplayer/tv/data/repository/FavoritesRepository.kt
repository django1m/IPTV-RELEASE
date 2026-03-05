package com.iptvplayer.tv.data.repository

import com.iptvplayer.tv.data.db.dao.FavoriteDao
import com.iptvplayer.tv.data.db.entity.FavoriteEntity
import com.iptvplayer.tv.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoritesRepository(private val favoriteDao: FavoriteDao) {

    val favorites: Flow<List<Favorite>> = favoriteDao.getAll().map { entities ->
        entities.map { it.toDomain() }
    }

    fun getFavoritesForAccount(accountId: String): Flow<List<Favorite>> {
        return favoriteDao.getByAccount(accountId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getFavoritesByType(accountId: String, contentType: ContentType): Flow<List<Favorite>> {
        return favoriteDao.getByAccountAndType(accountId, contentType.name).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun addFavorite(favorite: Favorite) {
        favoriteDao.insert(favorite.toEntity())
    }

    suspend fun removeFavorite(favoriteId: String) {
        favoriteDao.deleteById(favoriteId)
    }

    suspend fun isFavorite(contentType: ContentType, contentId: Int, accountId: String): Boolean {
        val id = "${contentType.name}_$contentId"
        return favoriteDao.exists(id)
    }

    suspend fun toggleFavorite(
        contentType: ContentType,
        contentId: Int,
        name: String,
        imageUrl: String?,
        accountId: String,
        extension: String? = null
    ): Boolean {
        val id = "${contentType.name}_$contentId"
        val isFav = favoriteDao.exists(id)

        if (isFav) {
            favoriteDao.deleteById(id)
        } else {
            favoriteDao.insert(
                FavoriteEntity(
                    id = id,
                    contentId = contentId,
                    contentType = contentType.name,
                    name = name,
                    imageUrl = imageUrl,
                    addedAt = System.currentTimeMillis(),
                    accountId = accountId,
                    extension = extension
                )
            )
        }
        return !isFav
    }

    suspend fun getAllFavoriteIds(accountId: String): List<String> {
        return favoriteDao.getAllIds(accountId)
    }

    // Helper functions to create favorites from content items
    fun createFavoriteFromLiveStream(stream: LiveStream, accountId: String): Favorite {
        return Favorite(
            id = "${ContentType.LIVE.name}_${stream.streamId}",
            contentId = stream.streamId,
            contentType = ContentType.LIVE,
            name = stream.name,
            imageUrl = stream.streamIcon,
            addedAt = System.currentTimeMillis(),
            accountId = accountId
        )
    }

    fun createFavoriteFromVodStream(vod: VodStream, accountId: String): Favorite {
        return Favorite(
            id = "${ContentType.VOD.name}_${vod.streamId}",
            contentId = vod.streamId,
            contentType = ContentType.VOD,
            name = vod.name,
            imageUrl = vod.streamIcon,
            addedAt = System.currentTimeMillis(),
            accountId = accountId,
            extension = vod.containerExtension
        )
    }

    fun createFavoriteFromSeries(series: Series, accountId: String): Favorite {
        return Favorite(
            id = "${ContentType.SERIES.name}_${series.seriesId}",
            contentId = series.seriesId,
            contentType = ContentType.SERIES,
            name = series.name,
            imageUrl = series.cover,
            addedAt = System.currentTimeMillis(),
            accountId = accountId
        )
    }
}

// Extension functions for mapping between Entity and Domain
private fun FavoriteEntity.toDomain() = Favorite(
    id = id,
    contentId = contentId,
    contentType = ContentType.valueOf(contentType),
    name = name,
    imageUrl = imageUrl,
    addedAt = addedAt,
    accountId = accountId,
    extension = extension
)

private fun Favorite.toEntity() = FavoriteEntity(
    id = id,
    contentId = contentId,
    contentType = contentType.name,
    name = name,
    imageUrl = imageUrl,
    addedAt = addedAt,
    accountId = accountId,
    extension = extension
)
