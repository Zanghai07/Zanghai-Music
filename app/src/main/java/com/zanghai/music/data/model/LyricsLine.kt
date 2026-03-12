package com.zanghai.music.data.model

data class LyricsLine(
    val timestamp: Long,
    val text: String,
    val isCurrent: Boolean = false
)