package com.iptvplayer.tv.ui.settings

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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EditAccountFragment : GuidedStepSupportFragment() {

    @Inject lateinit var repository: AccountRepository
    private var accountId: String = ""
    private var accountName: String = ""
    private var serverUrl: String = ""
    private var username: String = ""
    private var password: String = ""

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Modifier le compte",
            "Modifiez les informations Xtream Code",
            "",
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_settings)
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_NAME)
                .title("Nom")
                .description("Chargement...")
                .descriptionEditable(true)
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SERVER)
                .title("Serveur URL")
                .description("Chargement...")
                .descriptionEditable(true)
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_USERNAME)
                .title("Nom d'utilisateur")
                .description("Chargement...")
                .descriptionEditable(true)
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_PASSWORD)
                .title("Mot de passe")
                .description("Chargement...")
                .descriptionEditable(true)
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SAVE)
                .title("Sauvegarder")
                .description("Valider les modifications et rafraîchir la playlist")
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CANCEL)
                .title("Annuler")
                .description("Revenir sans modifier")
                .build()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load current account data (viewLifecycleOwner is available here)
        viewLifecycleOwner.lifecycleScope.launch {
            val account = repository.getActiveAccount() ?: return@launch
            accountId = account.id
            accountName = account.name
            serverUrl = account.serverUrl
            username = account.username
            password = account.password

            findActionById(ACTION_NAME)?.apply {
                description = accountName
                editDescription = accountName
            }
            notifyActionChanged(findActionPositionById(ACTION_NAME))

            findActionById(ACTION_SERVER)?.apply {
                description = serverUrl
                editDescription = serverUrl
            }
            notifyActionChanged(findActionPositionById(ACTION_SERVER))

            findActionById(ACTION_USERNAME)?.apply {
                description = username
                editDescription = username
            }
            notifyActionChanged(findActionPositionById(ACTION_USERNAME))

            findActionById(ACTION_PASSWORD)?.apply {
                description = "••••••••"
                editDescription = password
            }
            notifyActionChanged(findActionPositionById(ACTION_PASSWORD))
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val newValue = action.editDescription?.toString() ?: ""
        when (action.id) {
            ACTION_NAME -> {
                accountName = newValue
                action.description = newValue
            }
            ACTION_SERVER -> {
                serverUrl = newValue
                action.description = newValue
            }
            ACTION_USERNAME -> {
                username = newValue
                action.description = newValue
            }
            ACTION_PASSWORD -> {
                password = newValue
                action.description = "••••••••"
            }
        }
        notifyActionChanged(findActionPositionById(action.id))
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_SAVE -> saveAccount()
            ACTION_CANCEL -> parentFragmentManager.popBackStack()
        }
    }

    private fun saveAccount() {
        if (accountId.isEmpty() || serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            Toast.makeText(context, "Veuillez remplir tous les champs", Toast.LENGTH_SHORT).show()
            return
        }

        findActionById(ACTION_SAVE)?.apply {
            title = "Sauvegarde en cours..."
            isEnabled = false
        }
        notifyActionChanged(findActionPositionById(ACTION_SAVE))

        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.updateAccount(accountId, accountName, serverUrl, username, password)

            if (result.isSuccess) {
                ContentCache.forceRefresh()
                Toast.makeText(context, "Compte mis à jour, rechargement du contenu...", Toast.LENGTH_LONG).show()
                parentFragmentManager.popBackStack()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Erreur inconnue"
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()

                findActionById(ACTION_SAVE)?.apply {
                    title = "Sauvegarder"
                    isEnabled = true
                }
                notifyActionChanged(findActionPositionById(ACTION_SAVE))
            }
        }
    }

    companion object {
        private const val ACTION_NAME = 10L
        private const val ACTION_SERVER = 11L
        private const val ACTION_USERNAME = 12L
        private const val ACTION_PASSWORD = 13L
        private const val ACTION_SAVE = 14L
        private const val ACTION_CANCEL = 15L
    }
}
