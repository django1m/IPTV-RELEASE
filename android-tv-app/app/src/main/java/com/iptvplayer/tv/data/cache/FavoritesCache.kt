package com.iptvplayer.tv.data.cache

import com.iptvplayer.tv.data.model.ContentType

/**
 * In-memory cache for favorite IDs for quick lookup in CardPresenter.
 * Updated when favorites change.
 */
object FavoritesCache {

    // Set of favorite IDs in format "CONTENT_TYPE_contentId" (e.g., "LIVE_123")
    private val favoriteIds = mutableSetOf<String>()

    /**
     * Update the cache with a list of favorite IDs
     */
    fun update(ids: Set<String>) {
        favoriteIds.clear()
        favoriteIds.addAll(ids)
    }

    /**
     * Add a favorite ID to the cache
     */
    fun add(id: String) {
        favoriteIds.add(id)
    }

    /**
     * Remove a favorite ID from the cache
     */
    fun remove(id: String) {
        favoriteIds.remove(id)
    }

    /**
     * Check if a content item is a favorite
     */
    fun isFavorite(contentType: ContentType, contentId: Int): Boolean {
        val id = "${contentType.name}_$contentId"
        return favoriteIds.contains(id)
    }

    /**
     * Check if an ID is in favorites
     */
    fun isFavorite(id: String): Boolean {
        return favoriteIds.contains(id)
    }

    /**
     * Clear the cache
     */
    fun clear() {
        favoriteIds.clear()
    }
}
