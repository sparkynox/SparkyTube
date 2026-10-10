package dev.sparkynox.sparkytube.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.sparkynox.sparkytube.settings.SettingsPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// talks to our own update server (api/ folder on the website), downloads the apk
// itself and hands it to the system installer. state lives here so the update
// screen can be closed and reopened while a download is running
object UpdateManager {

    private const val BASE = "https://sparkytube.xo.je"
    private const val PREFS = "sparky_update"

    class Latest(
        val versionName: String,
        val versionCode: Int,
        val changelog: String,
        val publishedAt: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String,
        val mandatory: Boolean
    )

    class Message(
        val id: Int,
        val title: String,
        val body: String,
        val type: String,
        val link: String,
        val createdAt: String
    )

    enum class Phase { IDLE, CHECKING, DOWNLOADING, READY, ERROR }

    @Volatile var phase = Phase.IDLE
    @Volatile var latest: Latest? = null
    @Volatile var lastCheckOk = false
    @Volatile var error = ""
    @Volatile var downloaded = 0L
    @Volatile var total = 0L
    @Volatile var speed = 0L
    @Volatile var messages: List<Message> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- installed version ----

    fun installedName(c: Context): String = UpdateChecker.getInstalledVersionName(c)

    @Suppress("DEPRECATION")
    fun installedCode(c: Context): Int = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (e: Exception) { 0 }

    fun hasUpdate(c: Context): Boolean {
        val l = latest ?: return false
        return l.versionCode > installedCode(c) ||
            (l.versionCode == 0 && UpdateChecker.isNewer(l.versionName, installedName(c)))
    }

    // ---- fetching ----

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    suspend fun fetchLatest(): Latest? = withContext(Dispatchers.IO) {
        try {
            val body = get("$BASE/api/latest.php") ?: return@withContext null
            val j = JSONObject(body)
            if (!j.optBoolean("ok")) return@withContext null
            val u = j.optJSONObject("assets")?.optJSONObject("universal") ?: return@withContext null
            Latest(
                versionName = j.getString("versionName"),
                versionCode = j.optInt("versionCode", 0),
                changelog = j.optString("changelog", ""),
                publishedAt = j.optString("publishedAt", ""),
                apkUrl = u.getString("url") + "&src=app",
                size = u.optLong("size", 0L),
                sha256 = u.optString("sha256", ""),
                mandatory = j.optBoolean("mandatory", false)
            )
        } catch (e: Exception) { null }
    }

    suspend fun fetchMessages(after: Int = 0): List<Message>? = withContext(Dispatchers.IO) {
        try {
            val body = get("$BASE/api/messages.php?after=$after") ?: return@withContext null
            val arr = JSONObject(body).optJSONArray("messages") ?: return@withContext emptyList()
            (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                Message(m.getInt("id"), m.optString("title"), m.optString("body"), m.optString("type", "info"),
                    m.optString("link", ""), m.optString("createdAt", ""))
            }
        } catch (e: Exception) { null }
    }

    // update + messages in one go, both requests run at the same time
    suspend fun checkNow(c: Context) {
        if (phase == Phase.DOWNLOADING) return
        phase = Phase.CHECKING
        error = ""
        val ctx = c.applicationContext
        var gotLatest: Latest? = null
        var gotMessages: List<Message>? = null
        coroutineScope {
            val a = async { fetchLatest() }
            val b = async { fetchMessages(0) }
            gotLatest = a.await()
            gotMessages = b.await()
        }
        val l = gotLatest
        val m = gotMessages
        lastCheckOk = l != null || m != null
        if (l != null) latest = l
        if (m != null) messages = m
        phase = if (isApkReady(ctx)) Phase.READY else Phase.IDLE
    }

    // ---- unread messages ----

    fun lastReadId(c: Context) = prefs(c).getInt("last_read", 0)
    fun markAllRead(c: Context) {
        val top = messages.maxOfOrNull { it.id } ?: return
        prefs(c).edit().putInt("last_read", top).apply()
    }
    fun unreadCount(c: Context) = messages.count { it.id > lastReadId(c) }

