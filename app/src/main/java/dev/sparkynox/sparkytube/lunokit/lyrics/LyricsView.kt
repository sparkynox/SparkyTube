package dev.sparkynox.sparkytube.lunokit.lyrics

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dev.sparkynox.sparkytube.settings.SettingsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class LyricsUiState {
    var active by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
}

private class Track(val id: String?, val title: String, val channel: String?)

@Composable
fun LyricsLayer(
    ui: LyricsUiState,
    controller: () -> Player?,
    videoId: () -> String?,
    title: () -> String,
    channel: () -> String?,
) {
    if (!ui.active) return
    val ctx = LocalContext.current

    var enabled by remember { mutableStateOf(SettingsPrefs.isLyricsEnabled(ctx)) }
    var track by remember { mutableStateOf<Track?>(null) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }

    // what is playing now, checked twice a second, cheap
    LaunchedEffect(Unit) {
        while (true) {
            enabled = SettingsPrefs.isLyricsEnabled(ctx)
            val id = videoId()
            val t = title()
            val cur = track
            if (t.isNotBlank() && (cur == null || cur.id != id || cur.title != t)) track = Track(id, t, channel())
            delay(500)
        }
    }

    LaunchedEffect(track, enabled) {
        val t = track ?: return@LaunchedEffect
        lyrics = null
        if (!enabled) return@LaunchedEffect
        // duration shows up a moment after prepare()
        var dur = controller()?.duration ?: 0L
        var tries = 0
        while (dur <= 0 && tries < 10) { delay(300); dur = controller()?.duration ?: 0L; tries++ }
        lyrics = LunoLyrics.get(ctx, t.id, t.title, t.channel, dur)
    }

    val l = lyrics ?: return
    if (!enabled) return

    val pos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            pos.longValue = controller()?.currentPosition ?: 0L
        }
    }
    val active by remember(l) { derivedStateOf { l.indexAt(pos.longValue + 150) } }

    val id = track?.id
    var tint by remember { mutableStateOf(Color(0xFF4A4A4A)) }
    LaunchedEffect(id) {
        if (id != null) coverColor(ctx, "https://i.ytimg.com/vi/$id/mqdefault.jpg")?.let { tint = it }
    }
    val bgTint by animateColorAsState(tint, tween(900), label = "tint")
    val bg = Brush.verticalGradient(
        listOf(
            lerp(Color.Black, bgTint, 0.35f),
            lerp(Color.Black, bgTint, 0.85f),
            lerp(Color.Black, bgTint, 0.30f),
        )
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val landscape = maxWidth > maxHeight
        if (ui.fullscreen && landscape) {
            Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // hqdefault is 4:3 with black bars, a 16:9 crop lands exactly on the picture
                    modifier = Modifier.weight(0.38f).aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)),
                )
                LyricList(l, active, pos, 30f, 0.34f, Modifier.weight(0.62f).fillMaxHeight().padding(start = 36.dp))
            }
        } else {
            LyricList(
                l, active, pos,
                fontSp = if (ui.fullscreen) 30f else 20f,
                anchor = if (ui.fullscreen) 0.36f else 0.34f,
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            )
        }
        if (!l.synced) {
            BasicText(
                "unsynced lyrics",
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp),
            )
        }
    }
}

