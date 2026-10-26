package dev.sparkynox.sparkytube.fx

import java.nio.ByteBuffer

// jni side of the LunoFX audio engine (lunofx.cpp). safe to call even if the lib didn't load
object NativeFx {

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

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(h: Long)
    private external fun nativeConfigure(h: Long, rate: Int, channels: Int)
    private external fun nativeReset(h: Long)
    private external fun nativeProcess(h: Long, input: ByteBuffer, inOff: Int, output: ByteBuffer, outOff: Int, bytes: Int, channels: Int): Int
    private external fun nativeSetAll(
        on: Boolean, eq: FloatArray, bass: Float, width: Float,
        rvMix: Float, rvRoom: Float, rvDamp: Float,
        p8On: Boolean, p8Period: Float, p8Depth: Float,
        normOn: Boolean, normTarget: Float
    )

    fun create(): Long = if (loaded) nativeCreate() else 0L
    fun destroy(h: Long) { if (loaded && h != 0L) nativeDestroy(h) }
    fun configure(h: Long, rate: Int, channels: Int) { if (loaded && h != 0L) nativeConfigure(h, rate, channels) }
    fun reset(h: Long) { if (loaded && h != 0L) nativeReset(h) }

    fun process(h: Long, input: ByteBuffer, inOff: Int, output: ByteBuffer, outOff: Int, bytes: Int, channels: Int): Int =
        if (loaded && h != 0L) nativeProcess(h, input, inOff, output, outOff, bytes, channels) else 0

    fun setAll(p: LunoFx.Params) {
        if (!loaded) return
        nativeSetAll(
            p.on, p.eq.toFloatArray(), p.bass, p.width,
            p.rvMix, p.rvRoom, p.rvDamp,
            p.p8On, p.p8Period, p.p8Depth,
            p.normOn, p.normTarget
        )
    }
}
