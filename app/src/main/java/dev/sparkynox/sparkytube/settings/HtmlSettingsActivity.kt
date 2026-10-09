package dev.sparkynox.sparkytube.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import dev.sparkynox.sparkytube.MainActivity
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.databinding.ActivityHtmlSettingsBinding
import dev.sparkynox.sparkytube.download.DownloadQueue
import dev.sparkynox.sparkytube.extractor.StreamExtractor
import org.json.JSONArray
import org.json.JSONObject

// The stylish settings screen. All the looks live in assets/settings.html,
// this class only describes the rows and applies changes to SettingsPrefs.
// Rows mirror the stock SettingsActivity, so changing a value in either screen
// is the same thing underneath.
class HtmlSettingsActivity : AppCompatActivity() {

    private class Item(
        val id: String,
        val type: String,                    // toggle | choice | link | css
        val title: String,
        val icon: String,
        val sub: () -> String = { "" },
        val value: () -> Any? = { null },
        val options: List<Pair<String, String>> = emptyList(),
        val enabled: () -> Boolean = { true },
        val set: (String) -> Unit = {},
        val run: () -> Unit = {}
    )

    private class Section(val key: String, val title: String, val icon: String, val sub: String, val items: List<Item>)

    companion object {
        const val EXTRA_OPEN_SECTION = "open_section"
    }

