package com.zanghai.music.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

private val Context.dataStore by preferencesDataStore("playback_settings")

class PlaybackSettings(private val context: Context) {
    
    companion object {
        private val PLAYBACK_MODE_KEY = intPreferencesKey("playback_mode")
    }

    suspend fun savePlaybackMode(mode: Int) {
        context.dataStore.edit { preferences ->
            preferences[PLAYBACK_MODE_KEY] = mode
        }
    }
    
    val playbackMode: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[PLAYBACK_MODE_KEY] ?: 1
        }

    suspend fun getPlaybackMode(): Int {
        val preferences = context.dataStore.data.first()
        return preferences[PLAYBACK_MODE_KEY] ?: 1
    }

    suspend fun getPlaybackModeSafe(): Int {
        val preferences = context.dataStore.data.firstOrNull()
        return preferences?.get(PLAYBACK_MODE_KEY) ?: 1
    }
}