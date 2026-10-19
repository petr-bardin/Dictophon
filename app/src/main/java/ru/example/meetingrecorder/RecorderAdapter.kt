package ru.example.meetingrecorder

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.example.meetingrecorder.databinding.ItemRecordingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingItem(val recording: Recording, val favorite: Boolean)

class RecorderAdapter(
    private val onClick: (Recording) -> Unit,
    private val onLongClick: (Recording) -> Unit,
    private val onFavorite: (Recording) -> Unit
) : ListAdapter<RecordingItem, RecorderAdapter.VH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
    inner class VH(private val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: RecordingItem) = with(binding) {
            val item = row.recording
            tvName.text = item.title
            tvMeta.text = "${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.dateMillis))} · ${RecordingRepository.formatSize(item.sizeBytes)} · AAC"
            tvDuration.text = RecordingRepository.formatDuration(item.durationSec)
            root.contentDescription = root.context.getString(R.string.play_recording, item.title)
            root.setOnClickListener { onClick(item) }
            root.setOnLongClickListener { onLongClick(item); true }
            btnFavorite.alpha = if (row.favorite) 1f else .35f
            btnFavorite.isSelected = row.favorite
            btnFavorite.contentDescription = root.context.getString(if(row.favorite) R.string.remove_favorite else R.string.add_favorite, item.title)
            btnFavorite.setOnClickListener { onFavorite(item) }
        }
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecordingItem>() {
            override fun areItemsTheSame(old: RecordingItem, new: RecordingItem) = old.recording.uri == new.recording.uri
            override fun areContentsTheSame(old: RecordingItem, new: RecordingItem) = old == new
        }
    }
}
