package dev.sparkynox.sparkytube.ui

import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// like / dislike counts from returnyoutubedislikeapi.com (YouTube hides the dislike number)
object ReturnYouTubeDislike {

    data class Votes(val likes: Long, val dislikes: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val cache = object : LinkedHashMap<String, Votes>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Votes>) = size > 60
    }

    suspend fun get(videoId: String): Votes? {
        synchronized(cache) { cache[videoId]?.let { return it } }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
                    .header("User-Agent", "SparkyTube")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        LogRecorder.i("RYD", "$videoId: HTTP ${r.code}")
                        return@withContext null
                    }
                    val o = JSONObject(r.body?.string() ?: return@withContext null)
                    val v = Votes(o.optLong("likes", 0), o.optLong("dislikes", 0))
                    synchronized(cache) { cache[videoId] = v }
                    v
                }
            } catch (e: Exception) {
                LogRecorder.w("RYD", "fetch failed for $videoId: ${e.javaClass.simpleName} ${e.message}")
                null
            }
        }
    }

    // 1234 -> 1.2K, 12_500_000 -> 12M
    fun compact(n: Long): String = when {
        n >= 10_000_000 -> "${n / 1_000_000}M"
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
        n >= 10_000 -> "${n / 1_000}K"
        n >= 1_000 -> "%.1fK".format(n / 1_000.0).replace(".0K", "K")
        else -> n.toString()
    }
}
