package com.zanghai.music.ui.activities

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.zanghai.music.R
import com.zanghai.music.data.model.Song
import com.zanghai.music.data.repository.SongRepository
import com.zanghai.music.databinding.ActivityMainBinding
import com.zanghai.music.service.MusicPlayerService
import com.zanghai.music.service.PlaybackListener
import com.zanghai.music.ui.adapters.SongAdapter
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import android.app.ActivityManager
import android.graphics.BitmapFactory
import android.graphics.Color

class MainActivity : AppCompatActivity(), PlaybackListener {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var songAdapter: SongAdapter
    private lateinit var repository: SongRepository
    
    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false
    private var currentPlaylist: List<Song> = listOf()
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlayerService.MusicBinder
            musicService = binder.getService()
            musicService?.setListener(this@MainActivity)
            isServiceBound = true
            updateNowPlayingCard()        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val icon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            val taskDesc = ActivityManager.TaskDescription(
                null,
                icon,
                Color.TRANSPARENT
            )
            setTaskDescription(taskDesc)
        }
        
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        
        repository = SongRepository(this)
        setupRecyclerView()
        setupNowPlayingCard()
        checkPermissions()
        
        Intent(this, MusicPlayerService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (isServiceBound) {
            musicService?.setListener(this)
            updateNowPlayingCard()
        }
    }
    
    private fun setupRecyclerView() {        songAdapter = SongAdapter(
            onItemClick = { song ->
                playSong(song)
            },
            onItemLongClick = { song ->
                Toast.makeText(this, "Long click: ${song.title}", Toast.LENGTH_SHORT).show()
            }
        )
        
        binding.recyclerViewSongs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
        }
    }
    
    private fun setupNowPlayingCard() {
        binding.btnPlayPause.setOnClickListener {
            if (musicService?.isPlaying() == true) {
                musicService?.pause()
            } else {
                musicService?.play()
            }
        }
        
        binding.btnPrev.setOnClickListener {
            musicService?.previous()
        }
        
        binding.btnNext.setOnClickListener {
            musicService?.next()
        }
        
        binding.nowPlayingCard.setOnClickListener {
            val currentSong = musicService?.getCurrentSong()
            if (currentSong != null) {
                val intent = Intent(this, NowPlayingActivity::class.java)
                intent.putExtra("song", currentSong)
                startActivity(intent)
            }
        }
    }
    
    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setImageResource(icon)
    }
    
    override fun onSongChanged(song: Song) {
        binding.nowPlayingCard.visibility = View.VISIBLE
        binding.nowPlayingTitle.text = "${song.artist} - ${song.title}"
        binding.nowPlayingArtist.text = song.artist
        
        Glide.with(this)
            .load(song.albumArt)
            .placeholder(R.drawable.z_hai_rect)
            .error(R.drawable.z_hai_rect)
            .fallback(R.drawable.z_hai_rect)
            .centerCrop()
            .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(16))
            .into(binding.nowPlayingImage)
    }
    
    override fun onProgressUpdated(currentPosition: Int, duration: Int) {
    }
    
    private fun updateNowPlayingCard() {
        val currentSong = musicService?.getCurrentSong()
        if (currentSong != null) {
            onSongChanged(currentSong)
            onPlaybackStateChanged(musicService?.isPlaying() == true)
        } else {
            binding.nowPlayingCard.visibility = View.GONE
        }
    }
    
    private fun playSong(song: Song) {
        val index = currentPlaylist.indexOfFirst { it.id == song.id }
        musicService?.playSong(song, currentPlaylist, index)
    }
    
    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                repository.scanFolder("/storage/emulated/0/zanghai/")
                
                Handler(Looper.getMainLooper()).postDelayed({
                    loadSongs()
                }, 500)
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    PERMISSION_REQUEST_CODE                )
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadSongs()
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun loadSongs() {
        lifecycleScope.launch {
            repository.getAllSongs().collect { songs ->
                currentPlaylist = songs
                songAdapter.submitList(songs)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(connection)
        }
    }
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}