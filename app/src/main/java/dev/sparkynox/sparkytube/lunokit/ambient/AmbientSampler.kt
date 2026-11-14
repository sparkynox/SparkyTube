package dev.sparkynox.sparkytube.lunokit.ambient

import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// grabs a tiny copy of the frame the player is showing, several times a second, for the live ambient glow
object AmbientSampler {
    class Frame(val image: ImageBitmap, val barTop: Int, val barBottom: Int, val aspect: Float)

    var cur by mutableStateOf<Frame?>(null); private set

    // smoothing happens here, on the pixels: every new frame is mixed into a running average, so cuts and
    // fast motion fade over a few samples instead of flashing. (the old two-layer crossfade added the
    // old and new frame on top of each other, which made the glow pulse brighter and darker every frame)
    private var avg: IntArray? = null
    private var barT = 0f
    private var barB = 0f

    suspend fun run(texture: () -> TextureView?, shouldSample: () -> Boolean) {
        while (currentCoroutineContext().isActive) {
            if (!AmbientPrefs.enabled) {
                if (cur != null) { cur = null; avg = null }
            } else if (shouldSample()) {
                grab(texture())
            }
            delay(1000L / AmbientPrefs.fps.coerceIn(4, 30))
        }
    }

    private fun grab(tv: TextureView?) {
        if (tv == null || !tv.isAvailable || !tv.isShown || tv.visibility != View.VISIBLE || tv.width < 2 || tv.height < 2) return
        val w = 64
        val h = (w.toFloat() * tv.height / tv.width).toInt().coerceIn(16, 128)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val got = runCatching { tv.getBitmap(bmp) }.getOrNull() ?: return
        val (top, bottom) = LunoAmbient.process(got, AmbientPrefs.saturation)

        val n = got.width * got.height
        val px = IntArray(n)
        got.getPixels(px, 0, got.width, 0, 0, got.width, got.height)
        // weight of the new frame, 256 = take it as is
        val k = if (AmbientPrefs.smooth) 80 else 256
        var a = avg
        if (a == null || a.size != n) {
            a = px
            barT = top.toFloat()
            barB = bottom.toFloat()
        } else {
            for (i in 0 until n) {
                val p = px[i]
                val q = a[i]
                val r = (((q shr 16) and 255) * (256 - k) + ((p shr 16) and 255) * k) shr 8
                val g = (((q shr 8) and 255) * (256 - k) + ((p shr 8) and 255) * k) shr 8
                val b = ((q and 255) * (256 - k) + (p and 255) * k) shr 8
                a[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            // black bars move slower than the picture, a dark scene shouldn't make the glow's crop jump
            val kb = k / 256f * 0.4f
            barT += (top - barT) * kb
            barB += (bottom - barB) * kb
        }
        avg = a
        val out = Bitmap.createBitmap(a, got.width, got.height, Bitmap.Config.ARGB_8888)
        cur = Frame(out.asImageBitmap(), Math.round(barT), Math.round(barB), tv.width.toFloat() / tv.height)
    }
}
