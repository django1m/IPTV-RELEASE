package com.iptvplayer.tv.data.api

import com.iptvplayer.tv.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

class XtreamClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val baseUrl: String
        get() = serverUrl.trimEnd('/')

    private fun apiUrl(action: String): String {
        return "$baseUrl/player_api.php?username=$username&password=$password&action=$action"
    }

    private suspend fun <T> fetch(
        url: String,
        cacheControl: CacheControl? = null,
        deserializer: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "IPTV Player TV/1.0")
            .apply { cacheControl?.let { cacheControl(it) } }
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $body")
        }

        deserializer(body)
    }

    suspend fun authenticate(): AuthResponse {
        val url = "$baseUrl/player_api.php?username=$username&password=$password"
        return fetch(url, CacheControl.FORCE_NETWORK) { json.decodeFromString(it) }
    }

    // Live TV
    suspend fun getLiveCategories(): List<Category> {
        return fetch(apiUrl("get_live_categories")) { json.decodeFromString(it) }
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> {
        val url = if (categoryId != null) {
            "${apiUrl("get_live_streams")}&category_id=$categoryId"
        } else {
            apiUrl("get_live_streams")
        }
        return fetch(url) { json.decodeFromString(it) }
    }

    fun getLiveStreamUrl(streamId: Int): String {
        return "$baseUrl/live/$username/$password/$streamId.ts"
    }

    // VOD
    suspend fun getVodCategories(): List<Category> {
        return fetch(apiUrl("get_vod_categories")) { json.decodeFromString(it) }
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> {
        val url = if (categoryId != null) {
            "${apiUrl("get_vod_streams")}&category_id=$categoryId"
        } else {
            apiUrl("get_vod_streams")
        }
        return fetch(url) { json.decodeFromString(it) }
    }

    suspend fun getVodInfo(vodId: Int): VodInfo {
        return fetch("${apiUrl("get_vod_info")}&vod_id=$vodId") { json.decodeFromString(it) }
    }

    fun getVodStreamUrl(streamId: Int, extension: String = "mp4"): String {
        return "$baseUrl/movie/$username/$password/$streamId.$extension"
    }

    // Series
    suspend fun getSeriesCategories(): List<Category> {
        return fetch(apiUrl("get_series_categories")) { json.decodeFromString(it) }
    }

    suspend fun getSeries(categoryId: String? = null): List<Series> {
        val url = if (categoryId != null) {
            "${apiUrl("get_series")}&category_id=$categoryId"
        } else {
            apiUrl("get_series")
        }
        return fetch(url) { json.decodeFromString(it) }
    }

    suspend fun getSeriesInfo(seriesId: Int): SeriesInfo {
        return fetch("${apiUrl("get_series_info")}&series_id=$seriesId") { json.decodeFromString(it) }
    }

    fun getSeriesStreamUrl(episodeId: String, extension: String = "mp4"): String {
        return "$baseUrl/series/$username/$password/$episodeId.$extension"
    }

    // EPG / Catch-up
    suspend fun getShortEpg(streamId: Int, limit: Int = 20): EpgListing {
        return fetch("${apiUrl("get_short_epg")}&stream_id=$streamId&limit=$limit") {
            json.decodeFromString(it)
        }
    }

    fun getTimeshiftUrl(streamId: Int, startTimestamp: Long, durationMinutes: Int): String {
        return "$baseUrl/timeshift/$username/$password/$durationMinutes/$startTimestamp/$streamId.ts"
    }

    fun getTimeshiftStreamingUrl(streamId: Int, startTimestamp: Long): String {
        return "$baseUrl/streaming/timeshift.php?username=$username&password=$password&stream=$streamId&start=$startTimestamp"
    }
}
