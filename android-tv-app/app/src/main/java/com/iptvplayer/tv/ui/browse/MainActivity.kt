package com.iptvplayer.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import android.app.AlertDialog
import android.widget.Toast
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.api.XtreamClient
import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.cache.FavoritesCache
import com.iptvplayer.tv.data.model.ContentType
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.FavoritesRepository
import com.iptvplayer.tv.data.update.UpdateManager
import com.iptvplayer.tv.ui.epg.EpgActivity
import com.iptvplayer.tv.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository

    private lateinit var tabLive: Button
    private lateinit var tabMovies: Button
    private lateinit var tabSeries: Button
    private lateinit var tabFavorites: Button
    private lateinit var tabGuide: ImageButton
    private lateinit var tabSearch: ImageButton
    private lateinit var tabSettings: ImageButton

    private lateinit var loadingOverlay: View
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingText: TextView

    private var currentTab: Tab = Tab.LIVE
    private val tabButtons = mutableListOf<Button>()
    private var isContentLoaded = false

    // Fragment cache - keep fragments alive across tab switches
    private val fragmentCache = mutableMapOf<Tab, Fragment>()

    // Update manager
    private lateinit var updateManager: UpdateManager
    private var updateCheckDone = false

    // Long press detection for favorites
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var isLongPressTriggered = false
    private val longPressDelay = 800L // 800ms for long press
    private val longPressRunnable = Runnable {
        isLongPressTriggered = true
        handleLongPress()
    }

    enum class Tab {
        LIVE, MOVIES, SERIES, FAVORITES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        updateManager = UpdateManager(this)

        initViews()
        setupTabListeners()

        if (savedInstanceState == null) {
            // Load all content at startup
            loadAllContent()
            // Check for updates after content loads
            checkForUpdate()
        } else {
            val savedTab = savedInstanceState.getString(KEY_CURRENT_TAB, Tab.LIVE.name)
            currentTab = Tab.valueOf(savedTab)
            isContentLoaded = savedInstanceState.getBoolean(KEY_CONTENT_LOADED, false)

            if (isContentLoaded) {
                hideLoading()
                selectTab(currentTab)
            } else {
                loadAllContent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh favorites cache when returning to activity
        refreshFavoritesCache()

        // If content was loaded but cache got cleared (e.g. manual refresh from settings),
        // reload everything immediately
        if (isContentLoaded && ContentCache.getLiveCategories() == null) {
            isContentLoaded = false
            fragmentCache.clear()
            supportFragmentManager.fragments.forEach {
                supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
            }
            loadAllContent()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_TAB, currentTab.name)
        outState.putBoolean(KEY_CONTENT_LOADED, isContentLoaded)
    }

    private fun initViews() {
        tabLive = findViewById(R.id.tab_live)
        tabMovies = findViewById(R.id.tab_movies)
        tabSeries = findViewById(R.id.tab_series)
        tabFavorites = findViewById(R.id.tab_favorites)
        tabGuide = findViewById(R.id.tab_guide)
        tabSearch = findViewById(R.id.tab_search)
        tabSettings = findViewById(R.id.tab_settings)

        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingProgress = findViewById(R.id.loading_progress)
        loadingText = findViewById(R.id.loading_text)

        tabButtons.addAll(listOf(tabLive, tabMovies, tabSeries, tabFavorites))
    }

    private fun setupTabListeners() {
        tabLive.setOnClickListener { selectTab(Tab.LIVE) }
        tabMovies.setOnClickListener { selectTab(Tab.MOVIES) }
        tabSeries.setOnClickListener { selectTab(Tab.SERIES) }
        tabFavorites.setOnClickListener { selectTab(Tab.FAVORITES) }
        tabGuide.setOnClickListener { openGuide() }
        tabSearch.setOnClickListener { openSearch() }
        tabSettings.setOnClickListener { openSettings() }

        // Handle D-pad navigation on tabs - move focus to content grid
        tabButtons.forEach { button ->
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val fragment = fragmentCache[currentTab]
                    val gridView = fragment?.view?.findViewById<View>(R.id.grid_view)
                    gridView?.requestFocus() ?: false
                } else {
                    false
                }
            }
        }
    }

    private fun showLoading(message: String) {
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = message
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    private fun loadAllContent() {
        showLoading("Chargement du contenu...")

        lifecycleScope.launch {
            try {
                val account = accountRepository.getActiveAccount()
                if (account == null) {
                    hideLoading()
                    selectTab(Tab.LIVE)
                    return@launch
                }

                val client = accountRepository.getClient(account)

                // Initialize cache for account
                val needsRefresh = ContentCache.initForAccount(account.id)

                // Load from disk cache if available (on IO thread to avoid ANR)
                if (!needsRefresh && ContentCache.needsDiskLoad()) {
                    withContext(Dispatchers.IO) {
                        ContentCache.loadFromDiskSync()
                    }
                }

                // Load favorites cache
                refreshFavoritesCacheSync()

                // Check if we need to load content from network
                if (!needsRefresh && ContentCache.isLiveFullyLoaded() &&
                    ContentCache.isVodFullyLoaded() && ContentCache.isSeriesFullyLoaded()) {
                    // All content is cached in memory
                    isContentLoaded = true
                    hideLoading()
                    selectTab(Tab.LIVE)
                    return@launch
                }

                // Load all 3 categories in parallel from network
                withContext(Dispatchers.IO) {
                    val liveJob = async { loadLiveContent(client) }
                    val vodJob = async { loadVodContent(client) }
                    val seriesJob = async { loadSeriesContent(client) }
                    awaitAll(liveJob, vodJob, seriesJob)
                }

                ContentCache.markRefreshed()
                isContentLoaded = true
                hideLoading()
                selectTab(Tab.LIVE)

            } catch (e: Exception) {
                e.printStackTrace()
                hideLoading()
                isContentLoaded = true
                selectTab(Tab.LIVE)
            }
        }
    }

    private suspend fun loadLiveContent(client: XtreamClient) = coroutineScope {
        if (ContentCache.isLiveFullyLoaded()) return@coroutineScope

        try {
            val categories = client.getLiveCategories()
            ContentCache.setLiveCategories(categories)

            // Load all categories in parallel (10 at a time)
            categories.chunked(10).forEach { chunk ->
                chunk.map { category ->
                    async {
                        try {
                            val streams = client.getLiveStreams(category.categoryId)
                            ContentCache.setLiveStreams(category.categoryId, streams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }.awaitAll()
            }

            ContentCache.setLiveFullyLoaded(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadVodContent(client: XtreamClient) = coroutineScope {
        if (ContentCache.isVodFullyLoaded()) return@coroutineScope

        try {
            val categories = client.getVodCategories()
            ContentCache.setVodCategories(categories)

            // Load all categories in parallel (10 at a time)
            categories.chunked(10).forEach { chunk ->
                chunk.map { category ->
                    async {
                        try {
                            val streams = client.getVodStreams(category.categoryId)
                            ContentCache.setVodStreams(category.categoryId, streams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }.awaitAll()
            }

            ContentCache.setVodFullyLoaded(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadSeriesContent(client: XtreamClient) = coroutineScope {
        if (ContentCache.isSeriesFullyLoaded()) return@coroutineScope

        try {
            val categories = client.getSeriesCategories()
            ContentCache.setSeriesCategories(categories)

            // Load all categories in parallel (10 at a time)
            categories.chunked(10).forEach { chunk ->
                chunk.map { category ->
                    async {
                        try {
                            val series = client.getSeries(category.categoryId)
                            ContentCache.setSeries(category.categoryId, series)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }.awaitAll()
            }

            ContentCache.setSeriesFullyLoaded(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refreshFavoritesCache() {
        lifecycleScope.launch {
            refreshFavoritesCacheSync()
        }
    }

    private suspend fun refreshFavoritesCacheSync() {
        try {
            val account = accountRepository.getActiveAccount() ?: return
            val favorites = favoritesRepository.getFavoritesForAccount(account.id).first()
            FavoritesCache.update(favorites.map { it.id }.toSet())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab

        // Update button states
        tabButtons.forEach { it.isSelected = false }
        when (tab) {
            Tab.LIVE -> tabLive.isSelected = true
            Tab.MOVIES -> tabMovies.isSelected = true
            Tab.SERIES -> tabSeries.isSelected = true
            Tab.FAVORITES -> tabFavorites.isSelected = true
        }

        // Use cached fragments - show/hide instead of replace to avoid re-creating
        val transaction = supportFragmentManager.beginTransaction()

        // Hide all existing cached fragments
        fragmentCache.values.forEach { transaction.hide(it) }

        val fragment = fragmentCache[tab]
        if (fragment != null) {
            // Fragment already exists, just show it
            transaction.show(fragment)
        } else {
            // Create new fragment and cache it
            val newFragment: Fragment = when (tab) {
                Tab.LIVE -> ContentGridFragment.newInstance(ContentType.LIVE)
                Tab.MOVIES -> ContentGridFragment.newInstance(ContentType.VOD)
                Tab.SERIES -> ContentGridFragment.newInstance(ContentType.SERIES)
                Tab.FAVORITES -> FavoritesFragment.newInstance()
            }
            fragmentCache[tab] = newFragment
            transaction.add(R.id.content_fragment, newFragment)
        }

        transaction.commitAllowingStateLoss()
    }

    private fun openGuide() {
        startActivity(Intent(this, EpgActivity::class.java))
    }

    private fun openSearch() {
        val intent = Intent(this, SearchActivity::class.java)
        startActivity(intent)
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(event)

        val keyCode = event.keyCode

        // Only intercept for long press detection when focus is in content (not on tab buttons)
        val focusedView = currentFocus
        val isFocusOnTabs = tabButtons.contains(focusedView) ||
                           focusedView == tabSearch ||
                           focusedView == tabSettings

        // Long press detection for OK/Select button - only in content area
        if (!isFocusOnTabs && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        isLongPressTriggered = false
                        longPressHandler.postDelayed(longPressRunnable, longPressDelay)
                    }
                    // Don't consume, let it propagate for normal handling
                }
                KeyEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressTriggered) {
                        // Long press was handled, consume the event
                        isLongPressTriggered = false
                        return true
                    }
                    // Short press - let it through normally
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleLongPress() {
        // Find the currently visible fragment
        val fragment = fragmentCache[currentTab]
        if (fragment is ContentGridFragment) {
            if (fragment.onLongPress()) {
                refreshFavoritesCache()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle GUIDE key to open EPG
        if (keyCode == KeyEvent.KEYCODE_GUIDE) {
            openGuide()
            return true
        }

        // Handle menu button for adding to favorites (fallback for remotes that have it)
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BOOKMARK) {
            val fragment = fragmentCache[currentTab]
            if (fragment is ContentGridFragment && fragment.onLongPress()) {
                refreshFavoritesCache()
                return true
            }
        }

        // Handle back button
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // First, let the grid fragment handle back (content → categories)
            val fragment = fragmentCache[currentTab]
            if (fragment is ContentGridFragment && fragment.onBackPressed()) {
                return true
            }

            // Otherwise, move focus to the tab bar
            val currentFocus = currentFocus
            if (currentFocus != null && !tabButtons.contains(currentFocus)) {
                when (currentTab) {
                    Tab.LIVE -> tabLive.requestFocus()
                    Tab.MOVIES -> tabMovies.requestFocus()
                    Tab.SERIES -> tabSeries.requestFocus()
                    Tab.FAVORITES -> tabFavorites.requestFocus()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Update check ──────────────────────────────────────────────

    private fun checkForUpdate() {
        if (updateCheckDone) return
        updateCheckDone = true

        lifecycleScope.launch {
            val updateInfo = updateManager.checkForUpdate() ?: return@launch
            showUpdateDialog(updateInfo)
        }
    }

    private fun showUpdateDialog(updateInfo: com.iptvplayer.tv.data.update.UpdateInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)

        val currentVersion = updateManager.getCurrentVersionName()
        dialogView.findViewById<TextView>(R.id.update_version).text =
            getString(R.string.update_version, updateInfo.versionName, currentVersion)

        val notesView = dialogView.findViewById<TextView>(R.id.update_notes)
        if (updateInfo.releaseNotes.isNotBlank()) {
            notesView.text = updateInfo.releaseNotes
            notesView.visibility = View.VISIBLE
        } else {
            notesView.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this, R.style.UpdateDialogTheme)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<Button>(R.id.btn_update_later).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btn_update_now).setOnClickListener {
            // Check install permission first
            if (!updateManager.canInstallPackages()) {
                dialog.dismiss()
                Toast.makeText(this, "Veuillez autoriser l'installation depuis cette application", Toast.LENGTH_LONG).show()
                updateManager.openInstallPermissionSettings()
                return@setOnClickListener
            }

            // Disable buttons and show progress
            val btnUpdate = dialogView.findViewById<Button>(R.id.btn_update_now)
            val btnLater = dialogView.findViewById<Button>(R.id.btn_update_later)
            btnUpdate.isEnabled = false
            btnUpdate.text = "Telechargement 0%"
            btnLater.isEnabled = false
            dialog.setCancelable(false)

            lifecycleScope.launch {
                updateManager.downloadAndInstall(
                    updateInfo,
                    onProgress = { progress ->
                        btnUpdate.text = "Telechargement $progress%"
                    },
                    onError = { error ->
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    },
                    onDownloadComplete = {
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.show()

        // Focus the "Update" button by default
        dialogView.findViewById<Button>(R.id.btn_update_now).requestFocus()
    }

    companion object {
        private const val KEY_CURRENT_TAB = "current_tab"
        private const val KEY_CONTENT_LOADED = "content_loaded"
    }
}
