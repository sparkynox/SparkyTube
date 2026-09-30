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

    fun savePosition(context: Context, videoId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
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

    // Separate from the per-video pos_/dur_ entries above -- this is just
    // "whatever video was on screen when the app last closed", used to
    // reopen it on a cold start. savePosition() clearing a video's entry
    // (finished/too-early) doesn't touch this, they're independent.
    fun saveLastPlaying(context: Context, videoId: String) {
        prefs(context).edit().putString("last_playing_id", videoId).apply()
    }

    fun getLastPlaying(context: Context): String? =
        prefs(context).getString("last_playing_id", null)

    fun clearLastPlaying(context: Context) {
        prefs(context).edit().remove("last_playing_id").apply()
    }
}
