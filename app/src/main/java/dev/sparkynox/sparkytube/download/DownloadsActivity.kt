package dev.sparkynox.sparkytube.download

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import dev.sparkynox.sparkytube.databinding.ActivityDownloadsBinding
import dev.sparkynox.sparkytube.databinding.ItemDownloadBinding
import java.util.Locale

/**
 * VidMate-style downloads screen. Shows two kinds of jobs, merged into one
 * list: plain DownloadManager downloads (combined-format progressive
 * streams), AND adaptive video+audio+mux jobs tracked separately via
 * MuxTaskTracker (DownloadManager has no concept of "two downloads that
 * become one file", so those were previously invisible here even though
 * they were actually downloading -- see VideoDownloader.kt). Polls both
 * sources on a timer while visible and stops polling in onPause.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter
    private lateinit var downloadManager: DownloadManager

    private val pollHandler = Handler(Looper.getMainLooper())
    // Keyed by "dm_<id>" or "mux_<id>" so DownloadManager ids and
    // MuxTask ids (both plain Longs, easy to collide) never overwrite
    // each other's speed-tracking entries.
    private val lastBytesSeen = HashMap<String, Pair<Long, Long>>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshDownloads()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        binding.downloadsBackBtn.setOnClickListener { finish() }

        adapter = DownloadsAdapter { row ->
            if (row.isMuxTask) {
                MuxTaskTracker.remove(row.id)
            } else {
                downloadManager.remove(row.id)
            }
            refreshDownloads()
        }
        binding.downloadsList.layoutManager = LinearLayoutManager(this)
        binding.downloadsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun refreshDownloads() {
        val items = mutableListOf<DownloadRowUi>()
        items.addAll(readDownloadManagerRows())
        items.addAll(readMuxTaskRows())

        // Most recent first across both sources.
        items.sortByDescending { it.sortKey }
        adapter.submitList(items)
        binding.downloadsEmptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun readDownloadManagerRows(): List<DownloadRowUi> {
        val query = DownloadManager.Query() // no filter -- every download this app has ever enqueued
        val cursor = try {
            downloadManager.query(query)
        } catch (e: Exception) {
            return emptyList()
        }

        val items = mutableListOf<DownloadRowUi>()
        cursor.use {
            val idIdx = it.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleIdx = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val soFarIdx = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val localUriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                val title = if (titleIdx >= 0) it.getString(titleIdx) ?: "Download" else "Download"
                val status = if (statusIdx >= 0) it.getInt(statusIdx) else DownloadManager.STATUS_PENDING
                val bytesSoFar = if (soFarIdx >= 0) it.getLong(soFarIdx) else 0L
                val bytesTotal = if (totalIdx >= 0) it.getLong(totalIdx) else -1L
                val localUri = if (localUriIdx >= 0) it.getString(localUriIdx) else null

                val speedBytesPerSec = computeSpeed("dm_$id", bytesSoFar)
                val statusText: String
                val statusColor: Int
                val progress: Int
                val indeterminate: Boolean
                val showProgress: Boolean

                when (status) {
                    DownloadManager.STATUS_RUNNING -> {
                        val hasKnownTotal = bytesTotal > 0
                        val percent = if (hasKnownTotal) ((bytesSoFar * 100) / bytesTotal).toInt().coerceIn(0, 100) else 0
                        val sizeText = if (hasKnownTotal) {
                            "${formatBytes(bytesSoFar)} / ${formatBytes(bytesTotal)} ($percent%)"
                        } else {
                            "${formatBytes(bytesSoFar)} downloaded"
                        }
                        statusText = "$sizeText  •  ${formatSpeed(speedBytesPerSec)}"
                        statusColor = 0xFF9A9A9A.toInt()
                        progress = percent
                        indeterminate = !hasKnownTotal
                        showProgress = true
                    }
                    DownloadManager.STATUS_PENDING -> {
                        statusText = "Queued…"
                        statusColor = 0xFF9A9A9A.toInt()
                        progress = 0
                        indeterminate = true
                        showProgress = true
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        val percent = if (bytesTotal > 0) ((bytesSoFar * 100) / bytesTotal).toInt().coerceIn(0, 100) else 0
                        statusText = "Paused — ${formatBytes(bytesSoFar)} downloaded"
                        statusColor = 0xFFCFA23B.toInt()
                        progress = percent
                        indeterminate = false
                        showProgress = true
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val folder = localUriToPath(localUri)?.substringBeforeLast('/')?.substringAfterLast('/') ?: "Downloads"
                        statusText = "Done — ${formatBytes(bytesTotal)}  •  saved to $folder"
                        statusColor = 0xFF4CAF50.toInt()
                        progress = 100
                        indeterminate = false
                        showProgress = false
                    }
                    DownloadManager.STATUS_FAILED -> {
                        statusText = "Failed"
                        statusColor = 0xFFE05252.toInt()
                        progress = 0
                        indeterminate = false
                        showProgress = false
                    }
                    else -> {
                        statusText = "Unknown status"
                        statusColor = 0xFF9A9A9A.toInt()
                        progress = 0
                        indeterminate = false
                        showProgress = false
                    }
                }

                items.add(
                    DownloadRowUi(
                        id = id,
                        isMuxTask = false,
                        title = title,
                        statusText = statusText,
                        statusColor = statusColor,
                        progress = progress,
                        indeterminate = indeterminate,
                        showProgress = showProgress,
                        sortKey = id,
                        videoId = DownloadThumbnails.get(id)
                    )
                )
            }
        }
        return items
    }

    private fun readMuxTaskRows(): List<DownloadRowUi> {
        return MuxTaskTracker.all().map { task ->
            val speedVideo = computeSpeed("mux_v_${task.id}", task.videoBytesDownloaded)
            val speedAudio = computeSpeed("mux_a_${task.id}", task.audioBytesDownloaded)

            val (statusText, statusColor, progress, indeterminate, showProgress) = when (task.stage) {
                MuxTaskTracker.Stage.DOWNLOADING_VIDEO, MuxTaskTracker.Stage.DOWNLOADING_AUDIO -> {
                    val vHasTotal = task.videoBytesTotal > 0
                    val aHasTotal = task.audioBytesTotal > 0
                    val vPercent = if (vHasTotal) ((task.videoBytesDownloaded * 100) / task.videoBytesTotal).toInt().coerceIn(0, 100) else 0
                    val aPercent = if (aHasTotal) ((task.audioBytesDownloaded * 100) / task.audioBytesTotal).toInt().coerceIn(0, 100) else 0
                    // Video and audio download in parallel (see
                    // VideoDownloader), so show both at once rather than
                    // implying they're sequential stages.
                    val combinedPercent = if (vHasTotal && aHasTotal) (vPercent + aPercent) / 2 else 0
                    val text = "Video ${formatBytes(task.videoBytesDownloaded)}${if (vHasTotal) " ($vPercent%)" else ""} " +
                        "• Audio ${formatBytes(task.audioBytesDownloaded)}${if (aHasTotal) " ($aPercent%)" else ""}" +
                        "  •  ${formatSpeed(speedVideo + speedAudio)}"
                    Quintuple(text, 0xFF9A9A9A.toInt(), combinedPercent, !(vHasTotal && aHasTotal), true)
                }
                MuxTaskTracker.Stage.MUXING -> {
                    Quintuple("Combining video + audio…", 0xFFCFA23B.toInt(), 0, true, true)
                }
                MuxTaskTracker.Stage.DONE -> {
                    val folder = task.outputPath?.substringBeforeLast('/')?.substringAfterLast('/') ?: "Downloads"
                    Quintuple("Done  •  saved to $folder", 0xFF4CAF50.toInt(), 100, false, false)
                }
                MuxTaskTracker.Stage.FAILED -> {
                    Quintuple(task.errorMessage ?: "Failed", 0xFFE05252.toInt(), 0, false, false)
                }
            }

            DownloadRowUi(
                id = task.id,
                isMuxTask = true,
                title = "${task.title} (${task.qualityLabel})",
                statusText = statusText,
                statusColor = statusColor,
                progress = progress,
                indeterminate = indeterminate,
                showProgress = showProgress,
                // Offset well above any realistic DownloadManager id range
                // so a freshly-started mux task always sorts to the top,
                // same as a freshly-enqueued DownloadManager entry would.
                sortKey = 1_000_000_000L + task.id,
                videoId = task.videoId
            )
        }
    }

    private data class Quintuple(val text: String, val color: Int, val progress: Int, val indeterminate: Boolean, val showProgress: Boolean)

    private fun computeSpeed(key: String, bytesNow: Long): Long {
        val now = System.currentTimeMillis()
        val prev = lastBytesSeen[key]
        lastBytesSeen[key] = bytesNow to now

        if (prev == null) return 0L
        val (prevBytes, prevTime) = prev
        val deltaTimeSec = (now - prevTime) / 1000.0
        if (deltaTimeSec <= 0) return 0L
        val deltaBytes = bytesNow - prevBytes
        if (deltaBytes < 0) return 0L // restarted/reset -- don't report a negative speed
        return (deltaBytes / deltaTimeSec).toLong()
    }

    private fun localUriToPath(localUri: String?): String? {
        if (localUri == null) return null
        return try {
            android.net.Uri.parse(localUri).path
        } catch (e: Exception) {
            localUri
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }
}

/**
 * One row's worth of display data, already fully formatted -- built
 * fresh from either DownloadManager's cursor or MuxTaskTracker on every
 * poll tick, so the adapter itself doesn't need to know which source a
 * row came from except for routing delete taps correctly (isMuxTask).
 */
