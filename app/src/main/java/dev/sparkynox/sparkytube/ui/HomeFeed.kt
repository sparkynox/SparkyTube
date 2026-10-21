package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.HomeFeedViewModel

/**
 * The home feed, laid out like LunoMusic's Home tab: account header, filter chips, pull to refresh,
 * infinite scroll, rounded cards. Only the presentation is Compose; playback, downloads and the
 * rest of the app still go through MainActivity via the callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeFeedScreen(
    vm: HomeFeedViewModel,
    onOpen: (HomeFeedItem) -> Unit,
    onMore: (HomeFeedItem) -> Unit,
    onLogin: () -> Unit,
) {
    val state = rememberLazyListState()
    var confirmLogout by remember { mutableStateOf(false) }

    // infinite scroll, same trigger as Luno: a few rows before the end
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, vm.items.size) { if (nearEnd && vm.items.isNotEmpty()) vm.loadMore() }

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh(pull = true) }) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "header") {
                AccountHeader(
                    name = vm.accountName,
                    photo = vm.accountPhoto,
                    loggedIn = vm.loggedIn,
                    onClick = { if (vm.loggedIn) confirmLogout = true else onLogin() },
                )
            }

            if (!vm.loggedIn) item(key = "login") { LoginCard(onLogin) }

            if (vm.chips.isNotEmpty()) item(key = "chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.chips, key = { it.title }) { c ->
                        val on = vm.selectedChip?.title?.let { it == c.title } ?: (c.token == null)
                        FilterChip(selected = on, onClick = { vm.selectChip(c) }, label = { Text(c.title) })
                    }
                }
            }

            if (vm.loading && vm.items.isEmpty()) item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            vm.error?.let { msg ->
                if (vm.items.isEmpty()) item(key = "error") { ErrorCard(msg) { vm.refresh() } }
            }

            items(vm.items, key = { it.videoId ?: it.playlistId ?: it.title }) { item ->
                VideoCard(item, onOpen = { onOpen(item) }, onMore = { onMore(item) })
            }

            if (vm.loadingMore) item(key = "more") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You'll go back to the generic feed until you sign in again.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun AccountHeader(name: String, photo: String, loggedIn: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (loggedIn && name.isNotBlank()) "Hi, $name" else "Home",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (loggedIn) "Your feed" else "Not signed in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Avatar(photo, name.ifBlank { "?" }, 40, Modifier.clickable(onClick = onClick))
    }
}

@Composable
internal fun Avatar(url: String?, fallback: String, sizeDp: Int, modifier: Modifier = Modifier) {
    val shape = CircleShape
    Box(
        modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(url, null, Modifier.size(sizeDp.dp), contentScale = ContentScale.Crop)
        } else {
            Text(
                fallback.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
internal fun LoginCard(onLogin: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your own feed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Sign in with your Google account to get recommendations based on what you actually watch.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onLogin) { Text("Sign in") }
        }
    }
}

@Composable
internal fun ErrorCard(msg: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Couldn't load the feed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoCard(item: HomeFeedItem, onOpen: () -> Unit, onMore: () -> Unit) {
    val openChannel = LocalOpenChannel.current
    val chId = item.channelId
    val chTap = if (chId != null) Modifier.clickable { openChannel(chId) } else Modifier
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMore).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(item.thumbnailUrl, item.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
            val badge = item.durationText.ifBlank { item.itemCountText.orEmpty() }
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(item.channelAvatarUrl, item.channelName.ifBlank { "?" }, 36, chTap)
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val meta = listOf(item.channelName, item.viewCountText).filter { it.isNotBlank() }.joinToString(" • ")
                if (meta.isNotBlank()) Text(
                    meta,
                    modifier = chTap,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
