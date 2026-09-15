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
    fun downloadVideo(context: Context, videoUrl: String, audioUrl: String?, title: String, qualityLabel: String = "360p", videoId: String? = null) {
        val safeName = sanitizeFileName(title).ifBlank { "sparkytube_video" }
        if (audioUrl != null) {
            // Adaptive quality (video-only stream + separate audio-only
            // stream) -- DownloadManager alone can't combine two files
            // into one playable video, so this path downloads both then
            // muxes them with FFmpeg. This is the fix for the
            // "downloads aren't working because of SABR" limitation
            // downloadVideoById's caller used to refuse outright.
            startAdaptiveDownloadAndMux(context, videoUrl, audioUrl, safeName, qualityLabel, videoId)
        } else {
            startDirectDownload(context, videoUrl, safeName, qualityLabel, videoId)
        }
    }

    /**
     * Downloads the video-only and audio-only streams to the app's private
     * cache dir (not Downloads -- these are intermediate files, deleted
     * once muxing finishes or fails), then runs them through FFmpegKit's
     * "-c copy" mux (no re-encoding, just repackaging into one container
     * -- fast, and lossless since neither stream is touched) into a
     * single mp4 in the public Downloads folder.
     *
     * Registers with MuxTaskTracker so DownloadsActivity can show this
     * job's progress (video %, audio %, then a "muxing" stage) the same
     * way it shows plain DownloadManager downloads -- previously this
     * whole pipeline was invisible outside of Toast messages, which is
     * why it never appeared on the Downloads screen.
     */
    private fun startAdaptiveDownloadAndMux(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        safeName: String,
        qualityLabel: String,
        videoId: String?
    ) {
        val appContext = context.applicationContext
        val task = MuxTaskTracker.start(safeName, qualityLabel, videoId)

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "Downloading $qualityLabel (video + audio)…", Toast.LENGTH_SHORT).show()
        }

        val worker = Thread {
            val cacheDir = appContext.cacheDir
            val videoTemp = java.io.File(cacheDir, "dl_video_${task.id}.tmp")
            val audioTemp = java.io.File(cacheDir, "dl_audio_${task.id}.tmp")

            try {
                // Video then audio, one at a time -- NOT in parallel.
                // Two simultaneous connections to googlevideo.com from the
                // same client was triggering "Connection reset" mid-download
                // (Google's CDN appears to rate-limit/kill one of two
                // concurrent streams from the same session more often than
                // a single sequential one), which showed up as downloads
                // stalling or failing outright. Sequential is slightly
                // slower in theory but is what's actually reliable here.
                task.stage = MuxTaskTracker.Stage.DOWNLOADING_VIDEO
                MuxTaskTracker.update(task)
                downloadToFile(videoUrl, videoTemp, task) { downloaded, total ->
                    task.videoBytesDownloaded = downloaded
                    task.videoBytesTotal = total
                    MuxTaskTracker.update(task)
                }

                if (task.cancelled) { cleanupCancelled(videoTemp, audioTemp, task); return@Thread }

                task.stage = MuxTaskTracker.Stage.DOWNLOADING_AUDIO
                MuxTaskTracker.update(task)
                downloadToFile(audioUrl, audioTemp, task) { downloaded, total ->
                    task.audioBytesDownloaded = downloaded
                    task.audioBytesTotal = total
                    MuxTaskTracker.update(task)
                }

                if (task.cancelled) { cleanupCancelled(videoTemp, audioTemp, task); return@Thread }

                task.stage = MuxTaskTracker.Stage.MUXING
                MuxTaskTracker.update(task)

                val fileName = "${safeName}_$qualityLabel.mp4"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outputFile = java.io.File(downloadsDir, fileName)

                // -c copy: remux only, no re-encode -- both streams keep
                // their original quality exactly, this just repackages
                // them into one mp4 container with both tracks. This
                // stage is fast (seconds, not the minutes people were
                // seeing) since no actual video/audio encoding happens --
                // if muxing itself feels slow, the bottleneck was almost
                // always the two downloads above, not this step.
                val command = "-y -i \"${videoTemp.absolutePath}\" -i \"${audioTemp.absolutePath}\" " +
                    "-c copy -map 0:v:0 -map 1:a:0 \"${outputFile.absolutePath}\""

                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                videoTemp.delete()
                audioTemp.delete()

                if (task.cancelled) { MuxTaskTracker.remove(task.id); outputFile.delete(); return@Thread }

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
                        task.stage = MuxTaskTracker.Stage.DONE
                        task.outputPath = outputFile.absolutePath
                        MuxTaskTracker.update(task)
                        Toast.makeText(appContext, "Download complete: $fileName", Toast.LENGTH_LONG).show()
                    } else {
                        task.stage = MuxTaskTracker.Stage.FAILED
                        task.errorMessage = "Couldn't combine video and audio (FFmpeg error)"
                        MuxTaskTracker.update(task)
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
                if (!task.cancelled) {
                    task.stage = MuxTaskTracker.Stage.FAILED
                    task.errorMessage = e.message
                    MuxTaskTracker.update(task)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        worker.start()
    }

    /**
     * Deletes whatever partial temp files exist and removes the task from
     * the tracker -- called when a cancellation was requested mid-download.
     * No error Toast here since the user asked for this, it's not a failure.
     */
    private fun cleanupCancelled(videoTemp: java.io.File, audioTemp: java.io.File, task: MuxTaskTracker.MuxTask) {
        videoTemp.delete()
        audioTemp.delete()
        MuxTaskTracker.remove(task.id)
    }

    // Shared OkHttp client for all range-chunk downloads -- connection
    // pooling across chunks (and across separate downloads) avoids
    // re-doing TLS handshakes for every single chunk request, which adds
    // up fast when a download is split into 6-8 pieces.
    private val httpClient: okhttp3.OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            // Default OkHttp caps requests to the same host at 5 -- fine
            // for a browser, but wrong here: every download already opens
            // 5 chunk connections to googlevideo.com by design, so a
            // SECOND download queued while the first is running would
            // get stuck waiting behind the first one's chunks for that
            // same 5-slot limit. Raised well above what even several
            // simultaneous downloads' chunk counts would need.
            maxRequestsPerHost = 24
            maxRequests = 24
        }
        okhttp3.OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(24, 60, java.util.concurrent.TimeUnit.SECONDS))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Downloads url -> destination using multiple parallel byte-range
     * requests instead of one single-connection stream. This is the real
     * fix for downloads sitting at 15-100KB/s while other apps hit 1-2MB/s
     * on the same connection: googlevideo.com's CDN appears to cap
     * per-connection throughput fairly aggressively (this is by design --
     * it's meant to roughly match realtime playback speed for a single
     * player connection), but does NOT cap total throughput across
     * several concurrent range requests to the same URL. Every other fast
     * downloader (IDM, ADM, VidMate, etc.) gets its speed the same way --
     * splitting one file into N ranged chunks and pulling them at once.
     *
     * Falls back to a single non-ranged connection automatically if the
     * server doesn't report Content-Length or rejects Range requests
     * (some CDN edge nodes don't support it) -- see CHUNK_COUNT logic
     * below for exactly when that fallback kicks in.
     */
    private fun downloadToFile(
        url: String,
        destination: java.io.File,
        task: MuxTaskTracker.MuxTask,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            if (task.cancelled) return
            try {
                downloadChunked(url, destination, task, onProgress)
                return // success
            } catch (e: java.io.InterruptedIOException) {
                throw e // cancellation-triggered interrupt -- don't retry, propagate up
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) {
                    // One retry on a fresh set of connections -- covers
                    // transient CDN hiccups on one chunk rather than a
                    // permanently broken link.
                    Thread.sleep(800)
                }
            }
        }
        throw lastError ?: java.io.IOException("Download failed")
    }

    private const val MIN_BYTES_PER_CHUNK = 3 * 1024 * 1024L // don't split a chunk smaller than ~3MB

    private fun chunkCountFor(totalSize: Long): Int {
        // Scale to file size instead of always splitting into 5 --
        // audio streams are usually much smaller than video (often
        // under 10-15MB), so forcing 5 chunks on them was creating tiny
        // slivers per connection, which added per-request overhead for
        // no real speed benefit and made the audio download the slower
        // of the two despite being the smaller file.
        val ideal = (totalSize / MIN_BYTES_PER_CHUNK).toInt()
        return ideal.coerceIn(1, 6)
    }

    private fun downloadChunked(
        url: String,
        destination: java.io.File,
        task: MuxTaskTracker.MuxTask,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        // HEAD-equivalent: a tiny ranged GET (first byte only) just to
        // read Content-Length and confirm the server actually honors
        // Range requests, without committing to a full download yet.
        val probeRequest = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")
            .header("Range", "bytes=0-0")
            .build()

        val (totalSize, supportsRange) = httpClient.newCall(probeRequest).execute().use { resp ->
            val contentRange = resp.header("Content-Range") // format: "bytes 0-0/12345678"
            val total = contentRange?.substringAfterLast('/')?.toLongOrNull()
                ?: resp.header("Content-Length")?.toLongOrNull()
                ?: -1L
            val ranged = resp.code == 206 || contentRange != null
            total to ranged
        }

        if (!supportsRange || totalSize <= 0 || totalSize < MIN_BYTES_PER_CHUNK) {
            // Server doesn't support ranges, or file's small enough that
            // splitting wouldn't meaningfully help -- single connection,
            // same as before.
            downloadSingleConnection(url, destination, totalSize, task, onProgress)
            return
        }

        val chunkCount = chunkCountFor(totalSize)
        val chunkSize = totalSize / chunkCount
        val ranges = (0 until chunkCount).map { i ->
            val start = i * chunkSize
            val end = if (i == chunkCount - 1) totalSize - 1 else (start + chunkSize - 1)
            start to end
        }

        // RandomAccessFile lets every chunk thread write directly to its
        // own byte-offset in the final file concurrently -- no need to
        // download to separate temp files and concatenate afterward.
        val raf = java.io.RandomAccessFile(destination, "rw")
        raf.setLength(totalSize)
        raf.close()

        val progressPerChunk = LongArray(chunkCount)
        val progressLock = Any()
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)

        fun reportCombined() {
            val sum = synchronized(progressLock) { progressPerChunk.sum() }
            onProgress(sum, totalSize)
        }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(chunkCount)
        val futures = ranges.mapIndexed { index, (start, end) ->
            executor.submit {
                // Each chunk gets its own retry -- a "Connection reset"
                // hitting one chunk (often right near the end of a
                // transfer, where a keep-alive connection is more likely
                // to get closed by the CDN right as the last few bytes
                // land) used to fail the ENTIRE file and force restarting
                // every other already-finished chunk too. Now only the
                // one flaky chunk retries.
                var chunkError: Exception? = null
                var resumeFrom = start
                for (attempt in 0..2) {
                    if (task.cancelled) return@submit
                    try {
                        downloadRangeChunk(url, destination, resumeFrom, end, task) { chunkDownloaded ->
                            synchronized(progressLock) { progressPerChunk[index] = (resumeFrom - start) + chunkDownloaded }
                            reportCombined()
                        }
                        chunkError = null
                        break
                    } catch (e: java.io.InterruptedIOException) {
                        return@submit // cancelled -- don't retry
                    } catch (e: Exception) {
                        chunkError = e
                        // Resume from wherever this attempt actually got
                        // to instead of redownloading the whole chunk --
                        // matters most for the "reset right at 100%" case,
                        // where almost the entire chunk was already good.
                        resumeFrom = start + synchronized(progressLock) { progressPerChunk[index] }
                        Thread.sleep(500L * (attempt + 1))
                    }
                }
                if (chunkError != null) {
                    failure.compareAndSet(null, chunkError)
                }
            }
        }
        futures.forEach { it.get() }
        executor.shutdown()

        if (task.cancelled || failure.get() != null) {
            executor.shutdownNow()
        }

        if (task.cancelled) throw java.io.InterruptedIOException("cancelled")
        failure.get()?.let { throw it }

        reportCombined()
    }

    /**
     * Downloads one byte range [start, end] (inclusive) directly into its
     * slice of the pre-sized destination file via RandomAccessFile.seek --
     * runs on its own OkHttp call/connection from the shared pool, so
     * CHUNK_COUNT of these running via the executor above is what gets
     * multiple concurrent connections to the CDN.
     */
    private fun downloadRangeChunk(
        url: String,
        destination: java.io.File,
        start: Long,
        end: Long,
        task: MuxTaskTracker.MuxTask,
        onChunkProgress: (downloaded: Long) -> Unit
    ) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")
            .header("Range", "bytes=$start-$end")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Chunk request failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw java.io.IOException("Empty chunk response")

            java.io.RandomAccessFile(destination, "rw").use { raf ->
                raf.seek(start)
                body.byteStream().use { input ->
                    val buffer = ByteArray(65536)
                    var chunkDownloaded = 0L
                    var lastReportTime = 0L
                    while (true) {
                        if (task.cancelled) throw java.io.InterruptedIOException("cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        chunkDownloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 250) {
                            onChunkProgress(chunkDownloaded)
                            lastReportTime = now
                        }
                    }
                    onChunkProgress(chunkDownloaded)
                }
            }
        }
    }

    /**
     * Fallback path for servers that don't support Range requests, or
     * files too small for chunking to matter -- same single-stream
     * approach as before, just on OkHttp instead of HttpURLConnection
     * for consistency (and OkHttp's connection pooling still helps here
     * across retries/other downloads).
     */
    private fun downloadSingleConnection(
        url: String,
        destination: java.io.File,
        knownTotal: Long,
        task: MuxTaskTracker.MuxTask,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw java.io.IOException("Empty response")
            val total = if (knownTotal > 0) knownTotal else body.contentLength()

            body.byteStream().use { input ->
                java.io.FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(65536)
                    var downloaded = 0L
                    var lastReportTime = 0L
                    while (true) {
                        if (task.cancelled) throw java.io.InterruptedIOException("cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 250) {
                            onProgress(downloaded, total)
                            lastReportTime = now
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
        }
    }

    private fun startDirectDownload(context: Context, videoUrl: String, safeName: String, qualityLabel: String, videoId: String?) {
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

            // Thumbnail lookup -- DownloadManager itself has no concept of
            // "attach a thumbnail to this download," so this side-table
            // (keyed by DownloadManager's own row id) is what lets
            // DownloadsActivity show the video's thumbnail next to a
            // plain progressive download the same way it already does
            // for adaptive/mux downloads.
            if (!videoId.isNullOrBlank()) {
                DownloadThumbnails.register(downloadId, videoId)
            }

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
