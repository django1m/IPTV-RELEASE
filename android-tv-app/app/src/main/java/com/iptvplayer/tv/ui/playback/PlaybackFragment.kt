package com.iptvplayer.tv.ui.playback

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.model.ContentType
import com.iptvplayer.tv.data.model.EpisodeNavInfo
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.SettingsRepository
import com.iptvplayer.tv.data.repository.WatchHistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
@UnstableApi
class PlaybackFragment : VideoSupportFragment() {

    interface PlaybackErrorListener {
        fun onPlaybackError(errorMessage: String)
        fun onBufferingChanged(isBuffering: Boolean)
    }

    private var errorListener: PlaybackErrorListener? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var glue: SeriesPlaybackGlue? = null

    // Basic playback info
    private var streamUrl: String = ""
    private var streamName: String = ""
    private var isLive: Boolean = false

    // Content tracking
    private var contentId: Int = 0
    private var contentType: ContentType = ContentType.LIVE
    private var contentImage: String? = null
    private var resumePosition: Long = 0

    // Series info
    private var seriesId: Int? = null
    private var episodeId: String? = null
    private var seasonNumber: Int? = null
    private var episodeNumber: Int? = null
    private var episodeList: List<EpisodeNavInfo> = emptyList()
    private var currentEpisodeIndex: Int = -1

    // Progress tracking
    @Inject lateinit var watchHistoryRepository: WatchHistoryRepository
    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    private var accountId: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressSaveInterval = 10000L // 10 seconds

    private val progressSaveRunnable = object : Runnable {
        override fun run() {
            saveProgress()
            progressHandler.postDelayed(this, progressSaveInterval)
        }
    }

    // Retry logic — fonctionne pour live ET VOD
    private var retryCount = 0
    private val maxRetry = 5
    private val retryHandler = Handler(Looper.getMainLooper())

    // WiFi lock pour empêcher Android de couper le WiFi en arrière-plan
    private var wifiLock: WifiManager.WifiLock? = null

    // Détection changement réseau pour reconnexion automatique
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasNetworkLost = false

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        if (context is PlaybackErrorListener) {
            errorListener = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        errorListener = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireActivity().intent?.let { intent ->
            // Basic playback
            streamUrl = intent.getStringExtra(PlaybackActivity.EXTRA_STREAM_URL) ?: ""
            streamName = intent.getStringExtra(PlaybackActivity.EXTRA_STREAM_NAME) ?: ""
            isLive = intent.getBooleanExtra(PlaybackActivity.EXTRA_IS_LIVE, false)

            // Content tracking
            contentId = intent.getIntExtra(PlaybackActivity.EXTRA_CONTENT_ID, 0)
            contentType = intent.getStringExtra(PlaybackActivity.EXTRA_CONTENT_TYPE)?.let {
                ContentType.valueOf(it)
            } ?: ContentType.LIVE
            contentImage = intent.getStringExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE)
            resumePosition = intent.getLongExtra(PlaybackActivity.EXTRA_RESUME_POSITION, 0)

            // Series info
            seriesId = intent.getIntExtra(PlaybackActivity.EXTRA_SERIES_ID, -1).takeIf { it != -1 }
            episodeId = intent.getStringExtra(PlaybackActivity.EXTRA_EPISODE_ID)
            seasonNumber = intent.getIntExtra(PlaybackActivity.EXTRA_SEASON_NUMBER, -1).takeIf { it != -1 }
            episodeNumber = intent.getIntExtra(PlaybackActivity.EXTRA_EPISODE_NUMBER, -1).takeIf { it != -1 }
            currentEpisodeIndex = intent.getIntExtra(PlaybackActivity.EXTRA_CURRENT_EPISODE_INDEX, -1)

            // Parse episode list
            intent.getStringExtra(PlaybackActivity.EXTRA_EPISODE_LIST)?.let { jsonList ->
                try {
                    episodeList = Json.decodeFromString(jsonList)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Get account ID
        lifecycleScope.launch {
            accountId = accountRepository.getActiveAccount()?.id
        }

        // WiFi lock : garde le WiFi actif pendant la lecture
        val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IPTVPlayer:PlaybackLock")
        wifiLock?.acquire()

        // Surveillance réseau : reconnexion automatique si le réseau revient
        connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        registerNetworkCallback()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePlayer()
    }

    private fun initializePlayer() {
        if (player != null) return

        // Buffer adapté au type de contenu et au réglage utilisateur
        // Le mode buffer permet d'absorber les lags du fournisseur IPTV
        val bufferMode = runBlocking { settingsRepository.getBufferMode() }
        val loadControl = buildLoadControl(isLive, bufferMode)

        // Sélection de piste adaptative :
        // - Pas de forçage haute qualité, laisse ExoPlayer s'adapter à la bande passante
        // - Autorise le changement de codec/format en cours de lecture
        // - Préfère la fluidité à la résolution max
        trackSelector = DefaultTrackSelector(requireContext()).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(false)
                    .setForceLowestBitrate(false)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
            )
        }

        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector!!)
            .build()

