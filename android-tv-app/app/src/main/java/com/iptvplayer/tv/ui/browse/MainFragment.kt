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
import com.iptvplayer.tv.ui.detail.DetailActivity
import com.iptvplayer.tv.ui.login.LoginActivity
import com.iptvplayer.tv.ui.playback.PlaybackActivity
import com.iptvplayer.tv.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : BrowseSupportFragment() {

    @Inject lateinit var repository: AccountRepository
    private var client: XtreamClient? = null
    private var currentAccount: Account? = null

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    // Track header IDs for navigation
    private var currentHeaderId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadAccount()
    }

    private fun setupUI() {
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        // Set colors
        brandColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.primary)

        adapter = rowsAdapter

        // Item click listener
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
            when (item) {
                is LiveStream -> playLive(item)
                is VodStream -> openVodDetail(item)
                is Series -> openSeriesDetail(item)
                is CategoryItem -> loadCategoryContent(item)
                is SettingsItem -> handleSettingsItem(item)
            }
        }

        setOnSearchClickedListener {
            // TODO: Implement search
        }
    }

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            currentAccount = repository.getActiveAccount()
            if (currentAccount == null) {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                requireActivity().finish()
                return@launch
            }

            client = repository.getClient(currentAccount!!)
            loadMainCategories()
        }
    }

    private fun loadMainCategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            rowsAdapter.clear()
            currentHeaderId = 0L

            try {
                // === TV EN DIRECT ===
                addLiveTVSection()

                // === FILMS ===
                addVODSection()

                // === SÉRIES ===
                addSeriesSection()

                // === PARAMÈTRES ===
                addSettingsRow()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun addLiveTVSection() {
        val xtreamClient = client ?: return
        val categoryPresenter = CategoryPresenter()

        try {
            val categories = xtreamClient.getLiveCategories()
            if (categories.isEmpty()) return

            val adapter = ArrayObjectAdapter(categoryPresenter)

            // Add "All channels" option
            adapter.add(CategoryItem(
                id = "all",
                name = "Toutes les chaînes",
                type = ContentType.LIVE,
                iconRes = R.drawable.ic_live
            ))

            // Add each category
            categories.forEach { category ->
                adapter.add(CategoryItem(
                    id = category.categoryId,
                    name = category.categoryName,
                    type = ContentType.LIVE
                ))
            }

            val header = HeaderItem(currentHeaderId++, "📺  TV EN DIRECT")
            rowsAdapter.add(ListRow(header, adapter))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun addVODSection() {
        val xtreamClient = client ?: return
        val categoryPresenter = CategoryPresenter()

        try {
            val categories = xtreamClient.getVodCategories()
            if (categories.isEmpty()) return

            val adapter = ArrayObjectAdapter(categoryPresenter)

            // Add "All movies" option
            adapter.add(CategoryItem(
                id = "all",
                name = "Tous les films",
                type = ContentType.VOD,
                iconRes = R.drawable.ic_movie
            ))

            categories.forEach { category ->
                adapter.add(CategoryItem(
                    id = category.categoryId,
                    name = category.categoryName,
                    type = ContentType.VOD
                ))
            }

            val header = HeaderItem(currentHeaderId++, "🎬  FILMS")
            rowsAdapter.add(ListRow(header, adapter))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun addSeriesSection() {
        val xtreamClient = client ?: return
        val categoryPresenter = CategoryPresenter()

        try {
            val categories = xtreamClient.getSeriesCategories()
            if (categories.isEmpty()) return

            val adapter = ArrayObjectAdapter(categoryPresenter)

            // Add "All series" option
            adapter.add(CategoryItem(
                id = "all",
                name = "Toutes les séries",
                type = ContentType.SERIES,
                iconRes = R.drawable.ic_series
            ))

            categories.forEach { category ->
                adapter.add(CategoryItem(
                    id = category.categoryId,
                    name = category.categoryName,
                    type = ContentType.SERIES
                ))
            }

            val header = HeaderItem(currentHeaderId++, "📺  SÉRIES")
            rowsAdapter.add(ListRow(header, adapter))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addSettingsRow() {
        val settingsPresenter = SettingsPresenter()
        val adapter = ArrayObjectAdapter(settingsPresenter)

        adapter.add(SettingsItem(SettingsItem.Type.SETTINGS, getString(R.string.settings), R.drawable.ic_settings))
        adapter.add(SettingsItem(SettingsItem.Type.SWITCH_ACCOUNT, getString(R.string.switch_account), R.drawable.ic_account))
        adapter.add(SettingsItem(SettingsItem.Type.LOGOUT, getString(R.string.remove_account), R.drawable.ic_logout))

        val header = HeaderItem(currentHeaderId++, "⚙️  PARAMÈTRES")
        rowsAdapter.add(ListRow(header, adapter))
    }

    private fun loadCategoryContent(categoryItem: CategoryItem) {
        // Open category browser activity
        val intent = Intent(requireContext(), CategoryBrowseActivity::class.java).apply {
            putExtra(CategoryBrowseActivity.EXTRA_CATEGORY_ID, categoryItem.id)
            putExtra(CategoryBrowseActivity.EXTRA_CATEGORY_NAME, categoryItem.name)
            putExtra(CategoryBrowseActivity.EXTRA_CONTENT_TYPE, categoryItem.type.name)
        }
        startActivity(intent)
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

    private fun handleSettingsItem(item: SettingsItem) {
        when (item.type) {
            SettingsItem.Type.SETTINGS -> {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
            SettingsItem.Type.SWITCH_ACCOUNT -> {
                // TODO: Show account picker
            }
            SettingsItem.Type.LOGOUT -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    currentAccount?.let { repository.removeAccount(it.id) }
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                    requireActivity().finish()
                }
            }
        }
    }
}

// Data class for category items
data class CategoryItem(
    val id: String,
    val name: String,
    val type: ContentType,
    val iconRes: Int? = null
)

data class SettingsItem(
    val type: Type,
    val title: String,
    val iconRes: Int
) {
    enum class Type {
        SETTINGS, SWITCH_ACCOUNT, LOGOUT
    }
}
