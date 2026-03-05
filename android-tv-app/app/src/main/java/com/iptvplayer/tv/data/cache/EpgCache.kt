package com.iptvplayer.tv.data.cache

import com.iptvplayer.tv.data.model.EpgProgram
import java.util.concurrent.ConcurrentHashMap

object EpgCache {

    private val cache = ConcurrentHashMap<Int, List<EpgProgram>>()
    private val loadingChannels = ConcurrentHashMap.newKeySet<Int>()

    fun getPrograms(streamId: Int): List<EpgProgram>? = cache[streamId]

    fun setPrograms(streamId: Int, programs: List<EpgProgram>) {
        cache[streamId] = programs.sortedBy { it.startTimestampLong }
    }

    fun hasPrograms(streamId: Int): Boolean = cache.containsKey(streamId)

    fun isLoading(streamId: Int): Boolean = loadingChannels.contains(streamId)

    fun setLoading(streamId: Int, loading: Boolean) {
        if (loading) loadingChannels.add(streamId) else loadingChannels.remove(streamId)
    }

    fun getCurrentProgram(streamId: Int): EpgProgram? {
        return cache[streamId]?.firstOrNull { it.isCurrentlyAiring }
    }

    fun getProgramAt(streamId: Int, timestamp: Long): EpgProgram? {
        return cache[streamId]?.firstOrNull {
            timestamp in it.startTimestampLong..it.stopTimestampLong
        }
    }

    fun clearAll() {
        cache.clear()
        loadingChannels.clear()
    }
}
