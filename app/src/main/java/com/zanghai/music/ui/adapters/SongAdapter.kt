package com.zanghai.music.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.zanghai.music.R
import com.zanghai.music.data.model.Song
import com.zanghai.music.databinding.ItemSongBinding
import java.util.concurrent.TimeUnit

class SongAdapter(
    private val onItemClick: (Song) -> Unit,
    private val onItemLongClick: ((Song) -> Unit)? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SongViewHolder(binding, onItemClick, onItemLongClick)
    }
    
    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }
    
    class SongViewHolder(
        private val binding: ItemSongBinding,
        private val onItemClick: (Song) -> Unit,
        private val onItemLongClick: ((Song) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(song: Song, position: Int) {
            binding.textNumber.text = (position + 1).toString()
            binding.textTitle.text = song.title
            binding.textArtist.text = song.artist
            val context = binding.root.context
            if (context is AppCompatActivity && !context.isDestroyed && !context.isFinishing) {
                Glide.with(context)
                    .load(song.albumArt)
                    .placeholder(R.drawable.ic_play)
                    .error(R.drawable.ic_play)
                    .fallback(R.drawable.ic_play)
                    .centerCrop()
                    .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(12))
                    .into(binding.imageAlbum)
            } else {
                Glide.with(context.applicationContext)
                    .load(song.albumArt)
                    .placeholder(R.drawable.ic_play)
                    .error(R.drawable.ic_play)
                    .fallback(R.drawable.ic_play)
                    .centerCrop()
                    .into(binding.imageAlbum)
            }
            
            binding.root.setOnClickListener {
                onItemClick(song)
            }
            
            onItemLongClick?.let { listener ->
                binding.root.setOnLongClickListener {
                    listener(song)
                    true
                }
            }
        }
    }
    
    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song) = 
            oldItem.id == newItem.id
        
        override fun areContentsTheSame(oldItem: Song, newItem: Song) = 
            oldItem == newItem
    }
}