package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.PostData
import dev.sparkynox.sparkytube.homefeed.ShelfData

/** Opens any feed item like a tap on it would. Provided by [AppHost], used by shelves for the cards inside them. */
val LocalOpenItem = staticCompositionLocalOf<(HomeFeedItem) -> Unit> { { } }

/** A community post: author line, text, pictures and a poll. Tap opens the post on YouTube (voting happens there). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(post: PostData, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val openChannel = LocalOpenChannel.current
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val tap = post.channelId?.let { id -> Modifier.combinedClickable(onClick = { openChannel(id) }) } ?: Modifier
                Avatar(post.avatar, post.author.ifBlank { "?" }, 40, tap)
                Column(Modifier.weight(1f)) {
                    Text(
                        post.author, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (post.time.isNotBlank()) Text(
                        post.time, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (post.text.isNotBlank()) Text(post.text, style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)

            when {
                post.images.size == 1 -> AsyncImage(
                    post.images[0], null,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.FillWidth,
                )
                post.images.size > 1 -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 4.dp)) {
                    items(post.images) { url ->
                        AsyncImage(
                            url, null, Modifier.width(260.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }
            }

            post.poll?.let { poll ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    poll.choices.forEach { (label, pct) ->
                        Surface(
                            Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (pct != null) Text(pct, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    poll.total?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (post.likes.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_thumb_up), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(post.likes, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A titled row of videos ("Listen again", ...) that scrolls sideways, like on the YouTube site. */
@Composable
fun VideoShelf(shelf: ShelfData, modifier: Modifier = Modifier) {
    val open = LocalOpenItem.current
    val openMenu = LocalOpenMenu.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(shelf.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            shelf.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shelf.items, key = { it.videoId ?: it.playlistId ?: it.title }) { v ->
                Box(Modifier.width(300.dp)) {
                    VideoCard(v, onOpen = { open(v) }, onMore = { openMenu(v) })
                }
            }
        }
    }
}
