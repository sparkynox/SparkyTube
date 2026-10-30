package dev.sparkynox.sparkytube.lunokit.channels

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.homefeed.HiddenVideos
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import dev.sparkynox.sparkytube.ui.Avatar
import dev.sparkynox.sparkytube.ui.ErrorCard
import dev.sparkynox.sparkytube.ui.VideoCard
import kotlinx.coroutines.launch

private val FULL = { _: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope -> GridItemSpan(2) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    state: ChannelState,
    subscribed: List<InnerTubeClient.ChannelItem>,
    loggedIn: Boolean,
    onBack: () -> Unit,
    onOpen: (HomeFeedItem) -> Unit,
    onMore: (HomeFeedItem) -> Unit,
    onLogin: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }

    val tab = state.current
    val feed = tab?.let { state.feed(it) }
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 4
        }
    }
    val count = feed?.items?.size ?: 0
    LaunchedEffect(nearEnd, count, state.selected) { if (nearEnd && count > 0) state.loadMore() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { state.refresh() }) {
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "head", span = FULL) {
                    Header(state, subscribed, loggedIn, onBack, onLogin)
                }

                if (state.loading && state.header == null) {
                    item(key = "spin", span = FULL) {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                state.error?.let { msg ->
                    item(key = "err", span = FULL) { ErrorCard(msg) { state.refresh() } }
                }

                if (state.tabs.isNotEmpty()) item(key = "tabs", span = FULL) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(state.tabs) { i, t ->
                            FilterChip(
                                selected = i == state.selected,
                                onClick = { state.select(i) },
                                label = { Text(t.title) },
                            )
                        }
                    }
                }

                if (tab?.kind == ChannelState.Kind.ABOUT) {
                    item(key = "about", span = FULL) { About(state) }
                } else if (tab != null && feed != null) {
                    if (feed.loading && feed.items.isEmpty()) item(key = "feedspin", span = FULL) {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    feed.error?.let { msg ->
                        if (feed.items.isEmpty()) item(key = "feederr", span = FULL) { ErrorCard(msg) { state.refresh() } }
                    }
                    if (feed.loaded && !feed.loading && feed.error == null && feed.items.isEmpty()) {
                        item(key = "empty", span = FULL) {
                            Text(
                                "Nothing here.", Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val list = HiddenVideos.visible(feed.items)
                    if (tab.kind == ChannelState.Kind.SHORTS) {
                        itemsIndexed(list, key = { _, s -> s.videoId ?: s.title }) { i, s ->
                            val left = i % 2 == 0
                            Box(Modifier.padding(start = if (left) 12.dp else 4.dp, end = if (left) 4.dp else 12.dp)) {
                                ShortTile(s) { onOpen(s) }
                            }
                        }
                    } else {
                        items(list, key = { it.videoId ?: it.playlistId ?: it.title }, span = { GridItemSpan(maxLineSpan) }) { item ->
                            VideoCard(item, onOpen = { onOpen(item) }, onMore = { onMore(item) })
                        }
                    }
                    if (feed.loadingMore) item(key = "more", span = FULL) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: ChannelState,
    subscribedList: List<InnerTubeClient.ChannelItem>,
    loggedIn: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val h = state.header
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(state) { mutableStateOf(false) }
    var subscribed by remember(h, subscribedList) {
        mutableStateOf(h?.subscribed ?: subscribedList.any { it.id == state.channelId })
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            if (h?.banner != null) {
                AsyncImage(h.banner, null, Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxWidth().height(56.dp))
            }
            Box(
                Modifier.padding(10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                    .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("\u2190", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }

        if (h != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(h.avatar, h.name, 72)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        h.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val line = listOfNotNull(h.handle, h.subscribers, h.videoCount).joinToString(" \u2022 ")
                    if (line.isNotBlank()) Text(
                        line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            h.description?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp).clickable { expanded = !expanded },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
            }

            val onSub: () -> Unit = {
                if (!loggedIn && !Session.loggedIn) {
                    Toast.makeText(ctx, "Sign in first (Home tab)", Toast.LENGTH_SHORT).show()
                    onLogin()
                } else {
                    val target = !subscribed
                    subscribed = target
                    scope.launch {
                        val ok = if (target) InnerTubeClient.subscribe(state.channelId) else InnerTubeClient.unsubscribe(state.channelId)
                        if (!ok) {
                            subscribed = !target
                            Toast.makeText(ctx, "Couldn't update subscription", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (subscribed) OutlinedButton(onClick = onSub, Modifier.fillMaxWidth()) { Text("Subscribed") }
                else Button(onClick = onSub, Modifier.fillMaxWidth()) { Text("Subscribe") }
            }
        }
    }
}

@Composable
private fun About(state: ChannelState) {
    val h = state.header
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val desc = h?.description
        Text(
            if (desc.isNullOrBlank()) "No description." else desc,
            style = MaterialTheme.typography.bodyMedium,
        )
        listOfNotNull(h?.handle, h?.subscribers, h?.videoCount).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShortTile(s: HomeFeedItem, onOpen: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen),
    ) {
        AsyncImage(s.thumbnailUrl, s.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f)).padding(10.dp),
        ) {
            Text(
                s.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (s.viewCountText.isNotBlank()) Text(
                s.viewCountText, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
