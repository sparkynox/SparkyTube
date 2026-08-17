package dev.sparkynox.sparkytube.suggestions

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.extractor.StreamExtractor

/**
 * Shows the related-videos list fetched by StreamExtractor.fetchRelatedVideos.
 * onItemClick hands back just the video ID -- MainActivity resolves and
 * plays it the same way any other video tap does.
 */
class SuggestionsAdapter(
    private val onItemClick: (videoId: String) -> Unit
) : RecyclerView.Adapter<SuggestionsAdapter.ViewHolder>() {

    private var items: List<StreamExtractor.RelatedVideo> = emptyList()

    fun submitList(newItems: List<StreamExtractor.RelatedVideo>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.suggestionThumbnail)
        val duration: TextView = view.findViewById(R.id.suggestionDuration)
        val title: TextView = view.findViewById(R.id.suggestionTitle)
        val uploader: TextView = view.findViewById(R.id.suggestionUploader)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.uploader.text = item.uploaderName
        holder.duration.text = formatDuration(item.durationSeconds)
        holder.thumbnail.load(item.thumbnailUrl) {
            crossfade(true)
        }
        holder.itemView.setOnClickListener { onItemClick(item.videoId) }
    }

    override fun getItemCount() = items.size

    private fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return ""
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
