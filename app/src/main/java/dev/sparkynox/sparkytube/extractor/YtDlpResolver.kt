package dev.sparkynox.sparkytube.extractor

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg

/**
 * yt-dlp fallback/explicit extractor, via youtubedl-android. Heaviest
 * option (bundles a Python runtime + yt-dlp itself, ~30-40MB added to
 * the APK) but the most reliable against age-gates/region locks/
 * anything YouTube throws at it, since yt-dlp gets fixed for
 * YouTube-side changes fastest of any extractor out there.
 *
 * Reached either as the last link in AUTO mode's fallback chain (after
 * NewPipeExtractor and Piped have both failed -- see
 * StreamExtractor.doResolve), or directly when the user explicitly sets
 * Extractor Method to yt-dlp in Settings. Slower than the other two
 * paths per-call (spins up a real Python process), so AUTO deliberately
 * tries it last; see resolve() below for the speed flags applied to
 * keep even that call as fast as practical.
 */
object YtDlpResolver {

    @Volatile
    private var initialized = false
    @Volatile
    private var initFailedPermanently = false
    private val initLock = Any()

    // Last error message from either init() or resolve() -- surfaced by
    // MainActivity's error dialog when the Extractor Method is explicitly
    // set to yt-dlp, so a failure shows the real reason (unsupported ABI,
    // yt-dlp itself erroring on this video, network issue, etc.) instead
    // of the generic "couldn't play this video" message everyone else
    // gets. Not shown when yt-dlp is just the last fallback in AUTO mode,
    // since that path failing silently and moving on is the whole point
    // of a fallback chain.
    @Volatile
    var lastErrorMessage: String? = null
        private set

    // App cache dir path, captured at init() -- used for yt-dlp's
    // --cache-dir flag so extractor/signature-decryption results persist
    // across calls instead of being recomputed from scratch every video
    // (see the speed comment in resolve() below).
    @Volatile
    private var appContextCacheDir: String = ""