        val playerAdapter = LeanbackPlayerAdapter(requireContext(), player!!, UPDATE_INTERVAL)

        // Use custom glue for series navigation
        glue = SeriesPlaybackGlue(
            requireContext(),
            playerAdapter,
            showPreviousNext = contentType == ContentType.SERIES && episodeList.isNotEmpty()
        ).apply {
            host = VideoSupportFragmentGlueHost(this@PlaybackFragment)
            title = streamName
            subtitle = buildSubtitle()

            isSeekEnabled = !isLive

            // Series navigation callbacks
            onPreviousEpisode = { playPreviousEpisode() }
            onNextEpisode = { playNextEpisode() }

            // Track selection callback
            onTrackSelection = {
                (activity as? PlaybackActivity)?.showTrackSelectionFromFragment()
            }

            playWhenPrepared()
        }

        // Enable auto-hide for controls - the glue handles this automatically
        // The controls will auto-hide after playback starts

        // Source HTTP avec timeouts réduits pour détecter les problèmes plus vite
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("IPTV Player TV/1.0")
            .setConnectTimeoutMs(15_000)      // 15s connexion timeout
            .setReadTimeoutMs(15_000)         // 15s lecture timeout
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val uri = Uri.parse(streamUrl)
        val mediaSource = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            streamUrl.contains(".ts", ignoreCase = true) -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
        }

        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true

            // Resume from saved position
            if (resumePosition > 0 && !isLive) {
                seekTo(resumePosition)
            }

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            errorListener?.onBufferingChanged(true)
                        }
                        Player.STATE_READY -> {
                            errorListener?.onBufferingChanged(false)
                            // Reset retry count on successful playback
                            retryCount = 0
                            // Start progress tracking
                            if (!isLive) {
                                startProgressTracking()
                            }
                            // Auto-hide controls after playback starts
                            startControlsAutoHideTimer()
                        }
                        Player.STATE_ENDED -> {
                            errorListener?.onBufferingChanged(false)
                            handlePlaybackEnded()
                        }
                        Player.STATE_IDLE -> {
                            // Idle
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (retryCount < maxRetry) {
                        // Retry automatique avec backoff progressif :
                        // 1s, 2s, 3s, 4s, 5s
                        retryCount++
                        val delayMs = (retryCount * 1000).toLong()
                        retryHandler.postDelayed({
                            player?.prepare()
                            player?.play()
                        }, delayMs)
                    } else {
                        // Max retries atteint — afficher le diagnostic réseau
                        val message = error.localizedMessage ?: getString(R.string.error_playback)
                        errorListener?.onPlaybackError(message)
                            ?: Toast.makeText(context, getString(R.string.error_playback), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private fun buildSubtitle(): String {
        return when {
            isLive -> getString(R.string.live)
            contentType == ContentType.SERIES && seasonNumber != null && episodeNumber != null ->
                "S${seasonNumber}E${episodeNumber}"
            else -> ""
        }
    }

    private fun startProgressTracking() {
        progressHandler.removeCallbacks(progressSaveRunnable)
        progressHandler.postDelayed(progressSaveRunnable, progressSaveInterval)
    }

    private fun stopProgressTracking() {
        progressHandler.removeCallbacks(progressSaveRunnable)
    }

    private fun saveProgress() {
        val currentPlayer = player ?: return
        val accId = accountId ?: return
        if (contentId == 0) return

        val currentPosition = currentPlayer.currentPosition
        val duration = currentPlayer.duration
        if (duration <= 0) return

        val progressPercent = currentPosition.toFloat() / duration.toFloat()
        val isCompleted = progressPercent > 0.9f

        lifecycleScope.launch {
            watchHistoryRepository.updateProgress(
                contentType = contentType,
                contentId = contentId,
                name = streamName,
                imageUrl = contentImage,
                accountId = accId,
                currentPositionMs = currentPosition,
                totalDurationMs = duration,
                seriesId = seriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeId = episodeId
            )
        }
    }

    private fun handlePlaybackEnded() {
        // Save final progress
        saveProgress()
        stopProgressTracking()

        // For series, ask to play next episode or auto-play
        if (contentType == ContentType.SERIES && hasNextEpisode()) {
            playNextEpisode()
        } else {
            requireActivity().finish()
        }
    }

    private fun hasNextEpisode(): Boolean {
        return currentEpisodeIndex >= 0 && currentEpisodeIndex < episodeList.size - 1
    }

    private fun hasPreviousEpisode(): Boolean {
        return currentEpisodeIndex > 0
    }

    private fun playNextEpisode() {
        if (!hasNextEpisode()) return

        val nextIndex = currentEpisodeIndex + 1
        val nextEpisode = episodeList[nextIndex]

        // Stop current playback and save progress
        saveProgress()
        stopProgressTracking()
        player?.stop()

        // Get client and build new URL
        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount() ?: return@launch
            val client = accountRepository.getClient(account)

            val newUrl = client.getSeriesStreamUrl(nextEpisode.id, nextEpisode.extension)

            // Start new playback activity with updated info
            val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(PlaybackActivity.EXTRA_STREAM_URL, newUrl)
                putExtra(PlaybackActivity.EXTRA_STREAM_NAME, nextEpisode.title)
                putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
                putExtra(PlaybackActivity.EXTRA_CONTENT_ID, contentId)
                putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
                putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, contentImage)
                putExtra(PlaybackActivity.EXTRA_SERIES_ID, seriesId ?: -1)
                putExtra(PlaybackActivity.EXTRA_EPISODE_ID, nextEpisode.id)
                putExtra(PlaybackActivity.EXTRA_SEASON_NUMBER, nextEpisode.seasonNumber)
                putExtra(PlaybackActivity.EXTRA_EPISODE_NUMBER, nextEpisode.episodeNum)
                putExtra(PlaybackActivity.EXTRA_EPISODE_LIST, Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(EpisodeNavInfo.serializer()),
                    episodeList
                ))
                putExtra(PlaybackActivity.EXTRA_CURRENT_EPISODE_INDEX, nextIndex)
                putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, 0L)
            }

            requireActivity().finish()
            startActivity(intent)
        }
    }

    private fun playPreviousEpisode() {
        if (!hasPreviousEpisode()) return

        val prevIndex = currentEpisodeIndex - 1
        val prevEpisode = episodeList[prevIndex]

        // Stop current playback and save progress
        saveProgress()
        stopProgressTracking()
        player?.stop()

        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount() ?: return@launch
            val client = accountRepository.getClient(account)

            val newUrl = client.getSeriesStreamUrl(prevEpisode.id, prevEpisode.extension)

            val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(PlaybackActivity.EXTRA_STREAM_URL, newUrl)
                putExtra(PlaybackActivity.EXTRA_STREAM_NAME, prevEpisode.title)
                putExtra(PlaybackActivity.EXTRA_IS_LIVE, false)
                putExtra(PlaybackActivity.EXTRA_CONTENT_ID, contentId)
                putExtra(PlaybackActivity.EXTRA_CONTENT_TYPE, ContentType.SERIES.name)
                putExtra(PlaybackActivity.EXTRA_CONTENT_IMAGE, contentImage)
                putExtra(PlaybackActivity.EXTRA_SERIES_ID, seriesId ?: -1)
                putExtra(PlaybackActivity.EXTRA_EPISODE_ID, prevEpisode.id)
                putExtra(PlaybackActivity.EXTRA_SEASON_NUMBER, prevEpisode.seasonNumber)
                putExtra(PlaybackActivity.EXTRA_EPISODE_NUMBER, prevEpisode.episodeNum)
                putExtra(PlaybackActivity.EXTRA_EPISODE_LIST, Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(EpisodeNavInfo.serializer()),
                    episodeList
                ))
                putExtra(PlaybackActivity.EXTRA_CURRENT_EPISODE_INDEX, prevIndex)
                putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, 0L)
            }

            requireActivity().finish()
            startActivity(intent)
        }
    }

    // Public method for Activity to call on back press
    fun saveAndExit() {
        saveProgress()
        stopProgressTracking()
        stopControlsAutoHideTimer()
        unregisterNetworkCallback()
        releaseWifiLock()
        player?.stop()
    }

    // Switch to a different URL (used for catch-up / back to live)
    fun switchToUrl(url: String, isLive: Boolean) {
        this.streamUrl = url
        this.isLive = isLive

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("IPTV Player TV/1.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val uri = Uri.parse(url)
        val mediaSource = when {
            url.contains(".m3u8", ignoreCase = true) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            url.contains(".ts", ignoreCase = true) -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
        }

        retryCount = 0
        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }

        glue?.isSeekEnabled = !isLive
    }

    // Public method for Activity to retry playback after diagnostic
    fun retryPlayback() {
        retryCount = 0
        player?.release()
        player = null
        glue = null
        initializePlayer()
    }

    // ── Surveillance réseau ──────────────────────────────────────

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                wasNetworkLost = true
            }

            override fun onAvailable(network: Network) {
                if (wasNetworkLost) {
                    wasNetworkLost = false
                    // Le réseau est revenu — relancer le flux si le player est en erreur
                    retryHandler.post {
                        val state = player?.playbackState
                        if (state == Player.STATE_IDLE || state == null) {
                            retryCount = 0
                            player?.prepare()
                            player?.play()
                        }
                    }
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            // Ignore si les permissions manquent
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Ignore
        }
        networkCallback = null
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        wifiLock = null
    }

    fun onKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            // D-pad arrows = show controls menu
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Show controls overlay and restart auto-hide timer
                showControls()
                startControlsAutoHideTimer()
                return false // Let the system handle focus navigation in the controls
            }
            // Center/OK button = toggle play/pause and show controls
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                showControls()
                startControlsAutoHideTimer()
                return false // Let the glue handle play/pause
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlayPause()
                showControls()
                startControlsAutoHideTimer()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                saveAndExit()
                requireActivity().finish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!isLive) {
                    seekRelative(-10000)
                    showControls()
                    startControlsAutoHideTimer()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!isLive) {
                    seekRelative(10000)
                    showControls()
                    startControlsAutoHideTimer()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (hasNextEpisode()) {
                    playNextEpisode()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (hasPreviousEpisode()) {
                    playPreviousEpisode()
                    return true
                }
            }
        }
        return false
    }

    // Auto-hide controls after 3 seconds
    private val controlsAutoHideDelay = 3000L
    private val controlsHideHandler = Handler(Looper.getMainLooper())
    private val controlsHideRunnable = Runnable {
        if (isControlsOverlayVisible) {
            hideControlsOverlay(true)
        }
    }

    private fun showControls() {
        if (!isControlsOverlayVisible) {
            tickle()
        }
    }

    private fun startControlsAutoHideTimer() {
        controlsHideHandler.removeCallbacks(controlsHideRunnable)
        controlsHideHandler.postDelayed(controlsHideRunnable, controlsAutoHideDelay)
    }

    private fun stopControlsAutoHideTimer() {
        controlsHideHandler.removeCallbacks(controlsHideRunnable)
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    private fun seekRelative(ms: Long) {
        player?.let {
            val newPosition = (it.currentPosition + ms).coerceIn(0, it.duration)
            it.seekTo(newPosition)
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        saveProgress()
        stopProgressTracking()
    }

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == true && !isLive) {
            startProgressTracking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressTracking()
        stopControlsAutoHideTimer()
        unregisterNetworkCallback()
        releaseWifiLock()
        player?.release()
        player = null
    }

    private fun buildLoadControl(isLive: Boolean, bufferMode: Int): DefaultLoadControl {
        return when (bufferMode) {
            SettingsRepository.BUFFER_5S -> {
                if (isLive) {
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            20_000,   // minBufferMs
                            60_000,   // maxBufferMs
                            5_000,    // bufferForPlaybackMs: attend 5s avant de lancer
                            8_000     // bufferForPlaybackAfterRebufferMs
                        )
                        .setBackBuffer(10_000, true)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                } else {
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            40_000,
                            120_000,
                            5_000,
                            10_000
                        )
                        .setBackBuffer(30_000, true)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                }
            }
            SettingsRepository.BUFFER_10S -> {
                if (isLive) {
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            30_000,   // minBufferMs
                            90_000,   // maxBufferMs
                            10_000,   // bufferForPlaybackMs: attend 10s avant de lancer
                            15_000    // bufferForPlaybackAfterRebufferMs
                        )
                        .setBackBuffer(15_000, true)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                } else {
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            60_000,
                            180_000,
                            10_000,
                            15_000
                        )
                        .setBackBuffer(30_000, true)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                }
            }
            else -> {
                // BUFFER_DEFAULT — comportement actuel
                if (isLive) {
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            15_000,
                            45_000,
                            2_000,
                            5_000
                        )
                        .setBackBuffer(5_000, true)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                } else {
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            30_000,
                            90_000,
                            3_000,
                            7_000
                        )
                        .setBackBuffer(30_000, true)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                }
            }
        }
    }

    // ── Track selection ──────────────────────────────────────────

    data class TrackInfo(
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,
        val language: String?,
        val isSelected: Boolean,
        val trackType: Int // C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT
    )

    fun getAudioTracks(): List<TrackInfo> = getTracksOfType(C.TRACK_TYPE_AUDIO)

    fun getSubtitleTracks(): List<TrackInfo> = getTracksOfType(C.TRACK_TYPE_TEXT)

    private fun getTracksOfType(trackType: Int): List<TrackInfo> {
        val currentPlayer = player ?: return emptyList()
        val tracks = currentPlayer.currentTracks
        val result = mutableListOf<TrackInfo>()
        var counter = 1

        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type != trackType) continue

            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue

                val format = group.getTrackFormat(trackIndex)
                val lang = format.language
                val label = format.label
                    ?: lang?.let { java.util.Locale(it).displayLanguage }
                    ?: getString(R.string.track_label, counter)

                result.add(
                    TrackInfo(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        label = label,
                        language = lang,
                        isSelected = group.isTrackSelected(trackIndex),
                        trackType = trackType
                    )
                )
                counter++
            }
        }
        return result
    }

    fun selectTrack(groupIndex: Int, trackIndex: Int) {
        val currentPlayer = player ?: return
        val ts = trackSelector ?: return
        val group = currentPlayer.currentTracks.groups.getOrNull(groupIndex) ?: return

        ts.setParameters(
            ts.buildUponParameters()
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                )
                .setTrackTypeDisabled(group.type, false)
        )
    }

    fun disableTrackType(trackType: Int) {
        val ts = trackSelector ?: return
        ts.setParameters(
            ts.buildUponParameters()
                .setTrackTypeDisabled(trackType, true)
        )
    }

    fun isTrackTypeDisabled(trackType: Int): Boolean {
        val ts = trackSelector ?: return false
        return ts.parameters.disabledTrackTypes.contains(trackType)
    }

    companion object {
        private const val UPDATE_INTERVAL = 16 // ~60fps
    }
}

