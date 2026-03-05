package com.iptvplayer.tv.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.iptvplayer.tv.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cache
import java.io.File

/**
 * Global content cache with disk persistence.
 * Cache refreshes every 72 hours or on manual refresh.
 */
object ContentCache {

    private var httpCache: Cache? = null

    private const val PREFS_NAME = "content_cache_prefs"
    private const val KEY_LAST_REFRESH = "last_refresh_time"
    private const val KEY_ACCOUNT_ID = "cached_account_id"
    private const val REFRESH_INTERVAL_MS = 72 * 60 * 60 * 1000L // 72 hours

    private const val FILE_LIVE_CATEGORIES = "live_categories.json"
    private const val FILE_VOD_CATEGORIES = "vod_categories.json"
    private const val FILE_SERIES_CATEGORIES = "series_categories.json"
    private const val DIR_LIVE_STREAMS = "live_streams"
    private const val DIR_VOD_STREAMS = "vod_streams"
    private const val DIR_SERIES = "series"

    private var prefs: SharedPreferences? = null
    private var cacheDir: File? = null
    private var cachedAccountId: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // In-memory cache
    private var liveCategories: List<Category>? = null
    private var vodCategories: List<Category>? = null
    private var seriesCategories: List<Category>? = null

    private val liveStreamsCache = mutableMapOf<String, List<LiveStream>>()
    private val vodStreamsCache = mutableMapOf<String, List<VodStream>>()
    private val seriesCache = mutableMapOf<String, List<Series>>()

    // Loading state
    private var isLiveFullyLoaded = false
    private var isVodFullyLoaded = false
    private var isSeriesFullyLoaded = false

    /**
     * Initialize with context for persistent storage
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cacheDir = File(context.cacheDir, "content_cache")
            cacheDir?.mkdirs()
            cachedAccountId = prefs?.getString(KEY_ACCOUNT_ID, null)
        }
    }

    fun setHttpCache(cache: Cache?) {
        httpCache = cache
    }

    /**
     * Initialize cache for account. Returns true if refresh is needed.
     * Call loadFromDiskAsync() separately if this returns false.
     */
    fun initForAccount(accountId: String): Boolean {
        val needsRefresh = shouldRefresh(accountId)

        if (cachedAccountId != accountId) {
            clearAll()
            cachedAccountId = accountId
            prefs?.edit()?.putString(KEY_ACCOUNT_ID, accountId)?.apply()
            return true
        }

        if (needsRefresh) {
            clearAll()
            cachedAccountId = accountId
            return true
        }

        return false
    }

    /**
     * Check if disk cache needs to be loaded
     */
    fun needsDiskLoad(): Boolean {
        return liveCategories == null && cacheDir?.exists() == true
    }

    /**
     * Load from disk - call from background thread
     */
    fun loadFromDiskSync() {
        loadFromDisk()
    }

    private fun shouldRefresh(accountId: String): Boolean {
        if (cachedAccountId != accountId) return true

        val lastRefresh = prefs?.getLong(KEY_LAST_REFRESH, 0L) ?: 0L
        val now = System.currentTimeMillis()
        return (now - lastRefresh) > REFRESH_INTERVAL_MS
    }

    fun needsRefresh(): Boolean {
        val lastRefresh = prefs?.getLong(KEY_LAST_REFRESH, 0L) ?: 0L
        val now = System.currentTimeMillis()
        return (now - lastRefresh) > REFRESH_INTERVAL_MS
    }

    fun markRefreshed() {
        prefs?.edit()?.putLong(KEY_LAST_REFRESH, System.currentTimeMillis())?.apply()
        saveToDisk()
    }

