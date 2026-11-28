package dev.sparkynox.sparkytube.extractor

// headless JS for the BotGuard po token (QuickJS + a small browser shim, see cpp/lunoviewer)
class LunoJs(userAgent: String) : AutoCloseable {

    private var handle = nativeCreate(userAgent)
    val alive: Boolean get() = handle != 0L

    /** Runs the code, waits for its promise, returns the result. Throws with the engine's message on any error. */
    @Synchronized
    fun run(code: String, timeoutMs: Long): String {
        val r = nativeRun(handle, code, timeoutMs)
        if (r.startsWith("OK:")) return r.substring(3)
        throw IllegalStateException(r.removePrefix("ERR:"))
    }

    @Synchronized
    fun takeLog(): String = if (handle == 0L) "" else nativeTakeLog(handle)

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    private external fun nativeCreate(ua: String): Long
    private external fun nativeRun(handle: Long, code: String, timeoutMs: Long): String
    private external fun nativeTakeLog(handle: Long): String
    private external fun nativeDestroy(handle: Long)

    companion object {
        val available: Boolean = try {
            System.loadLibrary("lunoviewer")
            true
        } catch (e: Throwable) {
            false
        }
    }
}
