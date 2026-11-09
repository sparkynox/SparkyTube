package dev.sparkynox.sparkytube.lunokit.blur

import android.graphics.Bitmap

// native bitmap blur from libsparkynet.so, returns false if the lib is missing or the bitmap isn't ARGB_8888
object LunoBlur {
    @Volatile
    var loaded = false
        private set

    init {
        loaded = try {
            System.loadLibrary("sparkynet")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private external fun nativeBlur(bitmap: Bitmap, radius: Int): Boolean

    fun blur(bitmap: Bitmap, radius: Int): Boolean =
        loaded && bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable && nativeBlur(bitmap, radius)
}
