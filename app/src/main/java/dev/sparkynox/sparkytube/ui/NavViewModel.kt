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
    val pages = mutableStateListOf<Page>()

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
    var watchInfo by mutableStateOf<InnerTubeClient.WatchInfo?>(null); private set
    var related by mutableStateOf<List<HomeFeedItem>>(emptyList()); private set
    var relatedLoading by mutableStateOf(false); private set

    private var relatedToken: String? = null
    private var watchJob: Job? = null

    var watchPlaylistId by mutableStateOf<String?>(null); private set
    val queue: List<HomeFeedItem> get() = watchInfo?.queue ?: emptyList()

    fun openWatch(videoId: String, title: String, playlistId: String? = null) {
        if (videoId == watchId && watchInfo != null) {
            watchVisible = true
            return
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
        val q = queue
        if (q.isNotEmpty()) {
            val i = q.indexOfFirst { it.videoId == watchId }
            q.getOrNull(i + 1)?.let { return it }
        }
        return nextRelated()
    }

    private fun nextRelated(): HomeFeedItem? = related.firstOrNull { it.videoId != null && it.videoId != watchId }
}
