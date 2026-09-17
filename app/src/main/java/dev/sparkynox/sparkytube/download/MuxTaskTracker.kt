package dev.sparkynox.sparkytube.download

/**
 * Tracks the state of adaptive downloads (separate video-only + audio-only
 * streams that get downloaded then muxed into one file) -- DownloadManager
 * has no concept of "two downloads that become one job," so this is the
 * missing piece that makes those jobs visible anywhere outside a Toast.
 * A simple in-memory registry is enough: these jobs only need to be
 * visible while the app process is alive (same lifetime as the
 * Thread doing the actual work in VideoDownloader), and DownloadsActivity
 * polls this the same way it polls DownloadManager for regular downloads.
 */
object MuxTaskTracker {

    enum class Stage { DOWNLOADING_VIDEO, DOWNLOADING_AUDIO, MUXING, DONE, FAILED }

    data class MuxTask(
        val id: Long,
        val title: String,
        val qualityLabel: String,
        var stage: Stage,
        val videoId: String? = null,
        var videoBytesDownloaded: Long = 0L,
        var videoBytesTotal: Long = -1L,
        var audioBytesDownloaded: Long = 0L,
        var audioBytesTotal: Long = -1L,
        var errorMessage: String? = null,
        var outputPath: String? = null,
        // Set true by cancel() -- the download loop checks this
        // periodically and throws to unwind out of the current HTTP read,
        // rather than the task just disappearing from the UI while the
        // thread silently keeps running (and re-adding itself to the
        // tracker via update() calls) in the background.
        @Volatile var cancelled: Boolean = false
    )

    private val tasks = java.util.concurrent.ConcurrentHashMap<Long, MuxTask>()
    private val nextId = java.util.concurrent.atomic.AtomicLong(1)

    fun start(title: String, qualityLabel: String, videoId: String? = null): MuxTask {
        val task = MuxTask(id = nextId.getAndIncrement(), title = title, qualityLabel = qualityLabel, stage = Stage.DOWNLOADING_VIDEO, videoId = videoId)
        tasks[task.id] = task
        return task
    }

    fun update(task: MuxTask) {
        // Don't let a background thread's stale update() call resurrect a
        // task the user already deleted -- this was the bug where a
        // deleted row would disappear then reappear 1-2 seconds later,
        // because the download thread had no idea it had been "removed"
        // and kept calling update() with fresh progress on its next
        // chunk read.
        if (task.cancelled && !tasks.containsKey(task.id)) return
        tasks[task.id] = task
    }

    fun all(): List<MuxTask> = tasks.values.sortedByDescending { it.id }

    /**
     * Cancels the task's in-progress download/mux (its worker thread
     * checks task.cancelled and unwinds on its own -- see
     * VideoDownloader.downloadToFile) and removes it from the tracker
     * immediately, rather than just hiding it and letting the thread
     * silently continue in the background wasting bandwidth.
     */
    fun remove(id: Long) {
        tasks[id]?.cancelled = true
        tasks.remove(id)
    }
}
