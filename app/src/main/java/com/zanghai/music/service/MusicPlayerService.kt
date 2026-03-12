package com.zanghai.music.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media.app.NotificationCompat.MediaStyle
import com.zanghai.music.R
import com.zanghai.music.data.model.Song
import com.zanghai.music.ui.activities.MainActivity
import android.util.Log
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.support.v4.media.session.MediaSessionCompat
import android.widget.RemoteViews
import android.graphics.Color

class MusicPlayerService : Service() {
    
    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private var isPlaying = false
    private var playlist: List<Song> = listOf()
    private var currentIndex: Int = -1
    
    private var repeatMode = 0
    private var shuffleMode = false
    private var originalPlaylist: List<Song> = listOf()
    private var shuffledPlaylist: List<Song> = listOf()
    
    private var isProcessingAction = false
    private val actionLock = Any()
    
    private var listener: PlaybackListener? = null
        private var mediaSession: MediaSessionCompat? = null
    
    private lateinit var renderScript: RenderScript
    private var blurScript: ScriptIntrinsicBlur? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && mediaPlayer != null) {
                updateNotification()
                handler.postDelayed(this, 1000)
            }
        }
    }
    
    inner class MusicBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }
    
    override fun onCreate() {
        super.onCreate()
        renderScript = RenderScript.create(this)
        blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
        
        mediaSession = MediaSessionCompat(this, "MusicPlayerService")
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }
    
    fun setListener(listener: PlaybackListener) {
        this.listener = listener
    }
    
    fun getRepeatMode(): Int = repeatMode
    fun getShuffleMode(): Boolean = shuffleMode
    
    fun setRepeatMode(mode: Int) {        repeatMode = mode
    }
    
    fun setShuffleMode(shuffle: Boolean) {
        shuffleMode = shuffle
        if (shuffle) {
            shuffledPlaylist = originalPlaylist.shuffled()
            currentSong?.let { song ->
                currentIndex = shuffledPlaylist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            }
            playlist = shuffledPlaylist
        } else {
            playlist = originalPlaylist
            currentSong?.let { song ->
                currentIndex = originalPlaylist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            }
        }
    }
    
    fun playSong(song: Song, songList: List<Song>, index: Int) {
        originalPlaylist = songList
        playlist = if (shuffleMode) shuffledPlaylist else originalPlaylist
        currentIndex = index
        playSong(song)
    }
    
    private fun playSong(song: Song) {
        Log.d("MusicPlayerService", "playSong: ${song.title}, index: $currentIndex")
        
        currentSong = song
        
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        mediaPlayer = MediaPlayer().apply {
            setDataSource(song.path)
            prepare()
            start()
            
            setOnCompletionListener {
                next()
            }
        }
        
        isPlaying = true
        listener?.onSongChanged(song)
        listener?.onPlaybackStateChanged(true)
        
        handler.post(progressRunnable)
        startForeground(NOTIFICATION_ID, createNotification())
    }
    
    fun play() {
        if (mediaPlayer == null && currentSong != null) {
            currentSong?.let { playSong(it) }
        } else {
            mediaPlayer?.start()
            isPlaying = true
            listener?.onPlaybackStateChanged(true)
            handler.post(progressRunnable)
            updateNotification()
        }
    }
    
    fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
        listener?.onPlaybackStateChanged(false)
        handler.removeCallbacks(progressRunnable)
        updateNotification()
    }
    
    fun next() {
        synchronized(actionLock) {
            if (isProcessingAction) {
                Log.d("MusicPlayerService", "Next skipped - already processing")
                return
            }
            isProcessingAction = true
        }
        
        Log.d("MusicPlayerService", "next() called, repeatMode=$repeatMode, currentIndex=$currentIndex")
        
        when (repeatMode) {
            0 -> {
                if (currentIndex < playlist.size - 1) {
                    currentIndex++
                    playSong(playlist[currentIndex])
                } else {
                    pause()
                    seekTo(0)
                }
            }
            1 -> {
                currentIndex = (currentIndex + 1) % playlist.size
                playSong(playlist[currentIndex])            }
            2 -> {
                seekTo(0)
                play()
            }
        }
        
        handler.postDelayed({
            synchronized(actionLock) {
                isProcessingAction = false
            }
        }, 300)
    }
    
    fun previous() {
        synchronized(actionLock) {
            if (isProcessingAction) {
                Log.d("MusicPlayerService", "Previous skipped - already processing")
                return
            }
            isProcessingAction = true
        }
        
        Log.d("MusicPlayerService", "previous() called, repeatMode=$repeatMode, currentIndex=$currentIndex")
        
        when (repeatMode) {
            2 -> {
                seekTo(0)
                play()
            }
            else -> {
                if (currentIndex > 0) {
                    currentIndex--
                    playSong(playlist[currentIndex])
                } else {
                    if (repeatMode == 1) {
                        currentIndex = playlist.size - 1
                        playSong(playlist[currentIndex])
                    } else {
                        seekTo(0)
                        play()
                    }
                }
            }
        }
        
        handler.postDelayed({
            synchronized(actionLock) {
                isProcessingAction = false
            }        }, 300)
    }
    
    fun isPlaying(): Boolean = isPlaying
    
    fun getCurrentSong(): Song? = currentSong
    
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    
    fun getCurrentIndex(): Int = currentIndex
    
    fun getPlaylist(): List<Song> = playlist
    
    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        listener?.onProgressUpdated(position, mediaPlayer?.duration ?: 0)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zanghai Music Player"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun getBlurredAlbumArt(): Bitmap? {
        val song = currentSong ?: return null
        val uri = song.albumArt ?: return null
        
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            originalBitmap?.let { blurBitmap(it, 25f) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun blurBitmap(bitmap: Bitmap, radius: Float): Bitmap {        val scaleFactor = 0.5f
        val width = (bitmap.width * scaleFactor).toInt()
        val height = (bitmap.height * scaleFactor).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        
        val outputBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, scaledBitmap.config)
        
        val input = Allocation.createFromBitmap(renderScript, scaledBitmap)
        val output = Allocation.createFromBitmap(renderScript, outputBitmap)
        
        blurScript?.setRadius(radius)
        blurScript?.setInput(input)
        blurScript?.forEach(output)
        output.copyTo(outputBitmap)
        
        return outputBitmap
    }

    private fun createNotification(): Notification {
        val playIntent = Intent(this, MusicPlayerService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPendingIntent = PendingIntent.getService(
            this, 0, playIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val nextIntent = Intent(this, MusicPlayerService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this, 1, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val prevIntent = Intent(this, MusicPlayerService::class.java).apply { action = ACTION_PREV }
        val prevPendingIntent = PendingIntent.getService(
            this, 2, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val playIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val largeIcon = getBlurredAlbumArt()
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentSong?.title)            .setContentText(currentSong?.artist)
            .setSmallIcon(R.drawable.ic_play)
            .setLargeIcon(largeIcon)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(contentPendingIntent)
            .addAction(R.drawable.ic_previous, "Previous", prevPendingIntent)
            .addAction(playIcon, if (isPlaying) "Pause" else "Play", playPendingIntent)
            .addAction(R.drawable.ic_next, "Next", nextPendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSession?.sessionToken))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColor(Color.parseColor("#FF6200EE"))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }
    
    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification())
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onDestroy() {
        handler.removeCallbacks(progressRunnable)
        mediaPlayer?.release()
        mediaSession?.release()
        renderScript.destroy()
        super.onDestroy()
    }
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "music_player_channel"
        const val ACTION_PLAY = "com.zanghai.music.ACTION_PLAY"
        const val ACTION_PAUSE = "com.zanghai.music.ACTION_PAUSE"
        const val ACTION_NEXT = "com.zanghai.music.ACTION_NEXT"
        const val ACTION_PREV = "com.zanghai.music.ACTION_PREV"
        const val ACTION_STOP = "com.zanghai.music.ACTION_STOP"
    }
}

interface PlaybackListener {
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onSongChanged(song: Song)
    fun onProgressUpdated(currentPosition: Int, duration: Int)
}