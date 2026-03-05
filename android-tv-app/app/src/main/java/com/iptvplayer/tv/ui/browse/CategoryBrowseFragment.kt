package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.api.XtreamClient
import com.iptvplayer.tv.data.model.*
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.ui.detail.DetailActivity
import com.iptvplayer.tv.ui.playback.PlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CategoryBrowseFragment : VerticalGridSupportFragment() {

    @Inject lateinit var repository: AccountRepository
    private var client: XtreamClient? = null

    private lateinit var gridAdapter: ArrayObjectAdapter

    private var categoryId: String = "all"
    private var categoryName: String = ""
    private var contentType: ContentType = ContentType.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            categoryId = it.getString(ARG_CATEGORY_ID, "all")
            categoryName = it.getString(ARG_CATEGORY_NAME, "")
            contentType = ContentType.valueOf(it.getString(ARG_CONTENT_TYPE, "LIVE"))
        }

        setupUI()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadContent()
    }

    private fun setupUI() {
        title = categoryName

        // Set up the grid
        val gridPresenter = VerticalGridPresenter()
        gridPresenter.numberOfColumns = when (contentType) {
            ContentType.LIVE -> 5  // More columns for channels
            ContentType.VOD, ContentType.SERIES -> 6  // Poster layout
        }
        setGridPresenter(gridPresenter)

        // Create adapter with appropriate presenter
        val cardPresenter = CardPresenter()
        gridAdapter = ArrayObjectAdapter(cardPresenter)
        adapter = gridAdapter

        // Item click listener
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is LiveStream -> playLive(item)
                is VodStream -> openVodDetail(item)
                is Series -> openSeriesDetail(item)
            }
        }

        // Set colors
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.primary)
    }

    private fun loadContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            val account = repository.getActiveAccount() ?: return@launch
            client = repository.getClient(account)

            try {
                when (contentType) {
                    ContentType.LIVE -> loadLiveStreams()
                    ContentType.VOD -> loadVodStreams()
                    ContentType.SERIES -> loadSeries()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun loadLiveStreams() {
        val xtreamClient = client ?: return

        val streams = if (categoryId == "all") {
            xtreamClient.getLiveStreams()
        } else {
            xtreamClient.getLiveStreams(categoryId)
        }

        streams.forEach { gridAdapter.add(it) }
    }

    private suspend fun loadVodStreams() {
        val xtreamClient = client ?: return

        val streams = if (categoryId == "all") {
            xtreamClient.getVodStreams()
        } else {
            xtreamClient.getVodStreams(categoryId)
        }

        streams.forEach { gridAdapter.add(it) }
    }

    private suspend fun loadSeries() {
        val xtreamClient = client ?: return

        val series = if (categoryId == "all") {
            xtreamClient.getSeries()
        } else {
            xtreamClient.getSeries(categoryId)
        }

        series.forEach { gridAdapter.add(it) }
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

    companion object {
        private const val ARG_CATEGORY_ID = "category_id"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val ARG_CONTENT_TYPE = "content_type"

        fun newInstance(categoryId: String, categoryName: String, contentType: String): CategoryBrowseFragment {
            return CategoryBrowseFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY_ID, categoryId)
                    putString(ARG_CATEGORY_NAME, categoryName)
                    putString(ARG_CONTENT_TYPE, contentType)
                }
            }
        }
    }
}
