package dev.sparkynox.sparkytube.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a YouTube video ID into a real, directly-playable media URL that
 * ExoPlayer can load. Uses NewPipeExtractor (TeamNewPipe) instead of a
 * hand-rolled cipher/n-param solver, because YouTube changes those
 * obfuscation routines often — NewPipe is actively maintained against
 * exactly that churn, a boilerplate-local solution would break constantly.
 *
 * NewPipeExtractor needs a Downloader implementation wired in once per
 * process (init() below) — this is the plain HttpURLConnection version,
 * no third-party HTTP client required.
 */
object StreamExtractor {

    private var initialized = false

    // Short-lived cache: avoids a full re-extraction (which involves an HTTP
    // round-trip + page parsing) when the same video is opened again within
    // a few minutes (e.g. going back then forward, or the URL poller firing
    // twice for the same video during a fast page transition). Stream URLs
    // YouTube issues are signed and expire, so this is intentionally short —
    // NOT a permanent cache.
    private val cache = LinkedHashMap<String, Pair<Long, ResolvedStream>>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val CACHE_MAX_ENTRIES = 20

    fun init() {
        if (initialized) return
        NewPipe.init(SparkyDownloader)
        initialized = true
    }

    /**
     * @param videoId the 11-char YouTube video id (parsed out of the
     *                m.youtube.com URL / JsBridge payload on the Kotlin side)
     * @return best-available stream by default, PLUS the full list of
     *         available qualities so the UI can offer a picker. Null if
     *         extraction failed entirely (network issue, extractor needs
     *         updating for a YouTube-side change, private/region-locked video, etc.)
     */
    suspend fun resolvePlayableUrl(videoId: String): ResolvedStream? = withContext(Dispatchers.IO) {
        init()

        cache[videoId]?.let { (timestamp, cached) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                return@withContext cached
            } else {
                cache.remove(videoId)
            }
        }

        repeat(2) { attempt ->
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(ServiceList.YouTube, url)

                // .content + isUrl() is the current NewPipeExtractor API
                // (.url is deprecated and only wraps this same check).
                val progressiveStreams = info.videoStreams.filter { it.isUrl }

                val qualities = progressiveStreams
                    .mapNotNull { stream ->
                        val res = stream.getResolution() ?: return@mapNotNull null
                        // Resolutions look like "1080p", "720p60", "480p" — pull
                        // just the leading number (before "p") for sorting.
                        val resNum = Regex("""^(\d+)p""").find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        QualityOption(label = res, url = stream.content, resolutionValue = resNum)
                    }
                    .distinctBy { it.label }
                    .sortedByDescending { it.resolutionValue }

                val result = if (qualities.isNotEmpty()) {
                    val best = qualities.first()
                    ResolvedStream(
                        url = best.url,
                        title = info.name ?: "",
                        isHls = false,
                        durationSeconds = info.duration,
                        availableQualities = qualities
                    )
                } else {
                    // Fallback: HLS manifest if YouTube only offered adaptive/live
                    // streams (no per-quality picker possible here — HLS master
                    // playlist handles adaptive switching internally, which
                    // ExoPlayer follows automatically).
                    val hlsUrl = info.hlsUrl
                    if (!hlsUrl.isNullOrEmpty()) {
                        ResolvedStream(
                            url = hlsUrl,
                            title = info.name ?: "",
                            isHls = true,
                            durationSeconds = info.duration,
                            availableQualities = emptyList()
                        )
                    } else {
                        null
                    }
                }

                if (result != null) {
                    if (cache.size >= CACHE_MAX_ENTRIES) {
                        cache.remove(cache.keys.first())
                    }
                    cache[videoId] = System.currentTimeMillis() to result
                    return@withContext result
                }
                // No streams at all (rare) — don't bother retrying, this
                // won't change on a second attempt.
                return@withContext null
            } catch (e: Exception) {
                // One retry after a short delay — covers transient mobile-
                // network hiccups (timeout, connection reset), which were a
                // real contributor to intermittent play failures. Second
                // attempt failing means it's a real error (age-gated,
                // extractor needs updating, etc.), not a blip.
                if (attempt == 0) kotlinx.coroutines.delay(700)
            }
        }
        null
    }

    data class ResolvedStream(
        val url: String,
        val title: String,
        val isHls: Boolean,
        val durationSeconds: Long,
        val availableQualities: List<QualityOption> = emptyList()
    )

    data class QualityOption(
        val label: String,      // e.g. "1080p", "720p", "360p"
        val url: String,
        val resolutionValue: Int // numeric part, for sorting
    )
}

/** Minimal blocking Downloader impl required by NewPipeExtractor. */
private object SparkyDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.requestMethod = request.httpMethod()
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        request.headers().forEach { (key, values) ->
            values.forEach { v -> conn.addRequestProperty(key, v) }
        }
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")
        }

        request.dataToSend()?.let { body ->
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }

        val code = conn.responseCode
        val message = conn.responseMessage ?: ""
        val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bodyBytes = bodyStream?.use { it.readBytes() } ?: ByteArray(0)
        val bodyString = String(bodyBytes, Charsets.UTF_8)

        val headers = mutableMapOf<String, MutableList<String>>()
        conn.headerFields?.forEach { (k, v) ->
            if (k != null) headers[k] = v.toMutableList()
        }

        if (code == 429) {
            throw IOException("Rate limited (429) while extracting stream info")
        }

        return Response(code, message, headers, bodyString, conn.url.toString())
    }
}
