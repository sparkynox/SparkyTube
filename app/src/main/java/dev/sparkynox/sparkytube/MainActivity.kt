package dev.sparkynox.sparkytube

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.sparkynox.sparkytube.adblock.AdBlockEngine
import dev.sparkynox.sparkytube.databinding.ActivityMainBinding
import dev.sparkynox.sparkytube.extractor.StreamExtractor
import dev.sparkynox.sparkytube.player.PlaybackService
import dev.sparkynox.sparkytube.update.UpdateChecker
import dev.sparkynox.sparkytube.update.UpdateNotifier
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity(), JsBridge.VideoStateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var prefs: SharedPreferences

    private var cssPayload: String = ""
    private var jsPayload: String = ""

    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    // Guards against re-resolving/re-playing the same video repeatedly.
    private var lastResolvedVideoId: String? = null
    private var isNativePlaybackActive = false
    private var isResolving = false

    // Qualities available for the currently-playing video, populated once
    // StreamExtractor resolves them — used by the quality-picker dialog.
    private var currentQualities: List<StreamExtractor.QualityOption> = emptyList()
    private var currentQualityLabel: String = ""

    // PRIMARY detection mechanism: polls webView.url directly, independent of
    // whatever YouTube's page JS/DOM is doing. The DOM-based JsBridge signal
    // (injected.js -> onVideoState) is kept as a secondary/backup trigger.
    private val pollHandler = Handler(Looper.getMainLooper())
    private val urlPollRunnable = object : Runnable {
        override fun run() {
            checkCurrentUrlForVideo()
            pollHandler.postDelayed(this, 600)
        }
    }

    companion object {
        private const val HOME_URL = "https://m.youtube.com/"
        private const val PREFS_NAME = "sparkytube_prefs"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val GITHUB_ISSUES_URL = "https://github.com/sparkynox/SparkyTube/issues"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder
            playbackService = binder?.getService()
            playbackService?.getPlayer()?.let { binding.exoPlayerView.player = it }
            playbackService?.setOnPlaybackEndedListener {
                runOnUiThread {
                    // Video finished natively — hand control back to the
                    // WebView, which autoplays the next suggested video on
                    // its own (YouTube's default web-player behavior). The
                    // URL poller then picks up the new video ID automatically.
                    stopNativePlayback()
                }
            }
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* in-app dialog covers it either way */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Always-on native ad/tracker engine — loaded once, lives for the
        // whole app session. No toggle: active from the very first request.
        adBlockEngine = AdBlockEngine.getInstance(this)

        cssPayload = assets.open("injected.css").bufferedReader().use { it.readText() }
        jsPayload = assets.open("injected.js").bufferedReader().use { it.readText() }

        setupWebView()
        setupBottomNav()
        setupTopBar()
        bindPlaybackService()

        requestNotificationPermissionIfNeeded()
        checkForUpdatesOnce()
        showFirstLaunchDialogIfNeeded()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isNativePlaybackActive) {
                    stopNativePlayback()
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        webView.loadUrl(HOME_URL)
        pollHandler.post(urlPollRunnable)
    }

    // --- First launch welcome + About dialog ------------------------------------------

    private fun showFirstLaunchDialogIfNeeded() {
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) return
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()

        AlertDialog.Builder(this)
            .setTitle("Welcome to SparkyTube")
            .setMessage(
                "Thanks so much for trying SparkyTube!\n\n" +
                "This app is an independent project — a custom YouTube client " +
                "with native background playback and ad blocking, built from scratch.\n\n" +
                "If you run into a bug or want to request something, please open " +
                "an issue on GitHub — that's the best way to reach the developer."
            )
            .setPositiveButton("Got it") { _, _ -> }
            .setNeutralButton("Open GitHub Issues") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUES_URL)))
            }
            .setCancelable(true)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About SparkyTube")
            .setMessage(
                "SparkyTube is an independent, open-source YouTube client built by " +
                "SparkyNox — a solo developer working entirely from a phone, no PC, " +
                "shipping through GitHub Actions CI/CD.\n\n" +
                "This project exists because building things that don't officially " +
                "\"fit\" the tools you have is still worth doing. Every mod, bot, and " +
                "app in this journey has been about pushing what's possible from a " +
                "phone-only workflow.\n\n" +
                "Found a bug, or want to request a feature? Open an issue on GitHub " +
                "— that's the best way to reach me."
            )
            .setPositiveButton("Close", null)
            .setNeutralButton("GitHub Issues") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUES_URL)))
            }
            .show()
    }

    // --- Top bar: search + quality + about ---------------------------------------------

    private fun setupTopBar() {
        binding.searchBtn.setOnClickListener { showSearchDialog() }
        binding.aboutBtn.setOnClickListener { showAboutDialog() }
        binding.qualityBtn.setOnClickListener { showQualityPicker() }
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "Search YouTube"
            setSingleLine(true)
        }
        val container = wrapWithDialogMargin(this, input)

        AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(container)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    val encoded = Uri.encode(query)
                    stopNativePlayback()
                    webView.loadUrl("https://m.youtube.com/results?search_query=$encoded")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()

        input.requestFocus()
    }

    private fun showQualityPicker() {
        if (currentQualities.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Video quality")
                .setMessage("No quality options available for this video yet — try again once playback has started.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = currentQualities.map { it.label }.toTypedArray()
        val currentIndex = currentQualities.indexOfFirst { it.label == currentQualityLabel }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Video quality")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val chosen = currentQualities[which]
                currentQualityLabel = chosen.label
                playbackService?.loadAndPlay(
                    streamUrl = chosen.url,
                    title = playbackService?.getPlayer()?.mediaMetadata?.title?.toString() ?: "",
                    isHls = false
                )
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Sideloaded APK = no Play Store auto-update. Checks GitHub Releases once
     * per cold start; if a newer version is out, shows BOTH a system
     * notification AND an in-app dialog.
     */
    private fun checkForUpdatesOnce() {
        lifecycleScope.launch {
            val currentVersion = UpdateChecker.getInstalledVersionName(this@MainActivity)
            val update = UpdateChecker.checkForUpdate(currentVersion) ?: return@launch

            UpdateNotifier.notifyUpdateAvailable(this@MainActivity, update)
            showUpdateDialog(update)
        }
    }

    private fun showUpdateDialog(update: UpdateChecker.UpdateInfo) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Update available — v${update.versionTag}")
            .setMessage(update.releaseNotes.ifBlank { "A new version of SparkyTube is ready to download." })
            .setPositiveButton("Download") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun bindPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupWebView() {
        webView = WebView(this)
        binding.webViewContainer.addView(
            webView,
            0,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = userAgentString + " SparkyTube/1.0"
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        // Continuous JS <-> Kotlin channel.
        webView.addJavascriptInterface(JsBridge(this), "SparkyBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                if (adBlockEngine.shouldBlock(url)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.loadingSpinner.visibility = View.VISIBLE

                if (url != null && extractVideoId(url) == null) {
                    stopNativePlayback()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                binding.loadingSpinner.visibility = View.GONE
                injectAlwaysOnLayer(view)
            }
        }
    }

    private fun injectAlwaysOnLayer(view: WebView) {
        val cssInjectJs = """
            (function() {
                var s = document.getElementById('sparkytube-native-style');
                if (!s) {
                    s = document.createElement('style');
                    s.id = 'sparkytube-native-style';
                    document.documentElement.appendChild(s);
                }
                s.textContent = ${org.json.JSONObject.quote(cssPayload)};
            })();
        """.trimIndent()

        view.evaluateJavascript(cssInjectJs, null)
        view.evaluateJavascript(jsPayload, null)
    }

    private fun setupBottomNav() {
        highlightNav(binding.navHome)

        binding.navHome.setOnClickListener {
            highlightNav(it as LinearLayout)
            stopNativePlayback()
            webView.loadUrl(HOME_URL)
        }
        binding.navShorts.setOnClickListener {
            highlightNav(it as LinearLayout)
            stopNativePlayback()
            webView.loadUrl("https://m.youtube.com/shorts")
        }
        binding.navSubs.setOnClickListener {
            highlightNav(it as LinearLayout)
            stopNativePlayback()
            webView.loadUrl("https://m.youtube.com/feed/subscriptions")
        }
        binding.navLibrary.setOnClickListener {
            highlightNav(it as LinearLayout)
            stopNativePlayback()
            webView.loadUrl("https://m.youtube.com/feed/library")
        }
    }

    private fun highlightNav(selected: LinearLayout) {
        listOf(binding.navHome, binding.navShorts, binding.navSubs, binding.navLibrary).forEach {
            it.isSelected = (it == selected)
        }
    }

    private fun extractVideoId(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            when {
                uri.path?.startsWith("/watch") == true -> uri.getQueryParameter("v")
                uri.path?.startsWith("/shorts/") == true -> uri.path?.removePrefix("/shorts/")?.substringBefore("/")
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // --- JsBridge.VideoStateListener -------------------------------------------------
    // SECONDARY/backup trigger — URL polling (checkCurrentUrlForVideo) is primary.

    override fun onVideoState(
        streamUrl: String,
        title: String,
        isPaused: Boolean,
        adShowing: Boolean,
        currentTime: Double,
        duration: Double
    ) {
        if (adShowing) return

        val currentUrl = webView.url ?: return
        val videoId = extractVideoId(currentUrl) ?: return

        if (videoId == lastResolvedVideoId && (isNativePlaybackActive || isResolving)) return
        lastResolvedVideoId = videoId

        resolveAndPlayNative(videoId, title)
    }

    private fun resolveAndPlayNative(videoId: String, fallbackTitle: String) {
        isResolving = true
        lifecycleScope.launch {
            val resolved = StreamExtractor.resolvePlayableUrl(videoId)
            isResolving = false

            if (resolved == null) {
                // Extraction failed — stay on WebView's own player (still
                // ad-blocked at the network level), no native takeover.
                return@launch
            }

            currentQualities = resolved.availableQualities
            currentQualityLabel = resolved.availableQualities.firstOrNull()?.label ?: ""
            binding.qualityBtn.visibility = if (currentQualities.size > 1) View.VISIBLE else View.GONE

            // Mute/pause the WebView's in-page player so audio doesn't double
            // up once ExoPlayer takes over. The WebView keeps rendering
            // underneath (needed for the feed/comments/suggestions), but its
            // video surface is hidden via CSS class toggle.
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video'); if(v){v.pause(); v.muted=true;} document.body.classList.add('sparkytube-native-active');})();",
                null
            )

            playbackService?.loadAndPlay(
                streamUrl = resolved.url,
                title = resolved.title.ifBlank { fallbackTitle },
                isHls = resolved.isHls
            )
            binding.exoPlayerView.visibility = View.VISIBLE
            isNativePlaybackActive = true
        }
    }

    private fun stopNativePlayback() {
        if (!isNativePlaybackActive) return
        playbackService?.stopAndClear()
        binding.exoPlayerView.visibility = View.GONE
        binding.qualityBtn.visibility = View.GONE
        isNativePlaybackActive = false
        lastResolvedVideoId = null
        currentQualities = emptyList()
        webView.evaluateJavascript(
            "(function(){document.body.classList.remove('sparkytube-native-active'); var v=document.querySelector('video'); if(v){v.muted=false;}})();",
            null
        )
    }

    /**
     * PRIMARY trigger: called every 600ms regardless of what YouTube's page
     * JS is doing. Reads webView.url directly.
     */
    private fun checkCurrentUrlForVideo() {
        val currentUrl = webView.url ?: return
        val videoId = extractVideoId(currentUrl)

        if (videoId == null) {
            if (isNativePlaybackActive) stopNativePlayback()
            return
        }

        if (videoId == lastResolvedVideoId && (isNativePlaybackActive || isResolving)) return
        lastResolvedVideoId = videoId
        resolveAndPlayNative(videoId, fallbackTitle = "")
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(urlPollRunnable)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        binding.webViewContainer.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}

/** Small helper: wraps an EditText with dialog-appropriate margins. */
private fun wrapWithDialogMargin(context: Context, input: EditText): View {
    val padding = (16 * context.resources.displayMetrics.density).toInt()
    val frame = android.widget.FrameLayout(context)
    val params = android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
    )
    params.setMargins(padding, padding / 2, padding, 0)
    input.layoutParams = params
    frame.addView(input)
    return frame
}
