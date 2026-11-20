package dev.sparkynox.sparkytube.history

import android.content.Context

/**
 * Remembers which videos YouTube put an age gate on. Feed lists don't say it, so the 18+ badge comes from here:
 * LazyLuno reports the gate the first time such a video is opened, and from then on it shows on every card
 * and the video goes straight to the signed in clients.
 */
object AgeRestrictedStore {
    private const val PREFS = "sparkytube_age_restricted"
    private const val KEY = "ids"
    private const val MAX = 800

    private val ids = LinkedHashSet<String>()
    @Volatile private var loaded = false

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(ids) {
            if (loaded) return
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "")?.split(',')?.filter { it.length == 11 }?.let { ids.addAll(it) }
            loaded = true
        }
    }

    fun has(ctx: Context, videoId: String?): Boolean {
        if (videoId == null) return false
        load(ctx)
        return synchronized(ids) { videoId in ids }
    }

    fun mark(ctx: Context, videoId: String) {
        load(ctx)
        val snapshot = synchronized(ids) {
            if (!ids.add(videoId)) return
            while (ids.size > MAX) ids.remove(ids.first())
            ids.joinToString(",")
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, snapshot).apply()
    }
}