    fun forceRefresh() {
        prefs?.edit()?.putLong(KEY_LAST_REFRESH, 0L)?.apply()
        clearAll()
        clearDiskCache()
        try {
            httpCache?.evictAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAll() {
        liveCategories = null
        vodCategories = null
        seriesCategories = null
        liveStreamsCache.clear()
        vodStreamsCache.clear()
        seriesCache.clear()
        isLiveFullyLoaded = false
        isVodFullyLoaded = false
        isSeriesFullyLoaded = false
    }

    private fun clearDiskCache() {
        cacheDir?.deleteRecursively()
        cacheDir?.mkdirs()
    }

    // ===== DISK PERSISTENCE =====

    private fun saveToDisk() {
        try {
            val dir = cacheDir ?: return

            // Save categories
            liveCategories?.let {
                File(dir, FILE_LIVE_CATEGORIES).writeText(json.encodeToString(it))
            }
            vodCategories?.let {
                File(dir, FILE_VOD_CATEGORIES).writeText(json.encodeToString(it))
            }
            seriesCategories?.let {
                File(dir, FILE_SERIES_CATEGORIES).writeText(json.encodeToString(it))
            }

            // Save streams
            val liveDir = File(dir, DIR_LIVE_STREAMS).apply { mkdirs() }
            liveStreamsCache.forEach { (categoryId, streams) ->
                File(liveDir, "$categoryId.json").writeText(json.encodeToString(streams))
            }

            val vodDir = File(dir, DIR_VOD_STREAMS).apply { mkdirs() }
            vodStreamsCache.forEach { (categoryId, streams) ->
                File(vodDir, "$categoryId.json").writeText(json.encodeToString(streams))
            }

            val seriesDir = File(dir, DIR_SERIES).apply { mkdirs() }
            seriesCache.forEach { (categoryId, series) ->
                File(seriesDir, "$categoryId.json").writeText(json.encodeToString(series))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromDisk() {
        try {
            val dir = cacheDir ?: return

            // Load categories
            val liveCatFile = File(dir, FILE_LIVE_CATEGORIES)
            if (liveCatFile.exists()) {
                liveCategories = json.decodeFromString(liveCatFile.readText())
            }

            val vodCatFile = File(dir, FILE_VOD_CATEGORIES)
            if (vodCatFile.exists()) {
                vodCategories = json.decodeFromString(vodCatFile.readText())
            }

            val seriesCatFile = File(dir, FILE_SERIES_CATEGORIES)
            if (seriesCatFile.exists()) {
                seriesCategories = json.decodeFromString(seriesCatFile.readText())
            }

            // Load streams
            val liveDir = File(dir, DIR_LIVE_STREAMS)
            if (liveDir.exists()) {
                liveDir.listFiles()?.forEach { file ->
                    val categoryId = file.nameWithoutExtension
                    try {
                        val streams: List<LiveStream> = json.decodeFromString(file.readText())
                        liveStreamsCache[categoryId] = streams
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                isLiveFullyLoaded = liveStreamsCache.isNotEmpty()
            }

            val vodDir = File(dir, DIR_VOD_STREAMS)
            if (vodDir.exists()) {
                vodDir.listFiles()?.forEach { file ->
                    val categoryId = file.nameWithoutExtension
                    try {
                        val streams: List<VodStream> = json.decodeFromString(file.readText())
                        vodStreamsCache[categoryId] = streams
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                isVodFullyLoaded = vodStreamsCache.isNotEmpty()
            }

            val seriesDir = File(dir, DIR_SERIES)
            if (seriesDir.exists()) {
                seriesDir.listFiles()?.forEach { file ->
                    val categoryId = file.nameWithoutExtension
                    try {
                        val series: List<Series> = json.decodeFromString(file.readText())
                        seriesCache[categoryId] = series
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                isSeriesFullyLoaded = seriesCache.isNotEmpty()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ===== LIVE TV =====

    fun getLiveCategories(): List<Category>? = liveCategories

    fun setLiveCategories(categories: List<Category>) {
        liveCategories = categories
    }

    fun getLiveStreams(categoryId: String): List<LiveStream>? = liveStreamsCache[categoryId]

    fun setLiveStreams(categoryId: String, streams: List<LiveStream>) {
        liveStreamsCache[categoryId] = streams
    }

    fun hasLiveStreams(categoryId: String): Boolean = liveStreamsCache.containsKey(categoryId)

    fun isLiveFullyLoaded(): Boolean = isLiveFullyLoaded

    fun setLiveFullyLoaded(loaded: Boolean) {
        isLiveFullyLoaded = loaded
    }

    // ===== VOD / MOVIES =====

    fun getVodCategories(): List<Category>? = vodCategories

    fun setVodCategories(categories: List<Category>) {
        vodCategories = categories
    }

    fun getVodStreams(categoryId: String): List<VodStream>? = vodStreamsCache[categoryId]

    fun setVodStreams(categoryId: String, streams: List<VodStream>) {
        vodStreamsCache[categoryId] = streams
    }

    fun hasVodStreams(categoryId: String): Boolean = vodStreamsCache.containsKey(categoryId)

    fun isVodFullyLoaded(): Boolean = isVodFullyLoaded

    fun setVodFullyLoaded(loaded: Boolean) {
        isVodFullyLoaded = loaded
    }

    // ===== SERIES =====

    fun getSeriesCategories(): List<Category>? = seriesCategories

    fun setSeriesCategories(categories: List<Category>) {
        seriesCategories = categories
    }

    fun getSeries(categoryId: String): List<Series>? = seriesCache[categoryId]

    fun setSeries(categoryId: String, series: List<Series>) {
        seriesCache[categoryId] = series
    }

    fun hasSeries(categoryId: String): Boolean = seriesCache.containsKey(categoryId)

    fun isSeriesFullyLoaded(): Boolean = isSeriesFullyLoaded

    fun setSeriesFullyLoaded(loaded: Boolean) {
        isSeriesFullyLoaded = loaded
    }

    fun getLastRefreshTime(): Long {
        return prefs?.getLong(KEY_LAST_REFRESH, 0L) ?: 0L
    }

    fun getAllLiveStreams(): List<LiveStream> {
        val all = mutableListOf<LiveStream>()
        liveCategories?.forEach { cat ->
            liveStreamsCache[cat.categoryId]?.let { all.addAll(it) }
        }
        return all
    }
}