    private lateinit var binding: ActivityHtmlSettingsBinding
    private val sections: List<Section> by lazy { buildSections() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHtmlSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // back goes sub page -> settings home first, only then closes
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binding.htmlSettingsWeb.evaluateJavascript("(window.__goBack ? window.__goBack() : false)") { r ->
                    if (r != "true") finish()
                }
            }
        })

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        binding.htmlSettingsWeb.apply {
            setBackgroundColor(getColor(R.color.bg_root))
            settings.javaScriptEnabled = true
            addJavascriptInterface(Bridge(), "SparkySettings")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/settings.html")
        }
    }

    override fun onResume() {
        super.onResume()
        // coming back from Logs / Local Servers etc, their summary lines may have changed
        refreshPage()
    }

    private fun refreshPage() {
        if (!::binding.isInitialized) return
        binding.htmlSettingsWeb.evaluateJavascript("window.__refresh && window.__refresh()", null)
    }

    private fun toggle(id: String, title: String, icon: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        Item(id, "toggle", title, icon, sub = { sub }, value = get, set = { set(it == "true") })

    private fun choice(
        id: String, title: String, icon: String, options: List<Pair<String, String>>,
        get: () -> String, set: (String) -> Unit,
        sub: (String) -> String = { "" }, enabled: () -> Boolean = { true }
    ) = Item(id, "choice", title, icon, sub = { sub(get()) }, value = get, options = options, enabled = enabled, set = set)

    private fun link(id: String, title: String, icon: String, sub: () -> String, run: () -> Unit) =
        Item(id, "link", title, icon, sub = sub, run = run)

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun buildSections(): List<Section> {
        val p = SettingsPrefs
        return listOf(
            Section("features", "Features", "shield", "Ad blocking, SponsorBlock, anime streaming", listOf(
                toggle("adblock", "Ad blocking", "shield", "Blocks ad/tracker domains and hides ad slots",
                        { p.isAdBlockEnabled(this) }, { p.setAdBlockEnabled(this, it) }),
                toggle("sponsorblock", "SponsorBlock", "skip", "Auto-skip sponsor segments, self-promo, interaction reminders",
                        { p.isSponsorBlockEnabled(this) }, { p.setSponsorBlockEnabled(this, it) }),
                toggle("anime", "Anime streaming", "film", "Crunchyroll wrapper, reachable from the overflow menu",
                        { p.isAnimeStreamingEnabled(this) }, { p.setAnimeStreamingEnabled(this, it) })
            )),
            Section("youtube", "YouTube", "ext", "YouTube settings and related videos", listOf(
                link("ytsettings", "YT Settings", "ext", { "Open YouTube's own settings page" }) {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_OPEN_YT_SETTINGS, true)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                        finish()
                    },
                choice("related", "Related videos fetcher", "list",
                        listOf("AUTO" to "Auto (recommended)", "JAVASCRIPT" to "JavaScript (fastest)", "NEWPIPE" to "NewPipe (most reliable)"),
                        { p.getRelatedVideosFetcher(this).name }, { p.setRelatedVideosFetcher(this, SettingsPrefs.RelatedVideosFetcher.valueOf(it)) },
                        sub = {
                            when (it) {
                                "JAVASCRIPT" -> "JavaScript — fastest, only works while the video's page is loaded"
                                "NEWPIPE" -> "NewPipe — always works, one extra network request"
                                else -> "Auto — tries JavaScript first, falls back to NewPipe"
                            }
                        })
            )),
            Section("player", "Player and quality", "play", "Default quality and data saver", listOf(
                choice("quality", "Default video quality", "sliders",
                        listOf("144", "240", "360", "480", "720", "1080").map { it to "${it}p" },
                        { p.getDefaultQualityValue(this).toString() }, { p.setDefaultQualityValue(this, it.toInt()) },
                        sub = { "${it}p — applies to new videos as they load" }),
                toggle("datasaver", "Data Saver Mode", "pulse",
                        "Blocks YouTube telemetry, disables images, starts videos at the lowest quality",
                        { p.isDataSaverEnabled(this) }, { p.setDataSaverEnabled(this, it) })
            )),
            Section("extraction", "Extraction", "code", "Extractors, local servers and fallbacks", listOf(
                choice("extractor", "Extractor Method", "code",
                        listOf("AUTO" to "Auto", "LOCAL_SERVER" to "Local Servers", "NEWPIPE" to "NewPipe", "YT_DLP" to "yt-dlp (default)"),
                        { p.getExtractorMethod(this).name }, { p.setExtractorMethod(this, SettingsPrefs.ExtractorMethod.valueOf(it)) },
                        sub = {
                            when (it) {
                                "LOCAL_SERVER" -> "Local Servers only — set up under Local Servers above"
                                "NEWPIPE" -> "NewPipe only — skips the local server and JS fast path"
                                "YT_DLP" -> "yt-dlp first, NewPipe takes over if yt-dlp fails"
                                else -> "Auto — local server (if enabled) → JavaScript → NewPipe"
                            }
                        }),
                link("localservers", "Local Servers", "server", {
                        if (p.isLocalServerEnabled(this)) "Enabled — ${p.getLocalServerUrl(this)}" else "Off — using NewPipe/JavaScript extraction"
                    }) { open(LocalServerActivity::class.java) },
                toggle("piped", "Piped fallback", "globe",
                        "Fixes age-restricted videos by routing through a public Piped instance when NewPipe fails",
                        { p.isPipedFallbackEnabled(this) }, { p.setPipedFallbackEnabled(this, it) }),
                toggle("ytdlpfallback", "yt-dlp fallback (heavy)", "zap",
                        "Last-resort extractor for age-gated videos. Adds ~30-40MB to the app.",
                        { p.isYtDlpFallbackEnabled(this) }, { on ->
                            if (on && !p.isYtDlpFallbackEnabled(this)) {
                                AlertDialog.Builder(this)
                                    .setTitle("Enable yt-dlp fallback?")
                                    .setMessage(
                                        "This runs yt-dlp on-device as a last-resort fallback when NewPipe and Piped both fail " +
                                            "to play a video (usually age-restricted ones). It's slower than the other methods and " +
                                            "uses more storage/battery, but is the most reliable."
                                    )
                                    .setPositiveButton("Enable") { _, _ ->
                                        p.setYtDlpFallbackEnabled(this, true)
                                        refreshPage()
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            } else {
                                p.setYtDlpFallbackEnabled(this, on)
                            }
                        }),
                toggle("ytdlpsaver", "yt-dlp Data Saver", "pulse",
                        "On: only 360p, small and fast. Off: every quality yt-dlp finds (144p up to 1080p)",
                        { p.isYtDlpDataSaverEnabled(this) }, {
                            p.setYtDlpDataSaverEnabled(this, it)
                            StreamExtractor.clearCache()
                        })
            )),
            Section("downloads", "Downloads", "download", "Queue, progress and offline library", listOf(
                toggle("download", "Download button", "download", "Shows the download button on videos",
                        { p.isDownloadEnabled(this) }, { p.setDownloadEnabled(this, it) }),
                link("downloads", "Download manager", "layers", { "See progress, speed, and manage active downloads" }) {
                        open(dev.sparkynox.sparkytube.download.DownloadsActivity::class.java)
                    },
                choice("maxdl", "Simultaneous downloads", "layers",
                        listOf("1", "2", "3", "4").map { it to it },
                        { p.getMaxConcurrentDownloads(this).toString() }, {
                            p.setMaxConcurrentDownloads(this, it.toInt())
                            DownloadQueue.pump()
                        },
                        sub = { "$it at a time, the rest wait in the queue" }),
                link("offline", "Offline Library", "folder", { "Play or delete videos you've already downloaded" }) {
                        open(dev.sparkynox.sparkytube.download.OfflineLibraryActivity::class.java)
                    }
            )),
            Section("appearance", "Appearance", "moon", "Theme, colors and custom CSS", listOf(
                choice("theme", "Theme", "moon",
                        listOf("DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "Follow system"),
                        { p.getThemeMode(this).name }, {
                            p.setThemeMode(this, SettingsPrefs.ThemeMode.valueOf(it))
                            // flips night mode, AppCompat recreates this screen in the new colors
                            p.applyThemeMode(this)
                        },
                        sub = { "Dark, Light, or follow your phone" }),
                toggle("dynamiccolor", "Dynamic color (Material You)", "drop",
                        "Matches app colors to your wallpaper on Android 12+. Restart app to apply.",
                        { p.isDynamicColorEnabled(this) }, { p.setDynamicColorEnabled(this, it) }),
                toggle("customcss", "Custom CSS", "code", "Your own CSS, applied on top of SparkyTube's",
                        { p.isCustomCssEnabled(this) }, { p.setCustomCssEnabled(this, it) }),
                Item("cssedit", "css", "Edit CSS", "code", sub = { "Write or paste your CSS" }, value = { p.getCustomCss(this) })
            )),
            Section("experimental", "Experimental", "layout", "Beta home feed and Lumi AI", listOf(
                toggle("nativefeed", "Native Home Feed (Beta)", "layout",
                        "Renders Home natively instead of via WebView. Needs a YouTube login. Home only for now.",
                        { p.isNativeHomeFeedEnabled(this) }, { p.setNativeHomeFeedEnabled(this, it) }),
                choice("feedstyle", "Feed style", "layout",
                        listOf(
                            "KOTLIN_NATIVE" to "Native (RecyclerView)",
                            "YOUTUBE_SKIN" to "YouTube Skin (restyled real page)",
                            "CUSTOM_HTML" to "Custom HTML (built from scratch)"
                        ),
                        { p.getFeedStyle(this).name }, { p.setFeedStyle(this, SettingsPrefs.FeedStyle.valueOf(it)) },
                        sub = { if (p.isNativeHomeFeedEnabled(this)) "How the native Home feed is drawn" else "Turn on Native Home Feed first" },
                        enabled = { p.isNativeHomeFeedEnabled(this) }),
                toggle("lumiai", "Experimental features (Lumi AI)", "spark",
                        "Staff only for now — not available to regular users yet",
                        { false }, { on ->
                            if (on) {
                                AlertDialog.Builder(this)
                                    .setTitle("Coming soon")
                                    .setMessage("Lumi AI is still being worked on and is staff only for now (Owner/Mod/Admin). It isn't available to regular users yet.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        })
            )),
            Section("app", "App", "file", "Popups, updates and logs", listOf(
                toggle("popups", "Block all popups", "eyeoff", "Hides the contact-reminder, update, and welcome dialogs",
                        { p.arePopupsBlocked(this) }, { p.setPopupsBlocked(this, it) }),
                toggle("updater", "Check for updates", "refresh", "Looks for a new SparkyTube version on app open",
                        { p.isUpdaterEnabled(this) }, { p.setUpdaterEnabled(this, it) }),
                link("logs", "Logs", "file", {
                        if (dev.sparkynox.sparkytube.logs.LogRecorder.getLastCrash(this) != null) "⚠ Last session crashed — tap to view"
                        else "Session log + crash reports"
                    }) { open(dev.sparkynox.sparkytube.logs.LogsActivity::class.java) }
            ))
        )
    }

    private fun findItem(id: String): Item? = sections.flatMap { it.items }.firstOrNull { it.id == id }

    inner class Bridge {
        @JavascriptInterface
        fun schema(): String {
            val out = JSONArray()
            sections.forEach { sec ->
                val rows = JSONArray()
                sec.items.forEach { it0 ->
                    rows.put(JSONObject().apply {
                        put("id", it0.id)
                        put("type", it0.type)
                        put("title", it0.title)
                        put("icon", it0.icon)
                        put("sub", it0.sub())
                        put("value", it0.value() ?: JSONObject.NULL)
                        put("enabled", it0.enabled())
                        put("options", JSONArray().apply {
                            it0.options.forEach { (v, l) -> put(JSONObject().put("v", v).put("l", l)) }
                        })
                    })
                }
                out.put(JSONObject().put("key", sec.key).put("title", sec.title).put("icon", sec.icon).put("sub", sec.sub).put("rows", rows))
            }
            return out.toString()
        }

        @JavascriptInterface
        fun set(id: String, value: String) {
            runOnUiThread {
                findItem(id)?.set?.invoke(value)
                refreshPage()
            }
        }

        @JavascriptInterface
        fun run(id: String) {
            runOnUiThread { findItem(id)?.run?.invoke() }
        }

        @JavascriptInterface
        fun setCss(css: String) {
            SettingsPrefs.setCustomCss(this@HtmlSettingsActivity, css)
        }

        @JavascriptInterface
        fun getCss(): String = SettingsPrefs.getCustomCss(this@HtmlSettingsActivity)

        @JavascriptInterface
        fun isNight(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        @JavascriptInterface
        fun initialSection(): String = intent?.getStringExtra(EXTRA_OPEN_SECTION) ?: ""

        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }
}
