package dev.sparkynox.sparkytube.feed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import dev.sparkynox.sparkytube.R

/**
 * Feeds a native Home/Search/Subscriptions screen. Reuses item_suggestion.xml
 * (same row shape the suggestions panel already uses) rather than a new
 * layout, since a feed card and a related-video row show the same info.
 */
class FeedAdapter(
    private val onItemClick: (videoId: String) -> Unit,
    private val onNearEnd: () -> Unit
) : RecyclerView.Adapter<FeedAdapter.ViewHolder>() {

    private val items = mutableListOf<FeedVideo>()

    fun appendItems(newItems: List<FeedVideo>) {
        // De-dupe against what's already in the list -- YouTube's own DOM
        // can occasionally hand back an item twice across scroll ticks
        // (a row that was mid-render on one scrape, fully rendered by the
        // next), and a duplicate video card is a worse experience than a
        // slightly shorter feed.
        val existingIds = items.map { it.videoId }.toHashSet()
        val deduped = newItems.filter { it.videoId !in existingIds }
        if (deduped.isEmpty()) return

        val startPosition = items.size
        items.addAll(deduped)
        notifyItemRangeInserted(startPosition, deduped.size)
    }

    fun clear() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
        // Channel name and views share one line, same as YouTube's own
        // feed cards -- "Channel Name • 1.2M views" rather than two
        // separate rows, since item_suggestion.xml only has one subtitle
        // line under the title.
        holder.uploader.text = if (item.viewsText.isNotBlank()) {
            "${item.uploaderName} • ${item.viewsText}"
        } else {
            item.uploaderName
        }
        holder.duration.text = formatDuration(item.durationSeconds)
        holder.thumbnail.load(item.thumbnailUrl) {
            crossfade(true)
        }
        holder.itemView.setOnClickListener { onItemClick(item.videoId) }

        // Trigger the next batch load a few rows before the actual end,
        // so more content is usually ready by the time the user actually
        // scrolls that far -- same idea as any infinite-scroll feed.
        if (position >= items.size - 5) {
            onNearEnd()
        }
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
