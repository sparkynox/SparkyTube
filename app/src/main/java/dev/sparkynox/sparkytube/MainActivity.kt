package dev.sparkynox.sparkytube

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
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

    // MediaController is the correct way to connect to a MediaSessionService —
    // a plain bindService() with a custom LocalBinder (the old approach) never
    // triggers Media3's automatic startForeground()+notification flow. The
    // system notification, lock-screen controls, and reliable background
    // survival all depend on a real MediaController being connected.
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

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

    /**
     * Connects a real Media3 MediaController to PlaybackService's session.
     * This is what actually makes the system notification + lock-screen
     * controls appear reliably — see PlaybackService's class doc for why
     * the previous plain bindService() approach silently never posted one.
     */
    private fun connectMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                mediaController = controller
                binding.exoPlayerView.player = controller
                controller.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            advanceToNextVideo()
                        }
                    }
                })

                // Flush anything that was requested before we got here.
                pendingPlay?.let { pending ->
                    pendingPlay = null
                    playOnController(pending.streamUrl, pending.title, pending.isHls, pending.startPositionMs)
                }
            } catch (e: Exception) {
                // Controller connection failed (service crashed on start, etc.)
                // — native playback won't be available this session. Since
                // WebView video is permanently blocked (see AdBlockEngine),
                // resolveAndPlayNative's error dialog is what the user sees
                // if this happens right before they try to play something.
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Called when ExoPlayer finishes the current video. Just unmuting the
     * WebView's own <video> (the old stopNativePlayback behavior) doesn't
     * advance to a new video — that element's playback already ended too,
     * so it just sits there or replays the same clip. This explicitly
     * clicks YouTube's own "up next" autoplay card / first related video
     * inside the WebView, which triggers a real navigation the URL poller
     * then picks up as a fresh video ID.
     */
    private fun advanceToNextVideo() {
        stopNativePlayback()
        webView.evaluateJavascript(
            "(function(){return window.__sparkyPlayNext ? window.__sparkyPlayNext() : false;})();",
            null
        )
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granting triggers the next play-state notification automatically */ }

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

        requestNotificationPermissionIfNeeded()
        connectMediaController()

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
            .setPositiveButton("Got it") { _, _ -> requestBatteryOptimizationExemption() }
            .setNeutralButton("Open GitHub Issues") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUES_URL)))
                requestBatteryOptimizationExemption()
            }
            .setCancelable(true)
            .setOnCancelListener { requestBatteryOptimizationExemption() }
            .show()
    }

    /**
     * Many OEMs (Xiaomi/MIUI, OnePlus/OxygenOS, Oppo/ColorOS, etc.) apply
     * aggressive battery optimization that kills background services even
     * when a foreground notification is showing — this is almost certainly
     * why background playback was stopping ~4/10 times. Asking the user to
     * exempt SparkyTube from battery optimization (standard system dialog,
     * not a custom permission) meaningfully improves that reliability.
     */
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEM ROMs block this intent entirely — fails silently,
            // background playback just stays subject to that OEM's own
            // battery management (nothing more we can do from here).
        }
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
                val currentTitle = mediaController?.mediaMetadata?.title?.toString() ?: ""
                val resumePosition = mediaController?.currentPosition ?: 0L
                playOnController(streamUrl = chosen.url, title = currentTitle, isHls = false, startPositionMs = resumePosition)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // If a play is requested before the MediaController has finished
    // connecting (e.g. user taps a video right as the app launches), the
    // request used to be silently dropped — this is what caused roughly
    // 4/10 plays to just do nothing. Now it's queued and flushed as soon
    // as the controller connects.
    private data class PendingPlay(val streamUrl: String, val title: String, val isHls: Boolean, val startPositionMs: Long)
    private var pendingPlay: PendingPlay? = null

    /**
     * Loads and plays a stream URL through the connected MediaController —
     * this is the standard Player API (setMediaItem/prepare/play), used
     * instead of a custom service method so playback goes through the same
     * MediaController that Media3 needs connected for notifications to work.
     */
    private fun playOnController(streamUrl: String, title: String, isHls: Boolean, startPositionMs: Long = 0L) {
        val controller = mediaController
        if (controller == null) {
            // Not connected yet — queue it. connectMediaController's listener
            // flushes this the moment the controller becomes available.
            pendingPlay = PendingPlay(streamUrl, title, isHls, startPositionMs)
            return
        }

        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)

        val itemBuilder = androidx.media3.common.MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(metadataBuilder.build())

        if (isHls) {
            itemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }

        controller.setMediaItem(itemBuilder.build())
        controller.prepare()
        if (startPositionMs > 0) controller.seekTo(startPositionMs)
        controller.play()
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

        val formattedDate = formatReleaseDate(update.releaseDateIso)
        val changelog = update.releaseNotes.ifBlank { "No changelog provided for this release." }

        val message = buildString {
            append("Version: ${update.versionTag}\n")
            append("Release Date: $formattedDate\n\n")
            append("Changelog:\n")
            append(changelog)
        }

        AlertDialog.Builder(this)
            .setTitle("A new update is available")
            .setMessage(message)
            .setPositiveButton("Download") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
            }
            .setNegativeButton("GitHub Issues") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUES_URL)))
            }
            .setCancelable(true)
            .show()
    }

    private fun formatReleaseDate(isoDate: String): String {
        if (isoDate.isBlank()) return "Unknown"
        return try {
            val parsed = java.time.Instant.parse(isoDate)
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("dd MMM yyyy, hh:mm a")
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(parsed)
        } catch (e: Exception) {
            isoDate
        }
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
        binding.loadingSpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val resolved = StreamExtractor.resolvePlayableUrl(videoId)
            isResolving = false
            binding.loadingSpinner.visibility = View.GONE

            if (resolved == null) {
                // Extraction failed. YouTube's own player is permanently
                // blocked from loading real stream data (googlevideo.com is
                // always blocked in AdBlockEngine), so there's no silent
                // fallback here — show the user what happened instead of a
                // broken black video area.
                showPlaybackErrorDialog()
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

            playOnController(
                streamUrl = resolved.url,
                title = resolved.title.ifBlank { fallbackTitle },
                isHls = resolved.isHls
            )
            binding.exoPlayerView.visibility = View.VISIBLE
            isNativePlaybackActive = true
        }
    }

    private fun showPlaybackErrorDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Couldn't play this video")
            .setMessage(
                "SparkyTube wasn't able to load a playable stream for this video. " +
                "This can happen with age-restricted, region-locked, or otherwise " +
                "unusual videos.\n\nIf this keeps happening, please open a GitHub issue."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("GitHub Issues") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUES_URL)))
            }
            .show()
    }

    private fun stopNativePlayback() {
        if (!isNativePlaybackActive) return
        pendingPlay = null
        mediaController?.stop()
        mediaController?.clearMediaItems()
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
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