    // ---- background check (worker + app start) ----

    // checks quietly and posts notifications for what's new since last time
    suspend fun backgroundCheck(c: Context) {
        val ctx = c.applicationContext
        if (!SettingsPrefs.isUpdaterEnabled(ctx)) return
        val l = fetchLatest()
        if (l != null) latest = l
        val notify = SettingsPrefs.isUpdateNotificationsEnabled(ctx)

        if (l != null && hasUpdate(ctx) && notify && prefs(ctx).getInt("notified_code", 0) < l.versionCode) {
            prefs(ctx).edit().putInt("notified_code", l.versionCode).apply()
            UpdateNotifier.notifyUpdate(ctx, l.versionName, l.changelog)
        }

        val seen = prefs(ctx).getInt("notified_msg", 0)
        val fresh = fetchMessages(seen)
        if (fresh != null) {
            messages = (fresh + messages).distinctBy { it.id }.sortedByDescending { it.id }
            if (fresh.isNotEmpty()) {
                prefs(ctx).edit().putInt("notified_msg", fresh.maxOf { it.id }).apply()
                // very first run: don't spam old messages, they're still in the inbox
                if (notify && seen > 0) fresh.sortedBy { it.id }.forEach { UpdateNotifier.notifyMessage(ctx, it) }
            }
        }
    }

    // ---- download + install ----

    private fun updatesDir(c: Context) = File(c.filesDir, "updates").apply { mkdirs() }

    private fun apkFile(c: Context, l: Latest) = File(updatesDir(c), "SparkyTube-${l.versionName}.apk")

    private fun isApkReady(c: Context): Boolean {
        val l = latest ?: return false
        val f = apkFile(c, l)
        return hasUpdate(c) && f.exists() && (l.size <= 0 || f.length() == l.size)
    }

    fun startDownload(c: Context) {
        val ctx = c.applicationContext
        val l = latest ?: return
        if (phase == Phase.DOWNLOADING) return
        if (isApkReady(ctx)) {
            phase = Phase.READY
            install(ctx)
            return
        }
        phase = Phase.DOWNLOADING
        error = ""
        downloaded = 0
        total = l.size
        speed = 0

        scope.launch {
            val dir = updatesDir(ctx)
            dir.listFiles()?.forEach { it.delete() }   // old apks out of the way
            val target = apkFile(ctx, l)
            val part = File(dir, target.name + ".part")
            try {
                http.newCall(Request.Builder().url(l.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("Server said ${r.code}")
                    val body = r.body ?: throw IllegalStateException("Empty response")
                    if (body.contentLength() > 0) total = body.contentLength()
                    val md = MessageDigest.getInstance("SHA-256")
                    var lastT = System.currentTimeMillis()
                    var lastB = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastT >= 700) {
                                    speed = (downloaded - lastB) * 1000 / (now - lastT)
                                    lastT = now; lastB = downloaded
                                }
                            }
                        }
                    }
                    if (l.sha256.isNotBlank()) {
                        val got = md.digest().joinToString("") { "%02x".format(it) }
                        if (!got.equals(l.sha256, ignoreCase = true)) throw IllegalStateException("File check failed, try again")
                    }
                }
                if (!part.renameTo(target)) throw IllegalStateException("Couldn't save the file")
                phase = Phase.READY
                withContext(Dispatchers.Main) { install(ctx) }
            } catch (e: Exception) {
                part.delete()
                error = e.message ?: "Download failed"
                phase = Phase.ERROR
            }
        }
    }

    // opens the system installer, asks for "install unknown apps" first if needed
    fun install(c: Context) {
        val ctx = c.applicationContext
        val l = latest ?: return
        val f = apkFile(ctx, l)
        if (!f.exists()) { phase = Phase.IDLE; return }

        if (android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            error = "Allow SparkyTube to install apps, then tap Install again"
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        error = ""
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- periodic work ----

    fun schedule(c: Context) {
        val req = androidx.work.PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()
        androidx.work.WorkManager.getInstance(c.applicationContext)
            .enqueueUniquePeriodicWork("sparky_update_check", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
