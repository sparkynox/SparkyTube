package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem

/** Opens the native Shorts viewer on a list, starting at an index. Provided by [AppHost]. */
val LocalOpenShorts = staticCompositionLocalOf<(List<HomeFeedItem>, Int) -> Unit> { { _, _ -> } }

/**
 * The "Shorts" shelf of the YouTube feed: a header with a menu and a 2 column grid of cards, each with
 * its own menu button (same look as m.youtube.com). Shows the first four.
 */
@Composable
fun ShortsShelf(items: List<HomeFeedItem>, modifier: Modifier = Modifier) {
    val open = LocalOpenShorts.current
    val openMenu = LocalOpenMenu.current
    var headerMenu by remember { mutableStateOf(false) }
    val shown = items.take(4)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_shorts), null, Modifier.size(28.dp), tint = Color(0xFFFF2D2D))
            Text(
                "Shorts", Modifier.padding(start = 12.dp).weight(1f),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            Box {
                IconButton(onClick = { headerMenu = true }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, "Shorts menu")
                }
                DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                    DropdownMenuItem(text = { Text("Play all Shorts") }, onClick = { headerMenu = false; open(items, 0) })
                }
            }
        }
        shown.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    Box(
                        Modifier.weight(1f).aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { open(items, items.indexOf(s)) },
                    ) {
                        AsyncImage(s.thumbnailUrl, s.title, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC000000)),
                            ),
                        )
                        IconButton(
                            onClick = { openMenu(s) },
                            Modifier.align(Alignment.TopEnd).size(40.dp),
                        ) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                        Text(
                            s.title, Modifier.align(Alignment.BottomStart).padding(12.dp),
                            color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
