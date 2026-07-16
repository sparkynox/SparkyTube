package dev.sparkynox.sparkytube.player

import android.content.Intent
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
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
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Extraction gave a URL but ExoPlayer couldn't play it (expired
                // signature, geo-block, etc.) — MainActivity's controller-side
                // listener stays on WebView fallback in that case.
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
