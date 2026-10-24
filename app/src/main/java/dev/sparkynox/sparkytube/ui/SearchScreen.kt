package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.search.NewPipeSearch
import dev.sparkynox.sparkytube.search.SearchHit

private val FILTERS = listOf("all" to "All", "videos" to "Videos", "channels" to "Channels", "playlists" to "Playlists")

// Full-screen search. Suggestions while typing, recent searches when empty, results from NewPipe.
@Composable
fun SearchScreen(nav: NavViewModel, onOpen: (HomeFeedItem) -> Unit, onClose: () -> Unit) {
    val s = nav.search
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var recentTick by remember { mutableIntStateOf(0) }
    val recent = remember(recentTick) { NewPipeSearch.recent(ctx) }
    val openChannel = LocalOpenChannel.current
    val openMenu = LocalOpenMenu.current
    val list = rememberLazyListState()

    LaunchedEffect(Unit) { if (s.submitted.isEmpty()) requester.requestFocus() }

    // near the end of the results: next page
    LaunchedEffect(list, s.hits.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= s.hits.size - 3 && s.hits.isNotEmpty()) s.loadMore() }
    }

    fun go(q: String) {
        focus.clearFocus()
        NewPipeSearch.remember(ctx, q.trim())
        recentTick++
        s.submit(q)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                TextField(
                    value = s.query,
                    onValueChange = { s.onType(it) },
                    modifier = Modifier.weight(1f).focusRequester(requester),
                    placeholder = { Text("Search YouTube") },
                    singleLine = true,
                    shape = CircleShape,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (s.query.isNotEmpty()) IconButton(onClick = { s.clear(); requester.requestFocus() }) {
                            Icon(Icons.Filled.Clear, "Clear")
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go(s.query) }),
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(FILTERS) { (key, label) ->
                    FilterChip(
                        selected = s.filter == key,
                        onClick = { s.changeFilter(key) },
                        label = { Text(label) },
                        shape = CircleShape,
                        border = null,
                    )
                }
            }
            if (s.loading && s.hits.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))

            when {
                // typing: suggestions
                s.suggestions.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(s.suggestions.toList()) { sug ->
                        TextRow(sug, onFill = { s.onType("$sug ") }) { go(sug) }
                    }
                }
                // nothing searched yet: recent searches
                s.submitted.isEmpty() -> LazyColumn {
                    if (recent.isNotEmpty()) item {
                        Text(
                            "Recent", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(recent) { r ->
                        TextRow(r, onRemove = { NewPipeSearch.forget(ctx, r); recentTick++ }) { go(r) }
                    }
                }
                else -> Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(s.hits.toList()) { hit ->
                            when (hit) {
                                is SearchHit.Video -> VideoCard(hit.item, onOpen = { onOpen(hit.item) }, onMore = { openMenu(hit.item) })
                                is SearchHit.Playlist -> VideoCard(hit.item, onOpen = { onOpen(hit.item) }, onMore = { openMenu(hit.item) })
                                is SearchHit.Channel -> Row(
                                    Modifier.fillMaxWidth().clickable { openChannel(hit.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(hit.avatar, hit.name, 56)
                                    Column(Modifier.padding(start = 16.dp)) {
                                        Text(hit.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (hit.info.isNotBlank()) Text(
                                            hit.info, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (s.loading) item {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                    }
                    s.error?.let {
                        if (s.hits.isEmpty() && !s.loading) Text(
                            it, Modifier.align(Alignment.Center).padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextRow(text: String, onRemove: (() -> Unit)? = null, onFill: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onFill != null) Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "Use this text",
            Modifier.size(20.dp).rotate(45f).clickable(onClick = onFill),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRemove != null) IconButton(onClick = onRemove, Modifier.size(28.dp)) { Icon(Icons.Filled.Clear, "Remove") }
    }
}
