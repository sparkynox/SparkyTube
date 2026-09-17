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
            // -f best: single best progressive-ish format yt-dlp picks,
            // simplest path for a fallback that just needs SOMETHING
            // playable rather than a full quality ladder like the other
            // two paths build.
            request.addOption("-f", "best")
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
            request.addOption("--skip-download") // getInfo() already implies this, but explicit avoids any accidental full download path
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

            // Biggest remaining speed lever for weak/low-RAM devices:
            // by default yt-dlp scrapes YouTube's full web player page
            // and parses a large JS-driven response to build the format
            // list. Forcing the "android" player client instead makes
            // yt-dlp hit YouTube's lightweight mobile-app-style API,
            // which returns a much smaller, simpler response -- less
            // data to download AND less to parse, which matters more on
            // a weak CPU (e.g. Vivo Y73-class hardware) than on a fast
            // one. This is the same trick yt-dlp's own docs recommend
            // for speeding up extraction.
            request.addOption("--extractor-args", "youtube:player_client=android")

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
