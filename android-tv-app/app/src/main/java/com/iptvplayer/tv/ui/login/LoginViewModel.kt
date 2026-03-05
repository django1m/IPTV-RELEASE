package com.iptvplayer.tv.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptvplayer.tv.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: AccountRepository
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    val hasAccount = repository.accounts.map { it.isNotEmpty() }

    suspend fun checkHasAccountSync(): Boolean {
        return repository.accounts.first().isNotEmpty()
    }

    fun login(name: String, serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading

            val result = repository.addAccount(
                name = name,
                serverUrl = serverUrl,
                username = username,
                password = password
            )

            _loginState.value = result.fold(
                onSuccess = { LoginState.Success },
                onFailure = { LoginState.Error(it.message ?: "Unknown error") }
            )
        }
    }
}
