package dev.sparkynox.sparkytube.feed

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONTokener

/**
 * One video card in a native feed screen (Home/Search/Subscriptions).
 * Same shape as StreamExtractor.RelatedVideo plus a views count, since
 * feed cards show that and the related-videos panel doesn't.
 */
data class FeedVideo(
    val videoId: String,
    val title: String,
    val uploaderName: String,
    val durationSeconds: Long,
    val viewsText: String,
    val thumbnailUrl: String?
)

/**
 * Drives a native feed screen against the background WebView: scrolls it
 * to trigger YouTube's own infinite-scroll, then scrapes whatever new
 * items appeared. This is the same "invisible WebView as a live data
 * source" pattern the related-videos panel already uses, generalized to
 * any scrollable feed page (Home, Search results, Subscriptions) rather
 * than just a watch page's related list.
 *
 * One instance per feed screen (Home/Search/Subs each get their own),
 * since each tracks its own "how many rows have I already consumed"
 * position independently.
 */
class FeedRepository(private val webView: WebView) {

    // How many DOM rows __sparkyGetFeedVideosJson has already handed back
    // -- passed as sinceIndex on the next call so re-scraping after a
    // scroll only returns genuinely new items instead of the whole feed
    // over again every time.
    private var consumedRowCount = 0

    fun resetPosition() {
        consumedRowCount = 0
    }

    /**
     * Scrolls the background WebView to trigger YouTube's own
     * infinite-scroll loading, waits briefly for new content to render,
     * then scrapes and returns only the newly-appeared items. Returns an
     * empty list if nothing new loaded (end of feed, page not ready,
     * wrong page entirely, etc.) -- callers treat that as "no more items
     * right now", not necessarily "end of feed forever" (a retry after a
     * moment sometimes finds more, same as scrolling slowly on the real
     * site).
     */
    suspend fun loadMore(): List<FeedVideo> {
        scrollWebView()
        kotlinx.coroutines.delay(500) // let YouTube's own lazy-load actually render before scraping
        return scrapeNewItems()
    }

    private suspend fun scrollWebView() = suspendCancellableCoroutine<Unit> { continuation ->
        webView.evaluateJavascript(
            "(function(){if(window.__sparkyScrollFeed) window.__sparkyScrollFeed(); return true;})();"
        ) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private suspend fun scrapeNewItems(): List<FeedVideo> =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(
                "(function(){return window.__sparkyGetFeedVideosJson ? " +
                    "window.__sparkyGetFeedVideosJson($consumedRowCount) : " +
                    "JSON.stringify({items:[],totalRowsSeen:0});})();"
            ) { rawResult ->
                val parsed = try {
                    // evaluateJavascript double-JSON-encodes string results,
                    // same as the related-videos fetch -- one org.json
                    // decode to unwrap the outer quoting first.
                    val unwrapped = JSONTokener(rawResult ?: "\"{}\"").nextValue() as? String ?: "{}"
                    val obj = org.json.JSONObject(unwrapped)
                    val itemsArray = obj.optJSONArray("items") ?: JSONArray()
                    consumedRowCount = obj.optInt("totalRowsSeen", consumedRowCount)

                    (0 until itemsArray.length()).mapNotNull { i ->
                        val item = itemsArray.optJSONObject(i) ?: return@mapNotNull null
                        val videoId = item.optString("videoId").takeIf { it.length == 11 } ?: return@mapNotNull null
                        FeedVideo(
                            videoId = videoId,
                            title = item.optString("title"),
                            uploaderName = item.optString("uploaderName"),
                            durationSeconds = parseDurationText(item.optString("durationText")),
                            viewsText = item.optString("viewsText"),
                            thumbnailUrl = item.optString("thumbnailUrl").takeIf { it.isNotBlank() }
                        )
                    }
                } catch (e: Exception) {
                    emptyList()
                }
                if (continuation.isActive) continuation.resume(parsed)
            }
        }

    private fun parseDurationText(text: String): Long {
        val parts = text.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            1 -> parts[0].toLong()
            2 -> (parts[0] * 60L + parts[1])
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2])
            else -> 0L
        }
    }
}
