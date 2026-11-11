package dev.sparkynox.sparkytube.lunokit.ambient

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * The real thing: the frame that is on screen right now, blown up in layers around the video, each
 * layer a bit bigger and fainter than the one before. Same idea as the Ambient light for YouTube
 * extension (MIT, Wessel Kroos), redrawn here on top of the player's own frames.
 */
@Composable
fun LiveAmbient(modifier: Modifier = Modifier) {
    if (!AmbientPrefs.enabled) return
    val cur = AmbientSampler.cur ?: return
    val prev = AmbientSampler.prev
    val blend = remember { Animatable(1f) }
    val frameMs = 1000 / AmbientPrefs.fps.coerceIn(4, 30)
    val smooth = AmbientPrefs.smooth
    LaunchedEffect(cur) {
        if (smooth) {
            blend.snapTo(0f)
            blend.animateTo(1f, tween(frameMs, easing = LinearEasing))
        } else blend.snapTo(1f)
    }

    val spread = AmbientPrefs.spreadDp.dp
    var m = modifier.fillMaxWidth().height(220.dp + spread)
    // a real gaussian on top on Android 12+, below that the soft upscale of the tiny frame does the job
    if (AmbientPrefs.blurDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        m = m.blur(AmbientPrefs.blurDp.dp, BlurredEdgeTreatment.Unbounded)
    }
    Canvas(m) {
        val boxH = 220.dp.toPx()
        val w = size.width
        var vw = w
        var vh = w / cur.aspect
        if (vh > boxH) { vh = boxH; vw = boxH * cur.aspect }
        val left = (w - vw) / 2f
        val top = (boxH - vh) / 2f
        val spreadPx = spread.toPx()
        val levels = 6
        val strength = AmbientPrefs.strength / 100f * 0.9f
        val t = blend.value

        fun layer(frame: AmbientSampler.Frame, i: Int, alpha: Float) {
            if (alpha <= 0f) return
            val img = frame.image
            val srcTop = frame.barTop.coerceAtMost(img.height / 3)
            val srcH = (img.height - srcTop - frame.barBottom.coerceAtMost(img.height / 3)).coerceAtLeast(1)
            val grow = spreadPx * i / levels
            drawImage(
                image = img,
                srcOffset = IntOffset(0, srcTop),
                srcSize = IntSize(img.width, srcH),
                dstOffset = IntOffset((left - grow).toInt(), (top - grow).toInt()),
                dstSize = IntSize((vw + 2 * grow).toInt().coerceAtLeast(1), (vh + 2 * grow).toInt().coerceAtLeast(1)),
                alpha = alpha,
                filterQuality = FilterQuality.Low,
            )
        }

        // outermost first, so the strongest layer ends up on top and the glow fades outwards
        for (i in levels downTo 1) {
            val a = strength * (1f - (i - 1f) / levels).pow(1.3f)
            if (t < 1f && prev != null) layer(prev, i, a)
            layer(cur, i, if (t < 1f && prev != null) a * t else a)
        }
    }
}
