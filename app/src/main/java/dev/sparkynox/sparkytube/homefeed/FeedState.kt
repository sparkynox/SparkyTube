package dev.sparkynox.sparkytube.homefeed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One scrolling list of videos with pull-to-refresh and infinite scroll: subscriptions, history,
 * search results, a playlist. (The Home tab has its own HomeFeedViewModel because it also carries
 * chips and the account.)
 */
class FeedState(private val scope: CoroutineScope, private val source: Source) {

    sealed interface Source {
        data class Browse(val id: String) : Source
        data class Search(val query: String) : Source
    }

    var items by mutableStateOf<List<HomeFeedItem>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private var continuation: String? = null
    private var job: Job? = null

    fun onShown() {
        if (items.isEmpty() && !loading && error == null) refresh()
    }

    fun refresh(pull: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            try {
                val r = when (val s = source) {
                    is Source.Browse -> InnerTubeClient.fetchBrowse(s.id)
                    is Source.Search -> InnerTubeClient.fetchSearch(s.query)
                }
                if (r.error != null && r.items.isEmpty()) {
                    error = r.error
                } else {
                    items = r.items
                    continuation = r.continuationToken
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (loadingMore || loading || refreshing) return
        scope.launch {
            loadingMore = true
            try {
                val r = when (source) {
                    is Source.Browse -> InnerTubeClient.fetchHomeFeedContinuation(token)
                    is Source.Search -> InnerTubeClient.fetchSearchContinuation(token)
                }
                if (r.error != null) {
                    LogRecorder.w("Feed", "load more failed: ${r.error}")
                    return@launch
                }
                val seen = items.mapTo(HashSet()) { it.videoId ?: it.playlistId }
                items = items + r.items.filter { (it.videoId ?: it.playlistId) !in seen }
                continuation = r.continuationToken
            } catch (e: CancellationException) {
                throw e
            } finally {
                loadingMore = false
            }
        }
    }
}
