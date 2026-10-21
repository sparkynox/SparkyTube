package dev.sparkynox.sparkytube.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem

/**
 * Everything under the player while a video is open: title, channel, description, up next.
 * The player itself is the existing ExoPlayer view drawn on top of the first 220dp of the screen.
 */
@Composable
fun WatchScreen(nav: NavViewModel, onOpen: (HomeFeedItem) -> Unit, onMore: (HomeFeedItem) -> Unit) {
    val info = nav.watchInfo
    val openChannel = LocalOpenChannel.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 0 none, 1 liked, 2 disliked. YouTube's current state isn't read, so it starts neutral
    var reaction by remember(nav.watchId) { mutableStateOf(0) }
    var subscribed by remember(nav.watchId, info?.subscribed) { mutableStateOf(info?.subscribed ?: false) }
    fun needLogin(): Boolean {
        if (Session.loggedIn) return false
        Toast.makeText(ctx, "Sign in first (Home tab)", Toast.LENGTH_SHORT).show()
        return true
    }
    var expanded by remember(nav.watchId) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val nearEnd by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, nav.related.size) { if (nearEnd && nav.related.isNotEmpty()) nav.loadMoreRelated() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            state = list,
            // the first 220dp belong to the ExoPlayer view that MainActivity draws above this
            modifier = Modifier.fillMaxSize().padding(top = 220.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "title") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        info?.title ?: nav.watchTitle,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    info?.meta?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (info == null) item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            if (info?.channelName != null) item(key = "channel") {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cid = info.channelId
                    val tap = if (cid != null) Modifier.clickable { openChannel(cid) } else Modifier
                    Avatar(info.channelAvatar, info.channelName, 40, tap)
                    Column(Modifier.padding(start = 12.dp).weight(1f).then(tap)) {
                        Text(
                            info.channelName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        info.subscribers?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val cid2 = info.channelId
                    if (cid2 != null) {
                        val onSub: () -> Unit = {
                            if (!needLogin()) {
                                val target = !subscribed
                                subscribed = target
                                scope.launch {
                                    val ok = if (target) InnerTubeClient.subscribe(cid2) else InnerTubeClient.unsubscribe(cid2)
                                    if (!ok) { subscribed = !target; Toast.makeText(ctx, "Couldn't update subscription", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                        if (subscribed) OutlinedButton(onClick = onSub) { Text("Subscribed") }
                        else Button(onClick = onSub) { Text("Subscribe") }
                    }
                }
            }

            item(key = "actions") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val vid = nav.watchId
                    val likeBtn: @Composable (String, Boolean, () -> Unit) -> Unit = { label, on, click ->
                        if (on) Button(onClick = click) { Text(label) } else FilledTonalButton(onClick = click) { Text(label) }
                    }
                    likeBtn("Like", reaction == 1) {
                        if (vid != null && !needLogin()) {
                            val prev = reaction
                            reaction = if (prev == 1) 0 else 1
                            scope.launch {
                                val ok = if (prev == 1) InnerTubeClient.removeLike(vid) else InnerTubeClient.like(vid)
                                if (!ok) reaction = prev
                            }
                        }
                    }
                    likeBtn("Dislike", reaction == 2) {
                        if (vid != null && !needLogin()) {
                            val prev = reaction
                            reaction = if (prev == 2) 0 else 2
                            scope.launch {
                                val ok = if (prev == 2) InnerTubeClient.removeLike(vid) else InnerTubeClient.dislike(vid)
                                if (!ok) reaction = prev
                            }
                        }
                    }
                    FilledTonalButton(onClick = {
                        if (vid != null) {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$vid")
                            ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Share") }
                }
            }

            if (!info?.description.isNullOrBlank()) item(key = "description") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp)).clickable { expanded = !expanded },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        info?.description.orEmpty(),
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (nav.queue.isNotEmpty()) {
                item(key = "queue-title") {
                    val idx = nav.queue.indexOfFirst { it.videoId == nav.watchId } + 1
                    Text(
                        "Playlist \u2022 " + (info?.queueTitle ?: "Mix") + if (idx > 0) "  ($idx/${nav.queue.size})" else "",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                items(nav.queue, key = { "q-" + it.videoId }) { q ->
                    val current = q.videoId == nav.watchId
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { if (!current) onOpen(q) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            q.thumbnailUrl, q.title,
                            Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(q.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(q.channelName, q.durationText).filter { it.isNotBlank() }.joinToString(" \u2022 "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (nav.related.isNotEmpty()) item(key = "upnext") {
                Text(
                    "Up next", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
            }
            items(nav.related, key = { it.videoId ?: it.playlistId ?: it.title }) { item ->
                VideoCard(item, onOpen = { onOpen(item) }, onMore = { onMore(item) })
            }
            if (nav.relatedLoading) item(key = "more") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    }
}
