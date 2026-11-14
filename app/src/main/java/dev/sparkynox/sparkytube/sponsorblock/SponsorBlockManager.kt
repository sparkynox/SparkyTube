package dev.sparkynox.sparkytube.sponsorblock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sparkynox.sparkytube.logs.LogRecorder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Client for the public SponsorBlock API (sponsor.ajay.app) -- lets
 * SparkyTube auto-skip sponsor segments, self-promo, intros/outros, etc.
 * the same way the SponsorBlock browser extension does, using the same
 * community-maintained segment database.
 *
 * Uses the privacy-preserving hash-prefix lookup endpoint
 * (/api/skipSegments/{hashPrefix}) instead of sending the plain videoId,
 * matching how the official extension protects which videos a user is
 * watching from the server operator -- we hash the videoId (SHA-256),
 * send only the first 4 hex chars, and the server returns all segment
 * sets whose videoId hashes to that prefix; we then filter client-side
 * for the exact match.
 */
object SponsorBlockManager {

    enum class Category(val apiName: String, val label: String, val color: Long) {
        SPONSOR("sponsor", "Sponsor", 0xFF00D400),
        SELFPROMO("selfpromo", "Self-promo", 0xFFFFFF00),
        INTERACTION("interaction", "Interaction reminder", 0xFFCC00FF),
        INTRO("intro", "Intermission/intro", 0xFF00FFFF),
        OUTRO("outro", "Endcards/credits", 0xFF0202ED),
        PREVIEW("preview", "Preview/recap", 0xFF008FD6),
        FILLER("filler", "Filler tangent", 0xFF7300FF),
        MUSIC_OFFTOPIC("music_offtopic", "Non-music section", 0xFFFF9900)
    }

    data class Segment(val category: Category, val startSeconds: Double, val endSeconds: Double)

    // what the seek bar draws for the current video (set by MainActivity, read by PlayerControls)
    var shown by mutableStateOf<List<Segment>>(emptyList())

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "https://sponsor.ajay.app/api"

    /**
     * Fetches this video's skip segments, filtered to only the categories
     * the user has enabled (see SponsorBlockPrefs). Returns an empty list
     * on any failure (no network, no segments submitted for this video,
     * API down) -- SponsorBlock is a nice-to-have, never something worth
     * blocking or erroring playback over.
     */
    fun fetchSegments(videoId: String, enabledCategories: Set<Category>): List<Segment> {
        if (enabledCategories.isEmpty()) return emptyList()

        return try {
            val hash = sha256Hex(videoId)
            val hashPrefix = hash.substring(0, 4)
            val categoriesParam = enabledCategories.joinToString(",", prefix = "[", postfix = "]") { "\"${it.apiName}\"" }
            val url = "$API_BASE/skipSegments/$hashPrefix".toHttpUrl().newBuilder()
                .addQueryParameter("categories", categoriesParam)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")
                .build()

            client.newCall(request).execute().use { response ->
                // 404 just means nobody submitted segments for any video with this hash prefix
                if (!response.isSuccessful) {
                    LogRecorder.i("SponsorBlock", "no segments for $videoId (HTTP ${response.code})")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val list = parseHashPrefixResponse(body, videoId)
                LogRecorder.i("SponsorBlock", "$videoId: ${list.size} segments ${list.joinToString { it.category.apiName }}")
                list
            }
        } catch (e: Exception) {
            LogRecorder.w("SponsorBlock", "fetch failed for $videoId: ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
    }

    /**
     * The hash-prefix endpoint returns an array of per-video result
     * objects (one per videoId matching that hash prefix, since multiple
     * different videos can share the same 4-char prefix) -- this picks
     * out just the one for our actual videoId and flattens its segments.
     */
    private fun parseHashPrefixResponse(body: String, videoId: String): List<Segment> {
        val results = JSONArray(body)
        val segments = mutableListOf<Segment>()

        for (i in 0 until results.length()) {
            val videoResult = results.optJSONObject(i) ?: continue
            if (videoResult.optString("videoID") != videoId) continue

            val segmentsArray = videoResult.optJSONArray("segments") ?: continue
            for (j in 0 until segmentsArray.length()) {
                val seg = segmentsArray.optJSONObject(j) ?: continue
                val categoryName = seg.optString("category")
                val category = Category.entries.firstOrNull { it.apiName == categoryName } ?: continue
                val range = seg.optJSONArray("segment") ?: continue
                if (range.length() < 2) continue
                segments.add(Segment(category, range.getDouble(0), range.getDouble(1)))
            }
        }
        return segments
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
