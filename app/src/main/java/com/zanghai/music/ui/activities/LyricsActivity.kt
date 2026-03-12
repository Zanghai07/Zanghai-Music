package com.zanghai.music.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.zanghai.music.R
import com.zanghai.music.data.cache.LyricsCacheManager
import com.zanghai.music.data.model.LyricsLine
import com.zanghai.music.databinding.ActivityLyricsBinding
import com.zanghai.music.service.MusicPlayerService
import com.zanghai.music.data.remote.LrcLibApi
import com.zanghai.music.utils.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.os.Build
import android.graphics.Color
import com.dirror.lyricviewx.LyricViewX

class LyricsActivity : AppCompatActivity() {

    private lateinit var lyricsCache: LyricsCacheManager  
    private lateinit var binding: ActivityLyricsBinding  
    private lateinit var lyricViewX: LyricViewX  
    private val lrcLibApi = LrcLibApi.create()  
    private lateinit var renderScript: RenderScript  
    private var blurScript: ScriptIntrinsicBlur? = null  
    private var allLyricsLines = listOf<LyricsLine>()  
    private var currentIndex = 0  
    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false  
    private var currentSongId: Long = -1  
    private val frameHandler = Handler(Looper.getMainLooper())  
  
    private val updateRunnable = object : Runnable {  
        override fun run() {  
            musicService?.getCurrentPosition()?.let { pos ->  
                val posLong = pos.toLong()  
                  
                lyricViewX.updateTime(posLong)  
                  
                for (i in allLyricsLines.indices) {  
                    if (posLong >= allLyricsLines[i].timestamp) {  
                        currentIndex = i  
                    } else {  
                        break  
                    }  
                }  
            }  
            updateNowPlayingInfo()  
            frameHandler.postDelayed(this, 12)  
        }  
    }  

    private val memoryCache = mutableMapOf<String, List<LyricsLine>>()  

