package dev.sparkynox.sparkytube.lunokit.channels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChannelState(private val scope: CoroutineScope, val channelId: String) {

    enum class Kind { VIDEOS, SHORTS, LIVE, PLAYLISTS, ABOUT }

    class Tab(val title: String, val params: String?, val kind: Kind)

    class Feed {
        var items by mutableStateOf<List<HomeFeedItem>>(emptyList())
        var loading by mutableStateOf(false)
        var loadingMore by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var loaded by mutableStateOf(false)
        var token: String? = null
    }

    var header by mutableStateOf<InnerTubeClient.ChannelHeader?>(null); private set
    var tabs by mutableStateOf<List<Tab>>(emptyList()); private set
    var selected by mutableStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private val feeds = HashMap<String, Feed>()
    private var started = false
    private var job: Job? = null

    val current: Tab? get() = tabs.getOrNull(selected)

    fun feed(tab: Tab): Feed = feeds.getOrPut(tab.title) { Feed() }

    fun start() {
        if (started) return
        started = true
        load()
    }

    private fun load() {
        job?.cancel()
        job = scope.launch {
            loading = true
            error = null
            try {
                val page = InnerTubeClient.fetchChannel(channelId)
                header = page.header
                if (page.header == null && page.tabs.isEmpty()) {
                    error = page.error ?: "couldn't open this channel"
                    return@launch
                }
                tabs = buildTabs(page.tabs)
                selected = tabs.indexOfFirst { it.kind == Kind.VIDEOS }.coerceAtLeast(0)
            } catch (e: CancellationException) {
                throw e
            } finally {
                loading = false
            }
            current?.let { loadTab(it) }
        }
    }

    private fun buildTabs(raw: List<InnerTubeClient.ChannelTab>): List<Tab> {
        val out = raw.mapNotNull { t ->
            when (t.title.lowercase()) {
                "videos" -> Tab(t.title, t.params, Kind.VIDEOS)
                "shorts" -> Tab(t.title, t.params, Kind.SHORTS)
                "live" -> Tab(t.title, t.params, Kind.LIVE)
                "playlists" -> Tab(t.title, t.params, Kind.PLAYLISTS)
                else -> null
            }
        }.toMutableList()
        // tab list didn't parse, the Videos tab params are a long-standing constant
        if (raw.isEmpty()) out.add(0, Tab("Videos", VIDEOS_PARAMS, Kind.VIDEOS))
        out.add(Tab("About", null, Kind.ABOUT))
        return out
    }

    fun select(i: Int) {
        if (i == selected) return
        selected = i
        tabs.getOrNull(i)?.let { if (!feed(it).loaded) loadTab(it) }
    }

    fun refresh() {
        if (error != null || header == null) {
            load()
            return
        }
        val tab = current ?: return
        refreshing = true
        loadTab(tab)
    }

    private fun loadTab(tab: Tab) {
        if (tab.kind == Kind.ABOUT) { refreshing = false; return }
        val f = feed(tab)
        if (f.loading) return
        scope.launch {
            f.loading = true
            f.error = null
            try {
                val r = InnerTubeClient.fetchChannelTab(channelId, tab.params, tab.kind == Kind.SHORTS)
                if (r.error != null && r.items.isEmpty()) {
                    f.error = r.error
                } else {
                    f.items = decorate(r.items)
                    f.token = r.continuationToken
                }
                f.loaded = true
            } catch (e: CancellationException) {
                throw e
            } finally {
                f.loading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val tab = current ?: return
        val f = feed(tab)
        val token = f.token ?: return
        if (f.loadingMore || f.loading) return
        scope.launch {
            f.loadingMore = true
            try {
                val r = InnerTubeClient.fetchChannelContinuation(token, tab.kind == Kind.SHORTS)
                if (r.error == null) {
                    val seen = f.items.mapTo(HashSet()) { it.videoId ?: it.playlistId }
                    f.items = f.items + decorate(r.items).filter { (it.videoId ?: it.playlistId) !in seen }
                    f.token = r.continuationToken
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                f.loadingMore = false
            }
        }
    }

    // channel tabs don't carry the owner on each card, fill it from the header
    private fun decorate(items: List<HomeFeedItem>): List<HomeFeedItem> {
        val h = header ?: return items
        return items.map {
            it.copy(
                channelName = it.channelName.ifBlank { h.name },
                channelAvatarUrl = it.channelAvatarUrl ?: h.avatar,
                channelId = it.channelId ?: channelId,
            )
        }
    }

    private companion object {
        const val VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    }
}
