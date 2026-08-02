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
import android.view.ViewGroup
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
    private lateinit var webView: dev.sparkynox.sparkytube.webview.AlwaysVisibleWebView
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
    // Counts consecutive ExoPlayer errors for the SAME video id, reset the
    // moment a video actually starts playing successfully (see
    // onIsPlayingChanged below). Caps same-video retries so a genuinely
    // broken/dead stream doesn't retry forever -- falls through to
    // advanceToNextVideo() once the cap is hit.
    private var consecutivePlayerErrorCount = 0
    private var lastErroredVideoId: String? = null
    private var isResolving = false

    // Qualities available for the currently-playing video, populated once
    // StreamExtractor resolves them — used by the quality-picker dialog.
    private var currentQualities: List<StreamExtractor.QualityOption> = emptyList()
    private var currentQualityLabel: String = ""

    // Audio tracks available for the currently-playing video (multi-dub
    // videos only) — used by the audio-track picker dialog.
    private var currentAudioTracks: List<StreamExtractor.AudioTrackOption> = emptyList()
    private var currentAudioTrackLabel: String = ""

    // Tracks which video ID we've already fired a pre-cache request for, so
    // we don't call prefetch() repeatedly every poll tick for the same
    // upcoming video.
    private var prefetchedForVideoId: String? = null

    // PRIMARY detection mechanism: polls webView.url directly, independent of
    // whatever YouTube's page JS/DOM is doing. The DOM-based JsBridge signal
    // (injected.js -> onVideoState) is kept as a secondary/backup trigger.
    private val pollHandler = Handler(Looper.getMainLooper())
    private val urlPollRunnable = object : Runnable {
        override fun run() {
            checkCurrentUrlForVideo()
            maybePrefetchNextVideo()
            pollHandler.postDelayed(this, 600)
        }
    }

    companion object {
        const val EXTRA_OPEN_YT_SETTINGS = "open_yt_settings"
        private const val HOME_URL = "https://m.youtube.com/"
        private const val YT_SETTINGS_URL = "https://m.youtube.com/select_site"
        private const val CRUNCHYROLL_URL = "https://anikoto.cz/home"
        private const val PREFS_NAME = "sparkytube_prefs"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val GITHUB_ISSUES_URL = "https://github.com/sparkynox/SparkyTube/issues"

        // Domains the WebView is allowed to navigate its main frame to
        // while in Crunchyroll mode. Anything else attempting a top-level
        // redirect (the "https://crunchyroll.com -> https://hxjxjfg"
        // pattern Sparky described — malicious/ad redirect chains that
        // hijack the whole page, not just a sub-resource) gets blocked
        // instead of followed. Google/YouTube domains stay allowed too
        // since Crunchyroll uses Google sign-in.
        private val CRUNCHYROLL_ALLOWED_HOST_SUFFIXES = listOf(
            "anikoto.cz",
            "vrv.co",
            "anikoto.cz/home",
            "accounts.google.com",
            "google.com"
        )
    }

    // Which site the WebView is currently showing — controls ad-domain
    // matching scope and popup/redirect filtering behavior.
    private enum class BrowseMode { YOUTUBE, CRUNCHYROLL }
    private var browseMode = BrowseMode.YOUTUBE

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
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Screen-off during playback bug: Android's normal
                        // sleep timer doesn't know a video is playing
                        // (native ExoPlayer draws to its own SurfaceView,
                        // not something the OS associates with "user is
                        // active"), so without this the screen would just
                        // turn off mid-video like it would during any
                        // other idle period. FLAG_KEEP_SCREEN_ON is the
                        // same mechanism the real YouTube app uses --
                        // tied directly to actual playing state, not just
                        // "a video is loaded," so it correctly releases
                        // when paused (screen can sleep normally then) and
                        // re-applies on resume/mini-player without needing
                        // a manual WakeLock to acquire/release.
                        if (isPlaying) {
                            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            if (isMiniPlayerActive && extractVideoId(webView.url ?: "") != lastResolvedVideoId) {
                                // WebView is showing Home/Shorts/Subs/etc,
                                // not this video's watch page anymore — the
                                // related-videos DOM autoplay-next depends
                                // on isn't there, so there's nothing sane to
                                // advance to. Just close the mini-player.
                                exitMiniPlayerMode()
                                stopNativePlayback()
                            } else {
                                advanceToNextVideo()
                            }
                        } else if (playbackState == Player.STATE_READY) {
                            // Real playback actually started -- clear the
                            // error-retry counter so a later, unrelated
                            // failure on this same video still gets its
                            // own fresh set of retries.
                            consecutivePlayerErrorCount = 0
                        }
                    }

                    // This is the fix for the "notification still says
                    // Playing, but the video is frozen on the last frame
                    // and there's no audio" bug -- it happens when
                    // ExoPlayer fails to load a source (most often during
                    // an autoplay transition: the resolved stream URL is
                    // throttled or its signature expired between
                    // extraction and actual playback start). Without this
                    // listener that failure was silent: playbackState just
                    // moves to STATE_IDLE with no STATE_ENDED, so
                    // advanceToNextVideo() never fired and the session's
                    // playWhenReady (which the notification reflects)
                    // never got corrected either.
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val failedVideoId = lastResolvedVideoId
                        if (failedVideoId != null && failedVideoId == lastErroredVideoId) {
                            consecutivePlayerErrorCount++
                        } else {
                            lastErroredVideoId = failedVideoId
                            consecutivePlayerErrorCount = 1
                        }

                        if (consecutivePlayerErrorCount <= 2 && failedVideoId != null) {
                            // Likely a transient throttled/expired URL --
                            // drop the cached (bad) entry and re-resolve
                            // the SAME video fresh (new signed URLs)
                            // rather than skipping it outright.
                            dev.sparkynox.sparkytube.extractor.StreamExtractor.invalidate(failedVideoId)
                            lastResolvedVideoId = null
                            resolveAndPlayNative(failedVideoId, fallbackTitle = "")
                        } else {
                            // Either genuinely broken (retried twice, still
                            // failing) or we don't know which video it
                            // was -- move on instead of sitting frozen.
                            consecutivePlayerErrorCount = 0
                            advanceToNextVideo()
                        }
                    }
                })
                setupCustomPlayerControls()

                // Flush anything that was requested before we got here.
                pendingPlay?.let { pending ->
                    pendingPlay = null
                    playOnController(pending.streamUrl, pending.title, pending.isHls, pending.startPositionMs, pending.audioUrl)
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

    private var isPlayerFullscreen = false
    // Holds whatever view WebChromeClient.onShowCustomView hands us
    // (Crunchyroll's own fullscreen video element) and the callback to
    // invoke when exiting -- separate from the native ExoPlayer fullscreen
    // toggle above, since Crunchyroll plays through WebView's own player,
    // not the native one.
    private var fullscreenVideoView: android.view.View? = null
    private var fullscreenVideoCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    // True while the player is collapsed into the floating mini-player box.
    // Mutually exclusive with isPlayerFullscreen in practice (you can't
    // fullscreen a video that's currently minimized -- the fullscreen
    // button lives on the full player's controls, which aren't visible
    // while minimized).
    private var isMiniPlayerActive = false
    // The exoPlayerView's original FrameLayout.LayoutParams from
    // webViewContainer, captured once before the first minimize so
    // restoring it later doesn't have to guess sizes/gravity back.
    private var originalPlayerLayoutParams: android.widget.FrameLayout.LayoutParams? = null
    // Raw touch state for dragging the mini-player box around the screen.
    private var miniPlayerDragStartX = 0f
    private var miniPlayerDragStartY = 0f
    private var miniPlayerDragStartMarginEnd = 0
    private var miniPlayerDragStartMarginBottom = 0
    private var miniPlayerTouchDownX = 0f
    private var miniPlayerTouchDownY = 0f

    /**
     * Wires every button in our custom controller layout
     * (exo_player_controls.xml) manually. None of exo_prev/exo_play_pause/
     * exo_next/exo_quality_gear/exo_fullscreen_toggle are Media3's actual
     * built-in control IDs (an earlier version of this file assumed
     * exo_prev/exo_play_pause/exo_next were — they aren't, which caused an
     * "Unresolved reference" build failure), so nothing here is auto-bound
     * by PlayerView. Only the seek bar (exo_progress), position/duration
     * text, and buffering spinner use Media3's real IDs and get automatic
     * behavior.
     */
    private var videoScale = 1f
    private var videoTranslationX = 0f
    private var videoTranslationY = 0f

    /**
     * Pinch-to-zoom on the video surface. Scales/pans the PlayerView itself
     * (clipped by its parent FrameLayout) rather than anything inside
     * ExoPlayer's own rendering — this is purely a view-transform, so it
     * works the same regardless of resize mode or video aspect ratio.
     * Double-tap resets back to the normal 1x view.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlayerZoomGesture() {
        val scaleDetector = android.view.ScaleGestureDetector(
            this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    videoScale = (videoScale * detector.scaleFactor).coerceIn(1f, 4f)
                    applyVideoTransform()
                    return true
                }
            }
        )

        val gestureDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (videoScale > 1f) {
                        // Already zoomed in — double-tap resets back to normal
                        // instead of seeking, since a zoomed view makes the
                        // left/right-half seek gesture ambiguous to aim.
                        videoScale = 1f
                        videoTranslationX = 0f
                        videoTranslationY = 0f
                        applyVideoTransform()
                        return true
                    }

                    val controller = mediaController ?: return true
                    val viewWidth = binding.exoPlayerView.width
                    if (viewWidth <= 0) return true

                    val seekMs = 10_000L
                    if (e.x < viewWidth / 2f) {
                        controller.seekTo((controller.currentPosition - seekMs).coerceAtLeast(0))
                        showSeekFeedback(isForward = false)
                    } else {
                        val target = (controller.currentPosition + seekMs).coerceAtMost(controller.duration.coerceAtLeast(0))
                        controller.seekTo(target)
                        showSeekFeedback(isForward = true)
                    }
                    return true
                }

                override fun onScroll(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (videoScale <= 1f) return false
                    videoTranslationX -= distanceX
                    videoTranslationY -= distanceY
                    applyVideoTransform()
                    return true
                }
            }
        )

        binding.exoPlayerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            // Let PlayerView's own controls (play/pause/seek tap) still
            // receive the event too — we're only adding zoom/pan/double-tap-
            // seek on top, not replacing normal tap-to-show-controls behavior.
            false
        }
    }

    /**
     * Brief Toast feedback for double-tap seek, since there's no dedicated
     * "+10s" overlay animation wired up — this at least confirms the tap
     * registered and which direction it seeked.
     */
    private fun showSeekFeedback(isForward: Boolean) {
        val message = if (isForward) "+10s" else "-10s"
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun applyVideoTransform() {
        binding.exoPlayerView.scaleX = videoScale
        binding.exoPlayerView.scaleY = videoScale
        binding.exoPlayerView.translationX = videoTranslationX
        binding.exoPlayerView.translationY = videoTranslationY
    }

    private fun setupCustomPlayerControls(attempt: Int = 0) {
        val qualityGear = binding.exoPlayerView.findViewById<View>(R.id.exo_quality_gear)
        val fullscreenBtn = binding.exoPlayerView.findViewById<View>(R.id.exo_fullscreen_toggle)
        val downloadBtn = binding.exoPlayerView.findViewById<View>(R.id.exo_download_btn)
        val audioTrackBtnInPlayer = binding.exoPlayerView.findViewById<View>(R.id.exo_audio_track_btn)
        val prevBtn = binding.exoPlayerView.findViewById<View>(R.id.exo_prev)
        val playPauseBtn = binding.exoPlayerView.findViewById<android.widget.ImageButton>(R.id.exo_play_pause)
        val nextBtn = binding.exoPlayerView.findViewById<View>(R.id.exo_next)
        val minimizeBtn = binding.exoPlayerView.findViewById<View>(R.id.exo_minimize_btn)
        val suggestionsBtn = binding.exoPlayerView.findViewById<View>(R.id.exo_suggestions_btn)

        if (qualityGear == null || fullscreenBtn == null || playPauseBtn == null || downloadBtn == null) {
            // Controller layout hasn't inflated yet (PlayerView inflates it
            // lazily, the first time controls need to show) — retry rather
            // than silently leaving these buttons unwired.
            if (attempt < 5) {
                pollHandler.postDelayed({ setupCustomPlayerControls(attempt + 1) }, 200)
            }
            return
        }

        qualityGear.setOnClickListener { showQualityPicker() }
        fullscreenBtn.setOnClickListener { toggleFullscreenPlayer() }
        downloadBtn.setOnClickListener { showDownloadQualityPicker() }
        downloadBtn.visibility = if (dev.sparkynox.sparkytube.settings.SettingsPrefs.isDownloadEnabled(this)) View.VISIBLE else View.GONE
        audioTrackBtnInPlayer?.setOnClickListener { showAudioTrackPicker() }
        audioTrackBtnInPlayer?.visibility = if (currentAudioTracks.size > 1) View.VISIBLE else View.GONE
        minimizeBtn?.setOnClickListener { enterMiniPlayerMode() }
        suggestionsBtn?.setOnClickListener { showSuggestionsPanel() }

        // "Previous" and "Next" don't have a real queue behind them
        // (single-video playback, same limitation as the notification's
        // prev/next buttons) — hide rather than show buttons that do nothing.
        prevBtn?.visibility = View.GONE
        nextBtn?.visibility = View.GONE

        playPauseBtn.setOnClickListener {
            val controller = mediaController ?: return@setOnClickListener
            if (controller.isPlaying) controller.pause() else controller.play()
        }
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
        })
        playPauseBtn.setImageResource(
            if (mediaController?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )

        mediaController?.let { setPlayerTitle(it.mediaMetadata.title?.toString() ?: "") }
    }

    /**
     * Shows the same quality list the playback picker shows, but the label
     * tapped is cosmetic only — every download always resolves to the
     * actual progressive stream (audioUrl == null in QualityOption), since
     * DownloadManager just saves whatever bytes are at one URL and can't
     * mux a separate video-only + audio-only pair. Adaptive (480p+)
     * streams are video-only; downloading one of those directly produced
     * a silent MP4, which is the bug this fixes. If the video has no
     * progressive stream at all, download is refused rather than falling
     * back to an adaptive stream (that fallback was still silent-audio
     * prone). Progressive streams top out at 360p (YouTube's own limit,
     * not a SparkyTube restriction).
     */
    private fun showDownloadQualityPicker() {
        if (currentQualities.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Can't download this video")
                .setMessage("No quality options are available for this video yet — try again once playback has started.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = currentQualities.map { it.label }.toTypedArray()
        val title = mediaController?.mediaMetadata?.title?.toString() ?: "video"

        // Whatever label the user taps, we always download the actual
        // progressive stream (video+audio baked into one file by YouTube
        // itself, audioUrl == null) so downloads always have sound.
        // Deliberately NOT falling back to an adaptive (video-only) stream
        // here even if no progressive option exists for this video —
        // DownloadManager can't mux a separate audio track in, so that
        // fallback was still producing silent MP4s. No progressive stream
        // available just means download isn't offered for that video.
        val progressiveDownload = currentQualities.firstOrNull { it.audioUrl == null }

        AlertDialog.Builder(this)
            .setTitle("Download quality")
            .setItems(labels) { _, _ ->
                val target = progressiveDownload
                if (target == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Can't download this video")
                        .setMessage("This video has no audio+video combined stream available, so a downloaded file would have no sound. Not downloading it.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setItems
                }
                dev.sparkynox.sparkytube.download.VideoDownloader.downloadVideo(
                    this, target.url, target.audioUrl, title, target.label
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleFullscreenPlayer() {
        isPlayerFullscreen = !isPlayerFullscreen
        val params = binding.exoPlayerView.layoutParams

        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)

        if (isPlayerFullscreen) {
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            binding.topBar.visibility = View.GONE
            binding.bottomNav.visibility = View.GONE
        } else {
            params.height = (220 * resources.displayMetrics.density).toInt()
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            binding.topBar.visibility = View.VISIBLE
            binding.bottomNav.visibility = View.VISIBLE
        }
        binding.exoPlayerView.layoutParams = params
    }

    /**
     * Collapses the full player into the small floating box (bottom-right
     * by default, draggable) and lets the WebView feed behind it become
     * visible/scrollable again — same idea as the real YouTube app's
     * down-chevron. This REPARENTS the actual exoPlayerView (and therefore
     * its live ExoPlayer SurfaceView) from webViewContainer into
     * miniPlayerContainer rather than creating a second PlayerView, so
     * playback isn't interrupted or re-buffered by the transition — moving
     * a View to a new parent within the same Activity/Window doesn't tear
     * down its Surface.
     */
    private fun enterMiniPlayerMode() {
        if (isMiniPlayerActive || isPlayerFullscreen) return
        if (!isNativePlaybackActive) return

        isMiniPlayerActive = true

        if (originalPlayerLayoutParams == null) {
            originalPlayerLayoutParams = binding.exoPlayerView.layoutParams as android.widget.FrameLayout.LayoutParams
        }

        (binding.exoPlayerView.parent as? ViewGroup)?.removeView(binding.exoPlayerView)
        binding.miniPlayerContainer.addView(
            binding.exoPlayerView,
            0, // index 0 so it stays BEHIND the close button already in this container
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Controller UI (seek bar, quality gear, etc.) doesn't fit a
        // 160x90dp box — tapping the mini-player re-expands it instead.
        binding.exoPlayerView.useController = false
        binding.miniPlayerContainer.visibility = View.VISIBLE
        setupMiniPlayerDragging()

        binding.miniPlayerContainer.setOnClickListener { exitMiniPlayerMode() }
        binding.miniPlayerCloseBtn.setOnClickListener {
            exitMiniPlayerMode()
            stopNativePlayback()
        }
    }

    /**
     * Restores the player to its normal full-width spot above the WebView
     * feed and turns its controller back on. Called either by tapping the
     * mini-player box itself, or by navigating back to a URL that resolves
     * to the same video (handled elsewhere).
     */
    private fun exitMiniPlayerMode() {
        if (!isMiniPlayerActive) return
        isMiniPlayerActive = false

        binding.miniPlayerContainer.setOnClickListener(null)
        (binding.exoPlayerView.parent as? ViewGroup)?.removeView(binding.exoPlayerView)
        binding.miniPlayerContainer.visibility = View.GONE

        val restoredParams = originalPlayerLayoutParams
            ?: android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                (220 * resources.displayMetrics.density).toInt()
            )
        binding.webViewContainer.addView(binding.exoPlayerView, restoredParams) // appended at the end -- exact index doesn't matter here, just needs to sit above webView (index 0) to stay visible
        binding.exoPlayerView.useController = true
    }

    /**
     * Lets the user drag the mini-player box anywhere on screen. Simple
     * ACTION_MOVE-based approach updating layout margins directly — no
     * drag library needed for a single free-floating view. A tap (touch
     * up with negligible movement) still falls through to the container's
     * OnClickListener set in enterMiniPlayerMode, since ACTION_UP returns
     * false here for a non-drag.
     */
    private fun setupMiniPlayerDragging() {
        binding.miniPlayerContainer.setOnTouchListener { view, event ->
            val params = view.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    miniPlayerTouchDownX = event.rawX
                    miniPlayerTouchDownY = event.rawY
                    miniPlayerDragStartX = event.rawX
                    miniPlayerDragStartY = event.rawY
                    miniPlayerDragStartMarginEnd = params.rightMargin
                    miniPlayerDragStartMarginBottom = params.bottomMargin
                    false // let the click listener still see ACTION_DOWN
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - miniPlayerDragStartX
                    val dy = event.rawY - miniPlayerDragStartY
                    // rightMargin grows as the box moves LEFT (dx negative),
                    // bottomMargin grows as the box moves UP (dy negative) --
                    // both anchors are end/bottom, so signs are inverted.
                    params.rightMargin = (miniPlayerDragStartMarginEnd - dx).toInt().coerceIn(0, screenWidthPx() - view.width)
                    params.bottomMargin = (miniPlayerDragStartMarginBottom - dy).toInt().coerceIn(0, screenHeightPx() - view.height)
                    view.layoutParams = params
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val totalMove = kotlin.math.hypot(
                        (event.rawX - miniPlayerTouchDownX).toDouble(),
                        (event.rawY - miniPlayerTouchDownY).toDouble()
                    )
                    // Treat anything under ~8dp of total movement as a tap,
                    // not a drag, so the re-expand click still works.
                    val tapThresholdPx = 8 * resources.displayMetrics.density
                    if (totalMove < tapThresholdPx) {
                        view.performClick()
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun screenWidthPx() = resources.displayMetrics.widthPixels
    private fun screenHeightPx() = resources.displayMetrics.heightPixels

    // --- Suggestions/up-next panel ------------------------------------------------------

    private var suggestionsAdapter: dev.sparkynox.sparkytube.suggestions.SuggestionsAdapter? = null
    private var suggestionsFetchJob: kotlinx.coroutines.Job? = null

    /**
     * Native suggestions/related-videos panel — a Kotlin-built list, not
     * the WebView's own related-videos UI, so it works the same whether
     * playing full-screen, minimized, or from a shared-link download.
     * Fetches on demand (see StreamExtractor.fetchRelatedVideos) rather
     * than as part of the normal playback resolve, since that extra
     * getRelatedItems() call is exactly the network/parse overhead the
     * speed optimization earlier deliberately skips for every video —
     * this only pays that cost when the user actually opens the panel.
     */
    private fun showSuggestionsPanel() {
        val videoId = lastResolvedVideoId
        if (videoId == null) {
            android.widget.Toast.makeText(this, "No video playing yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val panelRoot = binding.suggestionsPanelInclude.root
        val recyclerView = panelRoot.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.suggestionsPanelList)
        val loadingView = panelRoot.findViewById<View>(R.id.suggestionsPanelLoading)
        val emptyView = panelRoot.findViewById<View>(R.id.suggestionsPanelEmpty)
        val closeBtn = panelRoot.findViewById<View>(R.id.suggestionsPanelCloseBtn)

        if (suggestionsAdapter == null) {
            suggestionsAdapter = dev.sparkynox.sparkytube.suggestions.SuggestionsAdapter { tappedVideoId ->
                hideSuggestionsPanel()
                lastResolvedVideoId = null
                resolveAndPlayNative(tappedVideoId, fallbackTitle = "")
            }
            recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            recyclerView.adapter = suggestionsAdapter
        }

        closeBtn.setOnClickListener { hideSuggestionsPanel() }
        panelRoot.visibility = View.VISIBLE
        loadingView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.GONE

        suggestionsFetchJob?.cancel()
        suggestionsFetchJob = lifecycleScope.launch {
            val related = dev.sparkynox.sparkytube.extractor.StreamExtractor.fetchRelatedVideos(videoId)
            loadingView.visibility = View.GONE
            if (related.isEmpty()) {
                emptyView.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                suggestionsAdapter?.submitList(related)
            }
        }
    }

    private fun hideSuggestionsPanel() {
        suggestionsFetchJob?.cancel()
        binding.suggestionsPanelInclude.root.visibility = View.GONE
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
    private fun advanceToNextVideo(attempt: Int = 0) {
        if (attempt == 0) stopNativePlayback()
        webView.evaluateJavascript(
            "(function(){return window.__sparkyPlayNext ? window.__sparkyPlayNext() : false;})();"
        ) { rawResult ->
            val succeeded = rawResult?.trim() == "true"
            if (!succeeded && attempt < 4) {
                // The related-videos list may not have been ready yet (e.g.
                // right after coming back from a throttled background JS
                // context) — back off and try again rather than silently
                // giving up, which is what caused the "same video loops
                // forever" bug in the background/lock-screen case.
                pollHandler.postDelayed({ advanceToNextVideo(attempt + 1) }, 800L * (attempt + 1))
            }
        }
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
        setupConnectionErrorRetry()
        setupBottomNav()
        setupTopBar()
        setupPlayerZoomGesture()

        requestNotificationPermissionIfNeeded()
        connectMediaController()

        checkForUpdatesOnce()
        showFirstLaunchDialogIfNeeded()
        showContactReminderDialog()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenVideoView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                if (binding.suggestionsPanelInclude.root.visibility == View.VISIBLE) {
                    hideSuggestionsPanel()
                    return
                }
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

        if (intent?.getBooleanExtra(EXTRA_OPEN_YT_SETTINGS, false) == true) {
            webView.loadUrl(YT_SETTINGS_URL)
        } else {
            webView.loadUrl(HOME_URL)
        }
        pollHandler.post(urlPollRunnable)

        handleSendIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // MainActivity is singleTop, so tapping "YT Settings" while
        // MainActivity is already alive in the background reuses this
        // same instance instead of running onCreate again — without this,
        // that path would silently do nothing.
        if (intent.getBooleanExtra(EXTRA_OPEN_YT_SETTINGS, false)) {
            browseMode = BrowseMode.YOUTUBE
            binding.bottomNav.visibility = View.VISIBLE
            webView.loadUrl(YT_SETTINGS_URL)
        }
        handleSendIntent(intent)
    }

    /**
     * Handles being launched from the Android share sheet — someone
     * shared a YouTube link from another app (official YouTube app,
     * browser, WhatsApp, etc.) and picked SparkyTube. Extracts the video
     * ID and downloads it directly using the exact same
     * progressive-360p-guaranteed-audio logic the in-app download button
     * uses (see showDownloadQualityPicker) — no UI navigation, no need to
     * actually open/play the video first.
     */
    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Shared text is often more than just the bare URL (e.g. "Check
        // this out: https://youtu.be/xyz via YouTube") -- pull out the
        // first http(s) URL rather than trying to Uri.parse the whole
        // string, which would mis-extract on anything but a lone link.
        val urlMatch = Regex("""https?://\S+""").find(sharedText)
        val extractedUrl = urlMatch?.value ?: sharedText
        val videoId = extractVideoId(extractedUrl)
        if (videoId == null) {
            android.widget.Toast.makeText(this, "That doesn't look like a YouTube video link", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        downloadVideoById(videoId)
    }

    /**
     * Resolves and downloads a video by ID directly, independent of
     * whatever is currently playing/loaded in the WebView or player —
     * used by the share-sheet download flow, where the user never opened
     * the video in SparkyTube's own UI at all.
     */
    private fun downloadVideoById(videoId: String) {
        android.widget.Toast.makeText(this, "Resolving video…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val resolved = dev.sparkynox.sparkytube.extractor.StreamExtractor.resolvePlayableUrl(videoId)
            if (resolved == null) {
                android.widget.Toast.makeText(this@MainActivity, "Couldn't resolve this video", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Same rule as the in-app download button: only ever download
            // a progressive (audio-baked-in) stream. No fallback to an
            // adaptive video-only stream, since DownloadManager can't mux
            // in a separate audio track and that fallback was the original
            // "downloaded video has no sound" bug.
            val progressiveOption = resolved.availableQualities.firstOrNull { it.audioUrl == null }
            if (progressiveOption == null) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "This video has no audio+video combined stream available, so a download would have no sound.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            dev.sparkynox.sparkytube.download.VideoDownloader.downloadVideo(
                this@MainActivity, progressiveOption.url, progressiveOption.audioUrl,
                resolved.title.ifBlank { videoId }, progressiveOption.label
            )
        }
    }

    // --- First launch welcome + About dialog ------------------------------------------

    private fun showFirstLaunchDialogIfNeeded() {
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) return
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()

        if (dev.sparkynox.sparkytube.settings.SettingsPrefs.arePopupsBlocked(this)) {
            requestBatteryOptimizationExemption()
            return
        }

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
     * Shown on every app open (unlike showFirstLaunchDialogIfNeeded, which
     * is one-time) — a short reminder of where to actually reach the dev,
     * since GitHub Issues is the right channel for bugs/feature requests
     * but people keep DMing instead. Skipped entirely when the user has
     * turned on "Block all popups" in Settings.
     */
    private fun showContactReminderDialog() {
        if (dev.sparkynox.sparkytube.settings.SettingsPrefs.arePopupsBlocked(this)) return

        AlertDialog.Builder(this)
            .setTitle("Before you message me")
            .setMessage(
                "Coding and fixing bugs takes time. If you have a problem, " +
                "please open a GitHub issue instead of messaging on Instagram or Telegram.\n\n" +
                "Telegram: @SparkyNox (mostly inactive)\n" +
                "Instagram: @sparkynox07"
            )
            .setPositiveButton("Open GitHub Issues") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUES_URL)))
            }
            .setNegativeButton("OK", null)
            .setCancelable(true)
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

    /**
     * "Support the Developer" — a fully opt-in rewarded ad. Never shown
     * automatically, never on a timer, never tied to any other feature.
     * The user explicitly asks to see this, once, from the overflow menu.
     */
    private fun showSupportDialog() {
        AlertDialog.Builder(this)
            .setTitle("Support the Developer")
            .setMessage(
                "SparkyTube is free and has no forced ads. If you'd like to " +
                "support development, you can optionally watch a short ad — " +
                "completely up to you, and it never affects anything else in the app."
            )
            .setPositiveButton("Watch an ad") { _, _ -> triggerSupportAd() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun triggerSupportAd() {
        dev.sparkynox.sparkytube.support.SupportAdManager.init(
            this,
            onReady = {
                dev.sparkynox.sparkytube.support.SupportAdManager.showSupportAd(
                    this,
                    object : dev.sparkynox.sparkytube.support.SupportAdManager.ResultListener {
                        override fun onAdShown() { /* no-op, ad SDK handles its own UI */ }

                        override fun onAdCompleted() {
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "Thanks for the support! 🧡",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onAdUnavailable(reason: String) {
                            runOnUiThread {
                                android.widget.Toast.makeText(this@MainActivity, reason, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            },
            onFailed = { reason ->
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, reason, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // --- Top bar: search + quality + overflow (about/support) ---------------------------

    private fun setupTopBar() {
        binding.searchBtn.setOnClickListener { showSearchDialog() }
        binding.qualityBtn.setOnClickListener { showQualityPicker() }
        binding.audioTrackBtn.setOnClickListener { showAudioTrackPicker() }
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, dev.sparkynox.sparkytube.settings.SettingsActivity::class.java))
        }
        binding.overflowMenuBtn.setOnClickListener { showOverflowMenu(it) }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "About SparkyTube")
        popup.menu.add(0, 2, 1, "Support the Developer")
        // Anime streaming has a master switch in Settings now — if it's
        // off, don't even show the toggle here (and if the user was
        // somehow still sitting on the Crunchyroll page from before it
        // was turned off, bounce them back to YouTube).
        if (dev.sparkynox.sparkytube.settings.SettingsPrefs.isAnimeStreamingEnabled(this)) {
            val siteToggleLabel = if (browseMode == BrowseMode.CRUNCHYROLL) "Back to YouTube" else "Anime Streaming"
            popup.menu.add(0, 3, 2, siteToggleLabel)
        } else if (browseMode == BrowseMode.CRUNCHYROLL) {
            toggleBrowseMode()
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showAboutDialog()
                2 -> showSupportDialog()
                3 -> toggleBrowseMode()
            }
            true
        }
        popup.show()
    }

    /**
     * Switches the WebView between YouTube and Crunchyroll. Same screen,
     * same WebView instance/ad-blocking infrastructure — just a different
     * site loaded, with browseMode gating which behaviors apply (native
     * ExoPlayer takeover and video-ID URL polling only make sense in
     * YouTube mode; the Crunchyroll redirect allowlist only applies in
     * Crunchyroll mode).
     */
    private fun toggleBrowseMode() {
        if (browseMode == BrowseMode.CRUNCHYROLL) {
            browseMode = BrowseMode.YOUTUBE
            stopNativePlayback()
            webView.loadUrl(HOME_URL)
            binding.bottomNav.visibility = View.VISIBLE
        } else {
            stopNativePlayback()
            browseMode = BrowseMode.CRUNCHYROLL
            webView.loadUrl(CRUNCHYROLL_URL)
            // Home/Shorts/Subs/Library only make sense for YouTube — hide
            // it entirely while on Crunchyroll rather than leave dead
            // buttons that do nothing (or worse, silently switch back to
            // YouTube) sitting on screen.
            binding.bottomNav.visibility = View.GONE
        }
        highlightNav(binding.navHome)
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
                playOnController(streamUrl = chosen.url, title = currentTitle, isHls = false, startPositionMs = resumePosition, audioUrl = chosen.audioUrl)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Lets the user manually pick a different audio-language dub. Only
     * offered when the video actually has more than one distinct audio
     * track (see StreamExtractor's availableAudioTracks — populated only
     * for multi-dub videos). Keeps the currently-selected video quality
     * and playback position, just swaps which audio track gets muxed in.
     */
    private fun showAudioTrackPicker() {
        if (currentAudioTracks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Audio track")
                .setMessage("This video only has one audio track available.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = currentAudioTracks.map { it.label }.toTypedArray()
        val currentIndex = currentAudioTracks.indexOfFirst { it.label == currentAudioTrackLabel }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Audio track")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val chosen = currentAudioTracks[which]
                currentAudioTrackLabel = chosen.label
                val currentQuality = currentQualities.firstOrNull { it.label == currentQualityLabel }
                val currentTitle = mediaController?.mediaMetadata?.title?.toString() ?: ""
                val resumePosition = mediaController?.currentPosition ?: 0L
                if (currentQuality != null) {
                    playOnController(
                        streamUrl = currentQuality.url,
                        title = currentTitle,
                        isHls = false,
                        startPositionMs = resumePosition,
                        audioUrl = chosen.url
                    )
                }
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
    private data class PendingPlay(val streamUrl: String, val title: String, val isHls: Boolean, val startPositionMs: Long, val audioUrl: String?)
    private var pendingPlay: PendingPlay? = null

    /**
     * Loads and plays a stream URL through the connected MediaController —
     * this is the standard Player API (setMediaItem/prepare/play), used
     * instead of a custom service method so playback goes through the same
     * MediaController that Media3 needs connected for notifications to work.
     *
     * When audioUrl is non-null, streamUrl is a video-only adaptive stream
     * (YouTube only offers 480p+ this way — progressive/combined streams
     * cap at 360p). The audio URL is passed through as a custom MediaItem
     * extra; PlaybackService's MediaSource.Factory reads it back out and
     * builds a MergingMediaSource so ExoPlayer plays both tracks together.
     * MediaController itself can't carry a MediaSource directly — only a
     * MediaItem — so this extra-bundle is the bridge between the two.
     */
    private fun playOnController(streamUrl: String, title: String, isHls: Boolean, startPositionMs: Long = 0L, audioUrl: String? = null) {
        val controller = mediaController
        if (controller == null) {
            // Not connected yet — queue it. connectMediaController's listener
            // flushes this the moment the controller becomes available.
            pendingPlay = PendingPlay(streamUrl, title, isHls, startPositionMs, audioUrl)
            return
        }

        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)

        val extras = android.os.Bundle()
        if (audioUrl != null) extras.putString(dev.sparkynox.sparkytube.player.PlaybackService.EXTRA_AUDIO_URL, audioUrl)

        val itemBuilder = androidx.media3.common.MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(metadataBuilder.build())
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setExtras(extras)
                    .build()
            )

        if (isHls) {
            itemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }

        controller.setMediaItem(itemBuilder.build())
        controller.prepare()
        if (startPositionMs > 0) controller.seekTo(startPositionMs)
        controller.play()

        setPlayerTitle(title)
    }

    /**
     * PlayerView lazily inflates its controller_layout_id the first time
     * controls actually need to show — calling findViewById too early (e.g.
     * the moment playback starts, before the user has ever tapped to reveal
     * controls) can return null and silently drop the title. Retries a
     * short handful of times instead of giving up on the first miss, which
     * is what caused the title to sometimes just never appear.
     */
    private fun setPlayerTitle(title: String, attempt: Int = 0) {
        val titleText = binding.exoPlayerView.findViewById<android.widget.TextView>(R.id.exo_title_text)
        if (titleText != null) {
            titleText.text = title
        } else if (attempt < 5) {
            pollHandler.postDelayed({ setPlayerTitle(title, attempt + 1) }, 200)
        }
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
        if (!dev.sparkynox.sparkytube.settings.SettingsPrefs.isUpdaterEnabled(this)) return

        lifecycleScope.launch {
            val currentVersion = UpdateChecker.getInstalledVersionName(this@MainActivity)
            val update = UpdateChecker.checkForUpdate(currentVersion) ?: return@launch

            if (dev.sparkynox.sparkytube.settings.SettingsPrefs.arePopupsBlocked(this@MainActivity)) return@launch

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

        // AlertDialog.setMessage() clips at a fixed height and does NOT
        // scroll on its own for long content — that's why a long changelog
        // got cut off. A ScrollView wrapping the TextView, passed via
        // setView(), actually scrolls.
        val padding = (20 * resources.displayMetrics.density).toInt()
        val textView = android.widget.TextView(this).apply {
            text = message
            setPadding(padding, padding, padding, padding)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
        }

        AlertDialog.Builder(this)
            .setTitle("A new update is available")
            .setView(scrollView)
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

    /**
     * Shows the custom no-internet/connection-error screen (mascot + title
     * + reason + retry), replacing WebView's own default browser-style
     * error page. The reason text passed in is WebView's own error
     * description (e.g. "net::ERR_INTERNET_DISCONNECTED") — shown as-is
     * since it's genuinely useful for diagnosing which specific network
     * problem occurred, not because it's user-friendly wording.
     */
    private fun showConnectionErrorScreen(reason: String?) {
        binding.connectionErrorInclude.root.visibility = View.VISIBLE
        binding.loadingSpinner.visibility = View.GONE
        val reasonView = binding.connectionErrorInclude.root
            .findViewById<android.widget.TextView>(R.id.connectionErrorReason)
        reasonView.text = if (!reason.isNullOrBlank()) {
            "Check your Wi-Fi or mobile data and try again.\n($reason)"
        } else {
            "Check your Wi-Fi or mobile data and try again."
        }
    }

    private fun hideConnectionErrorScreen() {
        binding.connectionErrorInclude.root.visibility = View.GONE
    }

    private fun setupConnectionErrorRetry() {
        val retryBtn = binding.connectionErrorInclude.root
            .findViewById<View>(R.id.connectionErrorRetryBtn)
        retryBtn.setOnClickListener {
            hideConnectionErrorScreen()
            webView.reload()
        }
    }

    private fun setupWebView() {
        webView = dev.sparkynox.sparkytube.webview.AlwaysVisibleWebView(this)
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
            // Popup/new-window ads (window.open() calls from ad scripts)
            // are the main "popup ad" vector on ad-heavy sites like
            // Crunchyroll's free tier. Leaving multipleWindows off (the
            // default) already stops most of these, but WebChromeClient
            // below explicitly refuses any that still request one.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        // Continuous JS <-> Kotlin channel.
        webView.addJavascriptInterface(JsBridge(this), "SparkyBridge")

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // Refuse every popup/new-window request outright — this is
                // exactly the "popup ad" pattern (an ad script calling
                // window.open()) rather than a real link the user tapped,
                // since real navigation goes through the current WebView
                // instead of asking for a new window.
                return false
            }

            // WebView has no native fullscreen of its own — when a page's
            // own JS calls video.requestFullscreen() (Crunchyroll's
            // player), the WebView asks the app (via these two callbacks)
            // to supply a view to render into and to restore things when
            // fullscreen is exited. Without this pair, tapping fullscreen
            // on Crunchyroll's player did nothing because there was
            // nowhere for WebView to put the fullscreen content.
            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                if (fullscreenVideoView != null) {
                    callback.onCustomViewHidden()
                    return
                }

                fullscreenVideoView = view
                fullscreenVideoCallback = callback

                binding.fullscreenVideoContainer.addView(
                    view,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                binding.fullscreenVideoContainer.visibility = View.VISIBLE
                binding.topBar.visibility = View.GONE
                binding.bottomNav.visibility = View.GONE

                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                insetsController.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

            override fun onHideCustomView() {
                val view = fullscreenVideoView ?: return
                binding.fullscreenVideoContainer.removeView(view)
                binding.fullscreenVideoContainer.visibility = View.GONE
                binding.topBar.visibility = View.VISIBLE
                binding.bottomNav.visibility = View.VISIBLE

                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

                fullscreenVideoCallback?.onCustomViewHidden()
                fullscreenVideoView = null
                fullscreenVideoCallback = null
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                if (dev.sparkynox.sparkytube.settings.SettingsPrefs.isAdBlockEnabled(this@MainActivity) &&
                    adBlockEngine.shouldBlock(url)
                ) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (browseMode != BrowseMode.CRUNCHYROLL) return false

                val host = request.url.host ?: return false
                val isAllowed = CRUNCHYROLL_ALLOWED_HOST_SUFFIXES.any {
                    host == it || host.endsWith(".$it")
                }
                // Block the whole-page redirect (ad/malware chain landing
                // the main frame somewhere outside Crunchyroll) instead of
                // following it — exactly the
                // "crunchyroll.com -> hxjxjfg.example -> blocked,
                // crunchyroll.com/watch -> passed" behavior asked for.
                return !isAllowed
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)

                // Only care about the main page failing to load, not a
                // sub-resource (an ad domain we already block, a tracker
                // pixel timing out, etc.) — those fail constantly and
                // don't mean the user has no internet.
                if (!request.isForMainFrame) return

                // Only genuine connectivity-type failures show the custom
                // screen. Other WebView error codes (bad URL, unsupported
                // scheme, etc.) aren't "no internet" and showing this
                // screen for those would be misleading.
                val isConnectivityError = when (error.errorCode) {
                    android.webkit.WebViewClient.ERROR_HOST_LOOKUP,
                    android.webkit.WebViewClient.ERROR_CONNECT,
                    android.webkit.WebViewClient.ERROR_TIMEOUT,
                    android.webkit.WebViewClient.ERROR_IO,
                    android.webkit.WebViewClient.ERROR_UNKNOWN -> true
                    else -> false
                }
                if (!isConnectivityError) return

                showConnectionErrorScreen(error.description?.toString())
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.loadingSpinner.visibility = View.VISIBLE
                hideConnectionErrorScreen()

                if (browseMode == BrowseMode.YOUTUBE && url != null && extractVideoId(url) == null && !isMiniPlayerActive) {
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

        // User-supplied CSS from Settings, injected as its own <style> tag
        // AFTER SparkyTube's own so it wins on any selector clash — lets
        // someone override or add to the built-in styling without needing
        // a whole app rebuild for a cosmetic tweak.
        if (dev.sparkynox.sparkytube.settings.SettingsPrefs.isCustomCssEnabled(this)) {
            val customCss = dev.sparkynox.sparkytube.settings.SettingsPrefs.getCustomCss(this)
            if (customCss.isNotBlank()) {
                val customCssInjectJs = """
                    (function() {
                        var s = document.getElementById('sparkytube-custom-style');
                        if (!s) {
                            s = document.createElement('style');
                            s.id = 'sparkytube-custom-style';
                            document.documentElement.appendChild(s);
                        }
                        s.textContent = ${org.json.JSONObject.quote(customCss)};
                    })();
                """.trimIndent()
                view.evaluateJavascript(customCssInjectJs, null)
            }
        }
    }

    private fun setupBottomNav() {
        highlightNav(binding.navHome)

        binding.navHome.setOnClickListener {
            navigateToSection(it as LinearLayout, HOME_URL)
        }
        binding.navShorts.setOnClickListener {
            navigateToSection(it as LinearLayout, "https://m.youtube.com/shorts")
        }
        binding.navSubs.setOnClickListener {
            navigateToSection(it as LinearLayout, "https://m.youtube.com/feed/subscriptions")
        }
        binding.navLibrary.setOnClickListener {
            navigateToSection(it as LinearLayout, "https://m.youtube.com/feed/library")
        }
    }

    /**
     * Only actually reloads the WebView when the tap is a real section
     * change (different tab, or coming back from Crunchyroll/a video
     * watch page). Tapping the tab you're already sitting on used to call
     * webView.loadUrl() unconditionally — a full network refetch and feed
     * re-render every time, losing scroll position, for a tap that should
     * just be a no-op (matches the real YouTube app: tapping the current
     * tab again doesn't reload it). Comparing against webView.url (not
     * just "which nav button was highlighted") also covers the case where
     * the user left the section via an in-page link/back-press without
     * touching the nav bar at all.
     */
    private fun navigateToSection(navItem: LinearLayout, targetUrl: String) {
        highlightNav(navItem)

        val alreadyThere = browseMode == BrowseMode.YOUTUBE &&
            webView.url?.trimEnd('/') == targetUrl.trimEnd('/')
        if (alreadyThere) return

        // While mini-player is active, playback should survive the tab
        // switch (that's the whole point of collapsing it) — only the
        // X button on the mini-player itself should stop it.
        if (!isMiniPlayerActive) {
            stopNativePlayback()
        }
        browseMode = BrowseMode.YOUTUBE
        webView.loadUrl(targetUrl)
    }

    private fun highlightNav(selected: LinearLayout) {
        listOf(binding.navHome, binding.navShorts, binding.navSubs, binding.navLibrary).forEach {
            it.isSelected = (it == selected)
        }
    }

    /**
     * Only matches regular /watch videos — Shorts (/shorts/...) are
     * deliberately excluded here. ExoPlayer's overlay is built around a
     * single video at a time with a fixed-height player area; Shorts'
     * rapid vertical-swipe-to-next-video UX doesn't fit that model, so
     * Shorts are left to play on YouTube's own in-page player instead of
     * being hijacked into native playback.
     */
    private fun extractVideoId(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            when {
                uri.path?.startsWith("/watch") == true -> uri.getQueryParameter("v")
                // youtu.be/VIDEO_ID shortlinks -- what the official
                // YouTube app's own Share sheet usually hands out, so the
                // share-to-SparkyTube download flow needs this too, not
                // just the m.youtube.com/watch?v= form the in-app WebView
                // always uses.
                uri.host?.endsWith("youtu.be") == true ->
                    uri.pathSegments?.firstOrNull()?.takeIf { it.length == 11 }
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
        // A tap on any video (from the feed, search, etc.) while the
        // mini-player is showing a DIFFERENT video should behave like the
        // real YouTube app: pull the player back to full view for the new
        // video rather than leaving the old mini-player box sitting there
        // while a second resolve happens underneath it.
        if (isMiniPlayerActive && videoId != lastResolvedVideoId) {
            exitMiniPlayerMode()
        }

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

            if (resolved.isLive) {
                // Live broadcasts skip native ExoPlayer entirely and stay on
                // YouTube's own WebView player, unmuted, with sound — the
                // muted-background-WebView + visible-ExoPlayer swap that
                // regular videos use doesn't fit a live stream (no fixed
                // duration/quality list to hand ExoPlayer, and reconnecting
                // ExoPlayer to a moving live edge on every resolve would be
                // far more fragile than just letting YouTube's own live
                // player, which is built for and tested against exactly
                // this, keep handling it).
                stopNativePlayback()
                binding.qualityBtn.visibility = View.GONE
                binding.audioTrackBtn.visibility = View.GONE
                currentQualities = emptyList()
                currentAudioTracks = emptyList()
                return@launch
            }

            currentQualities = resolved.availableQualities
            currentQualityLabel = resolved.defaultQualityLabel ?: resolved.availableQualities.firstOrNull()?.label ?: ""
            binding.qualityBtn.visibility = if (currentQualities.size > 1) View.VISIBLE else View.GONE

            currentAudioTracks = resolved.availableAudioTracks
            currentAudioTrackLabel = currentAudioTracks.firstOrNull { it.isOriginal }?.label
                ?: currentAudioTracks.firstOrNull()?.label ?: ""
            binding.audioTrackBtn.visibility = if (currentAudioTracks.size > 1) View.VISIBLE else View.GONE

            // Let the WebView's real HTML5 player actually keep playing —
            // muted and invisible (CSS handles hiding it via the
            // sparkytube-native-active class) — instead of pausing it.
            // This is what makes YouTube's own watch-history, view count,
            // and recommendation algorithm genuinely register the video as
            // watched: it's real playback through the user's actual
            // logged-in session, not a fabricated signal. ExoPlayer is the
            // only thing the user sees/hears; the background player is
            // silent and never shown.
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video'); if(v){v.muted=true; if(v.paused){v.play().catch(function(){});}} document.body.classList.add('sparkytube-native-active');})();",
                null
            )

            playOnController(
                streamUrl = resolved.url,
                title = resolved.title.ifBlank { fallbackTitle },
                isHls = resolved.isHls,
                audioUrl = resolved.defaultAudioUrl
            )
            binding.exoPlayerView.visibility = View.VISIBLE
            isNativePlaybackActive = true
            prefetchedForVideoId = null
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
        prefetchedForVideoId = null
        mediaController?.stop()
        mediaController?.clearMediaItems()
        binding.exoPlayerView.visibility = View.GONE
        binding.qualityBtn.visibility = View.GONE
        binding.audioTrackBtn.visibility = View.GONE
        isNativePlaybackActive = false
        lastResolvedVideoId = null
        currentQualities = emptyList()
        currentAudioTracks = emptyList()
        webView.evaluateJavascript(
            "(function(){document.body.classList.remove('sparkytube-native-active'); var v=document.querySelector('video'); if(v){v.muted=false;}})();",
            null
        )
    }

    /**
     * PRIMARY trigger: called every 600ms regardless of what YouTube's page
     * JS is doing. Reads webView.url directly.
     */
    /**
     * Pre-caching: once native ExoPlayer is within the last ~20s of the
     * current video, predict which video autoplay would jump to next and
     * start resolving it in the background. This is what eliminates the
     * 1-3s "current video keeps playing while the next one loads" stall —
     * by the time the video actually ends, StreamExtractor already has the
     * next video's stream URL cached, so playOnController fires instantly.
     *
     * Deliberately reads mediaController's own position, not the WebView
     * <video>'s currentTime/duration — that element is paused/muted once
     * native playback takes over, so its time stops progressing and would
     * never trigger this.
     */
    private fun maybePrefetchNextVideo() {
        if (!isNativePlaybackActive) return
        val controller = mediaController ?: return
        val duration = controller.duration
        val position = controller.currentPosition
        if (duration <= 0) return

        val remaining = duration - position
        if (remaining > 20_000) return // not close enough to the end yet

        webView.evaluateJavascript(
            "(function(){return window.__sparkyPredictNextVideoId ? window.__sparkyPredictNextVideoId() : null;})();"
        ) { rawResult ->
            // evaluateJavascript returns a JSON-quoted string like "\"abc123\"" or "null"
            val predictedId = rawResult?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
            if (predictedId != null && predictedId != prefetchedForVideoId) {
                prefetchedForVideoId = predictedId
                StreamExtractor.prefetch(predictedId, lifecycleScope)
            }
        }
    }

    private fun checkCurrentUrlForVideo() {
        if (browseMode != BrowseMode.YOUTUBE) return
        val currentUrl = webView.url ?: return
        val videoId = extractVideoId(currentUrl)

        if (videoId == null) {
            // Mini-player deliberately keeps the ExoPlayer instance alive
            // and playing even though the WebView has navigated away from
            // /watch (that's the whole point) — this poller runs every
            // 600ms regardless of tab, and without this check it would
            // call stopNativePlayback() the instant the URL stopped
            // looking like a watch page, killing mini-player playback
            // right after the user just collapsed it.
            if (isNativePlaybackActive && !isMiniPlayerActive) stopNativePlayback()
            return
        }

        if (videoId == lastResolvedVideoId && (isNativePlaybackActive || isResolving)) return
        lastResolvedVideoId = videoId
        resolveAndPlayNative(videoId, fallbackTitle = "")
    }

    override fun onPause() {
        super.onPause()
        // CRITICAL: do NOT let the WebView throttle/pause its JS timers here.
        // Android's default WebView behavior pauses JS execution when the
        // Activity backgrounds (like a browser tab losing focus) — that's
        // exactly what broke autoplay in the background/lock-screen: the
        // page's __sparkyPredictNextVideoId()/__sparkyPlayNext() functions
        // would return stale or empty results because the JS context was
        // frozen, so advanceToNextVideo() had nothing to act on and the
        // current video just looped. As long as native audio is still
        // playing, we deliberately keep resumeTimers() active.
        if (!isNativePlaybackActive) {
            webView.onPause()
            webView.pauseTimers()
        } else {
            // Safety net: some OEM audio-focus implementations can end up
            // pausing the VISIBLE ExoPlayer instead of the muted background
            // WebView video when both hold an audio track at once (even
            // though the background one is silenced) — this was showing up
            // as "ExoPlayer pauses itself when the app is minimized". If
            // playback was genuinely still going right before backgrounding,
            // force it to keep playing rather than trusting whatever
            // Android's focus system decided.
            val controller = mediaController
            if (controller != null && controller.playbackState != Player.STATE_ENDED) {
                controller.play()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Reinforce AlwaysVisibleWebView's override at the window-focus
        // layer too — losing window focus (screen lock, notification
        // shade pulled down, etc.) is a separate signal from Activity
        // onPause and can independently nudge Chromium's internal
        // visibility state if not also caught here.
        if (!hasFocus && ::webView.isInitialized) {
            webView.forceVisible()
        }
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
