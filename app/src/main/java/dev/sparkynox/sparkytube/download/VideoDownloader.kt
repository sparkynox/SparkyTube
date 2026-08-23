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
        if (audioUrl != null) {
            // Adaptive quality (video-only stream + separate audio-only
            // stream) -- DownloadManager alone can't combine two files
            // into one playable video, so this path downloads both then
            // muxes them with FFmpeg. This is the fix for the
            // "downloads aren't working because of SABR" limitation
            // downloadVideoById's caller used to refuse outright.
            startAdaptiveDownloadAndMux(context, videoUrl, audioUrl, safeName, qualityLabel)
        } else {
            startDirectDownload(context, videoUrl, safeName, qualityLabel)
        }
    }

    /**
     * Downloads the video-only and audio-only streams to the app's private
     * cache dir (not Downloads -- these are intermediate files, deleted
     * once muxing finishes or fails), then runs them through FFmpegKit's
     * "-c copy" mux (no re-encoding, just repackaging into one container
     * -- fast, and lossless since neither stream is touched) into a
     * single mp4 in the public Downloads folder.
     */
    private fun startAdaptiveDownloadAndMux(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        safeName: String,
        qualityLabel: String
    ) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "Downloading $qualityLabel (video + audio)…", Toast.LENGTH_SHORT).show()
        }

        Thread {
            val cacheDir = appContext.cacheDir
            val videoTemp = java.io.File(cacheDir, "dl_video_${System.currentTimeMillis()}.tmp")
            val audioTemp = java.io.File(cacheDir, "dl_audio_${System.currentTimeMillis()}.tmp")

            try {
                downloadToFile(videoUrl, videoTemp)
                downloadToFile(audioUrl, audioTemp)

                val fileName = "${safeName}_$qualityLabel.mp4"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outputFile = java.io.File(downloadsDir, fileName)

                // -c copy: remux only, no re-encode -- both streams keep
                // their original quality exactly, this just repackages
                // them into one mp4 container with both tracks.
                val command = "-y -i \"${videoTemp.absolutePath}\" -i \"${audioTemp.absolutePath}\" " +
                    "-c copy -map 0:v:0 -map 1:a:0 \"${outputFile.absolutePath}\""

                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                videoTemp.delete()
                audioTemp.delete()

                Handler(Looper.getMainLooper()).post {
                    if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                        // MediaScanner needs to be told about the new file
                        // explicitly -- files written directly to
                        // Downloads via java.io.File (rather than through
                        // DownloadManager or MediaStore) don't show up in
                        // the Files/Downloads app or other media scanners
                        // until a scan is requested for them.
                        android.media.MediaScannerConnection.scanFile(
                            appContext, arrayOf(outputFile.absolutePath), null, null
                        )
                        Toast.makeText(appContext, "Download complete: $fileName", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            appContext,
                            "Couldn't combine video and audio (FFmpeg error). Try a lower quality with combined audio+video instead.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                videoTemp.delete()
                audioTemp.delete()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun downloadToFile(url: String, destination: java.io.File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.inputStream.use { input ->
            java.io.FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        connection.disconnect()
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
                addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            // DownloadManager fails silently by default -- enqueue()
            // succeeding just means the request was accepted, not that
            // the download will actually complete. Without this listener,
            // a 403 (UA mismatch), an expired/throttled googlevideo.com
            // URL, or a network drop all look identical to the user:
            // nothing happens, no error, download just never shows up.
            registerDownloadCompletionReceiver(context, downloadManager, downloadId)

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

    /**
     * Listens for this specific download's completion and reports success
     * or failure (with the actual DownloadManager error code translated
     * to something readable) via Toast. Self-unregisters after firing
     * once — each call to downloadVideo() gets its own short-lived
     * receiver rather than one long-lived listener for every download
     * ever started in the app's lifetime.
     */
    private fun registerDownloadCompletionReceiver(context: Context, downloadManager: DownloadManager, downloadId: Long) {
        val appContext = context.applicationContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId != downloadId) return

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                cursor.use {
                    if (it.moveToFirst()) {
                        val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val status = if (statusIndex >= 0) it.getInt(statusIndex) else -1
                        val reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else -1

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Toast.makeText(appContext, "Download complete", Toast.LENGTH_SHORT).show()
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            Toast.makeText(appContext, "Download failed: ${describeFailureReason(reason)}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                try {
                    appContext.unregisterReceiver(this)
                } catch (e: Exception) {
                    // Already unregistered or never registered (activity
                    // torn down mid-download, etc.) -- not worth surfacing,
                    // the download outcome itself was already reported above.
                }
            }
        }

        val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun describeFailureReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "network data error"
        DownloadManager.ERROR_CANNOT_RESUME -> "couldn't resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage not found"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
        DownloadManager.ERROR_FILE_ERROR -> "file error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough storage space"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server rejected the request (likely an expired/invalid link — try again from the video)"
        else -> "unknown error (code $reason)"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100)
    }
}
