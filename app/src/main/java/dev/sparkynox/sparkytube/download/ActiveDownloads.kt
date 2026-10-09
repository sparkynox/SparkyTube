package dev.sparkynox.sparkytube.download

import android.app.DownloadManager
import android.content.Context

// what the little card under the player shows, one line for the download that's
// running right now plus how many more are going/waiting
object ActiveDownloads {

    class Info(
        val title: String,
        val detail: String,
        val percent: Int,       // -1 = no total yet
        val activeCount: Int,
        val queuedCount: Int
    )

    private val seen = HashMap<String, Pair<Long, Long>>()

    private fun speed(key: String, bytes: Long): Long {
        val now = System.currentTimeMillis()
        val prev = seen.put(key, bytes to now) ?: return 0L
        val dt = (now - prev.second) / 1000.0
        if (dt <= 0 || bytes < prev.first) return 0L
        return ((bytes - prev.first) / dt).toLong()
    }

    fun current(context: Context): Info? {
        val queued = DownloadQueue.pending().size

        val mux = MuxTaskTracker.all().filter {
            it.stage == MuxTaskTracker.Stage.DOWNLOADING_VIDEO ||
                it.stage == MuxTaskTracker.Stage.DOWNLOADING_AUDIO ||
                it.stage == MuxTaskTracker.Stage.MUXING
        }

        var dmTitle: String? = null
        var dmPercent = -1
        var dmDetail = ""
        var dmCount = 0
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val q = DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
            )
            dm.query(q).use { c ->
                val idI = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleI = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val soFarI = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalI = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    dmCount++
                    if (dmTitle != null) continue
                    val soFar = c.getLong(soFarI)
                    val total = c.getLong(totalI)
                    dmTitle = c.getString(titleI) ?: "Download"
                    dmPercent = if (total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else -1
                    val sp = speed("dm_" + c.getLong(idI), soFar)
                    dmDetail = formatBytes(soFar) + (if (total > 0) " / " + formatBytes(total) else "") +
                        (if (sp > 0) "  •  " + formatSpeed(sp) else "")
                }
            }
        } catch (e: Exception) { /* no download manager, just skip it */ }

        val total = mux.size + dmCount
        if (total == 0) return null

        val t = mux.firstOrNull()
        if (t != null) {
            val label = "${t.title} (${t.qualityLabel})"
            if (t.stage == MuxTaskTracker.Stage.MUXING) {
                return Info(label, "Combining video + audio…", -1, total, queued)
            }
            val vHas = t.videoBytesTotal > 0
            val aHas = t.audioBytesTotal > 0
            val vP = if (vHas) ((t.videoBytesDownloaded * 100) / t.videoBytesTotal).toInt().coerceIn(0, 100) else 0
            val aP = if (aHas) ((t.audioBytesDownloaded * 100) / t.audioBytesTotal).toInt().coerceIn(0, 100) else 0
            val pct = if (vHas && aHas) (vP + aP) / 2 else -1
            val sp = speed("mux_v_${t.id}", t.videoBytesDownloaded) + speed("mux_a_${t.id}", t.audioBytesDownloaded)
            val detail = (if (pct >= 0) "$pct%  •  " else "") + formatBytes(t.videoBytesDownloaded + t.audioBytesDownloaded) +
                (if (sp > 0) "  •  " + formatSpeed(sp) else "")
            return Info(label, detail, pct, total, queued)
        }

        val detail = (if (dmPercent >= 0) "$dmPercent%  •  " else "") + dmDetail
        return Info(dmTitle ?: "Download", detail, dmPercent, total, queued)
    }
}
