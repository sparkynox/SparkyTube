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
    var prev by mutableStateOf<Frame?>(null); private set

    suspend fun run(texture: () -> TextureView?, shouldSample: () -> Boolean) {
        while (currentCoroutineContext().isActive) {
            if (!AmbientPrefs.enabled) {
                if (cur != null) { cur = null; prev = null }
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
        prev = cur
        cur = Frame(got.asImageBitmap(), top, bottom, tv.width.toFloat() / tv.height)
    }
}
