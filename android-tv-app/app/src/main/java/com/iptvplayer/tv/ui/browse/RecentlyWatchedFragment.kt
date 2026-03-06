package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.api.XtreamClient
import com.iptvplayer.tv.data.model.*
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.WatchHistoryRepository
import com.iptvplayer.tv.ui.detail.DetailActivity
import com.iptvplayer.tv.ui.playback.PlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RecentlyWatchedFragment : BrowseSupportFragment() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var watchHistoryRepository: WatchHistoryRepository
    private var client: XtreamClient? = null
    private var currentAccount: Account? = null

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        loadAccount()
    }

    override fun onResume() {
        super.onResume()
        if (currentAccount != null) {
            loadHistory()
        }
    }

    private fun setupUI() {
        title = "Recemment"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.grid_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.primary)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is WatchHistory) openWatchHistory(item)
        }
    }

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            currentAccount = accountRepository.getActiveAccount() ?: return@launch
            client = accountRepository.getClient(currentAccount!!)
            loadHistory()
        }
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            rowsAdapter.clear()
            val accountId = currentAccount?.id ?: return@launch

            val continueWatching = watchHistoryRepository.getContinueWatching(accountId).first()
            val recentlyWatched = watchHistoryRepository.getRecentlyWatched(accountId).first()

            // Completed items = recently watched minus continue watching
            val continueIds = continueWatching.map { it.id }.toSet()
            val completed = recentlyWatched.filter { it.id !in continueIds }

            var headerIndex = 0L
            val cardPresenter = WatchHistoryCardPresenter()

            // Continue watching (in progress)
            if (continueWatching.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                continueWatching.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "Continuer (${continueWatching.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }

            // Completed / recently watched
            if (completed.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                completed.forEach { adapter.add(it) }
                val header = HeaderItem(headerIndex++, "Vus recemment (${completed.size})")
                rowsAdapter.add(ListRow(header, adapter))
            }

            // Empty state
            if (continueWatching.isEmpty() && completed.isEmpty()) {
                val emptyAdapter = ArrayObjectAdapter(EmptyHistoryPresenter())
                emptyAdapter.add("Aucun historique")
                val header = HeaderItem(0L, "Recemment")
                rowsAdapter.add(ListRow(header, emptyAdapter))
            }
        }
    }

    private fun openWatchHistory(history: WatchHistory) {
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
                    putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, history.imageUrl)
                }
                startActivity(intent)
            }
            ContentType.VOD -> {
                // Resume directly at last position
                val streamUrl = xtreamClient.getVodStreamUrl(history.contentId, history.extension ?: "mp4")
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                    putExtra(PlaybackActivity.EXTRA_STREAM_NAME, history.name)
                    putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_ID, history.contentId)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.VOD.name)
                    putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, history.imageUrl)
                    if (!history.isCompleted && history.watchedPositionMs > 0) {
                        putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, history.watchedPositionMs)
                    }
                }
                startActivity(intent)
            }
            ContentType.SERIES -> {
                // Open detail page for series (user picks episode)
                val seriesId = history.seriesId ?: history.contentId
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
                    putExtra(DetailActivity.EXTRA_CONTENT_ID, seriesId)
                    putExtra(DetailActivity.EXTRA_CONTENT_NAME, history.name)
                    putExtra(DetailActivity.EXTRA_CONTENT_IMAGE, history.imageUrl)
                }
                startActivity(intent)
            }
        }
    }

    companion object {
        fun newInstance(): RecentlyWatchedFragment {
            return RecentlyWatchedFragment()
        }
    }
}

// Card presenter for watch history items
class WatchHistoryCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(176, 264)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val history = item as WatchHistory
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = history.name

        // Show progress info
        val subtitle = when {
            history.isCompleted -> "Termine"
            history.progressPercent > 0.02f -> {
                val percent = (history.progressPercent * 100).toInt()
                "$percent% - ${formatDuration(history.watchedPositionMs)} / ${formatDuration(history.totalDurationMs)}"
            }
            else -> when (history.contentType) {
                ContentType.VOD -> "Film"
                ContentType.SERIES -> {
                    if (history.seasonNumber != null && history.episodeNumber != null) {
                        "S${history.seasonNumber} E${history.episodeNumber}"
                    } else "Serie"
                }
                ContentType.LIVE -> "TV en direct"
            }
        }
        cardView.contentText = subtitle

        // Load image
        if (!history.imageUrl.isNullOrEmpty()) {
            coil.Coil.imageLoader(cardView.context).enqueue(
                coil.request.ImageRequest.Builder(cardView.context)
                    .data(history.imageUrl)
                    .target { drawable -> cardView.mainImage = drawable }
                    .placeholder(R.drawable.default_card)
                    .error(R.drawable.default_card)
                    .build()
            )
        } else {
            cardView.mainImage = ContextCompat.getDrawable(cardView.context, R.drawable.default_card)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return if (hours > 0) "${hours}h${minutes.toString().padStart(2, '0')}" else "${minutes}min"
    }
}

// Empty state presenter
class EmptyHistoryPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(400, 200)
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            text = "Regardez des films ou series pour les voir ici"
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {}
    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
