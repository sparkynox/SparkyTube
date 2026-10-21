package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.homefeed.FeedState
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.HomeFeedViewModel
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient

/** Cards and the watch screen call this to open a channel page. Provided by [AppHost]. */
val LocalOpenChannel = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Root of the app's Compose UI: whichever tab or sub-page is current, with the Watch screen laid
 * over it while a video is open. All the state is in [NavViewModel].
 */
@Composable
fun AppHost(
    nav: NavViewModel,
    home: HomeFeedViewModel,
    onOpen: (HomeFeedItem) -> Unit,
    onMore: (HomeFeedItem) -> Unit,
    onLogin: () -> Unit,
    onOpenDownloads: () -> Unit,
    onChannel: (String) -> Unit = {},
) {
    CompositionLocalProvider(LocalOpenChannel provides onChannel) {
    Box(Modifier.fillMaxSize()) {
        val page = nav.pages.lastOrNull()
        if (page != null) {
            LaunchedEffect(page) { page.feed.onShown() }
            FeedScreen(
                state = page.feed, title = page.title, onBack = { nav.pop() },
                needsLogin = false, loggedIn = home.loggedIn, emptyHint = "Nothing here.",
                onOpen = onOpen, onMore = onMore, onLogin = onLogin,
            )
        } else when (nav.tab) {
            NavViewModel.Tab.HOME -> {
                LaunchedEffect(Unit) { home.onShown() }
                HomeFeedScreen(home, onOpen = onOpen, onMore = onMore, onLogin = onLogin)
            }
            NavViewModel.Tab.SHORTS -> {
                LaunchedEffect(Unit) { nav.loadShorts() }
                ShortsScreen(nav, onOpen)
            }
            NavViewModel.Tab.SUBS -> {
                LaunchedEffect(home.loggedIn) { if (home.loggedIn) { nav.subs.onShown(); nav.loadSubChannels() } }
                val strip = nav.subChannels.ifEmpty {
                    nav.subs.items.filter { it.channelId != null }.distinctBy { it.channelId }
                        .map { InnerTubeClient.ChannelItem(it.channelId!!, it.channelName, it.channelAvatarUrl) }
                }
                FeedScreen(
                    state = nav.subs, title = "Subscriptions", onBack = null,
                    needsLogin = !home.loggedIn, loggedIn = home.loggedIn,
                    emptyHint = "No new videos from your subscriptions.",
                    onOpen = onOpen, onMore = onMore, onLogin = onLogin,
                    header = if (strip.isEmpty()) null else ({ ChannelStrip(strip, onChannel) }),
                )
            }
            NavViewModel.Tab.LIBRARY -> LibraryScreen(
                loggedIn = home.loggedIn,
                onLogin = onLogin,
                onOpenPage = { title, browseId -> nav.push(title, FeedState.Source.Browse(browseId)) },
                onDownloads = onOpenDownloads,
            )
        }

        if (nav.watchVisible) WatchScreen(nav, onOpen = onOpen, onMore = onMore)
    }
    }
}

@Composable
private fun rememberNearEnd(state: LazyListState): Boolean {
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 4
        }
    }
    return nearEnd
}

/** A plain video list: subscriptions, history, search results, playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    state: FeedState,
    title: String,
    onBack: (() -> Unit)?,
    needsLogin: Boolean,
    loggedIn: Boolean,
    emptyHint: String,
    onOpen: (HomeFeedItem) -> Unit,
    onMore: (HomeFeedItem) -> Unit,
    onLogin: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val list = rememberLazyListState()
    val nearEnd = rememberNearEnd(list)
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.items.isNotEmpty()) state.loadMore() }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { state.refresh(pull = true) }) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "title") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        Text(
                            "\u2190", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp),
                        )
                    }
                    Text(
                        title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (header != null && loggedIn) item(key = "header") { header() }
            if (needsLogin && !loggedIn) {
                item(key = "login") { LoginCard(onLogin) }
            } else {
                if (state.loading && state.items.isEmpty()) item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                state.error?.let { msg ->
                    if (state.items.isEmpty()) item(key = "error") { ErrorCard(msg) { state.refresh() } }
                }
                if (!state.loading && state.error == null && state.items.isEmpty()) item(key = "empty") {
                    Text(
                        emptyHint, Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.items, key = { it.videoId ?: it.playlistId ?: it.title }) { item ->
                    VideoCard(item, onOpen = { onOpen(item) }, onMore = { onMore(item) })
                }
                if (state.loadingMore) item(key = "more") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortsScreen(nav: NavViewModel, onOpen: (HomeFeedItem) -> Unit) {
    PullToRefreshBox(isRefreshing = nav.shortsLoading, onRefresh = { nav.loadShorts(force = true) }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Shorts", Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                )
            }
            if (nav.shortsLoading && nav.shorts.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (!nav.shortsLoading && nav.shorts.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    nav.shortsError ?: "No Shorts found in your feed right now. Pull down to try again.",
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(nav.shorts, key = { it.videoId ?: it.title }) { s ->
                Box(
                    Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onOpen(s) },
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
        }
    }
}

@Composable
fun LibraryScreen(
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onOpenPage: (title: String, browseId: String) -> Unit,
    onDownloads: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Library", Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            )
        }
        if (!loggedIn) item { LoginCard(onLogin) }
        if (loggedIn) {
            item { LibraryRow("History", "Videos you've watched") { onOpenPage("History", "FEhistory") } }
            item { LibraryRow("Playlists", "Your saved and created playlists") { onOpenPage("Playlists", "FEplaylist_aggregation") } }
            item { LibraryRow("Liked videos", "Everything you've liked") { onOpenPage("Liked videos", "VLLL") } }
            item { LibraryRow("Watch later", "Your queue") { onOpenPage("Watch later", "VLWL") } }
        }
        item { LibraryRow("Downloads", "Videos saved on this device", onDownloads) }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.nav_library), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The bottom bar, in Compose now. Selection comes from [NavViewModel.tab]. */
@Composable
fun AppNavBar(selected: NavViewModel.Tab, onSelect: (NavViewModel.Tab) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        listOf(
            Triple(NavViewModel.Tab.HOME, R.drawable.nav_home, "Home"),
            Triple(NavViewModel.Tab.SHORTS, R.drawable.nav_shorts, "Shorts"),
            Triple(NavViewModel.Tab.SUBS, R.drawable.nav_subs, "Subs"),
            Triple(NavViewModel.Tab.LIBRARY, R.drawable.nav_library, "Library"),
        ).forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(icon), contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
internal fun ChannelStrip(channels: List<InnerTubeClient.ChannelItem>, onChannel: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(channels, key = { it.id }) { c ->
            Column(
                Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable { onChannel(c.id) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(c.avatar, c.name, 56)
                Text(
                    c.name, Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
