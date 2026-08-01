package dev.sparkynox.sparkytube.player

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service backing ExoPlayer. Gives SparkyTube:
 *  - Real background playback (screen off / app backgrounded)
 *  - System notification with title, play/pause, next/previous controls
 *  - Lock-screen media controls
 *
 * IMPORTANT: this service does NOT expose custom playback methods
 * (loadAndPlay/pause/etc) anymore. All playback control goes through a
 * standard Media3 MediaController connected from MainActivity via
 * SessionToken — that's the piece that was missing before and is the
 * actual reason notifications weren't appearing reliably. A plain
 * bindService() + custom LocalBinder never triggers Media3's internal
 * MediaNotificationManager; only a real MediaController connection does.
 * See MainActivity.connectMediaController().
 *
 * Video renders into the PlayerView MainActivity attaches by setting
 * exoPlayerView.player = mediaController — actual pixels come from
 * ExoPlayer, NOT the WebView's <video> element.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /** Bundle key MainActivity uses to smuggle an adaptive-stream's
         * audio-only URL through a MediaItem, since MediaController can only
         * carry a MediaItem (not a raw MediaSource) across the session
         * boundary. See buildMediaSourceFactory() below for the other half. */
        const val EXTRA_AUDIO_URL = "sparkytube_audio_url"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 5_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(buildMediaSourceFactory())
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // No-op here on purpose — MainActivity's MediaController-side
                // Player.Listener.onPlayerError is the one that actually
                // handles this (expired signature, throttled URL, geo-block,
                // etc.): it invalidates the cached stream, re-resolves the
                // same video fresh, and falls through to the next video
                // after repeated failures. That handler previously didn't
                // exist, which meant this exact error (ExoPlayer failing to
                // play a URL extraction handed it) went unhandled entirely —
                // playback would silently freeze while the session still
                // reported "playing". Kept as a no-op here rather than
                // removed so the service-level player always has an
                // explicit error listener, even though the real handling
                // lives one layer up.
            }
        })

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()

        // Explicit: makes sure this service's notification always uses
        // Media3's default provider (title/artwork/play-pause/next/previous
        // built from MediaSession + MediaMetadata). Combined with a real
        // MediaController being connected (see MainActivity), this is what
        // produces the system notification + lock-screen controls.
        //
        // NOTE: next/previous buttons appear because DefaultMediaNotificationProvider
        // always includes them, but they're no-ops right now since there's no
        // real queue behind single-video playback.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )
    }

    /**
     * A MediaSource.Factory that checks each MediaItem's requestMetadata
     * extras for EXTRA_AUDIO_URL. If present (a 480p+ adaptive video-only
     * stream was selected), builds a MergingMediaSource combining the
     * video-only URI with a separate ProgressiveMediaSource for the audio
     * track, so ExoPlayer plays them in sync as if they were one file.
     * Falls back to a plain HLS or progressive source otherwise (360p
     * progressive streams and HLS manifests already contain audio).
     */
    private fun buildMediaSourceFactory(): MediaSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) SparkyTube")
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        return object : MediaSource.Factory {
            private var drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider? = null
            private var loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy? = null

            override fun setDrmSessionManagerProvider(
                provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider
            ): MediaSource.Factory {
                drmSessionManagerProvider = provider
                return this
            }

            override fun setLoadErrorHandlingPolicy(
                policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
            ): MediaSource.Factory {
                loadErrorHandlingPolicy = policy
                return this
            }

            override fun getSupportedTypes(): IntArray {
                return intArrayOf(androidx.media3.common.C.CONTENT_TYPE_OTHER, androidx.media3.common.C.CONTENT_TYPE_HLS)
            }

            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val audioUrl = mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URL)
                val isHls = mediaItem.localConfiguration?.mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8

                if (isHls) {
                    return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                }

                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)

                if (audioUrl.isNullOrBlank()) {
                    // Progressive stream (360p or below) — already has audio baked in.
                    return videoSource
                }

                // Adaptive stream (480p+) — video-only, mux in the separate audio track.
                val audioItem = MediaItem.fromUri(Uri.parse(audioUrl))
                val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(audioItem)

                return MergingMediaSource(videoSource, audioSource)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Called when the app task is swiped away from Recents. Media3's default
     * behavior on some OEM skins is to kill the service along with the task;
     * this override keeps playback (and the notification) alive if audio is
     * still playing, matching how other media apps behave — only stop if
     * playback has already ended/paused.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val exoPlayer = player
        if (exoPlayer == null || !exoPlayer.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
