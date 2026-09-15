package dev.sparkynox.sparkytube.download

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.sparkynox.sparkytube.databinding.ActivityOfflinePlayerBinding

/**
 * Standalone local-file player for downloaded videos -- deliberately its
 * own self-contained ExoPlayer instance rather than reusing
 * MainActivity's shared MediaController/PlaybackService, since that
 * whole pipeline is built around the WebView+extractor state machine
 * (resolving stream URLs, prefetching, resume-state, etc.) that doesn't
 * apply to a file already sitting on disk. This keeps offline playback
 * simple and fully independent of network/extraction state.
 */
class OfflinePlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflinePlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflinePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Offline video"

        binding.offlinePlayerBackBtn.setOnClickListener { finish() }
        binding.offlinePlayerTitle.text = title

        if (filePath == null) {
            finish()
            return
        }

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        binding.offlinePlayerView.player = exoPlayer

        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(filePath))))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        // Local playback, no background/notification use case here (see
        // class doc) -- pausing/releasing on stop is the correct simple
        // behavior, unlike the main app's deliberate keep-playing-in-
        // background native pipeline.
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
    }
}
