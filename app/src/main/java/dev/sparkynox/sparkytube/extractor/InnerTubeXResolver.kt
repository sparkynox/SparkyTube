package dev.sparkynox.sparkytube.extractor

import android.content.Context
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeLocale
import dev.sparkynox.sparkytube.logs.LogRecorder
import dev.sparkynox.sparkytube.settings.SettingsPrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// InnerTubeX (MetrolistGroup) as an extractor. Pure Kotlin, so no Python and no extra process.
// the library gives one video stream per call (best one under maxVideoHeight), so the quality
// list is built by asking for a few heights in parallel, the config + cipher are cached after the first call
object InnerTubeXResolver {

    private const val TAG = "InnerTubeX"
    private val LADDER = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)

    @Volatile
    var lastErrorMessage: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var extractor: InnerTubeExtractor? = null
    private var appContext: Context? = null
    @Volatile
    private var warmStarted = false

    private val logger = InnerTubeLogger { e ->
        when (e.level) {
            InnerTubeLogLevel.DEBUG -> {}
            InnerTubeLogLevel.INFO -> LogRecorder.i(TAG, "${e.tag}: ${e.message}")
            InnerTubeLogLevel.WARN -> LogRecorder.w(TAG, "${e.tag}: ${e.message} ${e.details}")
            InnerTubeLogLevel.ERROR -> LogRecorder.e(TAG, "${e.tag}: ${e.message} ${e.details}")
        }
    }

    // cheap, safe to call on every start. the network warm-up (player config + cipher) runs in the background
    fun init(context: Context) {
        appContext = context.applicationContext
        val ex = getExtractor() ?: return
        if (warmStarted) return
        warmStarted = true
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                ex.prewarm()
                LogRecorder.i(TAG, "warm-up done in ${System.currentTimeMillis() - t0} ms")
            } catch (t: Throwable) {
                LogRecorder.w(TAG, "warm-up failed", t)
            }
        }
    }

    private fun getExtractor(): InnerTubeExtractor? = synchronized(lock) {
        extractor?.let { return it }
        try {
            val http = HttpClient(OkHttp) {
                engine {
                    config {
                        connectTimeout(10, TimeUnit.SECONDS)
                        readTimeout(20, TimeUnit.SECONDS)
                    }
                }
            }
            val country = Locale.getDefault().country.ifBlank { "US" }
            val tube = InnerTube(http, logger = logger)
            tube.locale = YouTubeLocale(gl = country, hl = "en")
            val cipher = YouTubeCipherService(http, logger = logger)
            val parser = YtConfigParserImpl(http, tube, logger = logger, cipherService = cipher)
            InnerTubeExtractor(
                parser, cipher, tube,
                tokenProvider = appContext?.let { BotGuardTokenProvider(it) },
                logger = logger,
            ).also { extractor = it }
        } catch (t: Throwable) {
            LogRecorder.e(TAG, "init failed", t)
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            null
        }
    }

    // prefetch and the real play can ask for the same video together, the second one just waits
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<StreamExtractor.ResolvedStream?>>()

    /** Blocking, call it from an IO thread. Null means it failed, see lastErrorMessage. */
    fun resolve(videoId: String): StreamExtractor.ResolvedStream? {
        val mine = CompletableFuture<StreamExtractor.ResolvedStream?>()
        val running = inFlight.putIfAbsent(videoId, mine)
        if (running != null) {
            return try { running.get(45, TimeUnit.SECONDS) } catch (e: Exception) { null }
        }
        return try {
            runBlocking { resolveNow(videoId) }.also { mine.complete(it) }
        } catch (t: Throwable) {
            mine.complete(null)
            LogRecorder.e(TAG, "resolve crashed for $videoId", t)
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            null
        } finally {
            inFlight.remove(videoId)
        }
    }

    private suspend fun resolveNow(videoId: String): StreamExtractor.ResolvedStream? {
        val ex = getExtractor() ?: return null
        val ctx = appContext
        val dataSaver = ctx?.let { SettingsPrefs.isDataSaverEnabled(it) } ?: false
        val wanted = StreamExtractor.preferredQualityTarget()
        val target = if (dataSaver) minOf(wanted, 360) else wanted
        val t0 = System.currentTimeMillis()

        val first = extract(ex, videoId, target) ?: return null

        // live streams and hls fallbacks come back as one manifest
        if (first.mimeType?.contains("mpegurl", ignoreCase = true) == true) {
            lastErrorMessage = null
            return StreamExtractor.ResolvedStream(
                url = first.videoUrl ?: first.audioUrl,
                title = first.mediaMetadata?.title.orEmpty(),
                isHls = true,
                durationSeconds = first.mediaMetadata?.durationSeconds ?: 0L,
                isLive = first.mediaMetadata?.isLive == true,
            )
        }
        val firstVideo = first.videoUrl
        val firstHeight = first.videoHeight
        if (firstVideo == null || firstHeight == null || first.audioUrl.startsWith("sabr")) {
            lastErrorMessage = "InnerTubeX found no direct video stream for this video"
            return null
        }

        val options = LinkedHashMap<Int, StreamExtractor.QualityOption>()
        options[firstHeight] = option(first, firstHeight)

        if (!dataSaver) {
            val others = first.availableVideoHeights
                .map { h -> LADDER.lastOrNull { it <= h } ?: h }
                .distinct()
                .filter { it != firstHeight && it in LADDER }
            if (others.isNotEmpty()) {
                val extra = withTimeoutOrNull(9_000) {
                    coroutineScope { others.map { h -> async { extract(ex, videoId, h, quiet = true) } }.awaitAll() }
                }.orEmpty()
                for (s in extra) {
                    val h = s?.videoHeight ?: continue
                    if (s.videoUrl != null && !s.audioUrl.startsWith("sabr")) options.putIfAbsent(h, option(s, h))
                }
            }
        }

        val list = options.values.sortedByDescending { it.resolutionValue }
        val default = list.minByOrNull { kotlin.math.abs(it.resolutionValue - wanted) } ?: list.first()
        LogRecorder.i(
            TAG, "ok in ${System.currentTimeMillis() - t0} ms, client=${first.clientName}, " +
                "dataSaver=$dataSaver, qualities: ${list.joinToString { it.label }}"
        )
        lastErrorMessage = null

        return StreamExtractor.ResolvedStream(
            url = default.url,
            title = first.mediaMetadata?.title.orEmpty(),
            isHls = false,
            durationSeconds = first.mediaMetadata?.durationSeconds ?: 0L,
            availableQualities = list,
            isLive = first.mediaMetadata?.isLive == true,
            defaultAudioUrl = default.audioUrl,
            defaultQualityLabel = default.label,
            downloadAudios = listOfNotNull(downloadAudio(first)),
        )
    }

    private suspend fun extract(
        ex: InnerTubeExtractor,
        videoId: String,
        maxHeight: Int,
        quiet: Boolean = false,
    ): ExtractedStream? {
        // sabr can't be played by exoplayer here, so it's switched off. hls stays on for live streams
        val hints = ContentHints(wantVideo = true, maxVideoHeight = maxHeight)
            .withStreamCapabilities(allowHls = true, allowSabr = false, allowBoundedRange = true)
        // m4a first, it muxes cleanly into the download mp4. any audio if that fails
        for (q in listOf(AudioQuality.MP4, AudioQuality.AUTO)) {
            try {
                val s = ex.extract(videoId, hints, audioQuality = q)
                if (s != null) return s
            } catch (e: StreamResolveException) {
                if (!quiet) {
                    lastErrorMessage = "${e.reason}: ${e.message}"
                    val tries = e.diagnostics?.attempts?.joinToString { "${it.clientName}=${it.outcome}" }
                    LogRecorder.w(TAG, "extract failed (${e.reason}) ${tries.orEmpty()}")
                }
                // age gate / unavailable won't change with another audio format
                if (e.reason != StreamResolveException.Reason.NO_PLAYABLE_STREAM) return null
            }
        }
        if (!quiet && lastErrorMessage == null) lastErrorMessage = "InnerTubeX returned no stream"
        return null
    }

    private fun option(s: ExtractedStream, height: Int): StreamExtractor.QualityOption {
        // a muxed stream lists two codecs ("avc1..., mp4a..."), adding the audio url on top would play audio twice
        val muxed = s.videoCodecs?.contains(',') == true
        return StreamExtractor.QualityOption(
            label = "${height}p",
            url = s.videoUrl!!,
            resolutionValue = height,
            audioUrl = if (muxed) null else s.audioUrl,
        )
    }

    private fun downloadAudio(s: ExtractedStream): StreamExtractor.DownloadAudioOption? {
        val mime = s.mimeType ?: return null
        if (s.audioUrl.startsWith("sabr") || !mime.startsWith("audio/")) return null
        val ext = if (mime.contains("webm")) "webm" else "m4a"
        val kbps = (s.bitrate ?: 0) / 1000
        if (kbps <= 0) return null
        return StreamExtractor.DownloadAudioOption("$kbps kbps ($ext)", s.audioUrl, ext, kbps)
    }
}
