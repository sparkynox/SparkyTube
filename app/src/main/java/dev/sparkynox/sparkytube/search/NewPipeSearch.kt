package dev.sparkynox.sparkytube.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sparkynox.sparkytube.extractor.StreamExtractor
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

sealed interface SearchHit {
    class Video(val item: HomeFeedItem) : SearchHit
    class Playlist(val item: HomeFeedItem) : SearchHit
    class Channel(val id: String, val name: String, val avatar: String?, val info: String) : SearchHit
}

// Search through the NewPipe extractor (no account, no InnerTube key). Results come in pages.
object NewPipeSearch {

    class Page(val hits: List<SearchHit>, val next: org.schabi.newpipe.extractor.Page?)

    private fun yt() = ServiceList.YouTube

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            StreamExtractor.init()
            yt().suggestionExtractor.suggestionList(query)
        } catch (e: Exception) {
            LogRecorder.w("Search", "suggestions failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun search(query: String, filter: String): Page = withContext(Dispatchers.IO) {
        StreamExtractor.init()
        val handler = yt().searchQHFactory.fromQuery(query, listOf(filter), "")
        val ex = yt().getSearchExtractor(handler)
        ex.fetchPage()
        val p = ex.initialPage
        Page(p.items.mapNotNull(::toHit), p.nextPage)
    }

    suspend fun more(query: String, filter: String, next: org.schabi.newpipe.extractor.Page): Page = withContext(Dispatchers.IO) {
        val handler = yt().searchQHFactory.fromQuery(query, listOf(filter), "")
        val ex = yt().getSearchExtractor(handler)
        val p = ex.getPage(next)
        Page(p.items.mapNotNull(::toHit), p.nextPage)
    }

    private fun toHit(i: InfoItem): SearchHit? = when (i) {
        is StreamInfoItem -> {
            val id = videoId(i.url) ?: return null
            SearchHit.Video(
                HomeFeedItem(
                    videoId = id, playlistId = null, title = i.name,
                    channelName = i.uploaderName.orEmpty(),
                    thumbnailUrl = i.thumbnails.firstOrNull()?.url ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    durationText = if (i.duration > 0) clock(i.duration) else "",
                    viewCountText = listOf(views(i.viewCount), i.textualUploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString(" • "),
                    isShort = i.isShortFormContent,
                    channelId = channelId(i.uploaderUrl),
                )
            )
        }
        is PlaylistInfoItem -> {
            val id = Regex("list=([\\w-]+)").find(i.url)?.groupValues?.get(1) ?: return null
            SearchHit.Playlist(
                HomeFeedItem(
                    videoId = null, playlistId = id, title = i.name,
                    channelName = i.uploaderName.orEmpty(),
                    thumbnailUrl = i.thumbnails.firstOrNull()?.url ?: "",
                    durationText = "", viewCountText = "",
                    itemCountText = if (i.streamCount >= 0) "${i.streamCount} videos" else null,
                )
            )
        }
        is ChannelInfoItem -> {
            val id = channelId(i.url) ?: return null
            SearchHit.Channel(
                id, i.name, i.thumbnails.firstOrNull()?.url,
                if (i.subscriberCount >= 0) "${count(i.subscriberCount)} subscribers" else "",
            )
        }
        else -> null
    }

    private fun videoId(url: String): String? =
        Regex("[?&]v=([\\w-]{11})").find(url)?.groupValues?.get(1)
            ?: Regex("/(?:shorts|live)/([\\w-]{11})").find(url)?.groupValues?.get(1)

    private fun channelId(url: String?): String? =
        url?.let { Regex("/channel/(UC[\\w-]+)").find(it)?.groupValues?.get(1) }

    private fun clock(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun count(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }.replace(".0", "")

    private fun views(n: Long): String = if (n >= 0) "${count(n)} views" else ""

    // ---- recent searches
    private const val PREFS = "sparkytube_search"

    fun recent(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "")
            .orEmpty().split("\n").filter { it.isNotBlank() }

    fun remember(ctx: Context, q: String) {
        val list = (listOf(q) + recent(ctx).filter { !it.equals(q, true) }).take(15)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", list.joinToString("\n")).apply()
    }

    fun forget(ctx: Context, q: String) {
        val list = recent(ctx).filter { it != q }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", list.joinToString("\n")).apply()
    }
}

// everything the Search screen shows, kept in the NavViewModel so rotating or opening a video doesn't lose it
class SearchState(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var filter by mutableStateOf("all")
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    val hits = mutableStateListOf<SearchHit>()
    val suggestions = mutableStateListOf<String>()
    private var next: org.schabi.newpipe.extractor.Page? = null
    private var job: Job? = null
    private var suggestJob: Job? = null

    val canLoadMore: Boolean get() = next != null && !loading

    // typing: suggestions after a short pause
    fun onType(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank()) { suggestions.clear(); return }
        suggestJob = scope.launch {
            delay(250)
            val r = NewPipeSearch.suggestions(text.trim())
            suggestions.clear()
            suggestions.addAll(r)
        }
    }

    fun submit(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        submitted = q
        suggestions.clear()
        run(reset = true)
    }

    fun changeFilter(f: String) {
        if (f == filter) return
        filter = f
        if (submitted.isNotEmpty()) run(reset = true)
    }

    fun loadMore() {
        if (!canLoadMore) return
        run(reset = false)
    }

    fun clear() {
        job?.cancel()
        query = ""; submitted = ""; hits.clear(); suggestions.clear(); next = null; error = null; loading = false
    }

    private fun run(reset: Boolean) {
        job?.cancel()
        job = scope.launch {
            loading = true
            error = null
            if (reset) { hits.clear(); next = null }
            try {
                val page = if (reset) NewPipeSearch.search(submitted, filter)
                else NewPipeSearch.more(submitted, filter, next ?: return@launch)
                hits.addAll(page.hits)
                next = page.next
                if (hits.isEmpty()) error = "Nothing found for \"$submitted\""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogRecorder.w("Search", "search failed: ${e.message}", e)
                error = e.message ?: "Search failed"
            } finally {
                loading = false
            }
        }
    }
}
