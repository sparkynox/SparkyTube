package dev.sparkynox.sparkytube.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// splits a file into small segments and lets a few workers take the next free one, so a slow
// connection never holds up the end of the download. finished segments are saved to a .parts file,
// a retry (or a restart) carries on instead of starting over
object SegmentedDownloader {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) SparkyTube"
    private const val MIN_SEGMENT = 512L * 1024
    private const val MAX_SEGMENT = 4L * 1024 * 1024

    fun download(
        client: OkHttpClient,
        url: String,
        dest: File,
        total: Long,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val segSize = (total / 24).coerceIn(MIN_SEGMENT, MAX_SEGMENT)
        val segCount = ((total + segSize - 1) / segSize).toInt()
        val done = AtomicIntegerArray(segCount)
        val parts = File(dest.path + ".parts")

        val resumed = dest.exists() && dest.length() == total && readParts(parts, total, segSize, done)
        if (!resumed) {
            dest.delete()
            parts.delete()
            for (i in 0 until segCount) done.set(i, 0)
        }

        var already = 0L
        for (i in 0 until segCount) if (done.get(i) == 1) already += segLen(i, segSize, total)
        val downloaded = AtomicLong(already)

        val sink = NativeIo.open(dest, total)
        val next = AtomicInteger(0)
        val failure = AtomicReference<Exception?>(null)
        val stop = AtomicBoolean(false)
        val lastReport = AtomicLong(0)
        val lastSave = AtomicLong(0)

        fun report(force: Boolean) {
            val now = System.currentTimeMillis()
            if (force || now - lastReport.get() > 250) {
                lastReport.set(now)
                onProgress(downloaded.get(), total)
            }
        }

        fun save(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastSave.get() < 1500) return
            lastSave.set(now)
            writeParts(parts, total, segSize, done)
        }

        fun segment(i: Int) {
            val start = i * segSize
            val end = start + segLen(i, segSize, total) - 1
            var pos = start
            var error: Exception? = null
            for (attempt in 0..3) {
                if (stop.get() || isCancelled()) return
                try {
                    val req = Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=$pos-$end").build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.code != 206) throw IOException("Server ignored the range request (HTTP ${resp.code})")
                        val body = resp.body ?: throw IOException("Empty response")
                        val buf = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (pos <= end) {
                                if (stop.get() || isCancelled()) throw InterruptedIOException("cancelled")
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                                if (n < 0) throw IOException("Connection closed early")
                                sink.write(buf, n, pos)
                                pos += n
                                downloaded.addAndGet(n.toLong())
                                report(false)
                            }
                        }
                    }
                    error = null
                    break
                } catch (e: InterruptedIOException) {
                    return
                } catch (e: Exception) {
                    error = e
                    Thread.sleep(400L * (attempt + 1))   // next try carries on from pos
                }
            }
            if (error != null) {
                failure.compareAndSet(null, error)
                stop.set(true)
                return
            }
            done.set(i, 1)
            save(false)
        }

        val workers = minOf(segCount, 8).coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val jobs = (0 until workers).map {
                pool.submit {
                    while (!stop.get() && !isCancelled()) {
                        val i = next.getAndIncrement()
                        if (i >= segCount) break
                        if (done.get(i) == 1) continue
                        segment(i)
                    }
                }
            }
            jobs.forEach { it.get() }
        } finally {
            pool.shutdownNow()
            try {
                sink.sync()
            } catch (_: Exception) {
            }
            sink.close()
            save(true)
        }

        if (isCancelled()) throw InterruptedIOException("cancelled")
        failure.get()?.let { throw it }
        for (i in 0 until segCount) if (done.get(i) != 1) throw IOException("Download incomplete")
        report(true)
        parts.delete()
    }

    private fun segLen(i: Int, segSize: Long, total: Long): Long = minOf(segSize, total - i * segSize)

    private fun writeParts(f: File, total: Long, segSize: Long, done: AtomicIntegerArray) {
        try {
            val sb = StringBuilder()
            sb.append("v1\n").append(total).append('\n').append(segSize).append('\n')
            for (i in 0 until done.length()) sb.append(if (done.get(i) == 1) '1' else '0')
            val tmp = File(f.path + ".tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    private fun readParts(f: File, total: Long, segSize: Long, done: AtomicIntegerArray): Boolean {
        return try {
            if (!f.exists()) return false
            val lines = f.readText().split('\n')
            if (lines.size < 4 || lines[0] != "v1" || lines[1].toLong() != total || lines[2].toLong() != segSize) return false
            val bits = lines[3]
            if (bits.length != done.length()) return false
            for (i in bits.indices) done.set(i, if (bits[i] == '1') 1 else 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
