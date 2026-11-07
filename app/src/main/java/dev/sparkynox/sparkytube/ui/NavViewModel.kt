package dev.sparkynox.sparkytube.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sparkynox.sparkytube.homefeed.FeedState
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import dev.sparkynox.sparkytube.lunokit.channels.ChannelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * All navigation state for the Compose UI lives here, so the system back button, the bottom bar and
 * the screens never disagree about where the user is.
 *
 *   tab          which bottom tab is selected
 *   pages        sub-pages pushed on top of the tab (search results, history, a playlist...)
 *   watchVisible the Watch screen (title, description, related) is open under the player
 */
class NavViewModel : ViewModel() {

    enum class Tab { HOME, SHORTS, SUBS, LIBRARY }

    class Page(val title: String, val feed: FeedState)

    var tab by mutableStateOf(Tab.HOME)

    // full-screen search (NewPipe), drawn above the tabs
    var searchVisible by mutableStateOf(false)
    val search = dev.sparkynox.sparkytube.search.SearchState(viewModelScope)
    val pages = mutableStateListOf<Page>()

    // channel pages (LunoChannels), drawn above the tab / pages, under search and watch
    val channels = mutableStateListOf<ChannelState>()

    fun openChannel(id: String) {
        if (channels.lastOrNull()?.channelId == id) return
        channels.add(ChannelState(viewModelScope, id))
    }

    fun popChannel(): Boolean {
        if (channels.isEmpty()) return false
        channels.removeAt(channels.lastIndex)
        return true
    }

    val subs = FeedState(viewModelScope, FeedState.Source.Browse("FEsubscriptions"))

    var subChannels by mutableStateOf<List<InnerTubeClient.ChannelItem>>(emptyList()); private set
    private var subChannelsLoaded = false

    fun loadSubChannels() {
        if (subChannelsLoaded) return
        subChannelsLoaded = true
        viewModelScope.launch {
            val r = InnerTubeClient.fetchSubscribedChannels()
            if (r.isEmpty()) subChannelsLoaded = false else subChannels = r
        }
    }

    fun push(title: String, source: FeedState.Source) {
        pages.add(Page(title, FeedState(viewModelScope, source)))
    }

    fun pop(): Boolean {
        if (pages.isEmpty()) return false
        pages.removeAt(pages.lastIndex)
        return true
    }

    // ---------- Shorts

    var shorts by mutableStateOf<List<HomeFeedItem>>(emptyList()); private set
    var shortsLoading by mutableStateOf(false); private set
    var shortsError by mutableStateOf<String?>(null); private set

    fun loadShorts(force: Boolean = false) {
        if (shortsLoading || (!force && shorts.isNotEmpty())) return
        viewModelScope.launch {
            shortsLoading = true
            shortsError = null
            try {
                val r = InnerTubeClient.fetchShorts()
                shorts = r.items
                shortsError = r.error
            } catch (e: CancellationException) {
                throw e
            } finally {
                shortsLoading = false
            }
        }
    }

    // ---------- Watch

    var watchVisible by mutableStateOf(false)
    var watchId by mutableStateOf<String?>(null); private set
    var watchTitle by mutableStateOf(""); private set

    // false from the moment a video is opened until the native player actually has it, the watch page shows a loading picture meanwhile
    var playerReady by mutableStateOf(false)
    var watchInfo by mutableStateOf<InnerTubeClient.WatchInfo?>(null); private set
    var related by mutableStateOf<List<HomeFeedItem>>(emptyList()); private set
    var relatedLoading by mutableStateOf(false); private set

    private var relatedToken: String? = null
    private var watchJob: Job? = null

    var watchPlaylistId by mutableStateOf<String?>(null); private set

    // videos added from the ⋮ menu, they play before playlist / related
    val userQueue = androidx.compose.runtime.mutableStateListOf<HomeFeedItem>()
    // playlists and mixes can be queued too, they have no videoId
    private fun key(i: HomeFeedItem) = i.videoId ?: i.playlistId

    fun clearQueue() = userQueue.clear()

    fun moveInQueue(index: Int, delta: Int) {
        val to = index + delta
        if (index !in userQueue.indices || to !in userQueue.indices) return
        val item = userQueue.removeAt(index)
        userQueue.add(to, item)
    }

    // the queue survives app restarts
    private var queuePrefs: android.content.SharedPreferences? = null

