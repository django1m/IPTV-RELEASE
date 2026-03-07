package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.app.RowsSupportFragment
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
class RecentlyWatchedFragment : RowsSupportFragment() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var watchHistoryRepository: WatchHistoryRepository
    private var client: XtreamClient? = null
    private var currentAccount: Account? = null

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter().apply {
        shadowEnabled = false
    })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is WatchHistory) openWatchHistory(item)
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            if (currentAccount == null) {
                currentAccount = accountRepository.getActiveAccount() ?: return@launch
                client = accountRepository.getClient(currentAccount!!)
            }
            loadHistory()
        }
    }

    private fun groupSeriesByLatestEpisode(items: List<WatchHistory>): List<WatchHistory> {
        val (seriesItems, otherItems) = items.partition { it.contentType == ContentType.SERIES && it.seriesId != null }
        val latestPerSeries = seriesItems
            .groupBy { it.seriesId }
            .map { (_, episodes) -> episodes.maxByOrNull { it.lastWatchedAt }!! }
        return (otherItems + latestPerSeries).sortedByDescending { it.lastWatchedAt }
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            rowsAdapter.clear()
            val accountId = currentAccount?.id ?: return@launch

            // Exclude LIVE content, only Films and Series
            val allHistory = groupSeriesByLatestEpisode(
                watchHistoryRepository.getRecentlyWatched(accountId).first()
                    .filter { it.contentType != ContentType.LIVE }
            )

            if (allHistory.isEmpty()) {
                val emptyAdapter = ArrayObjectAdapter(EmptyHistoryPresenter())
                emptyAdapter.add("Aucun historique")
                rowsAdapter.add(ListRow(HeaderItem(0L, "Recemment"), emptyAdapter))
                return@launch
            }

            // Split into in-progress vs completed
            val inProgress = allHistory.filter { !it.isCompleted && it.progressPercent > 0.02f }
            val films = allHistory.filter { it.contentType == ContentType.VOD && (it.isCompleted || it.progressPercent <= 0.02f) }
            val series = allHistory.filter { it.contentType == ContentType.SERIES && (it.isCompleted || it.progressPercent <= 0.02f) }

            var headerIndex = 0L
            val cardPresenter = WatchHistoryCardPresenter()

            // Row 1: Continue watching (in progress - mixed films & series)
            if (inProgress.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                inProgress.forEach { adapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(headerIndex++, "Continuer (${inProgress.size})"), adapter))
            }

            // Row 2: Films
            if (films.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                films.forEach { adapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(headerIndex++, "Films (${films.size})"), adapter))
            }

            // Row 3: Series
            if (series.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(cardPresenter)
                series.forEach { adapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(headerIndex++, "Series (${series.size})"), adapter))
            }
        }
    }

    private fun openWatchHistory(history: WatchHistory) {
        val xtreamClient = client ?: return

        when (history.contentType) {
            ContentType.LIVE -> return
            ContentType.VOD -> {
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
                val episodeId = history.episodeId
                if (episodeId != null) {
                    val ext = history.extension ?: "mp4"
                    val streamUrl = xtreamClient.getSeriesStreamUrl(episodeId, ext)
                    val episodeTitle = buildString {
                        append(history.name)
                        if (history.seasonNumber != null && history.episodeNumber != null) {
                            append(" - S${history.seasonNumber} E${history.episodeNumber}")
                        }
                    }
                    val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                        putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                        putExtra(PlaybackActivity.EXTRA_STREAM_NAME, episodeTitle)
                        putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
                        putExtra(PlaybackActivity.EXTRA_CONTENT_ID, history.contentId)
                        putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
                        putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, history.imageUrl)
                        putExtra(PlaybackActivity.EXTRA_SERIES_ID, history.seriesId ?: history.contentId)
                        putExtra(PlaybackActivity.EXTRA_EPISODE_ID, episodeId)
                        history.seasonNumber?.let { putExtra(PlaybackActivity.EXTRA_SEASON_NUMBER, it) }
                        history.episodeNumber?.let { putExtra(PlaybackActivity.EXTRA_EPISODE_NUMBER, it) }
                        if (!history.isCompleted && history.watchedPositionMs > 0) {
                            putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, history.watchedPositionMs)
                        }
                    }
                    startActivity(intent)
                } else {
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
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val history = item as WatchHistory
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = history.name

        val episodeInfo = if (history.contentType == ContentType.SERIES &&
            history.seasonNumber != null && history.episodeNumber != null) {
            "S${history.seasonNumber} E${history.episodeNumber}"
        } else null

        val subtitle = when {
            history.isCompleted -> {
                if (episodeInfo != null) "$episodeInfo - Termine" else "Termine"
            }
            history.progressPercent > 0.02f -> {
                val percent = (history.progressPercent * 100).toInt()
                if (episodeInfo != null) "$episodeInfo - $percent%" else "$percent%"
            }
            else -> when (history.contentType) {
                ContentType.VOD -> "Film"
                ContentType.SERIES -> episodeInfo ?: "Serie"
                else -> ""
            }
        }
        cardView.contentText = subtitle

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

    companion object {
        const val CARD_WIDTH = 240
        const val CARD_HEIGHT = 360
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
