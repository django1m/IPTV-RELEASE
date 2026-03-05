package com.iptvplayer.tv.ui.epg

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.cache.FavoritesCache
import com.iptvplayer.tv.data.model.ContentType
import com.iptvplayer.tv.data.model.EpgProgram
import com.iptvplayer.tv.data.model.LiveStream
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.FavoritesRepository
import com.iptvplayer.tv.ui.playback.PlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class EpgActivity : FragmentActivity() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository

    private val viewModel: EpgViewModel by viewModels()

    private lateinit var epgView: EpgView
    private lateinit var loadingOverlay: View
    private lateinit var infoPanel: View
    private lateinit var infoTitle: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoChannelName: TextView
    private lateinit var infoChannelLogo: ImageView
    private lateinit var infoDescription: TextView
    private lateinit var infoArchiveBadge: TextView
    private lateinit var categoryList: RecyclerView
    private lateinit var categoryDivider: View
    private lateinit var selectedCategoryLabel: TextView
    private lateinit var btnJumpNow: Button
    private lateinit var previewContainer: View
    private lateinit var previewPlayerView: PlayerView

    private lateinit var categoryAdapter: EpgCategoryAdapter

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ── Preview player state ──
    private var previewPlayer: ExoPlayer? = null
    private var previewingStreamId: Int? = null
    private var previewStreamUrl: String? = null
    private var previewChannel: LiveStream? = null
    private var previewProgram: EpgProgram? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)

        initViews()
        setupCategoryList()
        setupEpgView()
        observeViewModel()

        // Refresh favorites cache from DB before initializing EPG
        lifecycleScope.launch {
            refreshFavoritesFromDb()
            viewModel.initialize()
        }
    }

    override fun onResume() {
        super.onResume()
        previewPlayer?.play()
        // Refresh favorites cache when returning (e.g., from playback)
        lifecycleScope.launch {
            refreshFavoritesFromDb()
        }
    }

    private suspend fun refreshFavoritesFromDb() {
        try {
            val account = accountRepository.getActiveAccount() ?: return
            val favorites = favoritesRepository.getFavoritesForAccount(account.id).first()
            FavoritesCache.update(favorites.map { it.id }.toSet())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initViews() {
        epgView = findViewById(R.id.epg_view)
        loadingOverlay = findViewById(R.id.epg_loading_overlay)
        infoPanel = findViewById(R.id.info_panel)
        infoTitle = findViewById(R.id.info_title)
        infoTime = findViewById(R.id.info_time)
        infoChannelName = findViewById(R.id.info_channel_name)
        infoChannelLogo = findViewById(R.id.info_channel_logo)
        infoDescription = findViewById(R.id.info_description)
        infoArchiveBadge = findViewById(R.id.info_archive_badge)
        categoryList = findViewById(R.id.category_list)
        categoryDivider = findViewById(R.id.category_divider)
        selectedCategoryLabel = findViewById(R.id.selected_category_label)
        btnJumpNow = findViewById(R.id.btn_jump_now)
        previewContainer = findViewById(R.id.preview_container)
        previewPlayerView = findViewById(R.id.preview_player_view)

        btnJumpNow.setOnClickListener {
            epgView.jumpToNow()
            epgView.requestFocus()
        }
    }

    private fun setupCategoryList() {
        categoryAdapter = EpgCategoryAdapter { item ->
            selectedCategoryLabel.text = item.name
            if (item.isFavorites) {
                viewModel.setFavorites()
            } else {
                viewModel.setCategory(item.id)
            }
            hideCategorySidebar()
            epgView.exitChannelFocusMode()
            epgView.requestFocus()
        }

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter
    }

    private fun setupEpgView() {
        epgView.onFocusChanged = { channel, program ->
            viewModel.setFocusedInfo(channel, program)
        }

        epgView.onProgramSelected = { channel, program ->
            handleChannelSelect(channel, program)
        }

        epgView.onRequestMoreData = { streamIds ->
            viewModel.loadEpgForStreamIds(streamIds)
        }

        epgView.onExitLeft = {
            showCategorySidebar()
        }

        epgView.onChannelLongPress = { stream ->
            toggleChannelFavorite(stream)
        }

        epgView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isCategorySidebarVisible()) {
                hideCategorySidebar()
                epgView.exitChannelFocusMode()
            }
        }

        epgView.requestFocus()
    }

    // ── Preview / Fullscreen logic ──

    private fun handleChannelSelect(channel: LiveStream, program: EpgProgram?) {
        if (previewingStreamId == channel.streamId) {
            // Already previewing this channel → go fullscreen
            stopPreview()
            launchPlayback(channel, program)
        } else {
            // Start preview
            previewChannel = channel
            previewProgram = program
            startPreview(channel, program)
        }
    }

    private fun startPreview(channel: LiveStream, program: EpgProgram?) {
        val client = runCatching {
            val account = kotlinx.coroutines.runBlocking { accountRepository.getActiveAccount() }
            account?.let { kotlinx.coroutines.runBlocking { accountRepository.getClient(it) } }
        }.getOrNull() ?: return

        val isPast = program?.isPast == true
        val hasArchive = program?.hasArchive == 1 && channel.tvArchive == 1

        val url = if (isPast && hasArchive && program != null) {
            val durationMinutes = ((program.stopTimestampLong - program.startTimestampLong) / 60).toInt()
            client.getTimeshiftUrl(channel.streamId, program.startTimestampLong, durationMinutes)
        } else {
            client.getLiveStreamUrl(channel.streamId)
        }

        previewStreamUrl = url
        previewingStreamId = channel.streamId

        // Release old player
        previewPlayer?.release()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player.volume = 0f // Muted preview

        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true

        previewPlayerView.player = player
        previewContainer.visibility = View.VISIBLE
        previewPlayer = player
    }

    private fun stopPreview() {
        previewPlayer?.release()
        previewPlayer = null
        previewPlayerView.player = null
        previewContainer.visibility = View.GONE
        previewingStreamId = null
        previewStreamUrl = null
        previewChannel = null
        previewProgram = null
    }

    // ── Category sidebar ──

    private fun showCategorySidebar() {
        categoryList.visibility = View.VISIBLE
        categoryDivider.visibility = View.VISIBLE
        categoryList.requestFocus()
        categoryList.scrollToPosition(categoryAdapter.selectedPosition)
    }

    private fun hideCategorySidebar() {
        categoryList.visibility = View.GONE
        categoryDivider.visibility = View.GONE
    }

    private fun isCategorySidebarVisible(): Boolean {
        return categoryList.visibility == View.VISIBLE
    }

    // ── ViewModel observers ──

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.channels.observe(this) { (channels, isNewList) ->
            epgView.setChannels(channels, resetScroll = isNewList)
            if (isNewList && channels.isNotEmpty()) {
                epgView.jumpToNow()
            }
        }

        viewModel.focusedInfo.observe(this) { (channel, program) ->
            updateInfoPanel(channel, program)
        }

        viewModel.categories.observe(this) { categories ->
            val items = mutableListOf<EpgCategoryAdapter.CategoryItem>()
            items.add(EpgCategoryAdapter.CategoryItem(null, getString(R.string.epg_all_categories)))
            items.add(EpgCategoryAdapter.CategoryItem(null, getString(R.string.favorites), isFavorites = true))
            items.addAll(categories.map {
                EpgCategoryAdapter.CategoryItem(it.categoryId, it.categoryName)
            })
            categoryAdapter.setItems(items)
        }
    }

    // ── Info panel ──

    private fun updateInfoPanel(channel: LiveStream, program: EpgProgram?) {
        infoChannelName.text = channel.name

        if (!channel.streamIcon.isNullOrEmpty()) {
            infoChannelLogo.load(channel.streamIcon) {
                crossfade(true)
                transformations(RoundedCornersTransformation(8f))
                placeholder(R.drawable.ic_live)
            }
            infoChannelLogo.visibility = View.VISIBLE
        } else {
            infoChannelLogo.visibility = View.GONE
        }

        if (program != null) {
            infoTitle.text = program.decodedTitle
            infoTitle.visibility = View.VISIBLE

            val startStr = if (program.startTimestampLong > 0) timeFormat.format(Date(program.startTimestampLong * 1000)) else ""
            val endStr = if (program.stopTimestampLong > 0) timeFormat.format(Date(program.stopTimestampLong * 1000)) else ""
            infoTime.text = "$startStr - $endStr"
            infoTime.visibility = View.VISIBLE

            val desc = program.description
            if (!desc.isNullOrBlank()) {
                val decodedDesc = try {
                    val decoded = String(android.util.Base64.decode(desc, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    if (decoded.isNotBlank()) decoded else desc
                } catch (e: Exception) { desc }
                infoDescription.text = decodedDesc
                infoDescription.visibility = View.VISIBLE
            } else {
                infoDescription.visibility = View.GONE
            }

            val isPast = program.isPast
            val hasArchive = program.hasArchive == 1 && channel.tvArchive == 1
            infoArchiveBadge.visibility = if (isPast && hasArchive) View.VISIBLE else View.GONE
        } else {
            infoTitle.text = getString(R.string.epg_no_data)
            infoTime.visibility = View.GONE
            infoDescription.visibility = View.GONE
            infoArchiveBadge.visibility = View.GONE
        }
    }

    // ── Playback launch (fullscreen) ──

    private fun launchPlayback(channel: LiveStream, program: EpgProgram?) {
        val isPast = program?.isPast == true
        val hasArchive = program?.hasArchive == 1 && channel.tvArchive == 1

        if (isPast && hasArchive && program != null) {
            launchCatchup(channel, program)
        } else {
            launchLive(channel)
        }
    }

    private fun launchLive(channel: LiveStream) {
        val client = runCatching {
            val account = kotlinx.coroutines.runBlocking { accountRepository.getActiveAccount() }
            account?.let { kotlinx.coroutines.runBlocking { accountRepository.getClient(it) } }
        }.getOrNull() ?: return

        val streamUrl = client.getLiveStreamUrl(channel.streamId)
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlaybackActivity.EXTRA_STREAM_NAME, channel.name)
            putExtra(PlaybackActivity.EXTRA_IS_LIVE, true)
            putExtra(PlaybackActivity.EXTRA_TV_ARCHIVE, channel.tvArchive ?: 0)
            putExtra(PlaybackActivity.EXTRA_TV_ARCHIVE_DURATION, channel.tvArchiveDuration ?: 0)
            putExtra(PlaybackActivity.EXTRA_STREAM_ID, channel.streamId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_ID, channel.streamId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.LIVE.name)
            putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, channel.streamIcon)
        }
        startActivity(intent)
    }

    private fun launchCatchup(channel: LiveStream, program: EpgProgram) {
        val client = runCatching {
            val account = kotlinx.coroutines.runBlocking { accountRepository.getActiveAccount() }
            account?.let { kotlinx.coroutines.runBlocking { accountRepository.getClient(it) } }
        }.getOrNull() ?: return

        val durationMinutes = ((program.stopTimestampLong - program.startTimestampLong) / 60).toInt()
        val timeshiftUrl = client.getTimeshiftUrl(channel.streamId, program.startTimestampLong, durationMinutes)

        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, timeshiftUrl)
            putExtra(PlaybackActivity.EXTRA_STREAM_NAME, "${channel.name} - ${program.decodedTitle}")
            putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
            putExtra(PlaybackActivity.EXTRA_TV_ARCHIVE, channel.tvArchive ?: 0)
            putExtra(PlaybackActivity.EXTRA_TV_ARCHIVE_DURATION, channel.tvArchiveDuration ?: 0)
            putExtra(PlaybackActivity.EXTRA_STREAM_ID, channel.streamId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_ID, channel.streamId)
            putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.LIVE.name)
            putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, channel.streamIcon)
        }
        startActivity(intent)
    }

    // ── Favorites ──

    private fun toggleChannelFavorite(stream: LiveStream) {
        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount() ?: return@launch
            val isNowFavorite = favoritesRepository.toggleFavorite(
                contentType = ContentType.LIVE,
                contentId = stream.streamId,
                name = stream.name ?: "",
                imageUrl = stream.streamIcon,
                accountId = account.id
            )
            val favoriteId = "${ContentType.LIVE.name}_${stream.streamId}"
            if (isNowFavorite) {
                FavoritesCache.add(favoriteId)
            } else {
                FavoritesCache.remove(favoriteId)
            }
            epgView.invalidate()
            val msg = if (isNowFavorite) getString(R.string.added_to_favorites) else getString(R.string.removed_from_favorites)
            Toast.makeText(this@EpgActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Key handling ──

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (previewingStreamId != null) {
                stopPreview()
                return true
            }
            if (isCategorySidebarVisible()) {
                hideCategorySidebar()
                epgView.exitChannelFocusMode()
                epgView.requestFocus()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Lifecycle ──

    override fun onPause() {
        super.onPause()
        previewPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewPlayer?.release()
        previewPlayer = null
    }
}
