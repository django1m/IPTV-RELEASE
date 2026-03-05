package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.api.XtreamClient
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
class FavoritesFragment : BrowseSupportFragment() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    private var client: XtreamClient? = null
    private var currentAccount: Account? = null

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadAccount()
    }

    override fun onResume() {
        super.onResume()
        // Refresh favorites when returning to this fragment
        if (currentAccount != null) {
            loadFavorites()
        }
    }

    private fun setupUI() {
        title = getString(R.string.favorites)

        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        brandColor = ContextCompat.getColor(requireContext(), R.color.grid_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.primary)

        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Favorite -> openFavorite(item)
            }
        }
    }

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            currentAccount = accountRepository.getActiveAccount() ?: return@launch
            client = accountRepository.getClient(currentAccount!!)
            loadFavorites()
        }
    }

    private fun loadFavorites() {
        viewLifecycleOwner.lifecycleScope.launch {
            rowsAdapter.clear()

            val accountId = currentAccount?.id ?: return@launch

            // Get favorites by type
            val liveFavorites = favoritesRepository.getFavoritesByType(accountId, ContentType.LIVE).first()
            val vodFavorites = favoritesRepository.getFavoritesByType(accountId, ContentType.VOD).first()
            val seriesFavorites = favoritesRepository.getFavoritesByType(accountId, ContentType.SERIES).first()

            var headerIndex = 0L
            val cardPresenter = FavoriteCardPresenter()

            // Live TV favorites
            if (liveFavorites.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                liveFavorites.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "📺  TV en direct (${liveFavorites.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }

            // Movies favorites
            if (vodFavorites.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                vodFavorites.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "🎬  Films (${vodFavorites.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }

            // Series favorites
            if (seriesFavorites.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                seriesFavorites.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "📺  Séries (${seriesFavorites.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }

            // Show empty message if no favorites
            if (liveFavorites.isEmpty() && vodFavorites.isEmpty() && seriesFavorites.isEmpty()) {
                val emptyAdapter = ArrayObjectAdapter(EmptyFavoritesPresenter())
                emptyAdapter.add("Aucun favori")
                val header = HeaderItem(0L, "Favoris")
                rowsAdapter.add(ListRow(header, emptyAdapter))
            }
        }
    }

    private fun openFavorite(favorite: Favorite) {
        val xtreamClient = client ?: return

        when (favorite.contentType) {
            ContentType.LIVE -> {
                val streamUrl = xtreamClient.getLiveStreamUrl(favorite.contentId)
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                    putExtra(PlaybackActivity.EXTRA_STREAM_NAME, favorite.name)
                    putExtra(PlaybackActivity.EXTRA_IS_LIVE, true)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_ID, favorite.contentId)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.LIVE.name)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, favorite.imageUrl)
                }
                startActivity(intent)
            }
            ContentType.VOD -> {
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_CONTENT_TYPE, ContentType.VOD.name)
                    putExtra(DetailActivity.EXTRA_CONTENT_ID, favorite.contentId)
                    putExtra(DetailActivity.EXTRA_CONTENT_NAME, favorite.name)
                    putExtra(DetailActivity.EXTRA_CONTENT_IMAGE, favorite.imageUrl)
                    putExtra(DetailActivity.EXTRA_CONTENT_EXTENSION, favorite.extension)
                }
                startActivity(intent)
            }
            ContentType.SERIES -> {
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
                    putExtra(DetailActivity.EXTRA_CONTENT_ID, favorite.contentId)
                    putExtra(DetailActivity.EXTRA_CONTENT_NAME, favorite.name)
                    putExtra(DetailActivity.EXTRA_CONTENT_IMAGE, favorite.imageUrl)
                }
                startActivity(intent)
            }
        }
    }

    companion object {
        fun newInstance(): FavoritesFragment {
            return FavoritesFragment()
        }
    }
}

// Presenter for favorite cards
class FavoriteCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
        val cardView = androidx.leanback.widget.ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(176, 264)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val favorite = item as Favorite
        val cardView = viewHolder.view as androidx.leanback.widget.ImageCardView

        cardView.titleText = favorite.name
        cardView.contentText = when (favorite.contentType) {
            ContentType.LIVE -> "TV en direct"
            ContentType.VOD -> "Film"
            ContentType.SERIES -> "Série"
        }

        if (!favorite.imageUrl.isNullOrEmpty()) {
            coil.Coil.imageLoader(cardView.context).enqueue(
                coil.request.ImageRequest.Builder(cardView.context)
                    .data(favorite.imageUrl)
                    .target { drawable ->
                        cardView.mainImage = drawable
                    }
                    .placeholder(com.iptvplayer.tv.R.drawable.default_card)
                    .error(com.iptvplayer.tv.R.drawable.default_card)
                    .build()
            )
        } else {
            cardView.mainImage = androidx.core.content.ContextCompat.getDrawable(
                cardView.context,
                com.iptvplayer.tv.R.drawable.default_card
            )
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as androidx.leanback.widget.ImageCardView
        cardView.mainImage = null
    }
}

// Presenter for empty state
class EmptyFavoritesPresenter : Presenter() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
        val textView = android.widget.TextView(parent.context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(400, 200)
            gravity = android.view.Gravity.CENTER
            textSize = 18f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.iptvplayer.tv.R.color.text_secondary))
            text = "Appuyez longuement sur un contenu pour l'ajouter aux favoris"
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        // Already set in onCreateViewHolder
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
