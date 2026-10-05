package dev.sparkynox.sparkytube.download

import android.app.DownloadManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

// Holds back downloads past the "simultaneous downloads" limit and starts
// them one by one as slots free up. Every start block gets a release()
// callback and has to call it when its download is over (done, failed or
// cancelled), that's what frees the slot for the next one.
object DownloadQueue {

    class Queued(
        val id: Long,
        val title: String,
        val qualityLabel: String,
        val videoId: String?,
        val start: (release: () -> Unit) -> Unit
    )

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayDeque<Queued>()
    private var running = 0
    private var nextId = 1L
    private var appContext: Context? = null

    private fun maxSlots(): Int {
        val ctx = appContext ?: return 2
        return dev.sparkynox.sparkytube.settings.SettingsPrefs.getMaxConcurrentDownloads(ctx)
    }

    fun submit(context: Context, title: String, qualityLabel: String, videoId: String?, start: (release: () -> Unit) -> Unit) {
        appContext = context.applicationContext
        val id = synchronized(lock) {
            val n = nextId++
            waiting.addLast(Queued(n, title, qualityLabel, videoId, start))
            n
        }
        pump()

        val ahead = synchronized(lock) { waiting.indexOfFirst { it.id == id } }
        if (ahead >= 0) {
            main.post {
                Toast.makeText(appContext, "Added to queue (${ahead + 1} waiting)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun pump() {
        while (true) {
            val next = synchronized(lock) {
                if (waiting.isEmpty() || running >= maxSlots()) null
                else {
                    running++
                    waiting.removeFirst()
                }
            } ?: return
            runOne(next)
        }
    }

    private fun runOne(item: Queued) {
        val released = AtomicBoolean(false)
        val release: () -> Unit = {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) { running-- }
                main.post { pump() }
            }
        }
        try {
            item.start(release)
        } catch (e: Exception) {
            release()
        }
    }

    // DownloadManager downloads don't tell us when they end unless we ask,
    // so poll the row. A missing row (user deleted it) counts as finished too.
    fun watchDirect(context: Context, downloadId: Long, release: () -> Unit) {
        val dm = context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val tick = object : Runnable {
            override fun run() {
                val finished = try {
                    dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
                        if (!c.moveToFirst()) {
                            true
                        } else {
                            val s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            s == DownloadManager.STATUS_SUCCESSFUL || s == DownloadManager.STATUS_FAILED
                        }
                    }
                } catch (e: Exception) {
                    true
                }
                if (finished) release() else main.postDelayed(this, 2000)
            }
        }
        main.postDelayed(tick, 2000)
    }

    fun pending(): List<Queued> = synchronized(lock) { waiting.toList() }

    fun cancelPending(id: Long) {
        synchronized(lock) { waiting.removeAll { it.id == id } }
    }

    fun cancelAllPending() {
        synchronized(lock) { waiting.clear() }
    }
}
