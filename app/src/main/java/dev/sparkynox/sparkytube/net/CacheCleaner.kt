package dev.sparkynox.sparkytube.net

import android.content.Context

/**
 * Empties the app cache folders every time the app process starts (thumbnails, WebView cache, the media cache).
 * It runs on its own thread so start-up is not held up, and the media cache waits for it (await) before it opens
 * its folder, so nothing is deleted under a cache that is already in use. Logs, settings and downloads are not in the cache.
 */
object CacheCleaner {
    @Volatile private var worker: Thread? = null

    fun start(context: Context) {
        val app = context.applicationContext
        worker = Thread {
            try {
                app.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                app.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            } catch (_: Exception) {
            }
        }.also { it.name = "cache-cleaner"; it.start() }
    }

    /** Blocks (at most 5 s) until the clean-up has finished. */
    fun await() {
        try { worker?.join(5_000) } catch (_: InterruptedException) { }
    }
}
