package com.zanghai.music.utils

import com.zanghai.music.data.model.LyricsLine
import java.util.regex.Pattern

object LrcParser {
    
    private val LRC_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
    private val SIMPLE_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\](.*)")
    
    fun parse(syncedLyrics: String?): List<LyricsLine> {
        if (syncedLyrics.isNullOrBlank()) return emptyList()
        
        val lines = mutableListOf<LyricsLine>()
        
        syncedLyrics.lines().forEach { line ->
            var matcher = LRC_PATTERN.matcher(line)
            if (matcher.find()) {
                val minutes = matcher.group(1).toInt()
                val seconds = matcher.group(2).toInt()
                val millis = matcher.group(3).let {
                    when (it.length) {
                        2 -> it.toInt() * 10
                        3 -> it.toInt()
                        else -> 0
                    }
                }
                val text = matcher.group(4).trim()
                
                val timestamp = (minutes * 60 * 1000L) + (seconds * 1000L) + millis  // ← PAKE L
                if (text.isNotBlank()) {
                    lines.add(LyricsLine(timestamp, text))
                }
            } else {
                matcher = SIMPLE_PATTERN.matcher(line)
                if (matcher.find()) {
                    val minutes = matcher.group(1).toInt()
                    val seconds = matcher.group(2).toInt()
                    val text = matcher.group(3).trim()
                    
                    val timestamp = (minutes * 60 * 1000L) + (seconds * 1000L)  // ← PAKE L
                    if (text.isNotBlank()) {
                        lines.add(LyricsLine(timestamp, text))
                    }
                }
            }
        }
        
        return lines.sortedBy { it.timestamp }
    }
    
    fun getCurrentLineIndex(lines: List<LyricsLine>, currentPosition: Long): Int {
        if (lines.isEmpty()) return -1
        
        for (i in lines.indices.reversed()) {
            if (currentPosition >= lines[i].timestamp) {
                return i
            }
        }
        return -1
    }
}