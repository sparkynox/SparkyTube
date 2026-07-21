package dev.sparkynox.sparkytube.update

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer SparkyTube version.
 *
 * Since this app is sideloaded (not on Play Store), there's no automatic
 * update mechanism — Android won't notify the user on its own. This hits
 * the public GitHub Releases API (no auth needed for public repos, no rate
 * limit issue at this scale) and compares tag names against the installed
 * versionName.
 *
 * Wire this to fire once per app-cold-start (not every resume) so it isn't
 * chatty — see MainActivity.checkForUpdatesOnce().
 */
object UpdateChecker {

    private const val REPO_OWNER = "sparkynox"
    private const val REPO_NAME = "SparkyTube"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val ALL_RELEASES_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"

    data class UpdateInfo(
        val versionTag: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val releaseDateIso: String
    )

    /**
     * @return UpdateInfo if a newer version is published, null if already
     *         up to date or the check failed (offline, rate-limited, no
     *         releases published yet, etc — fails silently, never blocks the app).
     */
    suspend fun checkForUpdate(currentVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val latestJson = fetchJson(LATEST_URL)

            // /releases/latest only returns non-draft, non-prerelease releases.
            // If that comes back empty (e.g. the release was marked as a
            // pre-release, which GitHub excludes from "latest"), fall back to
            // the full releases list and take the newest entry there instead.
            val json: JSONObject = latestJson ?: run {
                val listBody = fetchRaw(ALL_RELEASES_URL) ?: return@withContext null
                val arr = org.json.JSONArray(listBody)
                if (arr.length() == 0) return@withContext null
                arr.getJSONObject(0)
            }

            val tagName = json.optString("tag_name", "").removePrefix("v")
            if (tagName.isBlank() || !isNewer(tagName, currentVersionName)) return@withContext null

            // Download button always opens the GitHub release page itself
            // (not a direct APK link) — the user downloads the APK from there.
            val downloadUrl = json.optString("html_url", "https://github.com/$REPO_OWNER/$REPO_NAME/releases")

            UpdateInfo(
                versionTag = tagName,
                downloadUrl = downloadUrl,
                releaseNotes = json.optString("body", "").take(500),
                releaseDateIso = json.optString("published_at", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchJson(url: String): JSONObject? {
        val body = fetchRaw(url) ?: return null
        return try { JSONObject(body) } catch (e: Exception) { null }
    }

    private fun fetchRaw(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        if (conn.responseCode != 200) return null
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** Simple semantic-ish version compare, good enough for "1.2.0" style tags. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    fun getInstalledVersionName(context: android.content.Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }
}
