package com.zanghai.music.data.cache

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.zanghai.music.data.model.CachedLyrics
import com.zanghai.music.data.model.LyricsLine
import kotlinx.coroutines.flow.firstOrNull

private val Context.dataStore by preferencesDataStore("lyrics_cache")

class LyricsCacheManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        private const val MAX_CACHE_SIZE = 50
        private val CACHE_KEYS = stringPreferencesKey("cache_keys")
    }
    suspend fun saveLyrics(artist: String, title: String, lyrics: List<LyricsLine>) {
        val key = getKey(artist, title)
        val cachedLyrics = CachedLyrics(artist, title, lyrics, System.currentTimeMillis())
        val cachedJson = gson.toJson(cachedLyrics)
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey(key)] = cachedJson
            val keysJson = preferences[CACHE_KEYS] ?: "[]"
            val keys = gson.fromJson(keysJson, Array<String>::class.java).toMutableList()
            keys.remove(key)
            keys.add(0, key) // terbaru di depan
            val trimmedKeys = keys.take(MAX_CACHE_SIZE)
            keys.drop(MAX_CACHE_SIZE).forEach { oldKey ->
                preferences.remove(stringPreferencesKey(oldKey))
            }
            preferences[CACHE_KEYS] = gson.toJson(trimmedKeys)
        }
    }

    suspend fun getLyrics(artist: String, title: String): List<LyricsLine>? {
        val key = getKey(artist, title)
        val preferences = context.dataStore.data.firstOrNull() ?: return null
        val cachedJson = preferences[stringPreferencesKey(key)] ?: return null
        return try {
            gson.fromJson(cachedJson, CachedLyrics::class.java).lyrics
        } catch (e: Exception) { null }
    }

    suspend fun isCached(artist: String, title: String): Boolean {
        return getLyrics(artist, title) != null
    }

    suspend fun removeLyrics(artist: String, title: String) {
        val key = getKey(artist, title)
        context.dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(key))
            val keysJson = preferences[CACHE_KEYS] ?: "[]"
            val keys = gson.fromJson(keysJson, Array<String>::class.java).toMutableList()
            keys.remove(key)
            preferences[CACHE_KEYS] = gson.toJson(keys)
        }
    }

    suspend fun clearAllCache() {
        context.dataStore.edit { it.clear() }
    }

    private fun getKey(artist: String, title: String): String {
        return "${artist.trim()}_${title.trim()}".replace(" ", "_").lowercase()
    }
}