package dev.sparkynox.sparkytube.download

/**
 * DownloadManager has no field for "thumbnail" or any app-specific
 * metadata -- this is the side-table that lets DownloadsActivity show a
 * video's thumbnail next to a plain progressive-stream download, keyed
 * by DownloadManager's own row id (returned from enqueue()). Same
 * in-memory-only lifetime as MuxTaskTracker: only needs to survive as
 * long as the app process does, since it's just decoration for an
 * already-visible row, not data anything depends on functionally.
 */
object DownloadThumbnails {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, String>()

    fun register(downloadId: Long, videoId: String) {
        map[downloadId] = videoId
    }

    fun get(downloadId: Long): String? = map[downloadId]
}
