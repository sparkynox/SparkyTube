package dev.sparkynox.sparkytube.extractor

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Chaquopy replacement for the old youtubedl-android fallback. The old
 * one spawned a fresh Python subprocess per video (interpreter boot +
 * yt-dlp import from scratch every single call) -- this keeps one
 * interpreter running for the app's whole lifetime with yt-dlp already
 * imported, so every resolve after the first one is just a function call
 * into warm Python, not a cold process start.
 *
 * The Python side is app/src/main/python/sparky_ytdlp.py.
 */
object PyYtDlp {

    @Volatile
    private var ytDlpModule: PyObject? = null
    @Volatile
    private var initFailedPermanently = false

    @Volatile
    var lastErrorMessage: String? = null
        private set

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (ytDlpModule != null || initFailedPermanently) return

        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            // sparky_ytdlp.py lives in src/main/python, Chaquopy bundles it
            // automatically. Importing it runs the module body once, which
            // is where yt-dlp gets imported and the YoutubeDL instance built.
            val t0 = android.os.SystemClock.elapsedRealtime()
            ytDlpModule = Python.getInstance().getModule("sparky_ytdlp")
            dev.sparkynox.sparkytube.logs.LogRecorder.i(
                "PyYtDlp", "python + yt-dlp ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms"
            )
            lastErrorMessage = null
        } catch (t: Throwable) {
            android.util.Log.e("PyYtDlp", "init failed", t)
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            dev.sparkynox.sparkytube.logs.LogRecorder.e("PyYtDlp", "init failed", t)
            initFailedPermanently = true
        }
    }

    /**
     * Blocking call -- run off the main thread. Returns the same raw
     * yt-dlp JSON string the old --dump-json CLI flag produced, so
     * parseFormats() below (ported straight from the old YtDlpResolver)
     * doesn't need to change at all.
     */
    fun resolve(videoId: String): StreamExtractor.ResolvedStream? {
        val module = ytDlpModule
        if (module == null) {
            lastErrorMessage = "yt-dlp isn't initialized -- check the \"yt-dlp fallback\" toggle in Settings is on, and restart the app if you just enabled it"
            return null
        }

        return try {
            val dataSaver = appContext?.let {
                dev.sparkynox.sparkytube.settings.SettingsPrefs.isYtDlpDataSaverEnabled(it)
            } ?: true
            val t0 = android.os.SystemClock.elapsedRealtime()
            val rawJson = module.callAttr("resolve_json", videoId, dataSaver).toString()
            val pyMs = android.os.SystemClock.elapsedRealtime() - t0
            val path = Regex("\"_sparky_path\": \"(\\w+)\"").find(rawJson)?.groupValues?.get(1) ?: "?"
            dev.sparkynox.sparkytube.logs.LogRecorder.i("PyYtDlp", "python call took $pyMs ms (path=$path)")
            if (rawJson.isBlank() || rawJson == "None") {
                lastErrorMessage = "yt-dlp returned no data for this video"
                return null
            }
            val parsed = parseFormats(rawJson, dataSaver)
            if (parsed == null) {
                lastErrorMessage = "yt-dlp returned no playable formats for this video"
                return null
            }
            lastErrorMessage = null
            parsed
        } catch (t: Throwable) {
            android.util.Log.e("PyYtDlp", "resolve failed for $videoId", t)
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            dev.sparkynox.sparkytube.logs.LogRecorder.e("PyYtDlp", "resolve failed for $videoId", t)
            null
        }
    }

    // ported as-is from the old YtDlpResolver.kt -- same format-bucketing
    // logic (progressive / video-only+bestAudio / audio-only), just reads
    // from a JSON string that came from an in-process call instead of a
    // subprocess's stdout
    private fun parseFormats(rawJson: String, dataSaver: Boolean): StreamExtractor.ResolvedStream? {
        val root = org.json.JSONObject(rawJson)
        val formats = root.optJSONArray("formats") ?: return null

        data class RawFormat(
            val url: String,
            val height: Int,
            val hasVideo: Boolean,
            val hasAudio: Boolean,
            val abr: Double,
            val vcodec: String,
            val isManifest: Boolean,
            val ext: String
        )

        // weak phones can't hardware-decode AV1, so try h264 first, then vp9
        fun codecRank(v: String) = when {
            v.startsWith("avc1") -> 0
            v.startsWith("vp09") || v.startsWith("vp9") -> 1
            v.startsWith("av01") -> 3
            else -> 2
        }

        val all = (0 until formats.length()).mapNotNull { i ->
            val f = formats.optJSONObject(i) ?: return@mapNotNull null
            val url = f.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            RawFormat(
                url = url,
                height = f.optInt("height", 0),
                hasVideo = vcodec != "none",
                hasAudio = acodec != "none",
                abr = f.optDouble("abr", 0.0).let { if (it.isNaN()) 0.0 else it },
                vcodec = vcodec,
                isManifest = f.optString("protocol", "https").let { it.startsWith("m3u8") || it.contains("dash") },
                ext = f.optString("ext", "")
            )
        }

        // manifests (hls/dash) aren't a single playable url, skip them unless nothing else exists
        val direct = all.filter { !it.isManifest }
        val usable = if (direct.any { it.hasVideo && it.height > 0 }) direct else all

        val bestAudioUrl = usable.filter { it.hasAudio && !it.hasVideo }
            .maxByOrNull { it.abr }
            ?.url

        val downloadAudios = usable
            .filter { it.hasAudio && !it.hasVideo && it.abr > 0 }
            .sortedByDescending { it.abr }
            .distinctBy { "${it.abr.toInt()}_${it.ext}" }
            .take(6)
            .map { StreamExtractor.DownloadAudioOption("${it.abr.toInt()} kbps (${it.ext})", it.url, it.ext, it.abr.toInt()) }

        val progressiveOptions = usable
            .filter { it.hasVideo && it.hasAudio && it.height > 0 }
            .sortedBy { codecRank(it.vcodec) }
            .map { StreamExtractor.QualityOption(label = "${it.height}p", url = it.url, resolutionValue = it.height, audioUrl = null) }

        val adaptiveOptions = if (bestAudioUrl != null) {
            usable.filter { it.hasVideo && !it.hasAudio && it.height > 0 }
                .sortedBy { codecRank(it.vcodec) }
                .map { StreamExtractor.QualityOption(label = "${it.height}p", url = it.url, resolutionValue = it.height, audioUrl = bestAudioUrl) }
        } else {
            emptyList()
        }

        val allOptions = (progressiveOptions + adaptiveOptions)
        val fullList = allOptions
            .sortedByDescending { it.audioUrl != null }
            .distinctBy { it.label }
            .sortedByDescending { it.resolutionValue }

        // data saver keeps a single 360p entry (muxed one if there is one),
        // otherwise the lowest thing available
        val qualities = if (dataSaver) {
            val pick = progressiveOptions.filter { it.resolutionValue <= 360 }.maxByOrNull { it.resolutionValue }
                ?: allOptions.filter { it.resolutionValue <= 360 }.maxByOrNull { it.resolutionValue }
                ?: allOptions.minByOrNull { it.resolutionValue }
            listOfNotNull(pick)
        } else {
            fullList
        }

        // shows up in Settings > Logs so we can see what yt-dlp really returned
        dev.sparkynox.sparkytube.logs.LogRecorder.i(
            "PyYtDlp",
            "dataSaver=$dataSaver, found: ${fullList.joinToString { it.label }}, using: ${qualities.joinToString { it.label }}"
        )

        val title = root.optString("title", "")
        val duration = root.optLong("duration", 0L)
        val description = root.optString("description").takeIf { it.isNotBlank() }

        if (qualities.isEmpty()) {
            val fallbackUrl = root.optString("url").takeIf { it.isNotBlank() } ?: return null
            return StreamExtractor.ResolvedStream(
                url = fallbackUrl,
                title = title,
                isHls = fallbackUrl.contains(".m3u8"),
                durationSeconds = duration,
                availableQualities = emptyList(),
                isLive = root.optBoolean("is_live", false) || (fallbackUrl.contains(".m3u8") && duration <= 0),
                description = description,
                downloadAudios = downloadAudios
            )
        }

        val defaultQuality = qualities.minByOrNull { kotlin.math.abs(it.resolutionValue - StreamExtractor.preferredQualityTarget()) }
            ?: qualities.first()

        return StreamExtractor.ResolvedStream(
            url = defaultQuality.url,
            title = title,
            isHls = false,
            durationSeconds = duration,
            availableQualities = qualities,
            isLive = root.optBoolean("is_live", false),
            defaultAudioUrl = defaultQuality.audioUrl,
            defaultQualityLabel = defaultQuality.label,
            availableAudioTracks = emptyList(),
            description = description,
            downloadAudios = downloadAudios
        )
    }
}
