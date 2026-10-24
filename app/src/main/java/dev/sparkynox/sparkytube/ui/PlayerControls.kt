package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import dev.sparkynox.sparkytube.R
import kotlinx.coroutines.delay

// Everything the controls need from the activity. State is read through lambdas so the
// activity keeps owning it; the controls re-read them a few times a second.
class PlayerControlsHost(
    val controller: () -> MediaController?,
    val enabled: () -> Boolean,           // false while mini player / picture in picture
    val loading: () -> Boolean,           // the stream is still being resolved
    val title: () -> String,
    val speedLabel: () -> String,
    val musicOn: () -> Boolean,
    val downloadEnabled: () -> Boolean,
    val audioTracks: () -> Int,
    val onMinimize: () -> Unit,
    val onFullscreen: () -> Unit,
    val onDownload: () -> Unit,
    val onDownloadsList: () -> Unit,
    val onQuality: () -> Unit,
    val onAudio: () -> Unit,
    val onMusic: () -> Unit,
    val onSpeed: () -> Unit,
    val onPlayPause: () -> Unit,
)

// The native controller stays in the PlayerView but is invisible (see exo_player_controls.xml),
// so the old wiring keeps working. These Compose controls sit on top, inside the PlayerView, which means
// they move into the mini player and into fullscreen together with it.
fun attachComposePlayerControls(playerView: PlayerView, host: PlayerControlsHost) {
    val overlay = playerView.overlayFrameLayout ?: return
    overlay.addView(
        androidx.compose.ui.platform.ComposeView(playerView.context).apply {
            setContent { SparkyTheme(fill = false) { PlayerControls(host) } }
        },
        android.view.ViewGroup.LayoutParams(-1, -1)
    )
}

private val ScrimTop = Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))
private val ScrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControls(h: PlayerControlsHost) {
    // bumped by player events and a timer, makes the lambdas below get read again
    var tick by remember { mutableIntStateOf(0) }
    val c = h.controller()

    DisposableEffect(c) {
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { tick++ }
        }
        c?.addListener(l)
        onDispose { c?.removeListener(l) }
    }
    LaunchedEffect(Unit) { while (true) { delay(400); tick++ } }
    @Suppress("UNUSED_VARIABLE") val redraw = tick

    if (!h.enabled()) return

    val playing = c?.isPlaying == true
    val buffering = h.loading() || c?.playbackState == Player.STATE_BUFFERING
    val duration = (c?.duration ?: 0L).let { if (it == C.TIME_UNSET || it < 0) 0L else it }
    val position = (c?.currentPosition ?: 0L).coerceAtLeast(0L)

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val shown = if (dragging) dragValue else position.toFloat()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(90.dp).align(Alignment.TopCenter).background(ScrimTop))
        Box(Modifier.fillMaxWidth().height(100.dp).align(Alignment.BottomCenter).background(ScrimBottom))

        // top row
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoundButton(onClick = h.onMinimize) { Icon(Icons.Filled.KeyboardArrowDown, "Minimize", tint = Color.White) }
            Text(
                h.title(), Modifier.weight(1f).padding(horizontal = 4.dp),
                color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (h.downloadEnabled()) RoundButton(onClick = h.onDownload, onLongClick = h.onDownloadsList) { ResIcon(R.drawable.ic_download, Color.White, 22.dp) }
            if (h.audioTracks() > 1) RoundButton(onClick = h.onAudio) { ResIcon(R.drawable.ic_audio_track, Color.White, 22.dp) }
            RoundButton(onClick = h.onMusic, highlight = h.musicOn()) { ResIcon(R.drawable.ic_music_note, Color.White, 22.dp) }
            RoundButton(onClick = h.onQuality) { ResIcon(R.drawable.ic_quality, Color.White, 22.dp) }
        }

        // centre: spinner while loading, play/pause otherwise
        Box(Modifier.align(Alignment.Center)) {
            if (buffering) {
                CircularProgressIndicator(Modifier.size(56.dp), color = Color.White, strokeWidth = 4.dp)
            } else {
                RoundButton(onClick = h.onPlayPause, size = 64.dp, highlight = true) {
                    ResIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, Color.White, 34.dp)
                }
            }
        }

        // bottom row: time, seek bar, duration, speed, fullscreen
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(clock(shown.toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = shown.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    c?.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(clock(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
            RoundButton(onClick = h.onSpeed, modifier = Modifier.padding(start = 8.dp), wide = true) {
                Text(h.speedLabel(), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            RoundButton(onClick = h.onFullscreen, modifier = Modifier.padding(start = 6.dp)) {
                ResIcon(R.drawable.ic_fullscreen, Color.White, 22.dp)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    highlight: Boolean = false,
    wide: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val bg = if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color(0x66000000)
    Box(
        modifier
            .then(if (wide) Modifier.height(size).widthIn(min = size) else Modifier.size(size))
            .clip(CircleShape)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (wide) 10.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