    fun attachContext(ctx: android.content.Context) {
        if (queuePrefs != null) return
        val sp = ctx.applicationContext.getSharedPreferences("sparkytube_queue", android.content.Context.MODE_PRIVATE)
        queuePrefs = sp
        runCatching {
            val arr = org.json.JSONArray(sp.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                userQueue.add(
                    HomeFeedItem(
                        videoId = o.optString("v").ifEmpty { null },
                        playlistId = o.optString("p").ifEmpty { null },
                        title = o.optString("t"),
                        channelName = o.optString("c"),
                        thumbnailUrl = o.optString("th"),
                        durationText = o.optString("d"),
                        viewCountText = "",
                        itemCountText = o.optString("n").ifEmpty { null },
                        channelId = o.optString("ci").ifEmpty { null },
                    )
                )
            }
        }
        var first = true
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { userQueue.toList() }.collect { list ->
                if (first) { first = false; return@collect }
                val arr = org.json.JSONArray()
                list.forEach {
                    arr.put(
                        org.json.JSONObject()
                            .put("v", it.videoId ?: "").put("p", it.playlistId ?: "")
                            .put("t", it.title).put("c", it.channelName).put("th", it.thumbnailUrl)
                            .put("d", it.durationText).put("n", it.itemCountText ?: "").put("ci", it.channelId ?: "")
                    )
                }
                sp.edit().putString("items", arr.toString()).apply()
            }
        }
    }

    // what was played before, for the Previous button
    class Played(val id: String, val title: String, val playlistId: String?)
    private val played = ArrayList<Played>()
    fun previousVideo(): Played? = if (played.isEmpty()) null else played.removeAt(played.lastIndex)
    fun playNext(item: HomeFeedItem) { userQueue.removeAll { key(it) == key(item) }; userQueue.add(0, item) }
    fun addToQueue(item: HomeFeedItem) { if (userQueue.none { key(it) == key(item) }) userQueue.add(item) }

    // the playlist / mix that was playing when a queued video cut in, so it carries on afterwards
    private class Resume(val playlistId: String, val items: List<HomeFeedItem>, val fromId: String?)
    private var resume: Resume? = null
    val queue: List<HomeFeedItem> get() = watchInfo?.queue ?: emptyList()

    // auto = opened by autoplay (queue / playlist / related), false = the user picked it
    fun openWatch(videoId: String, title: String, playlistId: String? = null, auto: Boolean = false, back: Boolean = false) {
        if (videoId == watchId && watchInfo != null) {
            watchVisible = true
            return
        }
        playerReady = false
        val cur = watchId
        if (!back && cur != null && cur != videoId) {
            played.add(Played(cur, watchTitle, watchPlaylistId))
            if (played.size > 40) played.removeAt(0)
        }
        userQueue.removeAll { it.videoId == videoId }
        val pl = watchPlaylistId
        resume = when {
            !auto -> null
            playlistId != null -> null
            pl != null && queue.isNotEmpty() -> Resume(pl, queue, watchId)
            else -> resume
        }
        watchId = videoId
        watchPlaylistId = playlistId
        watchTitle = title
        watchInfo = null
        related = emptyList()
        relatedToken = null
        watchVisible = true

        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            val info = InnerTubeClient.fetchWatch(videoId, playlistId)
            if (watchId != videoId) return@launch
            watchInfo = info
            related = info.related
            relatedToken = info.continuation
        }
    }

    fun closeWatch() {
        resume = null
        playerReady = false
        watchVisible = false
        watchId = null
        watchInfo = null
        related = emptyList()
        relatedToken = null
        watchJob?.cancel()
    }

    fun loadMoreRelated() {
        val token = relatedToken ?: return
        if (relatedLoading) return
        val forId = watchId
        viewModelScope.launch {
            relatedLoading = true
            try {
                val r = InnerTubeClient.fetchNextContinuation(token)
                if (watchId != forId) return@launch
                val seen = related.mapTo(HashSet()) { it.videoId ?: it.playlistId }
                related = related + r.items.filter { (it.videoId ?: it.playlistId) !in seen }
                relatedToken = r.continuationToken
            } catch (e: CancellationException) {
                throw e
            } finally {
                relatedLoading = false
            }
        }
    }

    /** What autoplay moves on to when the current video ends. */
    fun nextVideo(): HomeFeedItem? {
        userQueue.firstOrNull()?.let { return it }
        val q = queue
        if (q.isNotEmpty()) {
            val i = q.indexOfFirst { it.videoId == watchId }
            q.getOrNull(i + 1)?.let { return it }
        }
        resume?.let { r ->
            val i = r.items.indexOfFirst { it.videoId == r.fromId }
            if (i >= 0) r.items.getOrNull(i + 1)?.let { return it }
        }
        return nextRelated()
    }

    private fun nextRelated(): HomeFeedItem? = related.firstOrNull { it.videoId != null && it.videoId != watchId }
}
