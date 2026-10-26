package dev.sparkynox.sparkytube.net

// thin wrapper over libsparkynet.so, every call is safe even if the lib failed to load
object NativeNet {

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

    private external fun nativeRecord(bytes: Long, nanos: Long)
    private external fun nativeBps(): Long
    private external fun nativeReset()
    private external fun nativePrewarmDns(host: String): Boolean

    fun record(bytes: Long, nanos: Long) {
        if (loaded) nativeRecord(bytes, nanos)
    }

    fun bytesPerSecond(): Long = if (loaded) nativeBps() else 0L

    fun reset() {
        if (loaded) nativeReset()
    }

    fun prewarmDns(host: String): Boolean = loaded && nativePrewarmDns(host)
}
