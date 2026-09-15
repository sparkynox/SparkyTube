package dev.sparkynox.sparkytube.homefeed

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A minimal, hand-written client for YouTube's private InnerTube API --
 * the same JSON API youtube.com's own web frontend calls internally.
 * NewPipeExtractor deliberately doesn't do this (it only hits
 * unauthenticated public endpoints by design), so a native, *logged-in*
 * home feed needs its own client. This is intentionally small: one
 * endpoint (browse/FEwhat_to_watch for the home feed), not a general
 * InnerTube wrapper.
 *
 * Authentication here is TWO layers, both required for a WEB-client
 * request to come back personalized instead of a generic/logged-out
 * response:
 *   1. The session cookies (SID, HSID, SSID, APISID, SAPISID, etc) --
 *      read directly from CookieManager, the same store the WebView's
 *      YouTube login already populates. No separate login flow needed
 *      here; this rides on whatever session the WebView (or Google
 *      Sign-In) already established.
 *   2. A SAPISIDHASH Authorization header -- YouTube's WEB client
 *      requires this in addition to cookies; a cookie-only request gets
 *      rejected/treated as logged-out. It's a SHA1 hash of
 *      "{timestamp} {SAPISID cookie value} {origin}", not a secret
 *      exchanged via any API call -- computable locally from the
 *      SAPISID cookie already sitting in CookieManager.
 *
 * This only ever reads cookies that already exist (from a real YouTube
 * login the person did in the WebView or via Google Sign-In) -- it does
 * not implement any login flow itself.
 */
object InnerTubeClient {

    private const val ORIGIN = "https://www.youtube.com"
    private const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse"

    // The public, non-secret API key youtube.com's own web frontend
    // embeds in its page source and sends with every InnerTube call --
    // this is not a per-user credential, it's the same fixed value
    // every WEB-client request uses (see the InnerTube reverse-engineering
    // links this was cross-checked against).
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    // Kept in rough step with what a current Chrome-based WEB client
    // reports -- YouTube does loosely sanity-check this against the
    // request shape, though it's not pinned to an exact build like the
    // signature cipher is. Update if browse requests start silently
    // degrading to a logged-out-shaped response.
    private const val CLIENT_VERSION = "2.20250101.00.00"

    private val httpClient = OkHttpClient.Builder().build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class HomeFeedResult(
        val items: List<HomeFeedItem>,
        val isLoggedIn: Boolean
    )

    /**
     * Fetches the personalized home feed (browseId FEwhat_to_watch) for
     * whatever YouTube session is currently loaded in CookieManager.
     * Returns an empty, isLoggedIn=false result rather than throwing when
     * there's no session yet -- MainActivity's toggle for this feature
     * falls back to the WebView feed in that case rather than showing a
     * broken native screen.
     */
    suspend fun fetchHomeFeed(): HomeFeedResult = withContext(Dispatchers.IO) {
        try {
            val cookieString = CookieManager.getInstance().getCookie(ORIGIN)
            if (cookieString.isNullOrBlank()) {
                return@withContext HomeFeedResult(emptyList(), isLoggedIn = false)
            }

            val sapisid = extractCookieValue(cookieString, "SAPISID")
                ?: extractCookieValue(cookieString, "__Secure-3PAPISID")
            if (sapisid == null) {
                // Cookies exist but not a logged-in set (e.g. only
                // consent/preference cookies from browsing while logged
                // out) -- treat the same as no session.
                return@withContext HomeFeedResult(emptyList(), isLoggedIn = false)
            }

            val requestBody = buildBrowseRequestBody()
            val request = Request.Builder()
                .url("$BROWSE_URL?key=$API_KEY")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("Cookie", cookieString)
                .header("Authorization", buildSapisidHash(sapisid))
                .header("Origin", ORIGIN)
                .header("X-Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext HomeFeedResult(emptyList(), isLoggedIn = false)
                }
                val body = response.body?.string() ?: return@withContext HomeFeedResult(emptyList(), isLoggedIn = false)
                val items = parseHomeFeedItems(body)
                HomeFeedResult(items, isLoggedIn = true)
            }
        } catch (e: Exception) {
            HomeFeedResult(emptyList(), isLoggedIn = false)
        }
    }

    private fun buildBrowseRequestBody(): JSONObject {
        return JSONObject().apply {
            put("browseId", "FEwhat_to_watch")
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
    }

    private fun extractCookieValue(cookieString: String, name: String): String? {
        return cookieString.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * YouTube's WEB-client auth scheme: SHA1("{timestamp} {sapisid} {origin}"),
     * sent as "Authorization: SAPISIDHASH {timestamp}_{hash}". This is
     * computed entirely locally from a cookie value already present in
     * CookieManager -- it's not a token fetched from any endpoint, and
     * nothing here transmits the SAPISID cookie value anywhere except
     * back to youtube.com itself (which already has it, being the site
     * that set the cookie).
     */
    private fun buildSapisidHash(sapisid: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val raw = "$timestamp $sapisid $ORIGIN"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }

    /**
     * Walks the browse response's renderer tree looking for
     * videoRenderer/gridVideoRenderer objects -- InnerTube's JSON nests
     * these several levels deep inside section/shelf renderers whose
     * exact structure isn't worth modeling in full for a v1 native feed;
     * a recursive "find every videoRenderer anywhere in this tree" walk
     * is more resilient to YouTube reordering/renaming the wrapper
     * renderers around it than a rigid path would be.
     */
    private fun parseHomeFeedItems(rawJson: String): List<HomeFeedItem> {
        val root = JSONObject(rawJson)
        val results = mutableListOf<HomeFeedItem>()
        collectVideoRenderers(root, results)
        return results
    }

    private fun collectVideoRenderers(node: Any?, out: MutableList<HomeFeedItem>) {
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("videoRenderer") ?: node.optJSONObject("gridVideoRenderer")
                if (renderer != null) {
                    parseVideoRenderer(renderer)?.let { out.add(it) }
                }
                node.keys().forEach { key ->
                    collectVideoRenderers(node.opt(key), out)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideoRenderers(node.opt(i), out)
                }
            }
        }
    }

    private fun parseVideoRenderer(renderer: JSONObject): HomeFeedItem? {
        val videoId = renderer.optString("videoId").takeIf { it.isNotBlank() } ?: return null

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: renderer.optJSONObject("title")?.optString("simpleText")
            ?: ""

        val channelName = renderer.optJSONObject("ownerText")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: renderer.optJSONObject("shortBylineText")
                ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: ""

        val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnailUrl = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url") ?: ""

        val durationText = renderer.optJSONObject("lengthText")?.optString("simpleText")
            ?: renderer.optJSONObject("thumbnailOverlays")?.let { overlays ->
                // lengthText sometimes lives inside a
                // thumbnailOverlayTimeStatusRenderer instead of directly
                // on the video renderer (varies by shelf type) -- this
                // is intentionally best-effort; a missing duration just
                // means the feed card shows no duration badge, not a
                // parse failure for the whole item.
                null
            }
            ?: ""

        val viewCountText = renderer.optJSONObject("shortViewCountText")
            ?.optString("simpleText")
            ?: renderer.optJSONObject("shortViewCountText")
                ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: ""

        if (title.isBlank()) return null

        return HomeFeedItem(
            videoId = videoId,
            title = title,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            viewCountText = viewCountText
        )
    }
}
