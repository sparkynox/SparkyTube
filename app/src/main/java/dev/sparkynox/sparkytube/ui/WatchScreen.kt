package dev.sparkynox.sparkytube.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.height
import dev.sparkynox.sparkytube.lunokit.ambient.ambientGlow
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.togetherWith
import dev.sparkynox.sparkytube.lunokit.glass.GlassBackdrop
import dev.sparkynox.sparkytube.lunokit.glass.LunoGlass
import dev.sparkynox.sparkytube.lunokit.glass.panelBorder
import dev.sparkynox.sparkytube.lunokit.glass.panelColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
    val openMenu = LocalOpenMenu.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // the phone can turn window blur off at any time (battery saver), so look again for every video
    LaunchedEffect(nav.watchId) { LunoGlass.refresh(ctx) }
    // 0 none, 1 liked, 2 disliked, read from the watch page once it has loaded
    var reaction by remember(nav.watchId, info?.likeStatus) { mutableStateOf(info?.likeStatus ?: 0) }
    val rydOn = remember { dev.sparkynox.sparkytube.settings.SettingsPrefs.isDislikeCountEnabled(ctx) }
    var votes by remember(nav.watchId) { mutableStateOf<ReturnYouTubeDislike.Votes?>(null) }
    LaunchedEffect(nav.watchId) {
        val id = nav.watchId
        if (rydOn && id != null) votes = ReturnYouTubeDislike.get(id)
    }
    // the subscriptions list is the fallback when the watch page doesn't say
    LaunchedEffect(nav.watchId) { if (Session.loggedIn) nav.loadSubChannels() }
    val inSubList = info?.channelId?.let { id -> nav.subChannels.any { it.id == id } } ?: false
    var subscribed by remember(nav.watchId, info?.subscribed, inSubList) { mutableStateOf((info?.subscribed ?: false) || inSubList) }
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

    Surface(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f - nav.watchDrag * 0.9f
            translationY = nav.watchDrag * 90f
        },
        color = if (LunoGlass.active) Color.Transparent else MaterialTheme.colorScheme.background,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        GlassBackdrop(nav.watchId)
        // the live glow from the playing video, behind the player and the page
        dev.sparkynox.sparkytube.lunokit.ambient.LiveAmbient(Modifier.align(Alignment.TopStart))
        // the real player is a native view drawn over the top 220dp, this only shows while the next video is still loading
        if (!nav.playerReady && nav.watchId != null) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = "https://i.ytimg.com/vi/${nav.watchId}/mqdefault.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                LunoLoader(color = androidx.compose.ui.graphics.Color.White)
            }
        }
        LazyColumn(
            state = list,
            // the first 220dp belong to the ExoPlayer view that MainActivity draws above this
            modifier = Modifier.fillMaxSize()
                .then(if (dev.sparkynox.sparkytube.lunokit.ambient.AmbientPrefs.enabled) Modifier else Modifier.ambientGlow(nav.watchId))
                .padding(top = 220.dp),
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
                        androidx.compose.animation.AnimatedContent(
                            targetState = subscribed,
                            transitionSpec = {
                                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                                    androidx.compose.animation.scaleIn(initialScale = 0.85f)) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                            },
                            label = "subscribe",
                        ) { sub ->
                            if (sub) OutlinedButton(onClick = onSub) { Text("Subscribed") }
                            else Button(onClick = onSub) { Text("Subscribe") }
                        }
                    }
                }
            }

            item(key = "actions") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val vid = nav.watchId
                    val likeBtn: @Composable (String, Boolean, Int, () -> Unit) -> Unit = { label, on, iconRes, click ->
                        androidx.compose.animation.AnimatedContent(
                            targetState = on,
                            transitionSpec = {
                                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                                    androidx.compose.animation.scaleIn(initialScale = 0.8f)) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100))
                            },
                            label = "react-$label",
                        ) { isOn ->
                            if (isOn) Button(onClick = click) { ActionContent(iconRes, label) } else FilledTonalButton(onClick = click) { ActionContent(iconRes, label) }
                        }
                    }
                    likeBtn("Like" + (votes?.let { " · " + ReturnYouTubeDislike.compact(it.likes) } ?: ""), reaction == 1, dev.sparkynox.sparkytube.R.drawable.ic_thumb_up) {
                        if (vid != null && !needLogin()) {
                            val prev = reaction
                            reaction = if (prev == 1) 0 else 1
                            scope.launch {
                                val ok = if (prev == 1) InnerTubeClient.removeLike(vid) else InnerTubeClient.like(vid)
                                if (!ok) reaction = prev
                            }
                        }
                    }
                    likeBtn("Dislike" + (votes?.let { " · " + ReturnYouTubeDislike.compact(it.dislikes) } ?: ""), reaction == 2, dev.sparkynox.sparkytube.R.drawable.ic_thumb_down) {
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
                    }) { ActionContent(dev.sparkynox.sparkytube.R.drawable.ic_share, "Share") }
                    FilledTonalButton(onClick = {
                        if (vid != null) openMenu(
                            dev.sparkynox.sparkytube.homefeed.HomeFeedItem(
                                videoId = vid, playlistId = null,
                                title = info?.title ?: nav.watchTitle,
                                channelName = info?.channelName ?: "",
                                thumbnailUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                                channelAvatarUrl = info?.channelAvatar,
                                durationText = "", viewCountText = "",
                                channelId = info?.channelId,
                            )
                        )
                    }) { ActionContent(dev.sparkynox.sparkytube.R.drawable.ic_more, "More") }
                }
            }

            if (!info?.description.isNullOrBlank()) item(key = "description") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp)).clickable { expanded = !expanded },
                    shape = RoundedCornerShape(16.dp),
                    color = panelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = panelBorder(),
                ) {
                    Text(
                        info?.description.orEmpty(),
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (nav.userQueue.isNotEmpty()) {
                item(key = "uq-title") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Your queue (${nav.userQueue.size})",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { nav.clearQueue() }) { Text("Clear all") }
                    }
                }
                items(nav.userQueue.toList(), key = { "uq-" + (it.videoId ?: it.playlistId) }) { q ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp))
                            .clickable { onOpen(q) }.padding(6.dp),
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
                                q.channelName, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row {
                            Text("\u25B2", Modifier.clickable { nav.moveInQueue(nav.userQueue.indexOf(q), -1) }.padding(8.dp))
                            Text("\u25BC", Modifier.clickable { nav.moveInQueue(nav.userQueue.indexOf(q), 1) }.padding(8.dp))
                            Text("\u2715", Modifier.clickable { nav.userQueue.remove(q) }.padding(8.dp))
                        }
                    }
                }
            }

            // a real playlist if one is playing, otherwise the Mix YouTube offers for this video
            val panel = if (nav.queue.isNotEmpty()) nav.queue else nav.autoMix
            if (panel.isNotEmpty()) {
                item(key = "queue-title") {
                    val idx = panel.indexOfFirst { it.videoId == nav.watchId } + 1
                    val kind = if (nav.queue.isNotEmpty()) "Playlist" else "Mix"
                    Text(
                        kind + " \u2022 " + ((if (nav.queue.isNotEmpty()) info?.queueTitle else nav.autoMixTitle) ?: "Mix") + if (idx > 0) "  ($idx/${panel.size})" else "",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                items(panel, key = { "q-" + it.videoId }) { q ->
                    val current = q.videoId == nav.watchId
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (current) panelColor(MaterialTheme.colorScheme.secondaryContainer, 0.55f) else Color.Transparent)
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
}

// icon + label inside the action buttons under the player
@Composable
private fun ActionContent(iconRes: Int, label: String) {
    androidx.compose.material3.Icon(
        androidx.compose.ui.res.painterResource(iconRes), contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
    Text(label)
}
