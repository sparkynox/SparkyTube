package dev.sparkynox.sparkytube.ui.shorts

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.LayoutInflater
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.extractor.ShortsResolver
import dev.sparkynox.sparkytube.history.InnerTubeHistory
import dev.sparkynox.sparkytube.homefeed.HomeFeedItem
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import dev.sparkynox.sparkytube.net.NetCache
import dev.sparkynox.sparkytube.ui.Avatar
import dev.sparkynox.sparkytube.ui.CommentsSheet
import dev.sparkynox.sparkytube.ui.LocalOpenMenu
import dev.sparkynox.sparkytube.ui.LunoLoader
import dev.sparkynox.sparkytube.ui.NavViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun lifecycleOwnerOf(ctx: Context): LifecycleOwner? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/**
 * Full screen vertical Shorts, like the YouTube app. Streams come from LazyLuno only (see ShortsResolver),
 * the page after the current one is resolved and prepared while you watch, so the swipe is instant.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsViewer(nav: NavViewModel, onDownload: (HomeFeedItem) -> Unit, onClose: () -> Unit) {
    val shorts = nav.viewerShorts
    if (shorts.isEmpty()) return
    val pager = rememberPagerState(initialPage = nav.viewerStart.coerceIn(0, shorts.lastIndex)) { shorts.size }
    LaunchedEffect(pager.currentPage, shorts.size) {
        if (pager.currentPage >= shorts.size - 3) nav.loadMoreViewerShorts()
    }

    // the app going to the background pauses the short, coming back resumes it
    val ctx = LocalContext.current
    var appPaused by remember { mutableStateOf(false) }
    DisposableEffect(ctx) {
        val owner = lifecycleOwnerOf(ctx)
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) appPaused = true
            if (e == Lifecycle.Event.ON_RESUME) appPaused = false
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    var muted by remember { mutableStateOf(false) }
    var commentsFor by remember { mutableStateOf<String?>(null) }
    val openMenu = LocalOpenMenu.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { shorts.getOrNull(it)?.videoId ?: it },
        ) { page ->
            val item = shorts[page]
            ShortPage(
                item = item,
                active = pager.currentPage == page,
                preload = page == pager.currentPage + 1,
                appPaused = appPaused,
                muted = muted,
                onMute = { muted = !muted },
                onComments = { commentsFor = item.videoId },
                onDownload = { onDownload(item) },
                onMore = { openMenu(item) },
            )
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(
                "Shorts", Modifier.padding(start = 12.dp), color = Color.White,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
        }
    }
    commentsFor?.let { id -> CommentsSheet(videoId = id, knownToken = null, onDismiss = { commentsFor = null }) }
}

@Composable
private fun ShortPage(
    item: HomeFeedItem,
    active: Boolean,
    preload: Boolean,
    appPaused: Boolean,
    muted: Boolean,
    onMute: () -> Unit,
    onComments: () -> Unit,
    onDownload: () -> Unit,
    onMore: () -> Unit,
) {
    val id = item.videoId ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var clip by remember(id) { mutableStateOf(ShortsResolver.cached(id)) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    val want = active || preload

    LaunchedEffect(id, want, attempt) {
        if (clip == null && want) {
            failed = false
            val c = withContext(Dispatchers.IO) { ShortsResolver.resolve(ctx, id) }
            if (c != null) clip = c else failed = true
        }
    }

    var player by remember(id) { mutableStateOf<ExoPlayer?>(null) }
    var firstFrame by remember(id) { mutableStateOf(false) }
    DisposableEffect(clip, want) {
        val c = clip
        if (c != null && want) {
            val p = buildPlayer(ctx, c)
            val l = object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true }
            }
            p.addListener(l)
            player = p
            onDispose {
                p.removeListener(l)
                p.release()
                player = null
                firstFrame = false
            }
        } else {
            onDispose { }
        }
    }

    var userPaused by remember(id) { mutableStateOf(false) }
    LaunchedEffect(player, active, userPaused, appPaused) {
        player?.playWhenReady = active && !userPaused && !appPaused
    }
    LaunchedEffect(player, muted) { player?.volume = if (muted) 0f else 1f }

    // thin progress line at the bottom
    var progress by remember(id) { mutableFloatStateOf(0f) }
    LaunchedEffect(player, active) {
        while (active) {
            val p = player
            if (p != null) {
                val d = p.duration
                progress = if (d > 0 && d != C.TIME_UNSET) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            }
            delay(150)
        }
    }

    // a short that has been on screen for a moment counts as watched
    LaunchedEffect(active, id) {
        if (active) {
            delay(2500)
            InnerTubeHistory.markShort(ctx, id)
        }
    }

    var liked by remember(id) { mutableStateOf(false) }

    // like from the button (toggles) or from a double tap (only ever likes, the heart pops either way)
    var heartTick by remember(id) { mutableIntStateOf(0) }
    fun setLike(wantLiked: Boolean) {
        if (!Session.loggedIn) {
            Toast.makeText(ctx, "Sign in first (Home tab)", Toast.LENGTH_SHORT).show()
            return
        }
        if (liked == wantLiked) return
        val prev = liked
        liked = wantLiked
        scope.launch {
            val ok = if (wantLiked) InnerTubeClient.like(id) else InnerTubeClient.removeLike(id)
            if (!ok) liked = prev
        }
    }
    var heartShown by remember(id) { mutableStateOf(false) }
    LaunchedEffect(heartTick) {
        if (heartTick > 0) {
            heartShown = true
            delay(650)
            heartShown = false
        }
    }
    val heartScale by animateFloatAsState(if (heartShown) 1f else 0.4f, label = "heartScale")
    val heartAlpha by animateFloatAsState(if (heartShown) 1f else 0f, label = "heartAlpha")

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(id, failed) {
            detectTapGestures(
                onTap = {
                    if (failed) attempt++ else userPaused = !userPaused
                },
                onDoubleTap = {
                    if (!failed) {
                        // the first tap of a double tap paused it, put it back
                        userPaused = false
                        heartTick++
                        setLike(true)
                    }
                },
            )
        },
    ) {
        if (!firstFrame) {
            AsyncImage(
                model = item.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
                contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
        }
        val p = player
        if (p != null) {
            AndroidView(
                factory = { c -> LayoutInflater.from(c).inflate(R.layout.view_short_player, null) as PlayerView },
                update = { it.player = p },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (clip == null && !failed) Box(Modifier.align(Alignment.Center)) { LunoLoader(size = 52.dp, color = Color.White) }
        if (failed) Text(
            "Couldn't load this Short. Tap to try again.", Modifier.align(Alignment.Center).padding(32.dp),
            color = Color.White, style = MaterialTheme.typography.bodyMedium,
        )
        if (heartAlpha > 0f) Icon(
            Icons.Filled.Favorite, null,
            Modifier.align(Alignment.Center).size(110.dp).graphicsLayer { scaleX = heartScale; scaleY = heartScale; alpha = heartAlpha },
            tint = Color(0xFFFF2D55),
        )
        if (userPaused && !failed) Box(
            Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color(0x77000000)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_play), null, Modifier.size(38.dp), tint = Color.White) }

        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
        )

        // channel + title, bottom left
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.78f).padding(start = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.channelName.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.channelAvatarUrl, item.channelName, 32)
                Text(
                    item.channelName, Modifier.padding(start = 10.dp), color = Color.White,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                item.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (item.viewCountText.isNotBlank()) Text(
                item.viewCountText, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall,
            )
        }

        // actions, bottom right
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShortAction(R.drawable.ic_thumb_up, if (liked) "Liked" else "Like", highlight = liked) {
                setLike(!liked)
            }
            ShortAction(R.drawable.ic_comment, "Comments", onClick = onComments)
            ShortAction(R.drawable.ic_share, "Share") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://youtube.com/shorts/$id")
                ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ShortAction(R.drawable.ic_download, "Save", onClick = onDownload)
            ShortAction(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, if (muted) "Unmute" else "Mute", onClick = onMute)
            ShortAction(R.drawable.ic_more, "More", onClick = onMore)
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
            color = Color.White, trackColor = Color(0x33FFFFFF),
        )
    }
}

@Composable
private fun ShortAction(icon: Int, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .background(if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), label, Modifier.size(24.dp), tint = Color.White) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
    }
}

private fun buildPlayer(ctx: Context, c: ShortsResolver.Clip): ExoPlayer {
    NetCache.init(ctx)
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(2_000, 20_000, 250, 700)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val p = ExoPlayer.Builder(ctx).setLoadControl(load).build()
    p.setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true,
    )
    val src: MediaSource = if (c.videoUrl.contains(".m3u8")) {
        HlsMediaSource.Factory(NetCache.plainFactory(ctx)).createMediaSource(MediaItem.fromUri(c.videoUrl))
    } else {
        val df = NetCache.cachedFactory(ctx)
        val video = ProgressiveMediaSource.Factory(df).createMediaSource(MediaItem.fromUri(c.videoUrl))
        if (c.audioUrl.isNullOrBlank()) video
        else MergingMediaSource(video, ProgressiveMediaSource.Factory(df).createMediaSource(MediaItem.fromUri(c.audioUrl)))
    }
    p.setMediaSource(src)
    p.repeatMode = Player.REPEAT_MODE_ONE
    p.playWhenReady = false
    p.prepare()
    return p
}