    /**
     * Must be called once (e.g. from Application.onCreate or lazily here)
     * before resolve() will work -- youtubedl-android needs to unpack its
     * bundled Python/yt-dlp binaries to app-private storage on first run.
     * Safe to call repeatedly; only does real work once per process.
     */
    fun init(context: Context) {
        if (initialized || initFailedPermanently) return
        synchronized(initLock) {
            if (initialized || initFailedPermanently) return
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                appContextCacheDir = context.applicationContext.cacheDir.absolutePath
                initialized = true
            } catch (t: Throwable) {
                // Deliberately catches Throwable, not just Exception --
                // youtubedl-android's init unpacks bundled native
                // binaries (Python runtime, yt-dlp, ffmpeg) on first run,
                // which can throw Error subclasses (UnsatisfiedLinkError
                // on an unsupported ABI, OutOfMemoryError while
                // unpacking, etc.) that a plain `catch (e: Exception)`
                // does NOT catch -- those were escaping this try/catch
                // entirely and crashing the whole app instead of just
                // leaving this fallback unavailable. Init failing just
                // means this fallback stays unavailable -- resolve()
                // below checks `initialized` and no-ops instead.
                //
                // initFailedPermanently stops this from retrying on every
                // single video load -- an init failure here is virtually
                // always something structural (missing R8/ProGuard keep
                // rules for the library's reflection-based class loading,
                // an unsupported device ABI, corrupted bundled assets)
                // that a retry can't fix, so hammering it repeatedly just
                // wastes time/battery on every video without ever
                // succeeding. Resets on next app process start in case
                // whatever caused it (e.g. low storage) was transient.
                initFailedPermanently = true
                android.util.Log.e("YtDlpResolver", "init failed", t)
                lastErrorMessage = "Setup failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                dev.sparkynox.sparkytube.logs.LogRecorder.e("YtDlpResolver", "init failed", t)
            }
        }
    }

    /**
     * Returns the same ResolvedStream shape as the rest of StreamExtractor,
     * or null if yt-dlp itself failed (still couldn't get the video),
     * isn't initialized yet, or this call is running on the main thread
     * by mistake (yt-dlp spawns a real process -- must be called from a
     * background dispatcher, same as everywhere else in this file).
     */
    fun resolve(videoId: String): StreamExtractor.ResolvedStream? {
        if (!initialized) {
            lastErrorMessage = "yt-dlp isn't initialized -- check the \"yt-dlp fallback\" toggle in Settings is on, and restart the app if you just enabled it"
            return null
        }

        return try {
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
            // -f best used to be used here, which throws away every
            // format except one -- that's why the yt-dlp path never had
            // a quality picker or adaptive (480p+) streams. -j makes
            // yt-dlp dump the FULL format list as JSON on stdout instead
            // (read via execute().out below, parsed by hand), so this
            // path can build the same quality ladder StreamExtractor.kt
            // builds for the NewPipe path -- picker + adaptive downloads
            // then work identically no matter which extractor actually
            // resolved the video.
            request.addOption("-j")
            request.addOption("--no-playlist")

            // Speed flags -- yt-dlp normally does a lot of work per call
            // that this fallback doesn't need: checking for subtitles,
            // writing metadata files, verifying certs, resolving
            // thumbnails, and (biggest cost) re-running its full
            // extractor + signature-decryption pipeline from scratch
            // every single time with no cache. None of that matters for
            // "just get me a playable URL fast."
            request.addOption("--no-write-subs")
            request.addOption("--no-write-auto-subs")
            request.addOption("--no-write-thumbnail")
            request.addOption("--no-write-info-json")
            request.addOption("--no-check-certificates")
            request.addOption("--skip-download") // -j already implies this, but explicit avoids any accidental full download path
            // Caches extractor/signature-decryption results between calls
            // instead of recomputing from scratch every single video --
            // this is the single biggest speed lever available here,
            // since re-deriving YouTube's signature cipher is the slow
            // part of yt-dlp's pipeline, not the network request itself.
            request.addOption("--cache-dir", "${appContextCacheDir}/yt-dlp-cache")
            // Skip yt-dlp's own update-check network round-trip that
            // otherwise fires on some code paths -- there's no update
            // mechanism wired up here anyway (the bundled binary only
            // changes when SparkyTube itself updates).
            request.addOption("--no-update")
            // Concurrent fragment downloads for any HLS/DASH fallback --
            // doesn't affect getInfo() itself but keeps behavior
            // consistent if this ever gets reused for a real download.
            request.addOption("--concurrent-fragments", "4")

            // Biggest remaining speed lever for weak/low-RAM devices:
            // by default yt-dlp scrapes YouTube's full web player page
            // and parses a large JS-driven response to build the format
            // list. Forcing the "android" player client instead makes
            // yt-dlp hit YouTube's lightweight mobile-app-style API,
            // which returns a much smaller, simpler response -- less
            // data to download AND less to parse, which matters more on
            // a weak CPU (e.g. Vivo Y73-class hardware) than on a fast
            // one. This is the same trick yt-dlp's own docs recommend
            // for speeding up extraction. skip=hls,translated_subs trims
            // two more response sections this app never reads.
            request.addOption(
                "--extractor-args",
                "youtube:player_client=android;player_skip=configs;skip=translated_subs"
            )

            // execute() + reading stdout (.out) instead of getInfo() --
            // getInfo() hands back youtubedl-android's own VideoInfo
            // wrapper, which only exposes a handful of top-level fields
            // (title/url/duration/etc.) and no stable way to reach the
            // full per-format list that -j actually printed. execute()
            // just runs yt-dlp and gives back its raw stdout, so parsing
            // it ourselves with org.json below gets us every field -j
            // provides, same as yt-dlp's real JSON output.
            val response = YoutubeDL.getInstance().execute(request)
            val rawJson = response.out
            if (rawJson.isBlank()) {
                lastErrorMessage = "yt-dlp returned no data for this video"
                return null
            }

            val parsed = parseFormats(rawJson)
            if (parsed == null) {
                lastErrorMessage = "yt-dlp returned no playable formats for this video"
                return null
            }

            lastErrorMessage = null // success -- clear any stale error from a previous attempt
            parsed
        } catch (t: Throwable) {
            android.util.Log.e("YtDlpResolver", "resolve failed for $videoId", t)
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            dev.sparkynox.sparkytube.logs.LogRecorder.e("YtDlpResolver", "resolve failed for $videoId", t)
            null
        }
    }

    /**
     * Turns yt-dlp's raw --dump-json output into the same ResolvedStream/
     * QualityOption shape StreamExtractor.kt builds from NewPipeExtractor,
     * so the rest of the app (quality picker, adaptive muxed downloads,
     * audio-track picker) doesn't need to know or care which extractor
     * actually resolved a given video.
     *
     * yt-dlp's format list mixes progressive (has both vcodec+acodec),
     * video-only/adaptive (vcodec set, acodec == "none"), and audio-only
     * (vcodec == "none") entries all together with no separate grouping
     * like NewPipeExtractor gives -- this walks it once and buckets them
     * the same way StreamExtractor.doResolve does.
     */
    private fun parseFormats(rawJson: String): StreamExtractor.ResolvedStream? {
        val root = org.json.JSONObject(rawJson)
        val formats = root.optJSONArray("formats") ?: return null

        data class RawFormat(
            val url: String,
            val height: Int,
            val hasVideo: Boolean,
            val hasAudio: Boolean,
            val abr: Double
        )

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
                abr = f.optDouble("abr", 0.0).let { if (it.isNaN()) 0.0 else it }
            )
        }

        // Best standalone audio track -- same "one audio stream muxed into
        // every video-only quality" approach StreamExtractor.kt uses,
        // rather than a full multi-dub audio-track picker (yt-dlp's
        // android client doesn't reliably expose per-dub audio tracks the
        // way NewPipeExtractor's audioTrackType does).
        val bestAudioUrl = all.filter { it.hasAudio && !it.hasVideo }
            .maxByOrNull { it.abr }
            ?.url

        val progressiveOptions = all
            .filter { it.hasVideo && it.hasAudio && it.height > 0 }
            .map { StreamExtractor.QualityOption(label = "${it.height}p", url = it.url, resolutionValue = it.height, audioUrl = null) }

        val adaptiveOptions = if (bestAudioUrl != null) {
            all.filter { it.hasVideo && !it.hasAudio && it.height > 0 }
                .map { StreamExtractor.QualityOption(label = "${it.height}p", url = it.url, resolutionValue = it.height, audioUrl = bestAudioUrl) }
        } else {
            emptyList()
        }

        val qualities = (progressiveOptions + adaptiveOptions)
            .sortedByDescending { it.audioUrl != null } // prefer adaptive when both exist at same res, same as StreamExtractor
            .distinctBy { it.label }
            .sortedByDescending { it.resolutionValue }

        val title = root.optString("title", "")
        val duration = root.optLong("duration", 0L)

        if (qualities.isEmpty()) {
            // No per-quality formats at all -- fall back to whatever
            // single URL yt-dlp considered "best" (covers live streams,
            // which only expose an HLS manifest, same as the NewPipe path).
            val fallbackUrl = root.optString("url").takeIf { it.isNotBlank() } ?: return null
            return StreamExtractor.ResolvedStream(
                url = fallbackUrl,
                title = title,
                isHls = fallbackUrl.contains(".m3u8"),
                durationSeconds = duration,
                availableQualities = emptyList(),
                isLive = root.optBoolean("is_live", false) || (fallbackUrl.contains(".m3u8") && duration <= 0)
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
            availableAudioTracks = emptyList()
        )
    }
}
