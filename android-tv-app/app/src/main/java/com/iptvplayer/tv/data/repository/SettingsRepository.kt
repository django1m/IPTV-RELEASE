package com.iptvplayer.tv.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val BUFFER_MODE_KEY = intPreferencesKey("buffer_mode")

        const val BUFFER_DEFAULT = 0
        const val BUFFER_5S = 1
        const val BUFFER_10S = 2
    }

    val bufferMode: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[BUFFER_MODE_KEY] ?: BUFFER_DEFAULT
    }

    suspend fun getBufferMode(): Int {
        return bufferMode.first()
    }

    suspend fun setBufferMode(mode: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[BUFFER_MODE_KEY] = mode
        }
    }
}
