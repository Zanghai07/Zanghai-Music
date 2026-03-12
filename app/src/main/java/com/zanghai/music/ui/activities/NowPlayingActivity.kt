package com.zanghai.music.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.zanghai.music.R
import com.zanghai.music.data.model.Song
import com.zanghai.music.databinding.ActivityNowPlayingBinding
import com.zanghai.music.service.MusicPlayerService
import com.zanghai.music.service.PlaybackListener
import com.zanghai.music.data.settings.PlaybackSettings
import java.util.concurrent.TimeUnit
import android.view.View
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.os.Build
import kotlinx.coroutines.launch
import android.widget.ImageView

class NowPlayingActivity : AppCompatActivity() {

    private var seekBarAnimator: ValueAnimator? = null
    private lateinit var renderScript: RenderScript
    private var blurScript: ScriptIntrinsicBlur? = null

    private lateinit var binding: ActivityNowPlayingBinding
    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    
    private var playbackMode = 1
    private lateinit var playbackSettings: PlaybackSettings
    private var currentSongId: Long = -1
    
    private lateinit var audioManager: AudioManager
    private var maxVolume: Int = 0
    private var isVolumeChanging = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                if (!isVolumeChanging) {
                    updateVolumeSeekBar()
                }
            }
        }
    }

    private val playbackListener = object : PlaybackListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread {
                updatePlayPauseIcon()
            }
        }

        override fun onSongChanged(song: Song) {
            runOnUiThread {
                updateSongInfo(song)
                updatePlayPauseIcon()
                loadBlurBackground(song.albumArt)
            }
        }

        override fun onProgressUpdated(currentPosition: Int, duration: Int) {
        }
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            musicService?.let { service ->
                if (!isSeeking) {
                    val position = service.getCurrentPosition()
                    var duration = service.getDuration()
                    if (duration > 0) {
                        val targetProgress = (position.toFloat() / duration * 1000).toInt()
                        seekBarAnimator?.cancel()
                        seekBarAnimator = ValueAnimator.ofInt(binding.seekBar.progress, targetProgress).apply {
                            duration = 400
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                binding.seekBar.progress = anim.animatedValue as Int
                            }
                            start()
                        }

                        binding.currentTime.text = formatDuration(position.toLong())
                    }
                }
                checkForSongChange()
            }
            handler.postDelayed(this, 500)
        }
    }
    
    private fun checkForSongChange() {
        val currentSong = musicService?.getCurrentSong()
        if (currentSong != null && currentSong.id != currentSongId) {
            currentSongId = currentSong.id
            updateSongInfo(currentSong)
            updatePlayPauseIcon()
            loadBlurBackground(currentSong.albumArt)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlayerService.MusicBinder
            musicService = binder.getService()
            
            musicService?.setListener(playbackListener)
            
            isServiceBound = true
            syncPlaybackModeFromService()
            
            musicService?.getCurrentSong()?.let { song ->
                updateSongInfo(song)
                updatePlayPauseIcon()
                loadBlurBackground(song.albumArt)
            }
            currentSongId = musicService?.getCurrentSong()?.id ?: -1
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
        }    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playbackSettings = PlaybackSettings(this)
        
        lifecycleScope.launch {
            playbackSettings.playbackMode.collect { savedMode ->
                playbackMode = savedMode
                updatePlaybackModeIcon()
            }
        }

        renderScript = RenderScript.create(this)
        blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        Intent(this, MusicPlayerService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }

        setupViews()
        setupListeners()
        updatePlaybackModeIcon()
        
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    private fun setupViews() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && musicService != null) {                    val duration = musicService!!.getDuration()
                    val position = (progress / 1000f) * duration
                    binding.currentTime.text = formatDuration(position.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                seekBar?.let {
                    musicService?.let { service ->
                        val duration = service.getDuration()
                        val position = (it.progress / 1000f) * duration
                        service.seekTo(position.toInt())
                    }
                }
            }
        })
        
        setupVolumeControl()
    }
    
    private fun setupVolumeControl() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = (currentVolume * 100) / maxVolume
        binding.volumeSeekBar.progress = volumePercent
        
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isVolumeChanging = true
                    val newVolume = (progress * maxVolume) / 100
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isVolumeChanging = true
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isVolumeChanging = false
            }
        })
        
        binding.btnVolumeLow.setOnClickListener {
            isVolumeChanging = true
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            binding.volumeSeekBar.progress = 0
            isVolumeChanging = false
        }
        
        binding.btnVolumeHigh.setOnClickListener {
            isVolumeChanging = true
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            binding.volumeSeekBar.progress = 100
            isVolumeChanging = false
        }
    }
    
    private fun updateVolumeSeekBar() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = (currentVolume * 100) / maxVolume
        binding.volumeSeekBar.progress = volumePercent
    }

    private fun loadBlurBackground(albumArtUri: android.net.Uri?) {
        Glide.with(this)
            .asBitmap()
            .load(albumArtUri)
            .placeholder(R.drawable.z_hai_rect)
            .error(R.drawable.z_hai_rect)
            .fallback(R.drawable.z_hai_rect)
            .centerCrop()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val blurredBitmap = superBlurBitmap(resource)
                    
                    val drawable = BitmapDrawable(resources, blurredBitmap)
                    drawable.alpha = 0
                    
                    binding.root.background = drawable
                    
                    ValueAnimator.ofInt(0, 180).apply {
                        duration = 500
                        addUpdateListener {
                            binding.root.background.alpha = it.animatedValue as Int
                        }
                        start()
                    }
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    binding.root.setBackgroundColor(0xFF121212.toInt())
                }
            })
    }
    private fun superBlurBitmap(bitmap: Bitmap): Bitmap {
        val scaleFactor = 0.1f
        val smallWidth = (bitmap.width * scaleFactor).toInt()
        val smallHeight = (bitmap.height * scaleFactor).toInt()
        val smallBitmap = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
        
        val blurredSmall = Bitmap.createBitmap(smallWidth, smallHeight, smallBitmap.config)
        val input = Allocation.createFromBitmap(renderScript, smallBitmap)
        val output = Allocation.createFromBitmap(renderScript, blurredSmall)
        
        blurScript?.setRadius(25f)
        blurScript?.setInput(input)
        blurScript?.forEach(output)
        output.copyTo(blurredSmall)
        
        val blurredSmall2 = Bitmap.createBitmap(smallWidth, smallHeight, smallBitmap.config)
        val input2 = Allocation.createFromBitmap(renderScript, blurredSmall)
        val output2 = Allocation.createFromBitmap(renderScript, blurredSmall2)
        
        blurScript?.setInput(input2)
        blurScript?.forEach(output2)
        output2.copyTo(blurredSmall2)
        
        return Bitmap.createScaledBitmap(blurredSmall2, bitmap.width, bitmap.height, true)
    }

    private fun setupListeners() {
        binding.btnPlayPause.setOnClickListener {
            musicService?.let { service ->
                if (service.isPlaying()) {
                    service.pause()
                } else {
                    service.play()
                }
            }
        }

        binding.btnPrev.setOnClickListener {
            musicService?.previous()
        }

        binding.btnNext.setOnClickListener {
            musicService?.next()
        }
        
        binding.btnLyrics.setOnClickListener {
            val currentSong = musicService?.getCurrentSong()
            if (currentSong != null) {
                val intent = Intent(this, LyricsActivity::class.java).apply {                    putExtra("extra_artist", currentSong.artist)
                    putExtra("extra_title", currentSong.title)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "No song playing", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.btnPlaybackMode.setOnClickListener {
            playbackMode = (playbackMode + 1) % 3
            updatePlaybackModeIcon()
            updateServicePlaybackMode()
            
            lifecycleScope.launch {
                playbackSettings.savePlaybackMode(playbackMode)
            }
            
            val message = when (playbackMode) {
                0 -> "Shuffle On"
                1 -> "Repeat List"
                2 -> "Repeat Track"
                else -> ""
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateServicePlaybackMode() {
        when (playbackMode) {
            0 -> {
                musicService?.setShuffleMode(true)
                musicService?.setRepeatMode(0)
            }
            1 -> {
                musicService?.setShuffleMode(false)
                musicService?.setRepeatMode(1)
            }
            2 -> {
                musicService?.setShuffleMode(false)
                musicService?.setRepeatMode(2)
            }
        }
    }
    
    private fun syncPlaybackModeFromService() {
        try {
            val shuffle = musicService?.getShuffleMode() ?: false
            val repeat = musicService?.getRepeatMode() ?: 1
                        val serviceMode = when {
                shuffle -> 0
                repeat == 1 -> 1
                repeat == 2 -> 2
                else -> 1
            }
            
            if (playbackMode != serviceMode) {
                playbackMode = serviceMode
                updatePlaybackModeIcon()
            }
        } catch (e: Exception) {
        }
    }
    
    private fun updatePlaybackModeIcon() {
        val imageView = binding.btnPlaybackMode.getChildAt(0) as? ImageView
        
        when (playbackMode) {
            0 -> {
                imageView?.setImageResource(R.drawable.shuffle)
            }
            1 -> {
                imageView?.setImageResource(R.drawable.repeat_list)
            }
            2 -> {
                imageView?.setImageResource(R.drawable.repeat_track)
            }
        }
    }

    private fun updateSongInfo(song: Song) {
        if (isDestroyed || isFinishing) return
        
        currentSongId = song.id
        binding.upperArtist.text = song.artist.uppercase()
        binding.upperTitle.text = song.title.uppercase()
        binding.songTitle.text = song.title
        binding.songArtist.text = song.artist
        binding.songAlbum.text = song.album ?: song.title
        binding.totalTime.text = formatDuration(song.duration)

        if (!isDestroyed && !isFinishing) {
            Glide.with(this@NowPlayingActivity)
                .load(song.albumArt)
                .placeholder(R.drawable.z_hai_rect)
                .error(R.drawable.z_hai_rect)
                .fallback(R.drawable.z_hai_rect)
                .centerCrop()
                .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(12))                .into(binding.albumArt)
        }
    }

    private fun updatePlayPauseIcon() {
        musicService?.let { service ->
            val icon = if (service.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
            binding.btnPlayPause.setImageResource(icon)
        }
    }

    private fun formatDuration(duration: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) -
                TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onResume() {
        super.onResume()
        musicService?.getCurrentSong()?.let { song ->
            updateSongInfo(song)
            updatePlayPauseIcon()
            loadBlurBackground(song.albumArt)
        }
        updateServicePlaybackMode()
        updateVolumeSeekBar()
        handler.post(updateSeekBar)
    }

    override fun onPause() {
        super.onPause()
        seekBarAnimator?.cancel()
        handler.removeCallbacks(updateSeekBar)
    }

    override fun onDestroy() {
        super.onDestroy()
        seekBarAnimator?.cancel()
        handler.removeCallbacks(updateSeekBar)
        if (isServiceBound) unbindService(connection)
        try { 
            unregisterReceiver(volumeReceiver) 
            renderScript.destroy()
        } catch (e: Exception) {}
    }
}