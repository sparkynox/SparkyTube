package dev.sparkynox.sparkytube.adblock

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keeps the ad/tracker lists fresh without shipping a new APK.
 *
 * Every time the app comes to the foreground from nothing (see SparkyTubeApp) a one-time WorkManager job
 * fetches version.json (~200 bytes) and compares the sha256s in it with what is already on the phone.
 * Only the file that really changed gets downloaded.
 *
 *  - hosts.bin           -> binary hash index (~430 KB), checked, then memory mapped by C++ (HostIndex) live
 *  - filters_network.txt -> validated and staged as filters_network.new, picked up by NativeFilter on the
 *                           next start (it is loaded once by the C++ engine, with a crash-loop guard)
 *
 * The files are produced by tools/update_blocklist.py (run by .github/workflows/update-blocklist.yml)
 * and live in /blocklist in the repo. MIRRORS are tried in order, so a dead one just falls through.
 */
object BlocklistUpdater {

    private const val TAG = "AdBlock"
    private const val PREFS = "sparky_blocklist"

    // tried in order. to add your own server (Render, Voroa...) put it first, it only has to serve
    // version.json, hosts.bin and filters_network.txt from its root
    private val MIRRORS = listOf(
        "https://raw.githubusercontent.com/sparkynox/SparkyTube/main/blocklist/",
        "https://cdn.jsdelivr.net/gh/sparkynox/SparkyTube@main/blocklist/",
    )

    const val HOSTS_ASSET = "blocklist_hosts.bin"
    const val HOSTS_FILE = "blocklist_hosts.bin"
    const val FILTERS_FILE = "filters_network.txt"
    const val STAGED_FILTERS = "filters_network.new"
    private const val REMOTE_HOSTS = "hosts.bin"

    // don't hit the network again if we already checked a moment ago (quick app switches)
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    private const val MAX_BYTES = 12L * 1024 * 1024
    private const val MIN_HOSTS = 20_000
    private const val MIN_FILTER_LINES = 1_000

