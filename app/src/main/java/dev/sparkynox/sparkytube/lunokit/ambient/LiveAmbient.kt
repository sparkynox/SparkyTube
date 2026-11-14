package dev.sparkynox.sparkytube.lunokit.ambient

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

    val spread = AmbientPrefs.spreadDp.dp
    // the whole page, not just a strip under the player
    var m = modifier.fillMaxSize()
    // a real gaussian on top on Android 12+, below that the soft upscale of the tiny frame does the job
    // a big spread needs a softer blur too, otherwise the layer edges show as bands
    val blurDp = maxOf(AmbientPrefs.blurDp, AmbientPrefs.spreadDp / 14)
    if (blurDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        m = m.blur(blurDp.dp, BlurredEdgeTreatment.Unbounded)
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
        val levels = (AmbientPrefs.spreadDp / 34).coerceIn(6, 18)
        val strength = AmbientPrefs.strength / 100f * 0.9f

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
            val a = strength * (1f - (i - 1f) / levels).pow(1.8f)
            layer(cur, i, a)
        }
    }
}
