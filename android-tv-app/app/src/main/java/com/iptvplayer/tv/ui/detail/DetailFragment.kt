package com.iptvplayer.tv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.app.DetailsSupportFragmentBackgroundController
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.api.XtreamClient
import com.iptvplayer.tv.data.model.*
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.FavoritesRepository
import com.iptvplayer.tv.data.repository.WatchHistoryRepository
import com.iptvplayer.tv.ui.playback.PlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class DetailFragment : DetailsSupportFragment() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    @Inject lateinit var watchHistoryRepository: WatchHistoryRepository
    private var client: XtreamClient? = null
    private var accountId: String? = null

    private lateinit var detailsBackground: DetailsSupportFragmentBackgroundController
    private lateinit var presenterSelector: ClassPresenterSelector
    private lateinit var rowsAdapter: ArrayObjectAdapter

    private var contentType: ContentType = ContentType.VOD
    private var contentId: Int = 0
    private var contentName: String = ""
    private var contentImage: String? = null
    private var contentExtension: String = "mp4"

    private var vodInfo: VodInfo? = null
    private var seriesInfo: SeriesInfo? = null

    private var isFavorite: Boolean = false
    private var favoriteAction: Action? = null
    private var detailsRow: DetailsOverviewRow? = null

    // For series episode navigation
    private var allEpisodes: List<EpisodeNavInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detailsBackground = DetailsSupportFragmentBackgroundController(this)

        // Get intent extras
        requireActivity().intent?.let { intent ->
            contentType = ContentType.valueOf(intent.getStringExtra(DetailActivity.EXTRA_CONTENT_TYPE) ?: ContentType.VOD.name)
            contentId = intent.getIntExtra(DetailActivity.EXTRA_CONTENT_ID, 0)
            contentName = intent.getStringExtra(DetailActivity.EXTRA_CONTENT_NAME) ?: ""
            contentImage = intent.getStringExtra(DetailActivity.EXTRA_CONTENT_IMAGE)
            contentExtension = intent.getStringExtra(DetailActivity.EXTRA_CONTENT_EXTENSION) ?: "mp4"
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadAccount()
    }

    private fun setupUI() {
        presenterSelector = ClassPresenterSelector()
        rowsAdapter = ArrayObjectAdapter(presenterSelector)
        adapter = rowsAdapter

        // Set up details presenter
        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(DetailsDescriptionPresenter())
        detailsPresenter.backgroundColor = ContextCompat.getColor(requireContext(), R.color.background_light)
        detailsPresenter.initialState = FullWidthDetailsOverviewRowPresenter.STATE_FULL

        // Actions listener
        detailsPresenter.setOnActionClickedListener { action ->
            when (action.id) {
                ACTION_PLAY -> playContent()
                ACTION_RESUME -> resumeContent()
                ACTION_FAVORITE -> toggleFavorite()
            }
        }

        presenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
        presenterSelector.addClassPresenter(ListRow::class.java, ListRowPresenter())

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Episode) {
                playEpisode(item)
            }
        }
    }

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            val account = accountRepository.getActiveAccount() ?: return@launch
            accountId = account.id
            client = accountRepository.getClient(account)

            // Check if this content is a favorite
            isFavorite = favoritesRepository.isFavorite(contentType, contentId, account.id)

            loadDetails()
        }
    }

    private fun loadDetails() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (contentType) {
                ContentType.VOD -> loadVodDetails()
                ContentType.SERIES -> loadSeriesDetails()
                else -> {}
            }
        }
    }

    private suspend fun loadVodDetails() {
        val xtreamClient = client ?: return
        val accId = accountId ?: return

        try {
            vodInfo = xtreamClient.getVodInfo(contentId)
            val info = vodInfo?.info

            // Create details row
            val row = DetailsOverviewRow(vodInfo)
            detailsRow = row

            // Check for resume position
            val lastPosition = watchHistoryRepository.getLastPosition(contentType, contentId)

            // Add actions
            val actionAdapter = ArrayObjectAdapter()

            if (lastPosition != null && lastPosition > 0) {
                actionAdapter.add(Action(ACTION_RESUME, getString(R.string.resume)))
                actionAdapter.add(Action(ACTION_PLAY, getString(R.string.play_from_start)))
            } else {
                actionAdapter.add(Action(ACTION_PLAY, getString(R.string.play)))
            }

            // Favorite action
            favoriteAction = if (isFavorite) {
                Action(ACTION_FAVORITE, getString(R.string.remove_favorite), null,
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_favorite))
            } else {
                Action(ACTION_FAVORITE, getString(R.string.add_favorite), null,
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_favorite_border))
            }
            actionAdapter.add(favoriteAction)

            row.actionsAdapter = actionAdapter

            // Load poster image
            loadBackgroundImage(contentImage)
            loadPosterImage(row, info?.movieImage ?: contentImage)

            rowsAdapter.add(row)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadSeriesDetails() {
        val xtreamClient = client ?: return

        try {
            seriesInfo = xtreamClient.getSeriesInfo(contentId)
            val info = seriesInfo?.info

            // Create details row
            val row = DetailsOverviewRow(seriesInfo)
            detailsRow = row

            // Favorite action only for series (no direct play)
            val actionAdapter = ArrayObjectAdapter()
            favoriteAction = if (isFavorite) {
                Action(ACTION_FAVORITE, getString(R.string.remove_favorite), null,
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_favorite))
            } else {
                Action(ACTION_FAVORITE, getString(R.string.add_favorite), null,
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_favorite_border))
            }
            actionAdapter.add(favoriteAction)
            row.actionsAdapter = actionAdapter

            rowsAdapter.add(row)

            // Load background
            loadBackgroundImage(info?.cover ?: contentImage)
            loadPosterImage(row, info?.cover ?: contentImage)

            // Build flat episode list for navigation
            val episodeNavList = mutableListOf<EpisodeNavInfo>()

            // Add episodes by season
            val cardPresenter = EpisodePresenter()
            seriesInfo?.episodes?.toSortedMap(compareBy { it.toIntOrNull() ?: 0 })?.forEach { (seasonNum, episodes) ->
                if (episodes.isNotEmpty()) {
                    val episodeAdapter = ArrayObjectAdapter(cardPresenter)
                    episodes.sortedBy { it.episodeNum }.forEach { episode ->
                        episodeAdapter.add(episode)

                        // Add to navigation list
                        episodeNavList.add(EpisodeNavInfo(
                            id = episode.id,
                            episodeNum = episode.episodeNum ?: 0,
                            title = "${contentName} - ${episode.title}",
                            extension = episode.containerExtension ?: "mp4",
                            seasonNumber = seasonNum.toIntOrNull() ?: 0
                        ))
                    }

                    val header = HeaderItem(getString(R.string.season, seasonNum.toIntOrNull() ?: 0))
                    rowsAdapter.add(ListRow(header, episodeAdapter))
                }
            }

            allEpisodes = episodeNavList

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadBackgroundImage(url: String?) {
        if (url.isNullOrEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = ImageRequest.Builder(requireContext())
                    .data(url)
                    .allowHardware(false)
                    .build()

                val result = requireContext().imageLoader.execute(request)
                if (result is SuccessResult) {
                    detailsBackground.coverBitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadPosterImage(row: DetailsOverviewRow, url: String?) {
        if (url.isNullOrEmpty()) {
            row.imageDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.default_card)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = ImageRequest.Builder(requireContext())
                    .data(url)
                    .size(POSTER_WIDTH, POSTER_HEIGHT)
                    .allowHardware(false)
                    .build()

                val result = requireContext().imageLoader.execute(request)
                if (result is SuccessResult) {
                    row.imageDrawable = result.drawable
                }
            } catch (e: Exception) {
                row.imageDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.default_card)
            }
        }
    }

    private fun playContent() {
        val xtreamClient = client ?: return

        when (contentType) {
            ContentType.VOD -> {
                val streamUrl = xtreamClient.getVodStreamUrl(contentId, contentExtension)
                startPlayback(streamUrl, contentName, false, 0L)
            }
            else -> {}
        }
    }

    private fun resumeContent() {
        val xtreamClient = client ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val lastPosition = watchHistoryRepository.getLastPosition(contentType, contentId) ?: 0L

            when (contentType) {
                ContentType.VOD -> {
                    val streamUrl = xtreamClient.getVodStreamUrl(contentId, contentExtension)
                    startPlayback(streamUrl, contentName, false, lastPosition)
                }
                else -> {}
            }
        }
    }

    private fun playEpisode(episode: Episode) {
        val xtreamClient = client ?: return
        val extension = episode.containerExtension ?: "mp4"
        val streamUrl = xtreamClient.getSeriesStreamUrl(episode.id, extension)
        val title = "${contentName} - ${episode.title}"

        // Find episode index in the flat list
        val episodeIndex = allEpisodes.indexOfFirst { it.id == episode.id }

        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlaybackActivity.EXTRA_STREAM_NAME, title)
            putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
            putExtra(PlaybackActivity.EXTRA_CONTENT_ID, contentId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
            putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, contentImage)
            putExtra(PlaybackActivity.EXTRA_SERIES_ID, contentId)
            putExtra(PlaybackActivity.EXTRA_EPISODE_ID, episode.id)
            putExtra(PlaybackActivity.EXTRA_SEASON_NUMBER, episode.season ?: 0)
            putExtra(PlaybackActivity.EXTRA_EPISODE_NUMBER, episode.episodeNum ?: 0)
            putExtra(PlaybackActivity.EXTRA_CURRENT_EPISODE_INDEX, episodeIndex)

            // Pass episode list for navigation
            if (allEpisodes.isNotEmpty()) {
                putExtra(PlaybackActivity.EXTRA_EPISODE_LIST, Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(EpisodeNavInfo.serializer()),
                    allEpisodes
                ))
            }
        }
        startActivity(intent)
    }

    private fun startPlayback(url: String, title: String, isLive: Boolean, resumePosition: Long) {
        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, url)
            putExtra(PlaybackActivity.EXTRA_STREAM_NAME, title)
            putExtra(PlaybackActivity.EXTRA_IS_LIVE, isLive)
            putExtra(PlaybackActivity.EXTRA_CONTENT_ID, contentId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, contentType.name)
            putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, contentImage)
            putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, resumePosition)
        }
        startActivity(intent)
    }

    private fun toggleFavorite() {
        val accId = accountId ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val newState = favoritesRepository.toggleFavorite(
                contentType = contentType,
                contentId = contentId,
                name = contentName,
                imageUrl = contentImage,
                accountId = accId,
                extension = if (contentType == ContentType.VOD) contentExtension else null
            )

            isFavorite = newState

            // Update the action button
            val row = detailsRow ?: return@launch
            val actionAdapter = row.actionsAdapter as? ArrayObjectAdapter ?: return@launch

            // Find and update the favorite action
            for (i in 0 until actionAdapter.size()) {
                val action = actionAdapter.get(i) as? Action
                if (action?.id == ACTION_FAVORITE) {
                    val newAction = if (isFavorite) {
                        Action(ACTION_FAVORITE, getString(R.string.remove_favorite), null,
                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_favorite))
                    } else {
                        Action(ACTION_FAVORITE, getString(R.string.add_favorite), null,
                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_favorite_border))
                    }
                    actionAdapter.replace(i, newAction)
                    break
                }
            }

            // Show toast
            val message = if (isFavorite) {
                getString(R.string.added_to_favorites)
            } else {
                getString(R.string.removed_from_favorites)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ACTION_PLAY = 1L
        private const val ACTION_RESUME = 2L
        private const val ACTION_FAVORITE = 3L
        private const val POSTER_WIDTH = 274
        private const val POSTER_HEIGHT = 400
    }
}

class DetailsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(viewHolder: ViewHolder, item: Any) {
        when (item) {
            is VodInfo -> {
                viewHolder.title.text = item.info?.name ?: item.movieData?.name ?: ""
                viewHolder.subtitle.text = item.info?.genre ?: ""
                viewHolder.body.text = item.info?.plot ?: ""
            }
            is SeriesInfo -> {
                viewHolder.title.text = item.info?.name ?: ""
                viewHolder.subtitle.text = item.info?.genre ?: ""
                viewHolder.body.text = item.info?.plot ?: ""
            }
        }
    }
}
