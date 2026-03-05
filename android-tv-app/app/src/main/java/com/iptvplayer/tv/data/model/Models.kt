package com.iptvplayer.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String
)

@Serializable
data class UserInfo(
    val username: String? = null,
    val password: String? = null,
    val message: String? = null,
    val auth: Int? = null,
    val status: String? = null,
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("is_trial") val isTrial: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
    @SerialName("allowed_output_formats") val allowedOutputFormats: List<String>? = null
)

@Serializable
data class ServerInfo(
    val url: String? = null,
    val port: String? = null,
    @SerialName("https_port") val httpsPort: String? = null,
    @SerialName("server_protocol") val serverProtocol: String? = null,
    @SerialName("rtmp_port") val rtmpPort: String? = null,
    val timezone: String? = null,
    @SerialName("timestamp_now") val timestampNow: Long? = null,
    @SerialName("time_now") val timeNow: String? = null
)

@Serializable
data class AuthResponse(
    @SerialName("user_info") val userInfo: UserInfo? = null,
    @SerialName("server_info") val serverInfo: ServerInfo? = null
)

@Serializable
data class Category(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("parent_id") val parentId: Int? = null
)

@Serializable
data class LiveStream(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id") val streamId: Int,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("tv_archive") val tvArchive: Int? = null,
    @SerialName("direct_source") val directSource: String? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: Int? = null
)

@Serializable
data class VodStream(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id") val streamId: Int,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("rating_5based") val rating5based: Double? = null,
    @SerialName("added") val added: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("direct_source") val directSource: String? = null
)

@Serializable
data class Series(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String,
    @SerialName("series_id") val seriesId: Int,
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("cast") val cast: String? = null,
    @SerialName("director") val director: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("last_modified") val lastModified: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("rating_5based") val rating5based: Double? = null,
    @SerialName("backdrop_path") val backdropPath: List<String?>? = null,
    @SerialName("youtube_trailer") val youtubeTrailer: String? = null,
    @SerialName("episode_run_time") val episodeRunTime: String? = null,
    @SerialName("category_id") val categoryId: String? = null
)

@Serializable
data class SeriesInfo(
    val seasons: List<Season>? = null,
    val info: SeriesDetails? = null,
    val episodes: Map<String, List<Episode>>? = null
)

@Serializable
data class Season(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val cover: String? = null,
    @SerialName("cover_big") val coverBig: String? = null
)

@Serializable
data class SeriesDetails(
    val name: String? = null,
    val cover: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("rating_5based") val rating5based: Double? = null,
    @SerialName("backdrop_path") val backdropPath: List<String?>? = null,
    @SerialName("youtube_trailer") val youtubeTrailer: String? = null
)

@Serializable
data class Episode(
    val id: String,
    @SerialName("episode_num") val episodeNum: Int,
    val title: String,
    @SerialName("container_extension") val containerExtension: String? = null,
    val info: EpisodeInfo? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    val added: String? = null,
    val season: Int? = null,
    @SerialName("direct_source") val directSource: String? = null
)

@Serializable
data class EpisodeInfo(
    @SerialName("movie_image") val movieImage: String? = null,
    val plot: String? = null,
    val duration: String? = null,
    @SerialName("duration_secs") val durationSecs: Int? = null,
    val rating: Double? = null,
    val name: String? = null
)

@Serializable
data class VodInfo(
    val info: VodDetails? = null,
    @SerialName("movie_data") val movieData: MovieData? = null
)

@Serializable
data class VodDetails(
    @SerialName("movie_image") val movieImage: String? = null,
    val genre: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val rating: String? = null,
    val director: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("backdrop_path") val backdropPath: List<String?>? = null,
    @SerialName("youtube_trailer") val youtubeTrailer: String? = null,
    @SerialName("duration_secs") val durationSecs: Int? = null,
    val duration: String? = null,
    val name: String? = null
)

@Serializable
data class MovieData(
    @SerialName("stream_id") val streamId: Int? = null,
    val name: String? = null,
    val added: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("custom_sid") val customSid: String? = null,
    @SerialName("direct_source") val directSource: String? = null
)

enum class ContentType {
    LIVE, VOD, SERIES
}

data class ContentItem(
    val id: Int,
    val name: String,
    val imageUrl: String?,
    val type: ContentType,
    val categoryId: String?,
    val rating: Double? = null,
    val extra: Map<String, String> = emptyMap()
)

// Favorites
@Serializable
data class Favorite(
    val id: String,
    val contentId: Int,
    val contentType: ContentType,
    val name: String,
    val imageUrl: String?,
    val addedAt: Long,
    val accountId: String,
    val extension: String? = null
)

// Watch History
@Serializable
data class WatchHistory(
    val id: String,
    val contentId: Int,
    val contentType: ContentType,
    val name: String,
    val imageUrl: String?,
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

// Episode navigation info for series playback
@Serializable
data class EpisodeNavInfo(
    val id: String,
    val episodeNum: Int,
    val title: String,
    val extension: String,
    val seasonNumber: Int
)
