package dev.sparkynox.sparkytube.lunokit.ambient

import android.graphics.Bitmap

// native side of the live ambient glow, lives in libsparkynet.so next to the blur
object LunoAmbient {
    private val loaded = try {
        System.loadLibrary("sparkynet")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeProcess(bitmap: Bitmap, saturation: Int, brightness: Int, contrast: Int): Int

    /** Adjusts the frame in place. Returns black bar rows as (top, bottom). */
    fun process(bitmap: Bitmap, saturation: Int, brightness: Int = 100, contrast: Int = 100): Pair<Int, Int> {
        if (!loaded || !bitmap.isMutable) return 0 to 0
        val r = nativeProcess(bitmap, saturation, brightness, contrast)
        return (r ushr 16) to (r and 0xFFFF)
    }
}
