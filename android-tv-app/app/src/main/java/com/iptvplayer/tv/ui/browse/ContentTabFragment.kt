package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.api.XtreamClient
import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.cache.FavoritesCache
import com.iptvplayer.tv.data.model.*
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.FavoritesRepository
import com.iptvplayer.tv.data.repository.WatchHistoryRepository
import com.iptvplayer.tv.ui.detail.DetailActivity
import com.iptvplayer.tv.ui.playback.PlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
@UnstableApi
class ContentTabFragment : BrowseSupportFragment() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    @Inject lateinit var watchHistoryRepository: WatchHistoryRepository
    private var client: XtreamClient? = null
    private var currentAccount: Account? = null

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var contentType: ContentType = ContentType.LIVE

    // Track selected item for long press
    private var selectedItem: Any? = null

    // Lazy loading : garder la trace des catégories chargées
    private val loadedRows = mutableSetOf<Int>()
    private val categoryList = mutableListOf<Category>()
    private var hasContinueWatchingRow = false

    // Live channel preview
    private var previewPlayer: ExoPlayer? = null
    private var previewPlayerView: PlayerView? = null
    private var currentPreviewStreamId: Int = -1
    private val previewHandler = Handler(Looper.getMainLooper())
    private var pendingPreviewStream: LiveStream? = null
    private var pendingPreviewHolder: Presenter.ViewHolder? = null

    private val previewRunnable = Runnable {
        val stream = pendingPreviewStream ?: return@Runnable
        val holder = pendingPreviewHolder ?: return@Runnable
        val cardView = holder.view as? ImageCardView ?: return@Runnable
        startPreview(stream, cardView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            contentType = ContentType.valueOf(it.getString(ARG_CONTENT_TYPE, "LIVE"))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadAccount()
    }

    private fun setupUI() {
        // No title — the tab bar already shows which section we're in
        title = ""

        // Headers hidden by default, content takes full width
        // Press left on D-pad to reveal categories
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = false

        brandColor = ContextCompat.getColor(requireContext(), R.color.grid_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.primary)

        adapter = rowsAdapter

        // Click listener
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is LiveStream -> playLive(item)
                is VodStream -> openVodDetail(item)
                is Series -> openSeriesDetail(item)
                is WatchHistory -> resumeWatching(item)
            }
        }

        // Selection listener - track selected item + lazy load + live preview
        setOnItemViewSelectedListener { itemViewHolder, item, _, row ->
            selectedItem = item

            // Live channel preview after 3 seconds
            handlePreviewSelection(itemViewHolder, item)

            // Lazy load : charger le contenu de la catégorie sélectionnée
            if (row is ListRow) {
                val position = rowsAdapter.indexOf(row)
                if (position >= 0) {
                    loadRowContentIfNeeded(position, row)
                    // Pré-charger les lignes adjacentes
                    preloadAdjacentRows(position)
                }
            }
        }
    }

    // ── Lazy loading ──────────────────────────────────────────────

    private fun loadRowContentIfNeeded(position: Int, row: ListRow) {
        if (loadedRows.contains(position)) return

        val offset = if (hasContinueWatchingRow) 1 else 0
        val categoryIndex = position - offset
        if (categoryIndex < 0 || categoryIndex >= categoryList.size) return

        val category = categoryList[categoryIndex]
        loadedRows.add(position)

        val cardPresenter = CardPresenter()
        val items = when (contentType) {
            ContentType.LIVE -> ContentCache.getLiveStreams(category.categoryId)
            ContentType.VOD -> ContentCache.getVodStreams(category.categoryId)
            ContentType.SERIES -> ContentCache.getSeries(category.categoryId)
        }

        if (items != null) {
            val newAdapter = ArrayObjectAdapter(cardPresenter)
            items.forEach { newAdapter.add(it) }
            // Remplacer la ligne avec le contenu chargé
            val header = row.headerItem
            rowsAdapter.replace(position, ListRow(header, newAdapter))
        }
    }

    private fun preloadAdjacentRows(currentPosition: Int) {
        // Pré-charger 1 ligne au-dessus et 1 en-dessous
        for (delta in listOf(-1, 1)) {
            val pos = currentPosition + delta
            if (pos in 0 until rowsAdapter.size()) {
                val adjacentRow = rowsAdapter.get(pos)
                if (adjacentRow is ListRow) {
                    loadRowContentIfNeeded(pos, adjacentRow)
                }
            }
        }
    }

    // ── Favorites ──────────────────────────────────────────────────

    // Called by MainActivity on long press - returns true if handled
    fun onLongPress(): Boolean {
        val item = selectedItem ?: return false
        val accountId = currentAccount?.id ?: return false

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

            // Refresh only the loaded rows, not everything
            refreshLoadedRows()
        }
    }

    // ── Content loading ────────────────────────────────────────────

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            currentAccount = accountRepository.getActiveAccount() ?: return@launch
            client = accountRepository.getClient(currentAccount!!)

            displayCategoriesLazy()
        }
    }

    /**
     * Affiche les catégories avec des lignes VIDES.
     * Le contenu est chargé à la demande quand l'utilisateur navigue.
     */
    private fun displayCategoriesLazy() {
        viewLifecycleOwner.lifecycleScope.launch {
            rowsAdapter.clear()
            loadedRows.clear()
            categoryList.clear()
            hasContinueWatchingRow = false

            // Add "Continue Watching" row first
            val addedContinueWatching = addContinueWatchingRow()
            hasContinueWatchingRow = addedContinueWatching

            // Récupérer la liste des catégories
            val categories = when (contentType) {
                ContentType.LIVE -> ContentCache.getLiveCategories()
                ContentType.VOD -> ContentCache.getVodCategories()
                ContentType.SERIES -> ContentCache.getSeriesCategories()
            } ?: return@launch

            // Placeholder adapter vide pour les lignes non chargées
            val emptyPresenter = CardPresenter()

            categories.forEachIndexed { index, category ->
                categoryList.add(category)
                val emptyAdapter = ArrayObjectAdapter(emptyPresenter)
                val header = HeaderItem(
                    category.categoryId.toLongOrNull() ?: (index + 1L),
                    category.categoryName
                )
                rowsAdapter.add(ListRow(header, emptyAdapter))
            }

            // Charger immédiatement la première catégorie visible
            if (rowsAdapter.size() > 0) {
                val firstContentPos = if (hasContinueWatchingRow) 1 else 0
                if (firstContentPos < rowsAdapter.size()) {
                    val firstRow = rowsAdapter.get(firstContentPos) as? ListRow
                    if (firstRow != null) {
                        loadRowContentIfNeeded(firstContentPos, firstRow)
                        // Pré-charger aussi la 2e
                        preloadAdjacentRows(firstContentPos)
                    }
                }
            }
        }
    }

    private fun refreshLoadedRows() {
        // Recharger seulement les lignes déjà chargées pour mettre à jour les badges favoris
        val cardPresenter = CardPresenter()
        for (position in loadedRows.toList()) {
            val offset = if (hasContinueWatchingRow) 1 else 0
            val categoryIndex = position - offset
            if (categoryIndex < 0 || categoryIndex >= categoryList.size) continue

            val category = categoryList[categoryIndex]
            val items = when (contentType) {
                ContentType.LIVE -> ContentCache.getLiveStreams(category.categoryId)
                ContentType.VOD -> ContentCache.getVodStreams(category.categoryId)
                ContentType.SERIES -> ContentCache.getSeries(category.categoryId)
            } ?: continue

            val newAdapter = ArrayObjectAdapter(cardPresenter)
            items.forEach { newAdapter.add(it) }
            val row = rowsAdapter.get(position) as? ListRow ?: continue
            val header = row.headerItem
            rowsAdapter.replace(position, ListRow(header, newAdapter))
        }
    }

    /**
     * Returns true if "Continue Watching" row was added.
     */
    private suspend fun addContinueWatchingRow(): Boolean {
        val accountId = currentAccount?.id ?: return false

        val continueWatching = watchHistoryRepository
            .getContinueWatching(accountId)
            .first()
            .filter { it.contentType == contentType }
            .take(20)

        if (continueWatching.isEmpty()) return false

        val cardPresenter = ContinueWatchingPresenter()
        val adapter = ArrayObjectAdapter(cardPresenter)
        continueWatching.forEach { adapter.add(it) }

        val header = HeaderItem(0L, "Continuer")
        rowsAdapter.add(ListRow(header, adapter))
        return true
    }

    // ── Live channel preview ────────────────────────────────────────

    private fun handlePreviewSelection(viewHolder: Presenter.ViewHolder?, item: Any?) {
        previewHandler.removeCallbacks(previewRunnable)

        // Only preview for LIVE content type
        if (contentType != ContentType.LIVE) {
            stopPreview()
            return
        }

        if (item is LiveStream && viewHolder != null) {
            // Already previewing this stream — keep playing
            if (item.streamId == currentPreviewStreamId) return

            stopPreview()
            pendingPreviewStream = item
            pendingPreviewHolder = viewHolder
            previewHandler.postDelayed(previewRunnable, PREVIEW_DELAY_MS)
        } else {
            stopPreview()
        }
    }

    private fun startPreview(stream: LiveStream, cardView: ImageCardView) {
        val xtreamClient = client ?: return
        val streamUrl = xtreamClient.getLiveStreamUrl(stream.streamId)

        val player = ExoPlayer.Builder(requireContext()).build()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true

        // Stop silently on preview errors
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                stopPreview()
            }
        })

        // Create PlayerView — no controls, fill the image area
        val playerView = PlayerView(requireContext()).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            this.player = player
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Add the PlayerView inside the image wrapper (FrameLayout parent of mainImageView)
        val imageParent = cardView.mainImageView.parent as? ViewGroup
        imageParent?.addView(playerView)

        previewPlayer = player
        previewPlayerView = playerView
        currentPreviewStreamId = stream.streamId
    }

    private fun stopPreview() {
        previewHandler.removeCallbacks(previewRunnable)
        pendingPreviewStream = null
        pendingPreviewHolder = null
        currentPreviewStreamId = -1

        previewPlayerView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        previewPlayer?.release()
        previewPlayer = null
        previewPlayerView = null
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopPreview()
    }

    override fun onDestroyView() {
        stopPreview()
        super.onDestroyView()
    }

    // ── Navigation actions ─────────────────────────────────────────

    private fun playLive(stream: LiveStream) {
        stopPreview()
        val xtreamClient = client ?: return
        val streamUrl = xtreamClient.getLiveStreamUrl(stream.streamId)

        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlaybackActivity.EXTRA_STREAM_NAME, stream.name)
            putExtra(PlaybackActivity.EXTRA_IS_LIVE, true)
            putExtra(PlaybackActivity.EXTRA_CONTENT_ID, stream.streamId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.LIVE.name)
            putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, stream.streamIcon)
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

    private fun resumeWatching(history: WatchHistory) {
        val xtreamClient = client ?: return

        when (history.contentType) {
            ContentType.LIVE -> {
                val streamUrl = xtreamClient.getLiveStreamUrl(history.contentId)
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                    putExtra(PlaybackActivity.EXTRA_STREAM_NAME, history.name)
                    putExtra(PlaybackActivity.EXTRA_IS_LIVE, true)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_ID, history.contentId)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.LIVE.name)
                }
                startActivity(intent)
            }
            ContentType.VOD -> {
                val extension = history.extension ?: "mp4"
                val streamUrl = xtreamClient.getVodStreamUrl(history.contentId, extension)
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                    putExtra(PlaybackActivity.EXTRA_STREAM_NAME, history.name)
                    putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_ID, history.contentId)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.VOD.name)
                    putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, history.watchedPositionMs)
                }
                startActivity(intent)
            }
            ContentType.SERIES -> {
                if (history.episodeId != null) {
                    val extension = history.extension ?: "mp4"
                    val streamUrl = xtreamClient.getSeriesStreamUrl(history.episodeId, extension)
                    val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                        putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                        putExtra(PlaybackActivity.EXTRA_STREAM_NAME, history.name)
                        putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
                        putExtra(PlaybackActivity.EXTRA_CONTENT_ID, history.contentId)
                        putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
                        putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, history.watchedPositionMs)
                        putExtra(PlaybackActivity.EXTRA_SERIES_ID, history.seriesId)
                        putExtra(PlaybackActivity.EXTRA_EPISODE_ID, history.episodeId)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    companion object {
        private const val ARG_CONTENT_TYPE = "content_type"
        private const val PREVIEW_DELAY_MS = 3000L

        fun newInstance(contentType: ContentType): ContentTabFragment {
            return ContentTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTENT_TYPE, contentType.name)
                }
            }
        }
    }
}
