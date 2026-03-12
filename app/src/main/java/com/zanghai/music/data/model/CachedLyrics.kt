package com.zanghai.music.data.model

data class CachedLyrics(
    val artist: String,
    val title: String,
    val lyrics: List<LyricsLine>,
    val timestamp: Long
)