package com.iptvplayer.tv.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iptvplayer.tv.data.api.XtreamClient
import com.iptvplayer.tv.data.model.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "iptv_settings")

class AccountRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val ACCOUNTS_KEY = stringPreferencesKey("accounts")
        private val ACTIVE_ACCOUNT_KEY = stringPreferencesKey("active_account_id")
    }

    val accounts: Flow<List<Account>> = context.dataStore.data.map { prefs ->
        val accountsJson = prefs[ACCOUNTS_KEY] ?: "[]"
        try {
            json.decodeFromString<List<Account>>(accountsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val activeAccountId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ACTIVE_ACCOUNT_KEY]
    }

    suspend fun getActiveAccount(): Account? {
        val accountsList = accounts.first()
        val activeId = activeAccountId.first()
        return accountsList.find { it.id == activeId } ?: accountsList.firstOrNull()
    }

    suspend fun addAccount(
        name: String,
        serverUrl: String,
        username: String,
        password: String
    ): Result<Account> {
        return try {
            // Validate credentials by attempting to authenticate
            val client = XtreamClient(serverUrl, username, password, okHttpClient)
            val authResponse = client.authenticate()

            if (authResponse.userInfo?.auth != 1) {
                return Result.failure(Exception("Authentication failed: Invalid credentials"))
            }

            val account = Account(
                id = UUID.randomUUID().toString(),
                name = name,
                serverUrl = serverUrl.trimEnd('/'),
                username = username,
                password = password
            )

            context.dataStore.edit { prefs ->
                val currentAccounts = try {
                    json.decodeFromString<List<Account>>(prefs[ACCOUNTS_KEY] ?: "[]")
                } catch (e: Exception) {
                    emptyList()
                }
                val newAccounts = currentAccounts + account
                prefs[ACCOUNTS_KEY] = json.encodeToString(newAccounts)
                prefs[ACTIVE_ACCOUNT_KEY] = account.id
            }

            Result.success(account)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAccount(accountId: String, name: String, serverUrl: String, username: String, password: String): Result<Account> {
        return try {
            // Validate new credentials
            val client = XtreamClient(serverUrl, username, password, okHttpClient)
            val authResponse = client.authenticate()

            if (authResponse.userInfo?.auth != 1) {
                return Result.failure(Exception("Authentification échouée: identifiants invalides"))
            }

            val updatedAccount = Account(
                id = accountId,
                name = name,
                serverUrl = serverUrl.trimEnd('/'),
                username = username,
                password = password
            )

            context.dataStore.edit { prefs ->
                val currentAccounts = try {
                    json.decodeFromString<List<Account>>(prefs[ACCOUNTS_KEY] ?: "[]")
                } catch (e: Exception) {
                    emptyList()
                }
                val newAccounts = currentAccounts.map { if (it.id == accountId) updatedAccount else it }
                prefs[ACCOUNTS_KEY] = json.encodeToString(newAccounts)
            }

            Result.success(updatedAccount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            val currentAccounts = try {
                json.decodeFromString<List<Account>>(prefs[ACCOUNTS_KEY] ?: "[]")
            } catch (e: Exception) {
                emptyList()
            }
            val newAccounts = currentAccounts.filter { it.id != accountId }
            prefs[ACCOUNTS_KEY] = json.encodeToString(newAccounts)

            // If we removed the active account, set a new one
            if (prefs[ACTIVE_ACCOUNT_KEY] == accountId) {
                prefs[ACTIVE_ACCOUNT_KEY] = newAccounts.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun setActiveAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_ACCOUNT_KEY] = accountId
        }
    }

    fun getClient(account: Account): XtreamClient {
        return XtreamClient(account.serverUrl, account.username, account.password, okHttpClient)
    }
}
