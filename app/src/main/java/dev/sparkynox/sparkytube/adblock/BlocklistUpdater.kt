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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the ad/tracker lists fresh without shipping a new APK.
 *
 * Every time the app comes to the foreground from nothing (see SparkyTubeApp) a one-time WorkManager job
 * asks GitHub for the two list files. The request is conditional (ETag), so when nothing changed it is a
 * tiny 304 and costs almost no data.
 *
 *  - blocklist_hosts.txt   -> cleaned, validated, saved to filesDir and swapped into the running engine at once
 *  - filters_network.txt   -> validated and staged as filters_network.new, picked up by NativeFilter on the
 *                             next start (it is loaded once by the C++ engine, with a crash-loop guard)
 *
 * The files are produced by tools/update_blocklist.py (run by .github/workflows/update-blocklist.yml).
 */
object BlocklistUpdater {

    private const val TAG = "AdBlock"
    private const val PREFS = "sparky_blocklist"
    private const val BASE = "https://raw.githubusercontent.com/sparkynox/SparkyTube/main/app/src/main/assets/"

    const val HOSTS_FILE = "blocklist_hosts.txt"
    const val FILTERS_FILE = "filters_network.txt"
    const val STAGED_FILTERS = "filters_network.new"

    // don't hit the network again if we already checked a moment ago (quick app switches)
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    private const val MAX_BYTES = 12L * 1024 * 1024
    private const val MIN_HOSTS = 20_000
    private const val MIN_FILTER_LINES = 1_000

    private val HOST = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z][a-z0-9-]+$")

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

    /** The downloaded hosts file, but only if it was fetched for this exact app version. */
    internal fun downloadedHosts(ctx: Context): File? {
        val f = File(ctx.filesDir, HOSTS_FILE)
        return if (f.exists() && prefs(ctx).getLong("code_$HOSTS_FILE", -1) == versionCode(ctx)) f else null
    }

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

        val hostsChanged = updateHosts(ctx)
        val filtersChanged = updateFilters(ctx)

        p.edit().putLong("last_check", now).apply()
        return if (hostsChanged || filtersChanged) Outcome.UPDATED else Outcome.UP_TO_DATE
    }

    // ---- hosts ----

    private fun updateHosts(ctx: Context): Boolean {
        val p = prefs(ctx)
        val have = downloadedHosts(ctx) != null
        val fetched = fetch(HOSTS_FILE, if (have) p.getString("etag_$HOSTS_FILE", null) else null) ?: return false

        val hosts = HashSet<String>(70_000)
        for (raw in fetched.bytes.toString(Charsets.UTF_8).lineSequence()) {
            val h = raw.trim().lowercase()
            if (h.isNotEmpty() && HOST.matches(h) && h !in NEVER_BLOCK) hosts.add(h)
        }
        val before = p.getInt("hosts_count", 0)
        if (hosts.size < MIN_HOSTS || (before > 0 && hosts.size < before / 2)) {
            throw IOException("hosts list looks wrong (${hosts.size} entries, had $before), keeping the old one")
        }

        writeAtomic(File(ctx.filesDir, HOSTS_FILE), hosts.sorted().joinToString("\n", postfix = "\n").toByteArray())
        p.edit()
            .putLong("code_$HOSTS_FILE", versionCode(ctx))
            .putString("etag_$HOSTS_FILE", fetched.etag)
            .putInt("hosts_count", hosts.size)
            .apply()

        AdBlockEngine.applyHosts(hosts) // live, no restart needed
        LogRecorder.i(TAG, "block list updated: ${hosts.size} hosts (was $before)")
        return true
    }

    // ---- network filter rules ----

    private fun updateFilters(ctx: Context): Boolean {
        val p = prefs(ctx)
        val have = File(ctx.filesDir, STAGED_FILTERS).exists() ||
            p.getLong("code_$FILTERS_FILE", -1) == versionCode(ctx)
        val fetched = fetch(FILTERS_FILE, if (have) p.getString("etag_$FILTERS_FILE", null) else null) ?: return false

        if (fetched.bytes.any { it == 0.toByte() }) throw IOException("filter file has binary data")
        val lines = fetched.bytes.toString(Charsets.UTF_8).lineSequence().count { it.isNotBlank() }
        if (lines < MIN_FILTER_LINES) throw IOException("filter file looks wrong ($lines lines), keeping the old one")

        writeAtomic(File(ctx.filesDir, STAGED_FILTERS), fetched.bytes)
        p.edit()
            .putLong("code_$FILTERS_FILE", versionCode(ctx))
            .putString("etag_$FILTERS_FILE", fetched.etag)
            .apply()
        LogRecorder.i(TAG, "filter rules downloaded: $lines rules, active on next start")
        return true
    }

    // ---- helpers ----

    private class Fetched(val bytes: ByteArray, val etag: String?)

    // null means 304: nothing changed
    private fun fetch(name: String, etag: String?): Fetched? {
        val rb = Request.Builder().url(BASE + name).header("User-Agent", "SparkyTube-blocklist")
        if (etag != null) rb.header("If-None-Match", etag)
        client.newCall(rb.build()).execute().use { r ->
            if (r.code == 304) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $name")
            val body = r.body ?: throw IOException("empty response for $name")
            val declared = body.contentLength()
            if (declared > MAX_BYTES) throw IOException("$name is too large ($declared bytes)")
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) throw IOException("$name is too large (${bytes.size} bytes)")
            return Fetched(bytes, r.header("ETag"))
        }
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
