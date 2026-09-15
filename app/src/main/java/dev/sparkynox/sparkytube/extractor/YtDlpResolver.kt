package dev.sparkynox.sparkytube.extractor

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg

/**
 * Last-resort fallback: yt-dlp via youtubedl-android. Heaviest option
 * (bundles a Python runtime + yt-dlp itself, ~30-40MB added to the APK)
 * but the most reliable against age-gates/region locks/anything YouTube
 * throws at it, since yt-dlp gets fixed for YouTube-side changes fastest
 * of any extractor out there.
 *
 * Only reached when both NewPipeExtractor AND the Piped instances have
 * already failed (see StreamExtractor.doResolve) -- this is deliberately
 * the last thing tried since it's the slowest (spins up a Python
 * process per call) and heaviest on battery/CPU.
 */
object YtDlpResolver {

    @Volatile
    private var initialized = false
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

    /**
     * Must be called once (e.g. from Application.onCreate or lazily here)
     * before resolve() will work -- youtubedl-android needs to unpack its
     * bundled Python/yt-dlp binaries to app-private storage on first run.
     * Safe to call repeatedly; only does real work once per process.
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
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
            // -f best: single best progressive-ish format yt-dlp picks,
            // simplest path for a fallback that just needs SOMETHING
            // playable rather than a full quality ladder like the other
            // two paths build. -j gets structured info instead of just
            // triggering a download.
            request.addOption("-f", "best")
            request.addOption("--no-playlist")

            val streamInfo = YoutubeDL.getInstance().getInfo(request)
            val playableUrl = streamInfo.url
            if (playableUrl == null) {
                lastErrorMessage = "yt-dlp returned no playable URL for this video"
                return null
            }

            lastErrorMessage = null // success -- clear any stale error from a previous attempt
            StreamExtractor.ResolvedStream(
                url = playableUrl,
                title = streamInfo.title ?: "",
                isHls = playableUrl.contains(".m3u8"),
                durationSeconds = streamInfo.duration.toLong(),
                availableQualities = emptyList(), // single-format fallback, no picker
                // youtubedl-android's info object doesn't expose a clean
                // isLive flag -- an HLS (.m3u8) result with a duration of
                // 0 is the reliable signal yt-dlp gives for an in-progress
                // live broadcast (a finished/VOD stream always has a real
                // duration even when served as HLS).
                isLive = playableUrl.contains(".m3u8") && streamInfo.duration <= 0,
                defaultAudioUrl = null, // -f best is progressive/pre-muxed
                defaultQualityLabel = null,
                availableAudioTracks = emptyList()
            )
        } catch (t: Throwable) {
            android.util.Log.e("YtDlpResolver", "resolve failed for $videoId", t)
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            dev.sparkynox.sparkytube.logs.LogRecorder.e("YtDlpResolver", "resolve failed for $videoId", t)
            null
        }
    }
}
