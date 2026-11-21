package dev.sparkynox.sparkytube.extractor.social

import android.content.Context
import dev.sparkynox.sparkytube.logs.LogRecorder
import org.json.JSONObject

/**
 * The parts of the site parsers that break when a site changes something (query ids, app ids, patterns), kept as
 * data. Defaults are built in; lazyluno_rules.json in the repo can replace them without a new APK, it is fetched
 * in the background at most every 6 hours (GitHub raw, jsDelivr as the mirror) and cached.
 */
object SocialRules {
    private const val PREFS = "lazyluno_rules"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private val URLS = listOf(
        "https://raw.githubusercontent.com/sparkynox/SparkyTube/main/lazyluno_rules.json",
        "https://cdn.jsdelivr.net/gh/sparkynox/SparkyTube@main/lazyluno_rules.json",
    )

    @Volatile var version = 0; private set
    @Volatile var igDocId = "8845758582119845"; private set
    @Volatile var igAppId = "936619743392459"; private set
    @Volatile var igMobileUa = "Instagram 275.0.0.27.98 Android (33/13; 420dpi; 1080x2400; samsung; SM-G991B; o1s; exynos2100; en_US; 458229237)"; private set
    @Volatile var fbPatterns: List<String> = listOf(
        "\"browser_native_hd_url\":\"(.*?)\"", "\"browser_native_sd_url\":\"(.*?)\"",
        "\"playable_url_quality_hd\":\"(.*?)\"", "\"playable_url\":\"(.*?)\"",
        "hd_src:\"(.*?)\"", "sd_src:\"(.*?)\"", "\"hd_src\":\"(.*?)\"", "\"sd_src\":\"(.*?)\"",
    ); private set

    @Volatile private var loaded = false

    /** Applies the cached rules right away and refreshes from the net in the background when they are old. */
    fun ensure(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!loaded) {
            loaded = true
            p.getString("json", null)?.let { apply(it) }
        }
        if (System.currentTimeMillis() - p.getLong("at", 0) < EVERY_MS) return
        p.edit().putLong("at", System.currentTimeMillis()).apply() // one try per window, even when it fails
        Thread {
            for (u in URLS) {
                try {
                    val r = SocialHttp.get(u)
                    if (r.code == 200 && apply(r.body)) {
                        p.edit().putString("json", r.body).apply()
                        LogRecorder.i("Social", "rules v$version loaded from $u")
                        return@Thread
                    }
                } catch (e: Exception) {
                    LogRecorder.w("Social", "rules fetch failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun apply(json: String): Boolean = try {
        val o = JSONObject(json)
        val v = o.optInt("version", 0)
        if (v >= version) {
            version = v
            o.optJSONObject("instagram")?.let {
                it.optString("docId").takeIf { s -> s.isNotBlank() }?.let { s -> igDocId = s }
                it.optString("appId").takeIf { s -> s.isNotBlank() }?.let { s -> igAppId = s }
                it.optString("mobileUa").takeIf { s -> s.isNotBlank() }?.let { s -> igMobileUa = s }
            }
            o.optJSONObject("facebook")?.optJSONArray("patterns")?.let { a ->
                val l = (0 until a.length()).map { i -> a.optString(i) }.filter { s -> s.isNotBlank() }
                if (l.isNotEmpty()) fbPatterns = l
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}
