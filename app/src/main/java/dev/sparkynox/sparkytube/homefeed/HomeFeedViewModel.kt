package dev.sparkynox.sparkytube.homefeed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** State for the Compose home feed. Same shape as LunoMusic's home state: items, chips, continuation. */
class HomeFeedViewModel : ViewModel() {

    var items by mutableStateOf<List<HomeFeedItem>>(emptyList()); private set
    var chips by mutableStateOf<List<FeedChip>>(emptyList()); private set
    var selectedChip by mutableStateOf<FeedChip?>(null); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var loggedIn by mutableStateOf(Session.loggedIn); private set
    var accountName by mutableStateOf(Session.accountName); private set
    var accountPhoto by mutableStateOf(Session.accountPhoto); private set

    private var continuation: String? = null
    private var loadJob: Job? = null

    /** Called whenever the feed becomes visible; only loads when there is nothing yet. */
    fun onShown() {
        syncLoginState()
        if (items.isEmpty() && !loading) refresh()
    }

    fun refresh(pull: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            try {
                val r = selectedChip?.token?.let { InnerTubeClient.fetchHomeFeedContinuation(it) }
                    ?: InnerTubeClient.fetchHomeFeed()
                loggedIn = r.isLoggedIn
                if (r.error != null && r.items.isEmpty()) {
                    error = r.error
                } else {
                    items = r.items
                    continuation = r.continuationToken
                    // the chip bar only comes with the plain home response; keep it when a chip is active
                    if (r.chips.isNotEmpty() && selectedChip == null) chips = r.chips
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun selectChip(c: FeedChip) {
        // tapping the active chip, or "All", goes back to the plain feed
        val back = c.token == null || selectedChip == c
        selectedChip = if (back) null else c
        items = emptyList()
        continuation = null
        refresh()
    }

    fun loadMore() {
        val token = continuation ?: return
        if (loadingMore || loading || refreshing) return
        viewModelScope.launch {
            loadingMore = true
            try {
                val r = InnerTubeClient.fetchHomeFeedContinuation(token)
                if (r.error != null) {
                    LogRecorder.w("Home", "load more failed: ${r.error}")
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

    /** LoginActivity finished: start over with the new account. */
    fun onLoggedIn() {
        LogRecorder.i("Home", "logged in, reloading feed")
        syncLoginState()
        selectedChip = null; chips = emptyList(); items = emptyList(); continuation = null
        refresh()
        loadAccount()
    }

    fun logout() {
        Session.logout()
        LogRecorder.i("Home", "logged out")
        syncLoginState()
        accountName = ""; accountPhoto = ""
        selectedChip = null; chips = emptyList(); items = emptyList(); continuation = null
        refresh()
    }

    private fun syncLoginState() {
        loggedIn = Session.loggedIn
        if (loggedIn && accountName.isEmpty()) loadAccount()
    }

    private fun loadAccount() {
        viewModelScope.launch {
            val a = InnerTubeClient.fetchAccount() ?: return@launch
            Session.accountName = a.name; Session.accountHandle = a.handle ?: ""; Session.accountPhoto = a.photo ?: ""
            accountName = a.name; accountPhoto = a.photo ?: ""
        }
    }
}
