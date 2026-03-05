package com.iptvplayer.tv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.SettingsRepository
import com.iptvplayer.tv.data.update.UpdateManager
import com.iptvplayer.tv.ui.login.LoginActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : GuidedStepSupportFragment() {

    @Inject lateinit var repository: AccountRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings),
            getString(R.string.app_name),
            "",
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_settings)
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        // Account info action
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ACCOUNT)
                .title(getString(R.string.account))
                .description("Chargement...")
                .editable(false)
                .build()
        )

        // Add account action
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ADD_ACCOUNT)
                .title(getString(R.string.add_account))
                .description("Ajouter un nouveau compte IPTV")
                .build()
        )

        // Logout action
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_LOGOUT)
                .title(getString(R.string.remove_account))
                .description("Se déconnecter du compte actuel")
                .build()
        )

        // Refresh content action
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_REFRESH)
                .title("Rafraîchir le contenu")
                .description(getLastRefreshDescription())
                .build()
        )

        // Buffer mode action
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_BUFFER)
                .title(getString(R.string.buffer_mode))
                .description(getString(R.string.buffer_loading))
                .build()
        )

        // Check for updates
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CHECK_UPDATE)
                .title(getString(R.string.check_update))
                .description("")
                .build()
        )

        // Version info
        val versionName = UpdateManager(requireContext()).getCurrentVersionName()
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_VERSION)
                .title(getString(R.string.app_name))
                .description("Version $versionName")
                .editable(false)
                .focusable(false)
                .build()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load account info
        viewLifecycleOwner.lifecycleScope.launch {
            val account = repository.getActiveAccount()
            findActionById(ACTION_ACCOUNT)?.description = account?.name ?: "Non connecté"
            notifyActionChanged(findActionPositionById(ACTION_ACCOUNT))
        }

        // Load buffer mode
        viewLifecycleOwner.lifecycleScope.launch {
            val mode = settingsRepository.getBufferMode()
            findActionById(ACTION_BUFFER)?.description = getBufferDescription(mode)
            notifyActionChanged(findActionPositionById(ACTION_BUFFER))
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ACCOUNT -> {
                // Open edit account screen
                GuidedStepSupportFragment.add(
                    parentFragmentManager,
                    EditAccountFragment(),
                    android.R.id.content
                )
            }
            ACTION_ADD_ACCOUNT -> {
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            }
            ACTION_LOGOUT -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    val account = repository.getActiveAccount()
                    account?.let { repository.removeAccount(it.id) }
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                    requireActivity().finish()
                }
            }
            ACTION_REFRESH -> {
                ContentCache.forceRefresh()
                Toast.makeText(context, getString(R.string.refresh_on_back), Toast.LENGTH_LONG).show()

                // Update the action description
                findActionById(ACTION_REFRESH)?.description = getString(R.string.refresh_pending)
                notifyActionChanged(findActionPositionById(ACTION_REFRESH))
            }
            ACTION_BUFFER -> {
                // Cycle through buffer modes: Default → 5s → 10s → Default
                viewLifecycleOwner.lifecycleScope.launch {
                    val current = settingsRepository.getBufferMode()
                    val next = when (current) {
                        SettingsRepository.BUFFER_DEFAULT -> SettingsRepository.BUFFER_5S
                        SettingsRepository.BUFFER_5S -> SettingsRepository.BUFFER_10S
                        else -> SettingsRepository.BUFFER_DEFAULT
                    }
                    settingsRepository.setBufferMode(next)
                    findActionById(ACTION_BUFFER)?.description = getBufferDescription(next)
                    notifyActionChanged(findActionPositionById(ACTION_BUFFER))
                    Toast.makeText(context, getString(R.string.buffer_applied), Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_CHECK_UPDATE -> {
                checkForUpdate()
            }
        }
    }

    private fun checkForUpdate() {
        val updateAction = findActionById(ACTION_CHECK_UPDATE) ?: return
        updateAction.description = getString(R.string.update_checking)
        notifyActionChanged(findActionPositionById(ACTION_CHECK_UPDATE))

        viewLifecycleOwner.lifecycleScope.launch {
            val updateManager = UpdateManager(requireContext())
            val updateInfo = updateManager.checkForUpdate()

            if (updateInfo != null) {
                updateAction.description = getString(R.string.update_version, updateInfo.versionName, updateManager.getCurrentVersionName())
                notifyActionChanged(findActionPositionById(ACTION_CHECK_UPDATE))

                // Show update dialog
                showUpdateDialog(updateManager, updateInfo)
            } else {
                updateAction.description = getString(R.string.update_up_to_date, updateManager.getCurrentVersionName())
                notifyActionChanged(findActionPositionById(ACTION_CHECK_UPDATE))
            }
        }
    }

    private fun showUpdateDialog(updateManager: UpdateManager, updateInfo: com.iptvplayer.tv.data.update.UpdateInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)

        val currentVersion = updateManager.getCurrentVersionName()
        dialogView.findViewById<android.widget.TextView>(R.id.update_version).text =
            getString(R.string.update_version, updateInfo.versionName, currentVersion)

        val notesView = dialogView.findViewById<android.widget.TextView>(R.id.update_notes)
        if (updateInfo.releaseNotes.isNotBlank()) {
            notesView.text = updateInfo.releaseNotes
            notesView.visibility = View.VISIBLE
        } else {
            notesView.visibility = View.GONE
        }

        val dialog = android.app.AlertDialog.Builder(requireContext(), R.style.UpdateDialogTheme)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btn_update_later).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btn_update_now).setOnClickListener {
            // Check install permission first
            if (!updateManager.canInstallPackages()) {
                dialog.dismiss()
                Toast.makeText(context, "Veuillez autoriser l'installation depuis cette application", Toast.LENGTH_LONG).show()
                updateManager.openInstallPermissionSettings()
                return@setOnClickListener
            }

            // Disable buttons and show progress
            val btnUpdate = dialogView.findViewById<android.widget.Button>(R.id.btn_update_now)
            val btnLater = dialogView.findViewById<android.widget.Button>(R.id.btn_update_later)
            btnUpdate.isEnabled = false
            btnUpdate.text = "Telechargement 0%"
            btnLater.isEnabled = false
            dialog.setCancelable(false)

            viewLifecycleOwner.lifecycleScope.launch {
                updateManager.downloadAndInstall(
                    updateInfo,
                    onProgress = { progress ->
                        btnUpdate.text = "Telechargement $progress%"
                    },
                    onError = { error ->
                        dialog.dismiss()
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    },
                    onDownloadComplete = {
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.show()
        dialogView.findViewById<android.widget.Button>(R.id.btn_update_now).requestFocus()
    }

    private fun getLastRefreshDescription(): String {
        ContentCache.init(requireContext())
        val lastRefresh = ContentCache.getLastRefreshTime()
        return if (lastRefresh > 0) {
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            "Dernier: ${dateFormat.format(Date(lastRefresh))}"
        } else {
            "Jamais actualisé"
        }
    }

    private fun getBufferDescription(mode: Int): String {
        return when (mode) {
            SettingsRepository.BUFFER_5S -> getString(R.string.buffer_5s)
            SettingsRepository.BUFFER_10S -> getString(R.string.buffer_10s)
            else -> getString(R.string.buffer_default)
        }
    }

    companion object {
        private const val ACTION_ACCOUNT = 1L
        private const val ACTION_ADD_ACCOUNT = 2L
        private const val ACTION_LOGOUT = 3L
        private const val ACTION_REFRESH = 4L
        private const val ACTION_BUFFER = 6L
        private const val ACTION_CHECK_UPDATE = 7L
        private const val ACTION_VERSION = 5L
    }
}
