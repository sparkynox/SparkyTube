package dev.sparkynox.sparkytube.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Downloads a resolved video stream to the public Downloads folder.
 *
 * Two cases, because of how YouTube actually serves video:
 *  - Progressive streams (360p and below, YouTube's own cap): a single URL
 *    already has video+audio combined. DownloadManager saves it directly —
 *    fast, no processing needed.
 *  - Adaptive streams (480p and above): video-only, with a separate audio
 *    track. DownloadManager can't merge two URLs into one file, so a
 *    plain download of just the video-only URL would produce a silent
 *    MP4. For these, Media3 Transformer (Google's own on-device media
 *    editing library, includes a muxer) downloads both tracks and muxes
 *    them into a single real MP4 with audio, all locally.
 */
object VideoDownloader {

    /**
     * @param videoUrl progressive (already has audio) or adaptive
     *                 (video-only, needs audioUrl) stream URL.
     * @param audioUrl null for progressive streams; the matching audio
     *                 track URL for adaptive streams (see
     *                 StreamExtractor.QualityOption.audioUrl).
     * @param title used to name the downloaded file (sanitized for filesystem safety).
     */
    fun downloadVideo(context: Context, videoUrl: String, audioUrl: String?, title: String) {
        val safeName = sanitizeFileName(title).ifBlank { "sparkytube_video" }

        if (audioUrl == null) {
            downloadProgressive(context, videoUrl, safeName)
        } else {
            downloadAndMuxAdaptive(context, videoUrl, audioUrl, safeName)
        }
    }

    private fun downloadProgressive(context: Context, videoUrl: String, safeName: String) {
        try {
            val fileName = "$safeName.mp4"
            val request = DownloadManager.Request(Uri.parse(videoUrl))
                .setTitle(safeName)
                .setDescription("Downloading via SparkyTube")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(context, "Download started — check Downloads folder", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't start download: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Uses Media3 Transformer to pull the video-only and audio-only streams
     * and mux them into one MP4 with a Composition of two EditedMediaItem
     * tracks. Runs the actual encode on Transformer's own background
     * thread/looper; this function just kicks it off and reports progress
     * via Toasts since there's no dedicated download-progress UI yet.
     */
    private fun downloadAndMuxAdaptive(context: Context, videoUrl: String, audioUrl: String, safeName: String) {
        try {
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputFile = File(outputDir, "$safeName.mp4")

            val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(videoUrl))).build()
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(audioUrl))).build()

            val composition = Composition.Builder(
                EditedMediaItemSequence(videoItem),
                EditedMediaItemSequence(audioItem)
            ).build()

            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        moveToPublicDownloads(context, outputFile, safeName)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        Toast.makeText(
                            context,
                            "Download failed while combining video/audio: ${exportException.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
                .build()

            Toast.makeText(context, "Downloading and combining video + audio…", Toast.LENGTH_SHORT).show()
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't start download: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Transformer writes to app-private storage (needs a real file path,
     * not a MediaStore Uri, mid-export) — once the export finishes, copy
     * the finished MP4 into the public Downloads folder so it shows up
     * like any other download, then clean up the private copy.
     */
    private fun moveToPublicDownloads(context: Context, sourceFile: File, safeName: String) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!publicDir.exists()) publicDir.mkdirs()
            val destFile = File(publicDir, "$safeName.mp4")

            sourceFile.copyTo(destFile, overwrite = true)
            sourceFile.delete()

            downloadManager.addCompletedDownload(
                destFile.name,
                "Downloaded via SparkyTube",
                true,
                "video/mp4",
                destFile.absolutePath,
                destFile.length(),
                true
            )

            Toast.makeText(context, "Download complete — saved to Downloads", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Downloaded, but couldn't move to Downloads: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100)
    }
}
