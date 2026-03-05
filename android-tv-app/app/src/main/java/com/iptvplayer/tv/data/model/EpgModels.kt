package com.iptvplayer.tv.data.model

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpgListing(
    @SerialName("epg_listings") val listings: List<EpgProgram>? = null
)

@Serializable
data class EpgProgram(
    val id: String? = null,
    @SerialName("epg_id") val epgId: String? = null,
    val title: String? = null,
    @SerialName("lang") val language: String? = null,
    val start: String? = null,
    val end: String? = null,
    val description: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("start_timestamp") val startTimestamp: String? = null,
    @SerialName("stop_timestamp") val stopTimestamp: String? = null,
    @SerialName("has_archive") val hasArchive: Int? = null,
    @SerialName("now_playing") val nowPlaying: Int? = null
) {
    /** Decode Base64 title if needed (some providers encode EPG titles) */
    val decodedTitle: String
        get() {
            val raw = title ?: return ""
            return try {
                val decoded = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.isNotBlank() && decoded.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?:-()/'\"&" }) {
                    decoded
                } else {
                    raw
                }
            } catch (e: Exception) {
                raw
            }
        }

    val startTimestampLong: Long
        get() = startTimestamp?.toLongOrNull() ?: 0L

    val stopTimestampLong: Long
        get() = stopTimestamp?.toLongOrNull() ?: 0L

    val isCurrentlyAiring: Boolean
        get() {
            val now = System.currentTimeMillis() / 1000
            return now in startTimestampLong..stopTimestampLong
        }

    val isPast: Boolean
        get() = (System.currentTimeMillis() / 1000) > stopTimestampLong
}