    // The engine blocks a host AND everything under it, so one of these slipping into a list would break
    // YouTube or the app itself. Same safety net as NEVER in tools/update_blocklist.py, enforced on-device too.
    internal val NEVER_BLOCK = setOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com",
        "gstatic.com", "google.com", "googleapis.com", "gvt1.com", "gvt2.com", "youtube-nocookie.com",
        "github.com", "githubusercontent.com", "cloudflare.com", "jsdelivr.net", "apple.com", "microsoft.com",
        "amazonaws.com", "cloudfront.net", "akamaihd.net", "akamaized.net",
    )

    enum class Outcome { UPDATED, UP_TO_DATE, SKIPPED }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Queue one check. Calling it again while one is queued or running does nothing. */
    fun schedule(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<BlocklistWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork("sparky_blocklist_update", ExistingWorkPolicy.KEEP, req)
    }

    fun hostsFile(ctx: Context) = File(ctx.filesDir, HOSTS_FILE)

    /** A staged rule file for this app version, or null. A file staged for an older version is deleted. */
    internal fun stagedFilters(ctx: Context): File? {
        val f = File(ctx.filesDir, STAGED_FILTERS)
        if (!f.exists()) return null
        if (prefs(ctx).getLong("code_$FILTERS_FILE", -1) == versionCode(ctx)) return f
        f.delete()
        return null
    }

    /** Blocking, run it off the main thread. Throws IOException on network or validation problems. */
    fun update(ctx: Context, force: Boolean = false): Outcome {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("last_check", 0) < MIN_INTERVAL_MS) return Outcome.SKIPPED

        val info = fetchVersion()
        val hostsChanged = updateHosts(ctx, info)
        val filtersChanged = updateFilters(ctx, info)

        p.edit().putLong("last_check", now).apply()
        return if (hostsChanged || filtersChanged) Outcome.UPDATED else Outcome.UP_TO_DATE
    }

    private class Info(val version: String, val hostsSha: String, val filtersSha: String)

    private fun fetchVersion(): Info {
        val raw = download("version.json", 4096).toString(Charsets.UTF_8)
        return try {
            val o = JSONObject(raw)
            Info(o.optString("version"), o.getString("hosts_sha256"), o.getString("filters_sha256"))
        } catch (e: Exception) {
            throw IOException("version.json is not readable")
        }
    }

    // ---- hosts ----

    private fun updateHosts(ctx: Context, info: Info): Boolean {
        val p = prefs(ctx)
        val target = hostsFile(ctx)
        if (target.exists() && p.getString("sha_hosts", null) == info.hostsSha) return false

        val bytes = download(REMOTE_HOSTS, MAX_BYTES)
        if (sha256(bytes) != info.hostsSha) throw IOException("hosts.bin doesn't match its checksum")

        // header: "STHB", u32 version, u32 count, u32 0, then count * 8 bytes
        val count = if (bytes.size >= 16) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(8) else -1
        val before = p.getInt("hosts_count", 0)
        if (count < MIN_HOSTS || bytes.size.toLong() != 16L + count.toLong() * 8 || (before > 0 && count < before / 2)) {
            throw IOException("hosts list looks wrong ($count entries, had $before), keeping the old one")
        }

        val tmp = File(ctx.filesDir, "$HOSTS_FILE.tmp")
        tmp.writeBytes(bytes)
        // the engine blocks a host and everything under it, so a list with youtube.com in it is thrown away
        val bad = NEVER_BLOCK.firstOrNull { HostIndex.hasExact(tmp, it) }
        if (bad != null) {
            tmp.delete()
            throw IOException("hosts list contains $bad, keeping the old one")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("couldn't replace $HOSTS_FILE")
        }
        // live swap, the old mapping is released inside
        val n = HostIndex.open(target)
        if (n <= 0) throw IOException("new hosts list was rejected by the engine")

        p.edit().putString("sha_hosts", info.hostsSha).putInt("hosts_count", n).apply()
        LogRecorder.i(TAG, "block list updated: $n hosts (was $before), version ${info.version}")
        return true
    }

    // ---- network filter rules ----

    private fun updateFilters(ctx: Context, info: Info): Boolean {
        val p = prefs(ctx)
        val staged = File(ctx.filesDir, STAGED_FILTERS).exists() ||
            p.getLong("code_$FILTERS_FILE", -1) == versionCode(ctx)
        if (staged && p.getString("sha_filters", null) == info.filtersSha) return false

        val bytes = download(FILTERS_FILE, MAX_BYTES)
        if (sha256(bytes) != info.filtersSha) throw IOException("filters_network.txt doesn't match its checksum")
        if (bytes.any { it == 0.toByte() }) throw IOException("filter file has binary data")
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().count { it.isNotBlank() }
        if (lines < MIN_FILTER_LINES) throw IOException("filter file looks wrong ($lines lines), keeping the old one")

        writeAtomic(File(ctx.filesDir, STAGED_FILTERS), bytes)
        p.edit()
            .putLong("code_$FILTERS_FILE", versionCode(ctx))
            .putString("sha_filters", info.filtersSha)
            .apply()
        LogRecorder.i(TAG, "filter rules downloaded: $lines rules, active on next start")
        return true
    }

    // ---- helpers ----

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // first mirror that answers wins, the rest are only tried when one fails
    private fun download(name: String, maxBytes: Long): ByteArray {
        var last: IOException? = null
        for (base in MIRRORS) {
            try {
                val rb = Request.Builder().url(base + name).header("User-Agent", "SparkyTube-blocklist")
                client.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $name")
                    val body = r.body ?: throw IOException("empty response for $name")
                    val declared = body.contentLength()
                    if (declared > maxBytes) throw IOException("$name is too large ($declared bytes)")
                    val bytes = body.bytes()
                    if (bytes.size > maxBytes) throw IOException("$name is too large (${bytes.size} bytes)")
                    return bytes
                }
            } catch (e: IOException) {
                last = e
                LogRecorder.w(TAG, "mirror failed for $name: ${e.message}")
            }
        }
        throw last ?: IOException("no mirror configured")
    }

    // write next to the target, then rename: a half-written file can never be read
    private fun writeAtomic(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("couldn't replace ${target.name}")
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun versionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
}

class BlocklistWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val outcome = BlocklistUpdater.update(applicationContext)
            LogRecorder.i("AdBlock", "blocklist check: $outcome")
            Result.success()
        } catch (e: IOException) {
            LogRecorder.w("AdBlock", "blocklist check failed", e)
            // retry a couple of times with backoff, then give up until the next app open
            if (runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: Exception) {
            LogRecorder.w("AdBlock", "blocklist check crashed", e)
            Result.success()
        }
    }
}
