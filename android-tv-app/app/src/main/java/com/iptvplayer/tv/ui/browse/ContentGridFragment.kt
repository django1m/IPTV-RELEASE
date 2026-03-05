package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
@UnstableApi
class ContentGridFragment : Fragment() {

    enum class ViewState { CATEGORIES, CONTENT }

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    private var client: XtreamClient? = null
    private var currentAccount: Account? = null

    private var contentType: ContentType = ContentType.LIVE
    private var viewState = ViewState.CATEGORIES
    private var selectedCategory: Category? = null

    // Views
    private lateinit var gridView: VerticalGridView
    private lateinit var contentHeader: View
    private lateinit var headerCategoryName: TextView
    private lateinit var previewTexture: TextureView

    // Track selected item for long press
    var selectedItem: Any? = null

    // Live channel preview
    private var previewPlayer: ExoPlayer? = null
    private var currentPreviewStreamId: Int = -1
    private val previewHandler = Handler(Looper.getMainLooper())
    private var pendingPreviewStream: LiveStream? = null
    private var pendingPreviewCardView: ImageCardView? = null

    private val previewRunnable = Runnable {
        val stream = pendingPreviewStream ?: return@Runnable
        val cardView = pendingPreviewCardView ?: return@Runnable
        startPreview(stream, cardView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            contentType = ContentType.valueOf(it.getString(ARG_CONTENT_TYPE, "LIVE"))
        }

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_content_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        gridView = view.findViewById(R.id.grid_view)
        contentHeader = view.findViewById(R.id.content_header)
        headerCategoryName = view.findViewById(R.id.header_category_name)
        previewTexture = view.findViewById(R.id.preview_texture)

        // Grid spacing
        val spacingPx = (12 * resources.displayMetrics.density).toInt()
        gridView.setHorizontalSpacing(spacingPx)
        gridView.setVerticalSpacing(spacingPx)

        // Selection listener for preview + tracking selected item
        gridView.setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView,
                child: RecyclerView.ViewHolder?,
                position: Int,
                subposition: Int
            ) {
                val bridgeVH = child as? ItemBridgeAdapter.ViewHolder ?: return
                val item = bridgeVH.item
                selectedItem = item

                val cardView = bridgeVH.viewHolder.view as? ImageCardView
                handlePreviewSelection(cardView, item)
            }
        })

        loadAccount()
    }

    // ── Account loading ─────────────────────────────────────────────

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            currentAccount = accountRepository.getActiveAccount() ?: return@launch
            client = accountRepository.getClient(currentAccount!!)
            showCategories()
        }
    }

    // ── Categories grid ─────────────────────────────────────────────

    private fun showCategories() {
        viewState = ViewState.CATEGORIES
        selectedCategory = null
        contentHeader.visibility = View.GONE
        stopPreview()

        val categories = when (contentType) {
            ContentType.LIVE -> ContentCache.getLiveCategories()
            ContentType.VOD -> ContentCache.getVodCategories()
            ContentType.SERIES -> ContentCache.getSeriesCategories()
        } ?: return

        gridView.setNumColumns(NUM_COLUMNS_CATEGORIES)

        val presenter = CategoryCardPresenter(contentType)
        val objectAdapter = ArrayObjectAdapter(presenter)
        categories.forEach { objectAdapter.add(it) }

        val bridgeAdapter = ItemBridgeAdapter(objectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val card = viewHolder.itemView.findViewById<View>(R.id.category_card)
                card.setOnClickListener {
                    val category = viewHolder.item as? Category ?: return@setOnClickListener
                    showContent(category)
                }
            }
        })

        gridView.adapter = bridgeAdapter
        gridView.scrollToPosition(0)
    }

    // ── Content grid ────────────────────────────────────────────────

    private fun showContent(category: Category) {
        viewState = ViewState.CONTENT
        selectedCategory = category
        contentHeader.visibility = View.VISIBLE
        headerCategoryName.text = category.categoryName

        val items = when (contentType) {
            ContentType.LIVE -> ContentCache.getLiveStreams(category.categoryId)
            ContentType.VOD -> ContentCache.getVodStreams(category.categoryId)
            ContentType.SERIES -> ContentCache.getSeries(category.categoryId)
        } ?: return

        val numColumns = when (contentType) {
            ContentType.LIVE -> NUM_COLUMNS_CONTENT
            else -> NUM_COLUMNS_POSTER
        }
        gridView.setNumColumns(numColumns)

        val presenter = when (contentType) {
            ContentType.LIVE -> CardPresenter(
                CardPresenter.GRID_LIVE_WIDTH,
                CardPresenter.GRID_LIVE_HEIGHT,
                CardPresenter.GRID_LIVE_HEIGHT
            )
            else -> CardPresenter(
                CardPresenter.GRID_CARD_WIDTH,
                CardPresenter.GRID_CARD_HEIGHT,
                CardPresenter.GRID_POSTER_HEIGHT
            )
        }
        val objectAdapter = ArrayObjectAdapter(presenter)
        items.forEach { objectAdapter.add(it) }

        val bridgeAdapter = ItemBridgeAdapter(objectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    handleContentClick(viewHolder.item)
                }
            }
        })

        gridView.adapter = bridgeAdapter
        gridView.scrollToPosition(0)
    }

    // ── Back navigation ─────────────────────────────────────────────

    fun onBackPressed(): Boolean {
        if (viewState == ViewState.CONTENT) {
            stopPreview()
            showCategories()
            return true
        }
        return false
    }

    // ── Live channel preview (overlay approach) ─────────────────────

    private fun handlePreviewSelection(cardView: ImageCardView?, item: Any?) {
        previewHandler.removeCallbacks(previewRunnable)

        if (viewState != ViewState.CONTENT || contentType != ContentType.LIVE) {
            stopPreview()
            return
        }

        if (item is LiveStream && cardView != null) {
            if (item.streamId == currentPreviewStreamId) return

            stopPreview()
            pendingPreviewStream = item
            pendingPreviewCardView = cardView
            previewHandler.postDelayed(previewRunnable, PREVIEW_DELAY_MS)
        } else {
            stopPreview()
        }
    }

    private fun startPreview(stream: LiveStream, cardView: ImageCardView) {
        val xtreamClient = client ?: return
        val streamUrl = xtreamClient.getLiveStreamUrl(stream.streamId)

        // Position the TextureView overlay exactly over the card's image area
        // Account for focus scale transform on the parent card
        val imageView = cardView.mainImageView
        val scale = cardView.scaleX
        val imgW = (imageView.width * scale).toInt()
        val imgH = (imageView.height * scale).toInt()

        val imageLoc = IntArray(2)
        imageView.getLocationOnScreen(imageLoc)
        val rootLoc = IntArray(2)
        requireView().getLocationOnScreen(rootLoc)

        // Center of the image in root coordinates
        val centerX = imageLoc[0] - rootLoc[0] + imageView.width / 2
        val centerY = imageLoc[1] - rootLoc[1] + imageView.height / 2

        previewTexture.layoutParams = FrameLayout.LayoutParams(imgW, imgH).apply {
            leftMargin = centerX - imgW / 2
            topMargin = centerY - imgH / 2
        }
        previewTexture.visibility = View.VISIBLE

        // Create player and connect to TextureView
        val player = ExoPlayer.Builder(requireContext()).build()
        player.setVideoTextureView(previewTexture)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                stopPreview()
            }
        })

        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true

        previewPlayer = player
        currentPreviewStreamId = stream.streamId
    }

    private fun stopPreview() {
        previewHandler.removeCallbacks(previewRunnable)
        pendingPreviewStream = null
        pendingPreviewCardView = null
        currentPreviewStreamId = -1

        previewTexture.visibility = View.GONE
        previewPlayer?.release()
        previewPlayer = null
    }

    // ── Favorites (long press) ──────────────────────────────────────

    fun onLongPress(): Boolean {
        if (viewState != ViewState.CONTENT) return false
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

            selectedCategory?.let { showContent(it) }
        }
    }

    // ── Content click handling ───────────────────────────────────────

    private fun handleContentClick(item: Any?) {
        when (item) {
            is LiveStream -> playLive(item)
            is VodStream -> openVodDetail(item)
            is Series -> openSeriesDetail(item)
        }
    }

    private fun playLive(stream: LiveStream) {
        stopPreview()
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

    // ── Lifecycle ───────────────────────────────────────────────────

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

    companion object {
        private const val ARG_CONTENT_TYPE = "content_type"
        private const val PREVIEW_DELAY_MS = 3000L

        private const val NUM_COLUMNS_CATEGORIES = 4
        private const val NUM_COLUMNS_CONTENT = 3
        private const val NUM_COLUMNS_POSTER = 4

        fun newInstance(contentType: ContentType): ContentGridFragment {
            return ContentGridFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTENT_TYPE, contentType.name)
                }
            }
        }
    }
}
