package com.zanghai.music.data.repository

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.zanghai.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class SongRepository(private val context: Context) {
    
    fun getAllSongs(): Flow<List<Song>> = flow {
        val songs = getSongsFromDevice()
        emit(songs)
    }
    
    private suspend fun getSongsFromDevice(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        
        val targetPaths = listOf(
            "/storage/emulated/0/zanghai/",
            "/storage/emulated/0/Zanghai/",
            "/sdcard/zanghai/",
            "/sdcard/Zanghai/"
        ).distinct()
        
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )
        
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (" +
                targetPaths.joinToString(" OR ") { 
                    "${MediaStore.Audio.Media.DATA} LIKE ?" 
                } + ")"
        val selectionArgs = targetPaths.map { "$it%" }.toTypedArray()
        
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val album = cursor.getString(albumColumn) ?: "Unknown Album"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn) ?: ""
                val dateAdded = cursor.getLong(dateColumn)
                val albumId = cursor.getLong(albumIdColumn)
                
                Log.d("SongRepository", "Found: $title - $path")
                
                val albumArtUri = getAlbumArtUri(albumId, path)
                
                if (duration > 0) {
                    songs.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            path = path,
                            albumArt = albumArtUri,
                            dateAdded = dateAdded
                        )
                    )
                }
            }
        }        
        Log.d("SongRepository", "Total songs: ${songs.size}")
        return@withContext songs
    }
    
    private fun getAlbumArtUri(albumId: Long, songPath: String): Uri? {
        try {
            val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
            val uri = ContentUris.withAppendedId(sArtworkUri, albumId)
            context.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.count > 0) return uri
            }
        } catch (e: Exception) { }
        
        try {
            val songFile = File(songPath)
            val folder = songFile.parentFile
            val possibleNames = listOf("folder.jpg", "Folder.jpg", "cover.jpg", "Cover.jpg", "album.jpg", "Album.jpg")
            
            possibleNames.forEach { name ->
                val coverFile = File(folder, name)
                if (coverFile.exists()) {
                    return Uri.fromFile(coverFile)
                }
            }
        } catch (e: Exception) { }
        
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(songPath)
            val embeddedArt = retriever.embeddedPicture
            retriever.release()
            
            if (embeddedArt != null) {
                val tempFile = File(context.cacheDir, "cover_${albumId}.jpg")
                tempFile.writeBytes(embeddedArt)
                return Uri.fromFile(tempFile)
            }
        } catch (e: Exception) { }
        
        return null
    }
    
    fun scanFolder(path: String) {
        val folder = File(path)
        if (folder.exists() && folder.isDirectory) {
            folder.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    scanFolder(file.absolutePath)
                } else if (file.extension in listOf("mp3", "wav", "flac", "m4a", "aac", "ogg")) {                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        null,
                        null
                    )
                }
            }
        }
    }
}