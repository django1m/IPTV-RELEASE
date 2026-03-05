package com.iptvplayer.tv.data.repository

import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.cache.EpgCache
import com.iptvplayer.tv.data.model.EpgChannel
import com.iptvplayer.tv.data.model.EpgProgram
import com.iptvplayer.tv.data.model.LiveStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

class EpgRepository @Inject constructor(
    private val accountRepository: AccountRepository
) {

    suspend fun loadEpgForChannels(streamIds: List<Int>) = coroutineScope {
        val account = accountRepository.getActiveAccount() ?: return@coroutineScope
        val client = accountRepository.getClient(account)

        val toLoad = streamIds.filter { !EpgCache.isLoading(it) && !EpgCache.hasPrograms(it) }
        toLoad.forEach { EpgCache.setLoading(it, true) }

        toLoad.chunked(8).forEach { chunk ->
            chunk.map { streamId ->
                async(Dispatchers.IO) {
                    try {
                        val epg = client.getShortEpg(streamId, limit = 100)
                        val programs = epg.listings?.sortedBy { it.startTimestampLong } ?: emptyList()
                        EpgCache.setPrograms(streamId, programs)
                    } catch (e: Exception) {
                        EpgCache.setPrograms(streamId, emptyList())
                    } finally {
                        EpgCache.setLoading(streamId, false)
                    }
                }
            }.awaitAll()
        }
    }

    fun getAllLiveStreams(): List<LiveStream> {
        return ContentCache.getAllLiveStreams()
    }

    fun getLiveStreamsByCategory(categoryId: String?): List<LiveStream> {
        if (categoryId == null) return getAllLiveStreams()
        return ContentCache.getLiveStreams(categoryId) ?: emptyList()
    }

    fun buildEpgChannels(streams: List<LiveStream>): List<EpgChannel> {
        return streams.map { stream ->
            EpgChannel(
                stream = stream,
                programs = EpgCache.getPrograms(stream.streamId) ?: emptyList()
            )
        }
    }
}
