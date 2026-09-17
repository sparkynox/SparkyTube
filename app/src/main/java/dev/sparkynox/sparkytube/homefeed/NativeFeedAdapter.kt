package dev.sparkynox.sparkytube.homefeed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import dev.sparkynox.sparkytube.R

/**
 * RecyclerView adapter for the native Home feed. Supports infinite
 * scroll via appendItems() (see loadMoreNativeFeed in MainActivity) on
 * top of the original single-batch submitItems().
 */
class NativeFeedAdapter(
    private val onItemClick: (HomeFeedItem) -> Unit
) : RecyclerView.Adapter<NativeFeedAdapter.ViewHolder>() {

    private val items = mutableListOf<HomeFeedItem>()

    fun submitItems(newItems: List<HomeFeedItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * Appends a new page of items without disturbing the existing rows
     * (submitItems' notifyDataSetChanged would re-bind everything,
     * including re-loading every thumbnail already on screen). Used by
     * infinite-scroll pagination -- see loadMoreNativeFeed in
     * MainActivity.
     */
    fun appendItems(newItems: List<HomeFeedItem>) {
        val startPosition = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(startPosition, newItems.size)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnailImage)
        val duration: TextView = view.findViewById(R.id.durationText)
        val playlistBadge: TextView = view.findViewById(R.id.playlistBadgeText)
        val channelAvatar: ImageView = view.findViewById(R.id.channelAvatarImage)
        val title: TextView = view.findViewById(R.id.titleText)
        val meta: TextView = view.findViewById(R.id.metaText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.native_feed_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.meta.text = listOf(item.channelName, item.viewCountText)
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        // Mix/Playlist cards show an item-count badge instead of a plain
        // duration (they don't have a single duration the way a video
        // does) -- only one of the two badges is ever visible at once.
        if (item.itemCountText != null) {
            holder.playlistBadge.visibility = View.VISIBLE
            holder.playlistBadge.text = item.itemCountText
            holder.duration.visibility = View.GONE
        } else if (item.durationText.isNotBlank()) {
            holder.duration.visibility = View.VISIBLE
            holder.duration.text = item.durationText
            holder.playlistBadge.visibility = View.GONE
        } else {
            holder.duration.visibility = View.GONE
            holder.playlistBadge.visibility = View.GONE
        }

        holder.thumbnail.load(item.thumbnailUrl) {
            crossfade(true)
        }
        if (!item.channelAvatarUrl.isNullOrBlank()) {
            holder.channelAvatar.load(item.channelAvatarUrl) {
                crossfade(true)
            }
        } else {
            holder.channelAvatar.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
