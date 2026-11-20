package dev.sparkynox.sparkytube.extractor

import android.content.Context
import dev.sparkynox.sparkytube.logs.LogRecorder

/**
 * Stream urls for the native Shorts player. LazyLuno only, there is no NewPipe step here on purpose:
 * a short is small and a 360-720p stream is plenty. The ANDROID client answers in 1-3 s, the full
 * LazyLuno client cascade only runs when that comes back empty.
 */
object ShortsResolver {
    class Clip(val videoUrl: String, val audioUrl: String?, val height: Int)

    private const val TTL_MS = 4 * 60 * 1000L
    private val cache = object : LinkedHashMap<String, Pair<Long, Clip>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Clip>>?) = size > 12
    }

    fun cached(videoId: String): Clip? = synchronized(cache) {
        cache[videoId]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second
    }

    /** Blocking, call from a background thread. Null = LazyLuno had nothing playable, see LazyLunoResolver.lastErrorMessage. */
    fun resolve(ctx: Context, videoId: String): Clip? {
        cached(videoId)?.let { return it }
        val r = LazyLunoResolver.resolve(ctx, videoId, "ANDROID_APP") ?: LazyLunoResolver.resolve(ctx, videoId, "")
        if (r == null) {
            LogRecorder.w("Shorts", "LazyLuno had nothing for $videoId: ${LazyLunoResolver.lastErrorMessage}")
            return null
        }
        val clip = pick(r) ?: return null
        synchronized(cache) { cache[videoId] = System.currentTimeMillis() to clip }
        LogRecorder.d("Shorts", "$videoId -> ${clip.height}p, audio merge=${clip.audioUrl != null}")
        return clip
    }

    // muxed first (starts fastest, no second connection), tallest up to 720p; adaptive only when there is nothing muxed
    private fun pick(r: StreamExtractor.ResolvedStream): Clip? {
        if (r.isHls) return Clip(r.url, null, 0)
        val q = r.availableQualities.filter { it.resolutionValue <= 720 }.ifEmpty { r.availableQualities }
        if (q.isEmpty()) return null
        val muxed = q.filter { it.audioUrl == null }.maxByOrNull { it.resolutionValue }
        val best = muxed ?: q.maxByOrNull { it.resolutionValue } ?: return null
        return Clip(best.url, best.audioUrl, best.resolutionValue)
    }
}
