package dev.sparkynox.sparkytube.adblock

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * SparkyTube's native ad/tracker blocking engine.
 *
 * WHY THIS EXISTS INSTEAD OF A REAL CHROME EXTENSION:
 * Android WebView has no extension runtime — chrome.* APIs, manifest.json,
 * background service workers and declarativeNetRequest simply don't exist
 * inside WebView's process. This class replicates the *behavior* of
 * uBlock Origin / uBO-Lite (domain + path based network blocking) using
 * WebViewClient.shouldInterceptRequest, which IS a real, supported hook.
 *
 * The domain list in assets/blocklist.json was curated from the public
 * uBO-Lite / uBlock Origin filter lists (EasyList, EasyPrivacy, AdGuard),
 * narrowed down to entries relevant to the YouTube/Google ad ecosystem
 * plus common cross-site trackers, so the on-device list stays small and fast.
 *
 * Runs 24/7 for the lifetime of the WebView — every single request passes
 * through here, not just ones on a schedule or toggle.
 */
class AdBlockEngine private constructor(
    private val adDomains: List<String>,
    private val youtubeAdPaths: List<String>,
    private val youtubeAdParamHints: List<String>,
    private val trackingDomains: List<String>
) {

    // Blocks googlevideo.com — the actual CDN domain YouTube's web player
    // streams video/audio bytes from — but ONLY while on a /watch page.
    // This is what makes "the default YouTube player never loads" true for
    // regular videos (native ExoPlayer handles those instead). Shorts
    // deliberately are NOT covered by this block: Shorts play on YouTube's
    // own in-page player (see MainActivity.extractVideoId's doc comment for
    // why), so they need googlevideo.com allowed or they'd just show a
    // black/broken player.
    //
    // NewPipeExtractor itself is unaffected either way: it fetches streams
    // through its own native HTTP client (SparkyDownloader in
    // StreamExtractor.kt), not through the WebView.
    @Volatile
    private var blockGoogleVideoForWatchPages = false

    fun setOnWatchPage(isWatchPage: Boolean) {
        blockGoogleVideoForWatchPages = isWatchPage
    }

    // Running counter — feeds the "useless tracking blocked" UI signal
    private val blockedCount = AtomicInteger(0)

    fun blockedSoFar(): Int = blockedCount.get()

    /**
     * Returns true if this request URL should be blocked (return empty response
     * instead of letting it hit the network). Cheap substring checks only —
     * this runs on every single network request the WebView makes, so it has
     * to stay fast (no regex compilation, no allocations in the hot path).
     */
    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()

        if (blockGoogleVideoForWatchPages && lower.contains("googlevideo.com")) {
            blockedCount.incrementAndGet()
            return true
        }

        for (d in adDomains) {
            if (lower.contains(d)) {
                blockedCount.incrementAndGet()
                return true
            }
        }
        for (d in trackingDomains) {
            if (lower.contains(d)) {
                blockedCount.incrementAndGet()
                return true
            }
        }
        // YouTube-specific ad pixel / telemetry paths (these hit youtube.com /
        // googlevideo.com domains directly, so plain domain blocking above
        // won't catch them — the video itself lives on the same domain).
        for (p in youtubeAdPaths) {
            if (lower.contains(p)) {
                blockedCount.incrementAndGet()
                return true
            }
        }
        for (hint in youtubeAdParamHints) {
            if (lower.contains("$hint=") && (lower.contains("youtube.com") || lower.contains("googlevideo.com"))) {
                blockedCount.incrementAndGet()
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "AdBlockEngine"

        @Volatile
        private var instance: AdBlockEngine? = null

        fun getInstance(context: Context): AdBlockEngine {
            return instance ?: synchronized(this) {
                instance ?: buildFromAssets(context).also { instance = it }
            }
        }

        private fun buildFromAssets(context: Context): AdBlockEngine {
            return try {
                val json = context.assets.open("blocklist.json").use { stream ->
                    BufferedReader(InputStreamReader(stream)).readText()
                }
                val obj = JSONObject(json)
                AdBlockEngine(
                    adDomains = obj.getJSONArray("adDomains").toStringList(),
                    youtubeAdPaths = obj.getJSONArray("youtubeAdPaths").toStringList(),
                    youtubeAdParamHints = obj.getJSONArray("youtubeAdParamHints").toStringList(),
                    trackingDomains = obj.getJSONArray("trackingDomains").toStringList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load blocklist.json, falling back to minimal built-in list", e)
                AdBlockEngine(
                    adDomains = listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com"),
                    youtubeAdPaths = listOf("/pagead/", "/ptracking"),
                    youtubeAdParamHints = listOf("ad_type"),
                    trackingDomains = listOf("google-analytics.com")
                )
            }
        }

        private fun org.json.JSONArray.toStringList(): List<String> {
            val out = ArrayList<String>(length())
            for (i in 0 until length()) out.add(getString(i))
            return out
        }
    }
}