data class DownloadRowUi(
    val id: Long,
    val isMuxTask: Boolean,
    val title: String,
    val statusText: String,
    val statusColor: Int,
    val progress: Int,
    val indeterminate: Boolean,
    val showProgress: Boolean,
    val sortKey: Long,
    val videoId: String? = null
)

class DownloadsAdapter(
    private val onDelete: (DownloadRowUi) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    private var items: List<DownloadRowUi> = emptyList()

    fun submitList(newItems: List<DownloadRowUi>) {
        items = newItems
        notifyDataSetChanged() // small list (a handful of downloads at most) -- DiffUtil would be overkill here
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onDelete)
    }

    override fun getItemCount() = items.size

    class ViewHolder(private val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DownloadRowUi, onDelete: (DownloadRowUi) -> Unit) {
            binding.itemDownloadTitle.text = item.title

            if (!item.videoId.isNullOrBlank()) {
                binding.itemDownloadThumbnail.load("https://i.ytimg.com/vi/${item.videoId}/mqdefault.jpg") {
                    crossfade(true)
                }
            } else {
                binding.itemDownloadThumbnail.setImageDrawable(null)
            }

            binding.itemDownloadProgress.visibility = if (item.showProgress) View.VISIBLE else View.GONE
            binding.itemDownloadProgress.isIndeterminate = item.indeterminate
            if (!item.indeterminate) {
                binding.itemDownloadProgress.progress = item.progress
            }

            binding.itemDownloadStatus.text = item.statusText
            binding.itemDownloadStatus.setTextColor(item.statusColor)

            binding.itemDownloadDeleteBtn.setOnClickListener { onDelete(item) }
        }
    }
}

/**
 * Human-readable byte formatting (KB/MB/GB), shared by size and speed
 * display since the request specifically asked for "KB, MB, GB" style
 * numbers rather than always-MB or raw byte counts.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "-- KB/s"
    return "${formatBytes(bytesPerSec)}/s"
}
