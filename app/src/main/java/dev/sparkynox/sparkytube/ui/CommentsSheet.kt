package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.homefeed.Comment
import dev.sparkynox.sparkytube.homefeed.CommentSort
import dev.sparkynox.sparkytube.homefeed.CommentsApi
import dev.sparkynox.sparkytube.lunokit.glass.LunoSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** State of one open comments sheet. [knownToken] comes from the watch page when it has it. */
class CommentsState(
    private val scope: CoroutineScope,
    private val videoId: String,
    private val knownToken: String?,
) {
    val comments = mutableStateListOf<Comment>()
    var loading by mutableStateOf(true); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var total by mutableStateOf<String?>(null); private set
    var sorts by mutableStateOf<List<CommentSort>>(emptyList()); private set
    var activeSort by mutableStateOf(0); private set
    var disabled by mutableStateOf(false); private set

    // replies per comment id
    val replies = mutableStateMapOf<String, List<Comment>>()
    val repliesLoading = mutableStateMapOf<String, Boolean>()
    private val repliesNext = HashMap<String, String?>()

    private var next: String? = null
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            loading = true
            error = null
            val token = knownToken ?: CommentsApi.initialToken(videoId)
            if (token == null) {
                disabled = true
                loading = false
                return@launch
            }
            apply(CommentsApi.load(token), replace = true)
            loading = false
        }
    }

    fun selectSort(index: Int) {
        val s = sorts.getOrNull(index) ?: return
        if (index == activeSort) return
        job?.cancel()
        job = scope.launch {
            activeSort = index
            loading = true
            comments.clear(); replies.clear(); repliesNext.clear()
            val page = CommentsApi.load(s.token)
            // keep the sort list from the first page, later pages do not carry the header
            val keep = sorts
            apply(page, replace = true)
            if (sorts.isEmpty()) sorts = keep
            loading = false
        }
    }

    fun loadMore() {
        val token = next ?: return
        if (loadingMore || loading) return
        scope.launch {
            loadingMore = true
            apply(CommentsApi.load(token), replace = false)
            loadingMore = false
        }
    }

    private fun apply(page: dev.sparkynox.sparkytube.homefeed.CommentsPage, replace: Boolean) {
        if (page.error != null && page.comments.isEmpty()) {
            if (replace) error = page.error
            return
        }
        if (replace) comments.clear()
        val seen = comments.mapTo(HashSet()) { it.id }
        comments.addAll(page.comments.filter { it.id !in seen })
        next = page.next
        if (page.total != null) total = page.total
        if (page.sorts.isNotEmpty()) {
            sorts = page.sorts
            val sel = page.sorts.indexOfFirst { it.selected }
            if (sel >= 0) activeSort = sel
        }
    }

    fun toggleReplies(c: Comment) {
        if (replies.containsKey(c.id)) {
            replies.remove(c.id)
            return
        }
        val token = c.repliesToken ?: return
        loadReplies(c.id, token)
    }

    fun moreReplies(c: Comment) {
        val token = repliesNext[c.id] ?: return
        loadReplies(c.id, token)
    }

    fun hasMoreReplies(c: Comment) = repliesNext[c.id] != null

    private fun loadReplies(id: String, token: String) {
        if (repliesLoading[id] == true) return
        scope.launch {
            repliesLoading[id] = true
            val page = CommentsApi.load(token)
            replies[id] = (replies[id] ?: emptyList()) + page.comments
            repliesNext[id] = page.next
            repliesLoading[id] = false
        }
    }
}

@Composable
fun CommentsSheet(videoId: String, knownToken: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember(videoId) { CommentsState(scope, videoId, knownToken).also { it.start() } }
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.comments.size) { if (nearEnd && state.comments.isNotEmpty()) state.loadMore() }

    LunoSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.78f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                state.total?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.sorts.size > 1) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sorts.forEachIndexed { i, s ->
                        FilterChip(selected = i == state.activeSort, onClick = { state.selectSort(i) }, label = { Text(s.title) })
                    }
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LunoLoader() }
                state.disabled -> Message("Comments are turned off for this video.")
                state.error != null && state.comments.isEmpty() -> Column(Modifier.padding(20.dp)) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { state.start() }) { Text("Retry") }
                }
                state.comments.isEmpty() -> Message("No comments yet.")
                else -> LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.comments, key = { it.id }) { c ->
                        Column {
                            CommentRow(c, 38)
                            if (c.repliesToken != null) {
                                val open = state.replies.containsKey(c.id)
                                val label = when {
                                    state.repliesLoading[c.id] == true -> "Loading replies..."
                                    open -> "Hide replies"
                                    else -> (c.replyCount?.let { "$it ${if (it == "1") "reply" else "replies"}" } ?: "Show replies")
                                }
                                Text(
                                    label,
                                    Modifier.padding(start = 50.dp, top = 6.dp).clickable { state.toggleReplies(c) },
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                )
                                if (open) {
                                    Column(
                                        Modifier.padding(start = 50.dp, top = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        state.replies[c.id]?.forEach { r -> CommentRow(r, 28) }
                                        if (state.hasMoreReplies(c)) Text(
                                            "More replies",
                                            Modifier.clickable { state.moreReplies(c) },
                                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.loadingMore) item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { LunoLoader(size = 28.dp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommentRow(c: Comment, avatarDp: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(c.avatar, c.author.removePrefix("@").ifBlank { "?" }, avatarDp)
        Column(Modifier.weight(1f)) {
            if (c.pinned) Text(
                "Pinned", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.author, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (c.byCreator) FontWeight.Bold else FontWeight.Medium,
                    color = if (c.byCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (c.time.isNotBlank()) Text(
                    "  " + c.time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            if (c.likes.isNotBlank()) Row(
                Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_thumb_up), null, Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(c.likes, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
