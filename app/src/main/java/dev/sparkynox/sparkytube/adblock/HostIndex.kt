package dev.sparkynox.sparkytube.adblock

import android.content.Context
import dev.sparkynox.sparkytube.logs.LogRecorder
import java.io.File
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.nio.channels.FileChannel

// ~54k blocked hosts, memory mapped by C++ (hostindex.cpp). the file is ~430 KB of sorted hashes,
// so the list costs almost no heap and lookups don't allocate
object HostIndex {

    private const val TAG = "AdBlock"

    @Volatile
    var count = 0
        private set

    private val nativeOk = try {
        System.loadLibrary("sparkynet")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeOpen(path: String): Int
    private external fun nativeBlocked(host: String): Boolean
    private external fun nativeHasExact(path: String, host: String): Boolean

    // only used when the native lib didn't load
    @Volatile
    private var fallback: LongBuffer? = null

    fun init(ctx: Context) {
        if (count > 0) return
        File(ctx.filesDir, "blocklist_hosts.txt").delete() // old text list from before the index
        val downloaded = BlocklistUpdater.hostsFile(ctx)
        if (downloaded.exists() && open(downloaded) > 0) {
            LogRecorder.i(TAG, "host index: $count hosts (downloaded)")
            return
        }
        val bundled = bundledCopy(ctx)
        if (open(bundled) > 0) LogRecorder.i(TAG, "host index: $count hosts (bundled)")
        else LogRecorder.e(TAG, "host index could not be loaded")
    }

    // assets can't be mmap'd when compressed, so the bundled index is copied out once per app version
    private fun bundledCopy(ctx: Context): File {
        val f = File(ctx.filesDir, "blocklist_bundled.bin")
        val code = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        val p = ctx.getSharedPreferences("sparky_blocklist", Context.MODE_PRIVATE)
        if (!f.exists() || p.getLong("bundled_code", -1) != code) {
            ctx.assets.open(BlocklistUpdater.HOSTS_ASSET).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            p.edit().putLong("bundled_code", code).apply()
        }
        return f
    }

    /** Swaps the live index to this file. Returns the host count, or -1 and keeps the old one if the file is bad. */
    fun open(file: File): Int {
        val n = if (nativeOk) nativeOpen(file.absolutePath) else openFallback(file)
        if (n > 0) count = n
        return n
    }

    fun blocked(host: String): Boolean {
        if (count == 0) return false
        return if (nativeOk) nativeBlocked(host) else blockedFallback(host)
    }

    /** True if this exact name is in the given index file (no parent lookup). */
    fun hasExact(file: File, host: String): Boolean =
        if (nativeOk) nativeHasExact(file.absolutePath, host) else exactFallback(file, host)

    // ---- kotlin fallback, same file format ----

    private fun fnv(s: String, from: Int = 0): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (i in from until s.length) {
            var c = s[i].code
            if (c in 65..90) c += 32
            h = (h xor c.toLong()) * 0x100000001b3L
        }
        return h
    }

    private fun mapLongs(file: File): LongBuffer? {
        file.inputStream().channel.use { ch ->
            if (ch.size() < 16) return null
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
            val ok = buf.get(0) == 'S'.code.toByte() && buf.get(1) == 'T'.code.toByte() &&
                buf.get(2) == 'H'.code.toByte() && buf.get(3) == 'B'.code.toByte() && buf.getInt(4) == 1
            val n = buf.getInt(8)
            if (!ok || ch.size() != 16L + n.toLong() * 8) return null
            buf.position(16)
            return buf.asLongBuffer()
        }
    }

    private fun openFallback(file: File): Int {
        val lb = mapLongs(file) ?: return -1
        fallback = lb
        return lb.limit()
    }

    private fun contains(lb: LongBuffer, h: Long): Boolean {
        var lo = 0
        var hi = lb.limit()
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val v = lb.get(mid)
            if (v == h) return true
            // sorted as unsigned in the file
            if (java.lang.Long.compareUnsigned(v, h) < 0) lo = mid + 1 else hi = mid
        }
        return false
    }

    private fun blockedFallback(host: String): Boolean {
        val lb = fallback ?: return false
        var start = 0
        while (true) {
            if (contains(lb, fnv(host, start))) return true
            val dot = host.indexOf('.', start)
            if (dot < 0 || dot + 1 >= host.length) return false
            start = dot + 1
        }
    }

    private fun exactFallback(file: File, host: String): Boolean {
        val lb = mapLongs(file) ?: return false
        return contains(lb, fnv(host))
    }
}
