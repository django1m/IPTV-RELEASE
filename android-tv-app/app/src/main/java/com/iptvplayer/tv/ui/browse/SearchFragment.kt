package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.api.XtreamClient
import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.cache.FavoritesCache
import com.iptvplayer.tv.data.model.*
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.FavoritesRepository
import com.iptvplayer.tv.ui.detail.DetailActivity
import com.iptvplayer.tv.ui.playback.PlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    private var client: XtreamClient? = null
    private var currentAccountId: String? = null

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchJob: Job? = null

    // Selected item tracking for favorites
    private var selectedItem: Any? = null
    private var lastQuery: String = ""

    private var allLiveStreams: List<LiveStream> = emptyList()
    private var allVodStreams: List<VodStream> = emptyList()
    private var allSeries: List<Series> = emptyList()
    private var isDataLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSearchResultProvider(this)

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is LiveStream -> playLive(item)
                is VodStream -> openVodDetail(item)
                is Series -> openSeriesDetail(item)
            }
        }

        // Track selected item for favorites
        setOnItemViewSelectedListener { _, item, _, _ ->
            selectedItem = item
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAccount()
    }

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            val account = accountRepository.getActiveAccount() ?: return@launch
            currentAccountId = account.id
            client = accountRepository.getClient(account)
            loadAllContent()
        }
    }

    fun onLongPress(): Boolean {
        val item = selectedItem ?: return false
        val accountId = currentAccountId ?: return false

        when (item) {
            is LiveStream -> {
                toggleFavorite(ContentType.LIVE, item.streamId, item.name, item.streamIcon, accountId, null)
                return true
            }
            is VodStream -> {
                toggleFavorite(ContentType.VOD, item.streamId, item.name, item.streamIcon, accountId, item.containerExtension)
                return true
            }
            is Series -> {
                toggleFavorite(ContentType.SERIES, item.seriesId, item.name, item.cover, accountId, null)
                return true
            }
        }
        return false
    }

    private fun toggleFavorite(type: ContentType, id: Int, name: String, imageUrl: String?, accountId: String, extension: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val favoriteId = "${type.name}_$id"
            val isNowFavorite = favoritesRepository.toggleFavorite(
                contentType = type,
                contentId = id,
                name = name,
                imageUrl = imageUrl,
                accountId = accountId,
                extension = extension
            )

            // Update favorites cache
            if (isNowFavorite) {
                FavoritesCache.add(favoriteId)
            } else {
                FavoritesCache.remove(favoriteId)
            }

            val message = if (isNowFavorite) {
                getString(R.string.added_to_favorites)
            } else {
                getString(R.string.removed_from_favorites)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

            // Refresh search results to update heart icons
            refreshSearchResults()
        }
    }

    private fun refreshSearchResults() {
        // Re-run the current search to update the display with new favorite status
        if (lastQuery.length >= 2) {
            performSearch(lastQuery)
        }
    }

    private fun loadAllContent() {
        // Use cached content instead of loading from network
        val liveList = mutableListOf<LiveStream>()
        val vodList = mutableListOf<VodStream>()
        val seriesList = mutableListOf<Series>()

        // Get from cache
        ContentCache.getLiveCategories()?.forEach { category ->
            ContentCache.getLiveStreams(category.categoryId)?.let { streams ->
                liveList.addAll(streams)
            }
        }

        ContentCache.getVodCategories()?.forEach { category ->
            ContentCache.getVodStreams(category.categoryId)?.let { streams ->
                vodList.addAll(streams)
            }
        }

        ContentCache.getSeriesCategories()?.forEach { category ->
            ContentCache.getSeries(category.categoryId)?.let { series ->
                seriesList.addAll(series)
            }
        }

        allLiveStreams = liveList
        allVodStreams = vodList
        allSeries = seriesList
        isDataLoaded = true
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return rowsAdapter
    }

    override fun onQueryTextChange(newQuery: String): Boolean {
        searchHandler.removeCallbacksAndMessages(null)
        searchHandler.postDelayed({
            performSearch(newQuery)
        }, 300)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        performSearch(query)
        return true
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()

        if (query.length < 2) {
            rowsAdapter.clear()
            lastQuery = ""
            return
        }

        lastQuery = query

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            rowsAdapter.clear()

            val queryLower = query.lowercase()
            val cardPresenter = CardPresenter()
            var headerIndex = 0L

            // Search live streams
            val matchingLive = allLiveStreams.filter {
                it.name.lowercase().contains(queryLower)
            }

            if (matchingLive.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                matchingLive.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "TV en direct (${matchingLive.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }

            // Search VOD
            val matchingVod = allVodStreams.filter {
                it.name.lowercase().contains(queryLower)
            }

            if (matchingVod.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                matchingVod.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "Films (${matchingVod.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }

            // Search series
            val matchingSeries = allSeries.filter {
                it.name.lowercase().contains(queryLower)
            }

            if (matchingSeries.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                matchingSeries.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "Séries (${matchingSeries.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }
        }
    }

    private fun playLive(stream: LiveStream) {
        val xtreamClient = client ?: return
        val streamUrl = xtreamClient.getLiveStreamUrl(stream.streamId)

        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlaybackActivity.EXTRA_STREAM_NAME, stream.name)
            putExtra(PlaybackActivity.EXTRA_IS_LIVE, true)
            putExtra(PlaybackActivity.EXTRA_TV_ARCHIVE, stream.tvArchive ?: 0)
            putExtra(PlaybackActivity.EXTRA_TV_ARCHIVE_DURATION, stream.tvArchiveDuration ?: 0)
            putExtra(PlaybackActivity.EXTRA_STREAM_ID, stream.streamId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_ID, stream.streamId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.LIVE.name)
            putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, stream.streamIcon)
        }
        startActivity(intent)
    }

    private fun openVodDetail(vod: VodStream) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_CONTENT_TYPE, ContentType.VOD.name)
            putExtra(DetailActivity.EXTRA_CONTENT_ID, vod.streamId)
            putExtra(DetailActivity.EXTRA_CONTENT_NAME, vod.name)
            putExtra(DetailActivity.EXTRA_CONTENT_IMAGE, vod.streamIcon)
            putExtra(DetailActivity.EXTRA_CONTENT_EXTENSION, vod.containerExtension)
        }
        startActivity(intent)
    }

    private fun openSeriesDetail(series: Series) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
            putExtra(DetailActivity.EXTRA_CONTENT_ID, series.seriesId)
            putExtra(DetailActivity.EXTRA_CONTENT_NAME, series.name)
            putExtra(DetailActivity.EXTRA_CONTENT_IMAGE, series.cover)
        }
        startActivity(intent)
    }
}
