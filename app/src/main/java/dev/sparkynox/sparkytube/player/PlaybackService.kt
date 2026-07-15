package dev.sparkynox.sparkytube.player

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service backing ExoPlayer. Gives SparkyTube:
 *  - Real background playback (screen off / app backgrounded) via a proper
 *    foreground service + notification (Media3 handles the actual
 *    startForeground() call once a MediaSession + a playing player exist,
 *    but it needs setMediaNotificationProvider or the default provider
 *    wired correctly — the key thing that was missing before was simply
 *    never having an active MediaSession + player combo that Media3's
 *    default notification provider could latch onto reliably before the
 *    service got backgrounded and OS-killed).
 *  - System notification with title, play/pause, next/previous controls
 *    (Media3's DefaultMediaNotificationProvider builds this automatically
 *    from the MediaSession + current MediaItem metadata).
 *  - Lock-screen media controls.
 *
 * Real playback: MainActivity resolves a video ID -> playable URL via
 * StreamExtractor (NewPipeExtractor), then calls loadAndPlay() here.
 * Video renders into the PlayerView MainActivity attaches via getPlayer() —
 * actual pixels come from ExoPlayer, NOT the WebView's <video> element.
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()
    private var onEndedListener: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    /** MainActivity registers this to hand control back to WebView (which
     * autoplays the next suggested video on its own) once native playback
     * of the current video finishes. */
    fun setOnPlaybackEndedListener(listener: (() -> Unit)?) {
        onEndedListener = listener
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Support both MediaSessionService's own session binding AND a plain
        // local bind from MainActivity so it can attach ExoPlayer to a PlayerView.
        return super.onBind(intent) ?: binder
    }

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> onEndedListener?.invoke()
                    Player.STATE_READY -> { /* hook: hide loading spinner */ }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Extraction gave a URL but ExoPlayer couldn't play it (expired
                // signature, geo-block, etc.) — caller should fall back to WebView.
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Media3's MediaSessionService promotes itself to a foreground
                // service automatically as long as a MediaSession exists and
                // is actively playing — this callback is where that state
                // change is observed, but the actual startForeground() call
                // is handled internally by MediaSessionService once the
                // notification provider posts a notification for this session.
            }
        })

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()

        // Explicit: makes sure this service's notification always uses
        // Media3's default provider (title/artwork/play-pause/next/previous
        // built from MediaSession + MediaMetadata) rather than silently
        // having none — this is what actually produces the "Premium-style"
        // notification with playback controls Sparky asked for.
        //
        // NOTE: next/previous buttons appear because DefaultMediaNotificationProvider
        // always includes them, but they're no-ops right now since there's no
        // real queue behind single-video playback — wiring them to actually
        // skip to the next/previous suggested video (via the WebView's related
        // videos list) is a separate follow-up, not done here.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build()
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun getPlayer(): ExoPlayer? = player

    /**
     * Loads a resolved stream URL (from StreamExtractor.resolvePlayableUrl)
     * and starts native ExoPlayer playback.
     */
    fun loadAndPlay(streamUrl: String, title: String, isHls: Boolean) {
        val exoPlayer = player ?: return

        val builder = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .build()
            )

        if (isHls) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        exoPlayer.setMediaItem(builder.build())
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun stopAndClear() {
        player?.stop()
        player?.clearMediaItems()
    }

    /**
     * Called when the app task is swiped away from Recents. Media3's default
     * behavior on some OEM skins is to kill the service along with the task;
     * this override keeps playback (and the notification) alive if audio is
     * still playing, matching how Premium/other media apps behave — only
     * stop if playback has already ended/paused.
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
