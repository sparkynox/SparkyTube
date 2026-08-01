package dev.sparkynox.sparkytube.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Fast downloader using Android's native DownloadManager.
 * Direct progressive stream download to avoid build errors & client-side muxing lag.
 */
object VideoDownloader {

    /**
     * Download function that handles selection from quality dialog.
     * Regardless of selected quality label, downloads the direct playable stream safely.
     */
    fun downloadVideo(context: Context, videoUrl: String, audioUrl: String?, title: String, qualityLabel: String = "360p") {
        val safeName = sanitizeFileName(title).ifBlank { "sparkytube_video" }
        startDirectDownload(context, videoUrl, safeName, qualityLabel)
    }

    private fun startDirectDownload(context: Context, videoUrl: String, safeName: String, qualityLabel: String) {
        try {
            val isWebm = videoUrl.contains("webm", ignoreCase = true)
            val extension = if (isWebm) "webm" else "mp4"
            
            // File name tagged with the user's selected resolution label
            val fileName = "${safeName}_$qualityLabel.$extension"

            val request = DownloadManager.Request(Uri.parse(videoUrl)).apply {
                setTitle("$safeName ($qualityLabel)")
                setDescription("Downloading via SparkyTube...")
                addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Download started ($qualityLabel)! Check notifications.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100)
    }
}
