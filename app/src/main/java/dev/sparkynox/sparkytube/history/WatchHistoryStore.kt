package dev.sparkynox.sparkytube.history

import android.content.Context

/**
 * Tracks last-watched position per video so playback can resume where
 * you left off instead of always starting from 0. Simple flat
 * SharedPreferences key-per-video store -- no Room needed for something
 * this small (just a videoId -> positionMs + duration map).
 *
 * A video is only worth resuming if it's not basically finished (say
 * within the last 15s) and has actually been watched a bit (past the
 * first 10s) -- otherwise every video would show "resume from 0:03"
 * which is pointless, and a video watched to the end would show "resume"
 * right before it ends again.
 */
object WatchHistoryStore {

    private const val PREFS_NAME = "sparkytube_watch_history"
    private const val MIN_POSITION_MS = 10_000L
    private const val END_BUFFER_MS = 15_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // videos that were played for a while, drives the "Watched" badge on thumbnails
    private val watched = HashSet<String>()
    @Volatile private var watchedLoaded = false

    private fun loadWatched(context: Context) {
        if (watchedLoaded) return
        synchronized(watched) {
            if (watchedLoaded) return
            prefs(context).getString("watched_ids", "")?.split(',')?.filter { it.length == 11 }?.let { watched.addAll(it) }
            watchedLoaded = true
        }
    }

    fun isWatched(context: Context, videoId: String?): Boolean {
        if (videoId == null) return false
        loadWatched(context)
        return synchronized(watched) { videoId in watched }
    }

    private fun markWatched(context: Context, videoId: String) {
        loadWatched(context)
        val snapshot = synchronized(watched) {
            if (!watched.add(videoId)) return
            if (watched.size > 2000) watched.remove(watched.first())
            watched.joinToString(",")
        }
        prefs(context).edit().putString("watched_ids", snapshot).apply()
    }

    fun savePosition(context: Context, videoId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (positionMs >= MIN_POSITION_MS) markWatched(context, videoId)
        // the red line under thumbnails: how far this video got, kept even after the resume point is cleared
        val pct = if (positionMs > durationMs - END_BUFFER_MS) 100 else (positionMs * 100 / durationMs).toInt().coerceIn(0, 100)
        prefs(context).edit().putInt("pct_$videoId", pct).apply()
        // Don't bother persisting a save for something that isn't
        // resumable anyway (too early, or basically done) -- treat it
        // as "watched to completion" by just clearing any prior entry
        // instead of storing a stale near-the-end position.
        if (positionMs < MIN_POSITION_MS || positionMs > durationMs - END_BUFFER_MS) {
            clearPosition(context, videoId)
            return
        }
        prefs(context).edit()
            .putLong("pos_$videoId", positionMs)
            .putLong("dur_$videoId", durationMs)
            .apply()
    }

    /** 0-100, how much of the video was watched here (0 = never started). */
    fun getProgressPercent(context: Context, videoId: String?): Int =
        if (videoId == null) 0 else prefs(context).getInt("pct_$videoId", 0)

    /** Returns the saved position in ms, or null if there's nothing to resume. */
    fun getPosition(context: Context, videoId: String): Long? {
        val p = prefs(context)
        val pos = p.getLong("pos_$videoId", -1L)
        return if (pos > 0) pos else null
    }

    fun clearPosition(context: Context, videoId: String) {
        prefs(context).edit()
            .remove("pos_$videoId")
            .remove("dur_$videoId")
            .apply()
    }
}
