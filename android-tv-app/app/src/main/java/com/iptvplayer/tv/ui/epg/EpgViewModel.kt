package com.iptvplayer.tv.ui.epg

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptvplayer.tv.data.cache.EpgCache
import com.iptvplayer.tv.data.cache.FavoritesCache
import com.iptvplayer.tv.data.model.Category
import com.iptvplayer.tv.data.model.ContentType
import com.iptvplayer.tv.data.model.EpgChannel
import com.iptvplayer.tv.data.model.EpgProgram
import com.iptvplayer.tv.data.model.LiveStream
import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EpgViewModel @Inject constructor(
    private val epgRepository: EpgRepository
) : ViewModel() {

    // Pair: channels list + isNewList flag (true = new category/reset, false = EPG data update only)
    private val _channels = MutableLiveData<Pair<List<EpgChannel>, Boolean>>()
    val channels: LiveData<Pair<List<EpgChannel>, Boolean>> = _channels

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _focusedInfo = MutableLiveData<Pair<LiveStream, EpgProgram?>>()
    val focusedInfo: LiveData<Pair<LiveStream, EpgProgram?>> = _focusedInfo

    private val _categories = MutableLiveData<List<Category>>()
    val categories: LiveData<List<Category>> = _categories

    private var allStreams: List<LiveStream> = emptyList()
    private var currentCategoryId: String? = null

    fun initialize() {
        _isLoading.value = true

        // Load categories for filter
        val cats = ContentCache.getLiveCategories() ?: emptyList()
        _categories.value = cats

        // Load all streams
        allStreams = epgRepository.getAllLiveStreams()
        val streams = if (currentCategoryId != null) {
            epgRepository.getLiveStreamsByCategory(currentCategoryId)
        } else {
            allStreams
        }

        val epgChannels = epgRepository.buildEpgChannels(streams)
        _channels.value = Pair(epgChannels, true)
        _isLoading.value = false

        // Load EPG for first visible channels
        if (streams.isNotEmpty()) {
            loadEpgForVisibleChannels(0, 15)
        }
    }

    fun setCategory(categoryId: String?) {
        currentCategoryId = categoryId
        val streams = if (categoryId != null) {
            epgRepository.getLiveStreamsByCategory(categoryId)
        } else {
            allStreams
        }
        val epgChannels = epgRepository.buildEpgChannels(streams)
        _channels.value = Pair(epgChannels, true)

        if (streams.isNotEmpty()) {
            loadEpgForVisibleChannels(0, 15)
        }
    }

    fun setFavorites() {
        currentCategoryId = null
        // Refresh allStreams in case cache was updated
        if (allStreams.isEmpty()) {
            allStreams = epgRepository.getAllLiveStreams()
        }
        val streams = allStreams.filter { stream ->
            FavoritesCache.isFavorite(ContentType.LIVE, stream.streamId)
        }
        val epgChannels = epgRepository.buildEpgChannels(streams)
        _channels.value = Pair(epgChannels, true)

        if (streams.isNotEmpty()) {
            loadEpgForVisibleChannels(0, 15)
        }
    }

    fun loadEpgForVisibleChannels(startIndex: Int, count: Int) {
        val channelList = _channels.value?.first ?: return
        val endIdx = (startIndex + count).coerceAtMost(channelList.size)
        val streamIds = channelList.subList(startIndex, endIdx).map { it.stream.streamId }

        viewModelScope.launch {
            epgRepository.loadEpgForChannels(streamIds)
            // Rebuild channels with loaded EPG data — NOT a new list
            val currentList = _channels.value?.first ?: return@launch
            val updated = currentList.map { ch ->
                val programs = EpgCache.getPrograms(ch.stream.streamId)
                if (programs != null && programs.isNotEmpty()) {
                    ch.copy(programs = programs)
                } else {
                    ch
                }
            }
            _channels.value = Pair(updated, false)
        }
    }

    fun loadEpgForStreamIds(streamIds: List<Int>) {
        viewModelScope.launch {
            epgRepository.loadEpgForChannels(streamIds)
            // Rebuild — NOT a new list
            val currentList = _channels.value?.first ?: return@launch
            val updated = currentList.map { ch ->
                if (streamIds.contains(ch.stream.streamId)) {
                    val programs = EpgCache.getPrograms(ch.stream.streamId)
                    if (programs != null) ch.copy(programs = programs) else ch
                } else {
                    ch
                }
            }
            _channels.value = Pair(updated, false)
        }
    }

    fun setFocusedInfo(channel: LiveStream, program: EpgProgram?) {
        _focusedInfo.value = Pair(channel, program)
    }
}
