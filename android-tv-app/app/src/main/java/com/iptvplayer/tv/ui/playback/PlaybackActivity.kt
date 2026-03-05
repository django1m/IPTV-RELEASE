package com.iptvplayer.tv.ui.playback

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.model.EpgProgram
import com.iptvplayer.tv.data.network.DiagnosisType
import com.iptvplayer.tv.data.network.DiagnosticResult
import com.iptvplayer.tv.data.network.FullDiagnosticResult
import com.iptvplayer.tv.data.network.NetworkDiagnostic
import com.iptvplayer.tv.data.repository.AccountRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackActivity : FragmentActivity(), PlaybackFragment.PlaybackErrorListener {

    private var playbackFragment: PlaybackFragment? = null
    @Inject lateinit var accountRepository: AccountRepository

    // Diagnostic overlay views
    private lateinit var diagnosticOverlay: View
    private lateinit var diagnosticLoading: LinearLayout
    private lateinit var diagnosticResults: LinearLayout
    private lateinit var diagnosticMessage: TextView
    private lateinit var diagnosticErrorMessage: TextView
    private lateinit var internetStatusDot: View
    private lateinit var internetStatusText: TextView
    private lateinit var providerStatusDot: View
    private lateinit var providerStatusText: TextView
    private lateinit var btnDiagnose: Button
    private lateinit var btnRetry: Button
    private lateinit var btnQuit: Button

    // Buffering bar views
    private lateinit var bufferingBar: LinearLayout
    private lateinit var bufferingInternetDot: View
    private lateinit var bufferingInternetLatency: TextView
    private lateinit var bufferingProviderDot: View
    private lateinit var bufferingProviderLatency: TextView

    // Track selection overlay views
    private lateinit var trackSelectionOverlay: View
    private lateinit var audioTrackList: LinearLayout
    private lateinit var subtitleTrackList: LinearLayout
    private lateinit var noTracksMessage: TextView

    // Catch-up overlay views
    private lateinit var catchupOverlay: View
    private lateinit var catchupLoading: LinearLayout
    private lateinit var catchupProgramList: LinearLayout
    private lateinit var catchupNoData: TextView
    private lateinit var catchupChannelName: TextView
    private lateinit var btnBackToLive: Button

    private var isDiagnosticVisible = false
    private var isTrackSelectionVisible = false
    private var isCatchupVisible = false
    private var isCatchupMode = false
    private var tvArchive: Int = 0
    private var tvArchiveDuration: Int = 0
    private var liveStreamId: Int = 0
    private var bufferingDiagnosticJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        // Empêcher la mise en veille pendant la lecture vidéo
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Read catch-up extras
        tvArchive = intent.getIntExtra(EXTRA_TV_ARCHIVE, 0)
        tvArchiveDuration = intent.getIntExtra(EXTRA_TV_ARCHIVE_DURATION, 0)
        liveStreamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)

        // Init views
        initDiagnosticViews()
        initBufferingViews()
        initTrackSelectionViews()
        initCatchupViews()

        if (savedInstanceState == null) {
            playbackFragment = PlaybackFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.playback_fragment, playbackFragment!!)
                .commitNow()
        } else {
            playbackFragment = supportFragmentManager.findFragmentById(R.id.playback_fragment) as? PlaybackFragment
        }

        // Handle back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isCatchupVisible) {
                    hideCatchup()
                } else if (isTrackSelectionVisible) {
                    hideTrackSelection()
                } else if (isDiagnosticVisible) {
                    hideDiagnostic()
                } else {
                    playbackFragment?.saveAndExit()
                    finish()
                }
            }
        })
    }

    // ── Diagnostic overlay ─────────────────────────────────────────

    private fun initDiagnosticViews() {
        diagnosticOverlay = findViewById(R.id.diagnostic_overlay)
        diagnosticLoading = findViewById(R.id.diagnostic_loading)
        diagnosticResults = findViewById(R.id.diagnostic_results)
        diagnosticMessage = findViewById(R.id.diagnostic_message)
        diagnosticErrorMessage = findViewById(R.id.diagnostic_error_message)
        internetStatusDot = findViewById(R.id.internet_status_dot)
        internetStatusText = findViewById(R.id.internet_status_text)
        providerStatusDot = findViewById(R.id.provider_status_dot)
        providerStatusText = findViewById(R.id.provider_status_text)
        btnDiagnose = findViewById(R.id.btn_diagnose)
        btnRetry = findViewById(R.id.btn_retry)
        btnQuit = findViewById(R.id.btn_quit)

        btnDiagnose.setOnClickListener { runDiagnostic() }
        btnRetry.setOnClickListener {
            hideDiagnostic()
            playbackFragment?.retryPlayback()
        }
        btnQuit.setOnClickListener {
            playbackFragment?.saveAndExit()
            finish()
        }
    }

    override fun onPlaybackError(errorMessage: String) {
        hideBufferingBar()
        showDiagnostic(errorMessage)
    }

    private fun showDiagnostic(errorMessage: String) {
        isDiagnosticVisible = true
        diagnosticOverlay.visibility = View.VISIBLE
        diagnosticErrorMessage.text = errorMessage

        diagnosticResults.visibility = View.GONE
        diagnosticMessage.visibility = View.GONE

        runDiagnostic()
        btnRetry.requestFocus()
    }

    private fun hideDiagnostic() {
        isDiagnosticVisible = false
        diagnosticOverlay.visibility = View.GONE
    }

    private fun runDiagnostic() {
        diagnosticLoading.visibility = View.VISIBLE
        diagnosticResults.visibility = View.GONE
        diagnosticMessage.visibility = View.GONE
        btnDiagnose.isEnabled = false

        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)
        internetStatusDot.setBackgroundColor(mutedColor)
        providerStatusDot.setBackgroundColor(mutedColor)
        internetStatusText.text = "--"
        providerStatusText.text = "--"

        lifecycleScope.launch {
            val result = runNetworkDiagnostic()
            displayDiagnosticResult(result)
        }
    }

    private fun displayDiagnosticResult(result: FullDiagnosticResult) {
        diagnosticLoading.visibility = View.GONE
        diagnosticResults.visibility = View.VISIBLE
        diagnosticMessage.visibility = View.VISIBLE
        btnDiagnose.isEnabled = true

        val successColor = ContextCompat.getColor(this, R.color.success)
        val errorColor = ContextCompat.getColor(this, R.color.error)

        if (result.internet.ok) {
            internetStatusDot.setBackgroundColor(successColor)
            internetStatusText.text = getString(R.string.status_ok, result.internet.latencyMs.toInt())
            internetStatusText.setTextColor(successColor)
        } else {
            internetStatusDot.setBackgroundColor(errorColor)
            internetStatusText.text = getString(R.string.status_error)
            internetStatusText.setTextColor(errorColor)
        }

        if (result.provider.ok) {
            providerStatusDot.setBackgroundColor(successColor)
            providerStatusText.text = getString(R.string.status_ok, result.provider.latencyMs.toInt())
            providerStatusText.setTextColor(successColor)
        } else {
            providerStatusDot.setBackgroundColor(errorColor)
            providerStatusText.text = getString(R.string.status_error)
            providerStatusText.setTextColor(errorColor)
        }

        when (result.diagnosis) {
            DiagnosisType.INTERNET_DOWN -> {
                diagnosticMessage.text = getString(R.string.diag_internet_down)
                diagnosticMessage.setTextColor(errorColor)
            }
            DiagnosisType.PROVIDER_DOWN -> {
                diagnosticMessage.text = getString(R.string.diag_provider_down)
                diagnosticMessage.setTextColor(errorColor)
            }
            DiagnosisType.BOTH_DOWN -> {
                diagnosticMessage.text = getString(R.string.diag_both_down)
                diagnosticMessage.setTextColor(errorColor)
            }
            DiagnosisType.ALL_OK -> {
                diagnosticMessage.text = getString(R.string.diag_all_ok)
                diagnosticMessage.setTextColor(successColor)
            }
        }

        btnRetry.requestFocus()
    }

    // ── Buffering bar ──────────────────────────────────────────────

    private fun initBufferingViews() {
        bufferingBar = findViewById(R.id.buffering_bar)
        bufferingInternetDot = findViewById(R.id.buffering_internet_dot)
        bufferingInternetLatency = findViewById(R.id.buffering_internet_latency)
        bufferingProviderDot = findViewById(R.id.buffering_provider_dot)
        bufferingProviderLatency = findViewById(R.id.buffering_provider_latency)
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        if (isDiagnosticVisible) return

        if (isBuffering) {
            showBufferingBar()
        } else {
            hideBufferingBar()
        }
    }

    private fun showBufferingBar() {
        bufferingBar.visibility = View.VISIBLE

        // Reset dots to muted while checking
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)
        bufferingInternetDot.setBackgroundColor(mutedColor)
        bufferingProviderDot.setBackgroundColor(mutedColor)
        bufferingInternetLatency.text = ""
        bufferingProviderLatency.text = ""

        // Cancel previous job if still running
        bufferingDiagnosticJob?.cancel()

        // Run diagnostic in background
        bufferingDiagnosticJob = lifecycleScope.launch {
            val result = runNetworkDiagnostic()
            updateBufferingBar(result)
        }
    }

    private fun hideBufferingBar() {
        bufferingBar.visibility = View.GONE
        bufferingDiagnosticJob?.cancel()
        bufferingDiagnosticJob = null
    }

    private fun updateBufferingBar(result: FullDiagnosticResult) {
        if (bufferingBar.visibility != View.VISIBLE) return

        val successColor = ContextCompat.getColor(this, R.color.success)
        val errorColor = ContextCompat.getColor(this, R.color.error)

        if (result.internet.ok) {
            bufferingInternetDot.setBackgroundColor(successColor)
            bufferingInternetLatency.text = getString(R.string.status_ok, result.internet.latencyMs.toInt())
            bufferingInternetLatency.setTextColor(successColor)
        } else {
            bufferingInternetDot.setBackgroundColor(errorColor)
            bufferingInternetLatency.text = getString(R.string.status_error)
            bufferingInternetLatency.setTextColor(errorColor)
        }

        if (result.provider.ok) {
            bufferingProviderDot.setBackgroundColor(successColor)
            bufferingProviderLatency.text = getString(R.string.status_ok, result.provider.latencyMs.toInt())
            bufferingProviderLatency.setTextColor(successColor)
        } else {
            bufferingProviderDot.setBackgroundColor(errorColor)
            bufferingProviderLatency.text = getString(R.string.status_error)
            bufferingProviderLatency.setTextColor(errorColor)
        }
    }

    // ── Track selection overlay ──────────────────────────────────

    private fun initTrackSelectionViews() {
        trackSelectionOverlay = findViewById(R.id.track_selection_overlay)
        audioTrackList = findViewById(R.id.audio_track_list)
        subtitleTrackList = findViewById(R.id.subtitle_track_list)
        noTracksMessage = findViewById(R.id.no_tracks_message)
    }

    private fun showTrackSelection() {
        if (isDiagnosticVisible) return

        isTrackSelectionVisible = true
        trackSelectionOverlay.visibility = View.VISIBLE
        populateTrackLists()
    }

    fun showTrackSelectionFromFragment() {
        showTrackSelection()
    }

    private fun hideTrackSelection() {
        isTrackSelectionVisible = false
        trackSelectionOverlay.visibility = View.GONE
    }

    private fun populateTrackLists() {
        val fragment = playbackFragment ?: return
        audioTrackList.removeAllViews()
        subtitleTrackList.removeAllViews()

        val audioTracks = fragment.getAudioTracks()
        val subtitleTracks = fragment.getSubtitleTracks()

        val selectedColor = ContextCompat.getColor(this, R.color.primary)
        val normalColor = ContextCompat.getColor(this, R.color.text_secondary)

        // Audio tracks
        audioTracks.forEach { track ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_track, audioTrackList, false) as TextView
            view.text = if (track.isSelected) getString(R.string.track_selected, track.label) else track.label
            view.setTextColor(if (track.isSelected) selectedColor else normalColor)
            view.setOnClickListener {
                fragment.selectTrack(track.groupIndex, track.trackIndex)
                populateTrackLists()
            }
            audioTrackList.addView(view)
        }

        // Subtitle tracks
        val subsDisabled = fragment.isTrackTypeDisabled(C.TRACK_TYPE_TEXT)

        // "Disabled" option
        val disableView = LayoutInflater.from(this).inflate(R.layout.item_track, subtitleTrackList, false) as TextView
        disableView.text = if (subsDisabled) getString(R.string.track_selected, getString(R.string.track_disabled)) else getString(R.string.track_disabled)
        disableView.setTextColor(if (subsDisabled) selectedColor else normalColor)
        disableView.setOnClickListener {
            fragment.disableTrackType(C.TRACK_TYPE_TEXT)
            populateTrackLists()
        }
        subtitleTrackList.addView(disableView)

        subtitleTracks.forEach { track ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_track, subtitleTrackList, false) as TextView
            val isActive = track.isSelected && !subsDisabled
            view.text = if (isActive) getString(R.string.track_selected, track.label) else track.label
            view.setTextColor(if (isActive) selectedColor else normalColor)
            view.setOnClickListener {
                fragment.selectTrack(track.groupIndex, track.trackIndex)
                populateTrackLists()
            }
            subtitleTrackList.addView(view)
        }

        // Show "no alternatives" if only 1 audio and 0 subtitle tracks
        val hasAlternatives = audioTracks.size > 1 || subtitleTracks.isNotEmpty()
        noTracksMessage.visibility = if (hasAlternatives) View.GONE else View.VISIBLE

        // Focus first item
        if (audioTrackList.childCount > 0) {
            audioTrackList.getChildAt(0).requestFocus()
        } else if (subtitleTrackList.childCount > 0) {
            subtitleTrackList.getChildAt(0).requestFocus()
        }
    }

    // ── Catch-up / Replay overlay ───────────────────────────────

    private fun initCatchupViews() {
        catchupOverlay = findViewById(R.id.catchup_overlay)
        catchupLoading = findViewById(R.id.catchup_loading)
        catchupProgramList = findViewById(R.id.catchup_program_list)
        catchupNoData = findViewById(R.id.catchup_no_data)
        catchupChannelName = findViewById(R.id.catchup_channel_name)
        btnBackToLive = findViewById(R.id.btn_back_to_live)

        btnBackToLive.setOnClickListener {
            switchBackToLive()
        }
    }

    private fun showCatchup() {
        if (isDiagnosticVisible || isTrackSelectionVisible) return
        if (tvArchive != 1 || liveStreamId == 0) {
            android.widget.Toast.makeText(this, getString(R.string.catchup_not_available), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        isCatchupVisible = true
        catchupOverlay.visibility = View.VISIBLE
        catchupLoading.visibility = View.VISIBLE
        catchupProgramList.removeAllViews()
        catchupNoData.visibility = View.GONE

        val streamName = intent.getStringExtra(EXTRA_STREAM_NAME) ?: ""
        catchupChannelName.text = streamName

        // Show back to live button only if we're in catchup mode
        btnBackToLive.visibility = if (isCatchupMode) View.VISIBLE else View.GONE

        loadEpgPrograms()
    }

    private fun hideCatchup() {
        isCatchupVisible = false
        catchupOverlay.visibility = View.GONE
    }

    private fun loadEpgPrograms() {
        lifecycleScope.launch {
            try {
                val account = accountRepository.getActiveAccount() ?: return@launch
                val client = accountRepository.getClient(account)
                val epg = client.getShortEpg(liveStreamId)
                val programs = epg.listings ?: emptyList()

                catchupLoading.visibility = View.GONE

                if (programs.isEmpty()) {
                    catchupNoData.visibility = View.VISIBLE
                    return@launch
                }

                populateProgramList(programs)
            } catch (e: Exception) {
                e.printStackTrace()
                catchupLoading.visibility = View.GONE
                catchupNoData.visibility = View.VISIBLE
            }
        }
    }

    private fun populateProgramList(programs: List<EpgProgram>) {
        catchupProgramList.removeAllViews()

        val selectedColor = ContextCompat.getColor(this, R.color.primary)
        val normalColor = ContextCompat.getColor(this, R.color.text_secondary)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)
        val nowColor = ContextCompat.getColor(this, R.color.success)

        val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        var firstFocusable: View? = null

        programs.forEach { program ->
            val view = layoutInflater.inflate(R.layout.item_epg_program, catchupProgramList, false)
            val timeView = view.findViewById<TextView>(R.id.epg_time)
            val titleView = view.findViewById<TextView>(R.id.epg_title)
            val statusView = view.findViewById<TextView>(R.id.epg_status)

            // Format time
            val startTime = if (program.startTimestampLong > 0) {
                dateFormat.format(java.util.Date(program.startTimestampLong * 1000))
            } else {
                program.start ?: ""
            }
            val endTime = if (program.stopTimestampLong > 0) {
                dateFormat.format(java.util.Date(program.stopTimestampLong * 1000))
            } else {
                program.end ?: ""
            }
            timeView.text = "$startTime\n$endTime"

            titleView.text = program.decodedTitle

            when {
                program.isCurrentlyAiring -> {
                    statusView.visibility = View.VISIBLE
                    statusView.text = getString(R.string.catchup_now_playing)
                    statusView.setTextColor(nowColor)
                    titleView.setTextColor(nowColor)
                }
                program.isPast && program.hasArchive == 1 -> {
                    statusView.visibility = View.VISIBLE
                    statusView.text = getString(R.string.catchup_archive_available)
                    statusView.setTextColor(selectedColor)
                    titleView.setTextColor(normalColor)

                    view.setOnClickListener {
                        playCatchup(program)
                    }
                    if (firstFocusable == null) firstFocusable = view
                }
                program.isPast -> {
                    titleView.setTextColor(mutedColor)
                    view.isFocusable = false
                }
                else -> {
                    titleView.setTextColor(normalColor)
                    view.isFocusable = false
                }
            }

            catchupProgramList.addView(view)
        }

        // Show back to live if already in catchup mode
        btnBackToLive.visibility = if (isCatchupMode) View.VISIBLE else View.GONE
        if (isCatchupMode && firstFocusable == null) {
            btnBackToLive.requestFocus()
        }
        firstFocusable?.requestFocus()
    }

    private fun playCatchup(program: EpgProgram) {
        hideCatchup()
        isCatchupMode = true

        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount() ?: return@launch
            val client = accountRepository.getClient(account)

            val durationMinutes = ((program.stopTimestampLong - program.startTimestampLong) / 60).toInt()
            val timeshiftUrl = client.getTimeshiftUrl(liveStreamId, program.startTimestampLong, durationMinutes)

            playbackFragment?.switchToUrl(timeshiftUrl, isLive = false)
        }
    }

    private fun switchBackToLive() {
        hideCatchup()
        isCatchupMode = false

        val originalUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return
        playbackFragment?.switchToUrl(originalUrl, isLive = true)
    }

    // ── Shared diagnostic logic ────────────────────────────────────

    private suspend fun runNetworkDiagnostic(): FullDiagnosticResult {
        val account = accountRepository.getActiveAccount()
        return if (account != null) {
            NetworkDiagnostic.runFullDiagnostic(
                account.serverUrl,
                account.username,
                account.password
            )
        } else {
            val internet = NetworkDiagnostic.checkInternet()
            FullDiagnosticResult(
                internet = internet,
                provider = DiagnosticResult(ok = false),
                diagnosis = if (internet.ok) DiagnosisType.PROVIDER_DOWN else DiagnosisType.BOTH_DOWN
            )
        }
    }

    // ── Key handling ───────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(event)

        // Catch-up overlay handles back key
        if (isCatchupVisible) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideCatchup()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Track selection overlay handles its own key events
        if (isTrackSelectionVisible) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideTrackSelection()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (isDiagnosticVisible) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideDiagnostic()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    playbackFragment?.saveAndExit()
                    finish()
                    return true
                }
                // MENU key → open track selection
                KeyEvent.KEYCODE_MENU -> {
                    showTrackSelection()
                    return true
                }
                // GUIDE or INFO key → open catch-up overlay (for live channels with archive)
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_INFO -> {
                    if (tvArchive == 1 && liveStreamId > 0) {
                        showCatchup()
                    } else {
                        showTrackSelection()
                    }
                    return true
                }
                else -> {
                    if (playbackFragment?.onKeyDown(event.keyCode) == true) {
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_STREAM_NAME = "stream_name"
        const val EXTRA_IS_LIVE = "is_live"

        const val EXTRA_CONTENT_ID = "content_id"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_CONTENT_IMAGE = "content_image"
        const val EXTRA_RESUME_POSITION = "resume_position"

        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_SEASON_NUMBER = "season_number"
        const val EXTRA_EPISODE_NUMBER = "episode_number"
        const val EXTRA_EPISODE_LIST = "episode_list"
        const val EXTRA_CURRENT_EPISODE_INDEX = "current_episode_index"

        const val EXTRA_TV_ARCHIVE = "tv_archive"
        const val EXTRA_TV_ARCHIVE_DURATION = "tv_archive_duration"
        const val EXTRA_STREAM_ID = "stream_id"
    }
}
