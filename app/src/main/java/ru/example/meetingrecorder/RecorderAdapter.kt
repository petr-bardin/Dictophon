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

class RecorderAdapter(
    private val onClick: (Recording) -> Unit,
    private val onLongClick: (Recording) -> Unit
) : ListAdapter<Recording, RecorderAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemRecordingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Recording) = with(binding) {
            tvName.text = item.title
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(Date(item.dateMillis))
            val duration = if (item.durationSec > 0) {
                " • ${RecordingRepository.formatDuration(item.durationSec)}"
            } else ""
            tvMeta.text = "$date$duration • ${RecordingRepository.formatSize(item.sizeBytes)}"

            root.setOnClickListener { onClick(item) }
            root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Recording>() {
            override fun areItemsTheSame(oldItem: Recording, newItem: Recording) =
                oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: Recording, newItem: Recording) =
                oldItem == newItem
        }
    }
}
