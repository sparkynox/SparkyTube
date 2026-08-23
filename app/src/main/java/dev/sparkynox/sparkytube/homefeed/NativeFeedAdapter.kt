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
 * Plain RecyclerView adapter, no diffing/paging -- v1 native feed is a
 * single fetchHomeFeed() call's worth of items rendered once, not an
 * infinite-scroll feed (that's follow-up scope once this test proves
 * the InnerTube approach is worth building out further, per Sparky's
 * "test easy first" plan).
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

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnailImage)
        val duration: TextView = view.findViewById(R.id.durationText)
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
        if (item.durationText.isNotBlank()) {
            holder.duration.visibility = View.VISIBLE
            holder.duration.text = item.durationText
        } else {
            holder.duration.visibility = View.GONE
        }
        holder.thumbnail.load(item.thumbnailUrl) {
            crossfade(true)
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