// Custom Glue with Previous/Next episode buttons and CC action
class SeriesPlaybackGlue(
    context: android.content.Context,
    adapter: LeanbackPlayerAdapter,
    private val showPreviousNext: Boolean = false
) : PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {

    var onPreviousEpisode: (() -> Unit)? = null
    var onNextEpisode: (() -> Unit)? = null
    var onTrackSelection: (() -> Unit)? = null

    private val skipPreviousAction = PlaybackControlsRow.SkipPreviousAction(context)
    private val skipNextAction = PlaybackControlsRow.SkipNextAction(context)
    private val closedCaptioningAction = PlaybackControlsRow.ClosedCaptioningAction(context)

    override fun onCreatePrimaryActions(primaryActionsAdapter: ArrayObjectAdapter) {
        super.onCreatePrimaryActions(primaryActionsAdapter)

        if (showPreviousNext) {
            // Add skip previous at position 0
            primaryActionsAdapter.add(0, skipPreviousAction)
            // Add skip next at the end
            primaryActionsAdapter.add(skipNextAction)
        }
    }

    override fun onCreateSecondaryActions(secondaryActionsAdapter: ArrayObjectAdapter) {
        super.onCreateSecondaryActions(secondaryActionsAdapter)
        secondaryActionsAdapter.add(closedCaptioningAction)
    }

    override fun onActionClicked(action: Action) {
        when (action) {
            skipPreviousAction -> onPreviousEpisode?.invoke()
            skipNextAction -> onNextEpisode?.invoke()
            closedCaptioningAction -> onTrackSelection?.invoke()
            else -> super.onActionClicked(action)
        }
    }
}
