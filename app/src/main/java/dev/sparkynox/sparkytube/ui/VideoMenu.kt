package dev.sparkynox.sparkytube.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.homefeed.HiddenVideos
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import kotlinx.coroutines.launch

/** Cards and the watch screen call this to open the ⋮ menu for a video. Provided by [AppHost]. */
val LocalOpenMenu = androidx.compose.runtime.staticCompositionLocalOf<(HomeFeedItem) -> Unit> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoMenuSheet(
    item: HomeFeedItem,
    nav: NavViewModel,
    onDownload: (HomeFeedItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val openChannel = LocalOpenChannel.current
    val id = item.videoId
    var picking by remember { mutableStateOf(false) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    fun needLogin(): Boolean {
        if (Session.loggedIn) return false
        toast("Sign in first (Home tab)")
        return true
    }

    if (picking && id != null) {
        PlaylistPicker(id, onDone = { picking = false; onDismiss() }, onCancel = { picking = false })
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                item.title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (id != null) {
                MenuRow(Icons.Filled.PlayArrow, "Play next") { nav.playNext(item); toast("Plays next"); onDismiss() }
                MenuRow(Icons.AutoMirrored.Filled.List, "Add to queue") { nav.addToQueue(item); toast("Added to queue"); onDismiss() }
                MenuRow(Icons.Filled.Add, "Save to playlist") { if (!needLogin()) picking = true }
                MenuRow(Icons.Filled.Refresh, "Save to Watch later") {
                    if (!needLogin()) {
                        onDismiss()
                        scope.launch {
                            val ok = InnerTubeClient.addToPlaylist("WL", id)
                            toast(if (ok) "Saved to Watch later" else "Couldn't save")
                        }
                    }
                }
                MenuRow(Icons.Filled.KeyboardArrowDown, "Download") { onDismiss(); onDownload(item) }
            }
            val share = id ?: item.playlistId
            if (share != null) {
                MenuRow(Icons.Filled.Share, "Share") {
                    val url = if (id != null) "https://youtu.be/$id" else "https://www.youtube.com/playlist?list=$share"
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                }
            }
            item.channelId?.let { ch ->
                MenuRow(Icons.Filled.Person, "Go to channel") { onDismiss(); openChannel(ch) }
            }
            if (id != null) {
                MenuRow(Icons.Filled.Clear, "Not interested") {
                    HiddenVideos.ids.add(id)
                    val token = item.feedbackToken
                    if (token != null && Session.loggedIn) scope.launch { InnerTubeClient.notInterested(token) }
                    toast("Okay, hidden")
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PlaylistPicker(videoId: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<InnerTubeClient.PlaylistOption>?>(null) }
    LaunchedEffect(videoId) { lists = InnerTubeClient.fetchPlaylistsFor(videoId) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save to playlist") },
        text = {
            val l = lists
            when {
                l == null -> CircularProgressIndicator()
                l.isEmpty() -> Text("No playlists found.")
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(l, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val ok = InnerTubeClient.addToPlaylist(p.id, videoId)
                                    Toast.makeText(ctx, if (ok) "Added to ${p.title}" else "Couldn't add", Toast.LENGTH_SHORT).show()
                                }
                                onDone()
                            }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(p.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.hasVideo) Text("Saved", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