    override fun onCreate(savedInstanceState: Bundle?) {  
        super.onCreate(savedInstanceState)  
        binding = ActivityLyricsBinding.inflate(layoutInflater)  
        setContentView(binding.root)  

        lyricViewX = binding.lyricsView  
        renderScript = RenderScript.create(this)  
        blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))  
        lyricsCache = LyricsCacheManager(this)  

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN  
        window.statusBarColor = Color.TRANSPARENT  
        window.navigationBarColor = Color.TRANSPARENT  
      
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  
            window.setDecorFitsSystemWindows(false)  
        } else {  
            window.decorView.systemUiVisibility = (  
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or  
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN  
            )  
        }  

        setupLyricsView()  
        Intent(this, MusicPlayerService::class.java).also { intent ->  
            bindService(intent, connection, Context.BIND_AUTO_CREATE)  
        }  

        val artist = intent.getStringExtra("extra_artist") ?: ""  
        val title = intent.getStringExtra("extra_title") ?: ""  
        loadLyrics(artist, title)  
    }  

    private fun setupLyricsView() {  
        val avenirFont = ResourcesCompat.getFont(this, R.font.avenir_semi)  
        avenirFont?.let {  
            lyricViewX.setLyricTypeface(it)  
        }  
      
        lyricViewX.setNormalColor(Color.parseColor("#CCFFFFFF"))  
        lyricViewX.setCurrentColor(Color.WHITE)  
        lyricViewX.setNormalTextSize(44f)  
        lyricViewX.setCurrentTextSize(54f)  
        lyricViewX.setTimelineColor(Color.parseColor("#FF6200EE"))  
        lyricViewX.setTimelineTextColor(Color.WHITE)  
      
        lyricViewX.setDraggable(false, null)  
    }  

    private fun loadLyrics(artist: String, title: String) {  
        val key = "${artist.trim()}_${title.trim()}".replace(" ", "_").lowercase()  

        memoryCache[key]?.let { cachedLyrics ->  
            allLyricsLines = cachedLyrics  
            showLyrics(cachedLyrics)  
            return  
        }  

        binding.progressBar.visibility = View.VISIBLE  
        binding.tvError.visibility = View.GONE  

        lifecycleScope.launch {  
            val cachedLyrics = withContext(Dispatchers.IO) { lyricsCache.getLyrics(artist, title) }  
          
            if (cachedLyrics != null) {  
                allLyricsLines = cachedLyrics  
                memoryCache[key] = cachedLyrics  
                showLyrics(cachedLyrics)  
                binding.progressBar.visibility = View.GONE  
            } else {  
                try {  
                    val response = withContext(Dispatchers.IO) { lrcLibApi.getLyrics(artist, title) }  
                    response.syncedLyrics?.let {                          val parsedLyrics = LrcParser.parse(it)  
                        allLyricsLines = parsedLyrics  

                        withContext(Dispatchers.IO) {  
                            lyricsCache.saveLyrics(artist, title, parsedLyrics)  
                        }  

                        memoryCache[key] = parsedLyrics  
                        showLyrics(parsedLyrics)  
                    } ?: run {  
                        binding.tvError.visibility = View.VISIBLE  
                        binding.tvError.text = "Lyrics not found"  
                    }  
                } catch (e: Exception) {  
                    binding.tvError.visibility = View.VISIBLE  
                    binding.tvError.text = "Error: ${e.message}"  
                } finally {  
                    binding.progressBar.visibility = View.GONE  
                }  
            }  
        }  
    }  

    private fun showLyrics(lines: List<LyricsLine>) {  
        val lrcContent = lines.joinToString("\n") {   
            val menit = it.timestamp / 60000  
            val detik = (it.timestamp % 60000) / 1000  
            val mili = (it.timestamp % 1000) / 10  
            "[%02d:%02d.%02d]%s".format(menit, detik, mili, it.text)  
        }  
      
        lyricViewX.loadLyric(lrcContent)  
      
        binding.lyricsView.post {  
            lyricViewX.updateTime(0)  
        }  
      
        binding.lyricsView.visibility = View.VISIBLE  
        binding.progressBar.visibility = View.GONE  
    }  

    private fun updateNowPlayingInfo() {  
        if (isDestroyed || isFinishing) return  

        val currentSong = musicService?.getCurrentSong()  
        currentSong?.let {  
            if (currentSong.id != currentSongId) {  
                currentSongId = currentSong.id  
                loadBlurBackground(it.albumArt)  
            }            
            binding.nowPlayingTitle.text = it.title  
            binding.nowPlayingArtist.text = it.artist  

            Glide.with(this)  
                .load(it.albumArt)  
                .placeholder(R.drawable.z_hai_rect)  
                .error(R.drawable.z_hai_rect)  
                .fallback(R.drawable.z_hai_rect)  
                .centerCrop()  
                .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(12))  
                .into(binding.nowPlayingImage)  
        }  
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
                        interpolator = DecelerateInterpolator()  
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

    private val connection = object : ServiceConnection {  
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {  
            val binder = service as MusicPlayerService.MusicBinder  
            musicService = binder.getService()  
            isServiceBound = true  
            musicService?.getCurrentSong()?.let { song ->  
                currentSongId = song.id  
                loadBlurBackground(song.albumArt)  
            }  
            frameHandler.post(updateRunnable)  
        }  

        override fun onServiceDisconnected(name: ComponentName?) {  
            isServiceBound = false  
        }  
    }  

    override fun onResume() {  
        super.onResume()  
        frameHandler.post(updateRunnable)  
    }  

    override fun onPause() {  
        super.onPause()  
        frameHandler.removeCallbacks(updateRunnable)  
    }  
    override fun onDestroy() {  
        super.onDestroy()  
        frameHandler.removeCallbacks(updateRunnable)  
        if (isServiceBound) unbindService(connection)  
        renderScript.destroy()  
    }

}