@Composable
private fun LyricList(
    l: Lyrics,
    active: Int,
    pos: LongState,
    fontSp: Float,
    anchor: Float,
    modifier: Modifier,
) {
    val state = rememberLazyListState()
    BoxWithConstraints(modifier) {
        val h = maxHeight
        LaunchedEffect(active, h) { state.animateScrollToItem(active.coerceAtLeast(0)) }
        LazyColumn(
            state = state,
            userScrollEnabled = false,
            contentPadding = PaddingValues(top = h * anchor, bottom = h * (1f - anchor)),
            modifier = Modifier.fillMaxSize().edgeFade(),
        ) {
            itemsIndexed(l.lines) { i, line ->
                LyricRow(line, i - active, pos, fontSp, l.synced)
            }
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, dist: Int, pos: LongState, fontSp: Float, synced: Boolean) {
    val isActive = dist == 0
    val d = abs(dist)
    val shown by animateFloatAsState(if (isActive) 1f else (0.5f - 0.12f * d).coerceAtLeast(0.14f), tween(400), label = "alpha")
    val scale by animateFloatAsState(if (isActive) 1f else 0.92f, tween(400), label = "scale")
    val blurDp by animateDpAsState(if (isActive) 0.dp else (minOf(d, 4) * 1.2f).dp, tween(400), label = "blur")

    val fx = if (blurDp > 0.1.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(blurDp, BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }

    Box(
        Modifier.fillMaxWidth()
            .padding(vertical = (fontSp * 0.35f).dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = shown
            }
            .then(fx),
    ) {
        if (isActive && synced) {
            ActiveText(line, pos, fontSp)
        } else {
            BasicText(
                line.text,
                style = TextStyle(
                    color = Color.White, fontSize = fontSp.sp, fontWeight = FontWeight.Bold,
                    lineHeight = (fontSp * 1.35f).sp,
                ),
            )
        }
    }
}

// the line fills with white as it is sung, with a glow that follows it.
// real word timing when the source has it, otherwise spread over the line
@Composable
private fun ActiveText(line: LyricLine, pos: LongState, fontSp: Float) {
    fun styleFor(t: Float) = SpanStyle(
        color = Color.White.copy(alpha = 0.45f + 0.55f * t),
        shadow = Shadow(Color.White.copy(alpha = 0.55f * t), Offset.Zero, 26f),
    )

    val words = line.words
    val text = if (words != null) {
        val tick by remember(line) { derivedStateOf { (pos.longValue / 50).toInt() } }
        val now = tick * 50L
        buildAnnotatedString {
            var last = 0f
            words.forEachIndexed { wi, w ->
                val len = w.text.length
                val dur = (w.endMs - w.startMs).coerceAtLeast(1)
                w.text.forEachIndexed { ci, ch ->
                    val cs = w.startMs + dur * ci / len
                    val cd = (dur / len).coerceAtLeast(90)
                    last = ((now - cs).toFloat() / cd).coerceIn(0f, 1f)
                    withStyle(styleFor(last)) { append(ch) }
                }
                if (wi < words.lastIndex) withStyle(styleFor(last)) { append(' ') }
            }
        }
    } else {
        val n = line.text.length
        val span = (line.endMs - line.timeMs).coerceAtLeast(300)
        val steps = (n * 2).coerceIn(24, 120) // limits how often the text is rebuilt
        val step by remember(line) {
            derivedStateOf { (((pos.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f) * steps).toInt() }
        }
        val p = step / steps.toFloat()
        val soft = 3f
        buildAnnotatedString {
            line.text.forEachIndexed { i, ch ->
                withStyle(styleFor(((p * (n + soft) - i) / soft).coerceIn(0f, 1f))) { append(ch) }
            }
        }
    }
    BasicText(
        text,
        style = TextStyle(fontSize = fontSp.sp, fontWeight = FontWeight.Bold, lineHeight = (fontSp * 1.35f).sp),
    )
}

private fun Modifier.edgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

internal suspend fun coverColor(ctx: Context, url: String): Color? = withContext(Dispatchers.Default) {
    try {
        val res = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).build())
        val d = (res as? SuccessResult)?.drawable ?: return@withContext null
        val bmp = d.toBitmap(24, 14)
        val hsv = FloatArray(3)
        var r = 0f; var g = 0f; var b = 0f; var w = 0f
        for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
            val px = bmp.getPixel(x, y)
            android.graphics.Color.colorToHSV(px, hsv)
            // black bars and grey pixels shouldn't drag the color down
            val wt = hsv[1] * hsv[2] + 0.02f
            r += android.graphics.Color.red(px) * wt
            g += android.graphics.Color.green(px) * wt
            b += android.graphics.Color.blue(px) * wt
            w += wt
        }
        if (w == 0f) return@withContext null
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt()), hsv
        )
        hsv[1] = hsv[1].coerceIn(0.25f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.85f)
        Color(android.graphics.Color.HSVToColor(hsv))
    } catch (e: Exception) {
        null
    }
}
