package dev.sparkynox.sparkytube.download

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.ui.SparkyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

// Downloads screen. Plain DownloadManager downloads, adaptive video+audio+mux jobs
// (MuxTaskTracker) and the waiting queue are merged into one list that refreshes every second.
class DownloadsActivity : AppCompatActivity() {

    private lateinit var downloadManager: DownloadManager
    // keyed "dm_<id>" / "mux_.." so ids from different sources never share a speed entry
    private val lastBytesSeen = HashMap<String, Pair<Long, Long>>()
    private var rows by mutableStateOf<List<DownloadRowUi>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        setContentView(ComposeView(this).apply {
            setContent { SparkyTheme { DownloadsScreen() } }
        })
    }

    private fun loadRows(): List<DownloadRowUi> {
        val items = mutableListOf<DownloadRowUi>()
        items.addAll(readDownloadManagerRows())
        items.addAll(readMuxTaskRows())
        items.addAll(readQueuedRows())
        // active first, then queued, then finished; newest first inside each group
        items.sortWith(compareBy<DownloadRowUi> { it.state }.thenByDescending { it.sortKey })
        return items
    }

    private fun remove(row: DownloadRowUi) {
        if (row.isQueued) DownloadQueue.cancelPending(row.id)
        else if (row.isMuxTask) MuxTaskTracker.remove(row.id)
        else downloadManager.remove(row.id)
        rows = rows.filterNot { it.id == row.id && it.isMuxTask == row.isMuxTask && it.isQueued == row.isQueued }
    }

    @Composable
    private fun DownloadsScreen() {
        LaunchedEffect(Unit) {
            while (true) {
                rows = withContext(Dispatchers.IO) { loadRows() }
                delay(1000)
            }
        }
        val active = rows.count { it.state == 0 }
        val queued = rows.count { it.state == 1 }
        val finished = rows.count { it.state == 2 }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("Downloads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Downloading $active  \u2022  Queued $queued  \u2022  Finished $finished",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (queued > 0) TextButton(onClick = { DownloadQueue.cancelAllPending() }) { Text("Cancel queued") }
                }
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(rows, key = { "${it.isMuxTask}-${it.isQueued}-${it.id}" }) { r -> DownloadCard(r) }
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadCard(r: DownloadRowUi) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(72.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (!r.videoId.isNullOrBlank()) {
                        AsyncImage(
                            "https://i.ytimg.com/vi/${r.videoId}/mqdefault.jpg", null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        r.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (r.showProgress) {
                        if (r.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { r.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(r.statusText, style = MaterialTheme.typography.labelMedium, color = Color(r.statusColor))
                }
                IconButton(onClick = { remove(r) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
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
                        videoId = DownloadThumbnails.get(id),
                        state = if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) 0 else 2
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
                videoId = task.videoId,
                state = if (task.stage == MuxTaskTracker.Stage.DONE || task.stage == MuxTaskTracker.Stage.FAILED) 2 else 0
            )
        }
    }

    private fun readQueuedRows(): List<DownloadRowUi> {
        return DownloadQueue.pending().mapIndexed { index, q ->
            DownloadRowUi(
                id = q.id,
                isMuxTask = false,
                title = "${q.title} (${q.qualityLabel})",
                statusText = "Queued  •  #${index + 1} in line",
                statusColor = 0xFFCFA23B.toInt(),
                progress = 0,
                indeterminate = false,
                showProgress = false,
                // first in line shows first inside the queued group
                sortKey = 1_500_000_000L - q.id,
                videoId = q.videoId,
                isQueued = true,
                state = 1
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
    val videoId: String? = null,
    val isQueued: Boolean = false,
    // 0 = active, 1 = queued, 2 = finished
    val state: Int = 0
)

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
