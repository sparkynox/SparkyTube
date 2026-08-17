package dev.sparkynox.sparkytube.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
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

    // Tracks extractions currently in progress so a pre-cache call and a
    // real "play this now" call for the same video ID share one network
    // round-trip instead of firing two concurrent extractions.
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ResolvedStream?>>()

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
    suspend fun resolvePlayableUrl(videoId: String): ResolvedStream? = coroutineScope {
        cache[videoId]?.let { (timestamp, cached) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                return@coroutineScope cached
            } else {
                cache.remove(videoId)
            }
        }

        // Join an already-running extraction for this exact video instead
        // of starting a second one (this is what makes pre-caching safe to
        // call speculatively without wasting a duplicate network request
        // if playback catches up to it).
        inFlight[videoId]?.let { return@coroutineScope it.await() }

        val deferred = async(Dispatchers.IO) { doResolve(videoId) }
        inFlight[videoId] = deferred
        try {
            deferred.await()
        } finally {
            inFlight.remove(videoId)
        }
    }

    /**
     * Speculatively resolves a video in the background without anyone
     * waiting on the result — the point is purely to warm the cache so
     * that when resolvePlayableUrl() is called "for real" later (once the
     * current video actually ends), it's a cache hit instead of a fresh
     * network round-trip. That round-trip was the source of the 1-3s
     * autoplay stall Sparky reported.
     *
     * Safe to call repeatedly with a changing predicted video ID — if the
     * prediction changes (e.g. the related-videos list reshuffles) before
     * the old one finishes, both just populate the cache; the short TTL
     * means an unused pre-cached entry simply expires on its own rather
     * than needing explicit cleanup.
     */
    fun prefetch(videoId: String, scope: kotlinx.coroutines.CoroutineScope) {
        if (cache.containsKey(videoId) || inFlight.containsKey(videoId)) return
        scope.launch(Dispatchers.IO) {
            resolvePlayableUrl(videoId)
        }
    }

    /**
     * Drops a cached entry even though it's still within TTL — used when
     * ExoPlayer reports a playback error for a stream that resolvePlayableUrl
     * just handed back, since that means the cached URL itself has gone bad
     * (throttled/expired signature) despite looking fresh by the clock.
     * Without this, retrying resolvePlayableUrl for the same video within
     * the 5-minute window would just serve the identical broken URL back
     * out of cache instead of doing a real re-extraction.
     */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /**
     * Builds a ResolvedStream straight from the JSON __sparkyGetFastStreamData
     * (injected.js) hands back -- no NewPipe involved at all. Caller
     * (MainActivity) still owns the cache write since this function doesn't
     * know the videoId; caching happens the same way as the NewPipe path.
     * Returns null on any parse issue, which the caller treats exactly like
     * "fast path didn't have it" and falls through to doResolve().
     */
    fun parseFastStreamData(json: String): ResolvedStream? {
        return try {
            val obj = org.json.JSONObject(json)
            val qualitiesJson = obj.optJSONArray("qualities") ?: return null
            val qualities = (0 until qualitiesJson.length()).mapNotNull { i ->
                val q = qualitiesJson.optJSONObject(i) ?: return@mapNotNull null
                val url = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QualityOption(
                    label = q.optString("label"),
                    url = url,
                    resolutionValue = q.optInt("resolutionValue"),
                    audioUrl = q.optString("audioUrl").takeIf { it.isNotBlank() && it != "null" }
                )
            }
            if (qualities.isEmpty()) return null

            val defaultQuality = qualities.minByOrNull { kotlin.math.abs(it.resolutionValue - 360) }
                ?: qualities.first()

            ResolvedStream(
                url = defaultQuality.url,
                title = obj.optString("title"),
                isHls = false,
                durationSeconds = obj.optLong("durationSeconds"),
                availableQualities = qualities,
                isLive = false,
                defaultAudioUrl = defaultQuality.audioUrl,
                defaultQualityLabel = defaultQuality.label
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stores a stream in the same short-lived cache doResolve() uses, so a
     * fast-path result behaves identically to a NewPipe-resolved one for
     * things like the 5-minute reuse window on back/forward navigation.
     */
    fun cacheResolved(videoId: String, resolved: ResolvedStream) {
        if (cache.size >= CACHE_MAX_ENTRIES) {
            cache.remove(cache.keys.first())
        }
        cache[videoId] = System.currentTimeMillis() to resolved
    }

    /**
     * Resolves via a local server (Lumi Fetcher, or a future
     * SparkyTube-Server backend) instead of NewPipeExtractor or the
     * in-app JS fast path. Hits GET {baseUrl}/extract/{videoId} — see
     * /sparkytube-server/server.js for the response shape this parses.
     * Same short timeout philosophy as the rest of this file: a local
     * server that isn't actually running should fail fast so the caller
     * falls through to NewPipe rather than hanging.
     *
     * Only handles non-live videos, same as parseFastStreamData -- a
     * live isLive:true response has no qualities to build a
     * ResolvedStream from, so it's treated as a miss here and the caller
     * falls back to NewPipeExtractor, which already has real live-stream
     * handling (HLS manifest path in doResolve()).
     */
    suspend fun resolveViaLocalServer(videoId: String, baseUrl: String, apiKey: String): ResolvedStream? = withContext(Dispatchers.IO) {
        val tag = "SparkyLocalServer"
        try {
            val trimmedBase = baseUrl.trim().trimEnd('/')
            if (trimmedBase.isEmpty()) {
                android.util.Log.w(tag, "resolveViaLocalServer: baseUrl is empty, skipping")
                return@withContext null
            }

            val fullUrl = "$trimmedBase/extract/$videoId"
            android.util.Log.d(tag, "requesting $fullUrl (apiKey set: ${apiKey.isNotBlank()})")

            val conn = URL(fullUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("x-api-key", apiKey)
            }

            val code = conn.responseCode
            android.util.Log.d(tag, "response code: $code")
            if (code !in 200..299) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
                android.util.Log.w(tag, "non-2xx response: $code, body: $errorBody")
                conn.disconnect()
                return@withContext null
            }

            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()

            val obj = org.json.JSONObject(body)
            if (obj.optBoolean("isLive")) return@withContext null

            val qualitiesJson = obj.optJSONArray("qualities") ?: return@withContext null
            val qualities = (0 until qualitiesJson.length()).mapNotNull { i ->
                val q = qualitiesJson.optJSONObject(i) ?: return@mapNotNull null
                val url = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QualityOption(
                    label = q.optString("label"),
                    url = url,
                    resolutionValue = q.optInt("resolutionValue"),
                    audioUrl = q.optString("audioUrl").takeIf { it.isNotBlank() && it != "null" }
                )
            }
            if (qualities.isEmpty()) return@withContext null

            val defaultQuality = qualities.minByOrNull { kotlin.math.abs(it.resolutionValue - 360) }
                ?: qualities.first()

            val result = ResolvedStream(
                url = defaultQuality.url,
                title = obj.optString("title"),
                isHls = false,
                durationSeconds = obj.optLong("durationSeconds"),
                availableQualities = qualities,
                isLive = false,
                defaultAudioUrl = defaultQuality.audioUrl,
                defaultQualityLabel = defaultQuality.label
            )
            cacheResolved(videoId, result)
            result
        } catch (e: Exception) {
            android.util.Log.e(tag, "resolveViaLocalServer failed with exception", e)
            null
        }
    }

    /**
     * One entry in the related-videos/suggestions panel. Deliberately
     * lightweight — title, channel, duration, thumbnail URL, and the
     * video ID to resolve/play if tapped — not a full StreamInfoItem,
     * since the panel only ever needs to show a row and hand off a
     * video ID on tap.
     */
    data class RelatedVideo(
        val videoId: String,
        val title: String,
        val uploaderName: String,
        val durationSeconds: Long,
        val thumbnailUrl: String?
    )

    /**
     * Fetches related/suggested videos for the given video — this is the
     * getRelatedItems() call that tryNewPipe() deliberately skips (see the
     * comment there) to keep normal playback resolves fast. Only called
     * on demand when the user actually opens the suggestions panel, so it
     * doesn't cost anything for people who never open it.
     */
    suspend fun fetchRelatedVideos(videoId: String): List<RelatedVideo> = withContext(Dispatchers.IO) {
        try {
            init()
            val url = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(url)
            extractor.fetchPage()

            val related = extractor.relatedItems ?: return@withContext emptyList()
            related.items.mapNotNull { item ->
                val itemUrl = item.url ?: return@mapNotNull null
                val relatedVideoId = extractRelatedVideoId(itemUrl) ?: return@mapNotNull null
                val duration = (item as? org.schabi.newpipe.extractor.stream.StreamInfoItem)?.duration ?: 0L
                val uploader = (item as? org.schabi.newpipe.extractor.stream.StreamInfoItem)?.uploaderName ?: ""
                RelatedVideo(
                    videoId = relatedVideoId,
                    title = item.name ?: "",
                    uploaderName = uploader,
                    durationSeconds = duration,
                    thumbnailUrl = item.thumbnails.firstOrNull()?.url
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractRelatedVideoId(url: String): String? {
        val match = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(url)
        return match?.groupValues?.get(1)
    }

    private suspend fun doResolve(videoId: String): ResolvedStream? {
        val newPipeResult = tryNewPipe(videoId)
        if (newPipeResult != null) return newPipeResult

        // NewPipeExtractor failed (all retries exhausted, or found zero
        // usable streams) — try the personal yt-dlp-backed fallback server
        // before giving up entirely. No-op (returns null immediately) if
        // ServerFallbackResolver hasn't been configured with a URL/key
        // yet, so this is safe even before that's set up.
        val fallback = ServerFallbackResolver.resolve(videoId)
        if (fallback != null) {
            if (cache.size >= CACHE_MAX_ENTRIES) {
                cache.remove(cache.keys.first())
            }
            cache[videoId] = System.currentTimeMillis() to fallback
        }
        return fallback
    }

    private suspend fun tryNewPipe(videoId: String): ResolvedStream? {
        init()
        val maxAttempts = 5
        repeat(maxAttempts) { attempt ->
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                // Lower-level API instead of StreamInfo.getInfo(): that
                // convenience wrapper does THREE extraction passes
                // (extractImportantData -> extractStreams ->
                // extractOptionalData), and the third pass fetches related
                // videos, comment-panel meta-info, etc. that this app never
                // uses. Going straight to the StreamExtractor and calling
                // fetchPage() + the specific getters we need skips that
                // whole third pass — same stream URLs, less network/parse
                // work, faster perceived load time.
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                val streamName = extractor.name
                val streamDuration = extractor.length
                val streamType = extractor.streamType
                val streamHlsUrl = extractor.hlsUrl

                // Pick the original-language audio track by default (not
                // just the highest bitrate one) — multi-dub videos can have
                // a dub encoded at a higher bitrate than the original,
                // which was causing the wrong language to auto-play.
                // AudioTrackType.ORIGINAL is NewPipeExtractor's own signal
                // for "this is the video's native audio track, not a dub."
                val allAudioStreams = extractor.audioStreams ?: emptyList()
                val urlAudioStreams = allAudioStreams.filter { it.isUrl }
                val originalAudioStreams = urlAudioStreams.filter {
                    it.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
                }
                val bestAudioUrl = (originalAudioStreams.ifEmpty { urlAudioStreams })
                    .maxByOrNull { it.averageBitrate }
                    ?.content

                // .content + isUrl() is the current NewPipeExtractor API
                // (.url is deprecated and only wraps this same check).
                // Progressive streams (video+audio combined) exist, but
                // YouTube has capped these at 360p since ~2015 — anything
                // above that only comes from adaptive/DASH video-only
                // streams, which is why 480p-1080p+ never showed up when
                // only progressive streams were considered.
                val progressiveOptions = extractor.videoStreams
                    .filter { it.isUrl }
                    .mapNotNull { stream ->
                        val res = stream.getResolution() ?: return@mapNotNull null
                        val resNum = Regex("""^(\d+)p""").find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        QualityOption(label = res, url = stream.content, resolutionValue = resNum, audioUrl = null)
                    }

                val allVideoOnlyStreams = extractor.videoOnlyStreams ?: emptyList()
                val adaptiveOptions = if (bestAudioUrl != null) {
                    // Only build adaptive options when we actually have an
                    // audio track to mux — an adaptive stream with a null
                    // audioUrl would get misread downstream as "already has
                    // audio" (that's what null means for progressive
                    // streams), silently producing a silent video.
                    allVideoOnlyStreams
                        .filter { it.isUrl }
                        .mapNotNull { stream ->
                            val res = stream.getResolution() ?: return@mapNotNull null
                            val resNum = Regex("""^(\d+)p""").find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            QualityOption(label = res, url = stream.content, resolutionValue = resNum, audioUrl = bestAudioUrl)
                        }
                } else {
                    emptyList()
                }

                android.util.Log.d(
                    "StreamExtractor",
                    "videoId=$videoId progressiveStreams=${progressiveOptions.size} " +
                    "videoOnlyStreams=${allVideoOnlyStreams.size} audioStreams=${allAudioStreams.size} " +
                    "bestAudioUrl=${bestAudioUrl != null} -> progressiveOptions=${progressiveOptions.size} adaptiveOptions=${adaptiveOptions.size}"
                )

                // One entry per distinct language track (picking the best
                // bitrate variant of each), for the manual audio-track
                // picker. Only meaningful for adaptive/multi-dub videos —
                // progressive streams have audio baked into the video
                // itself with nothing separate to switch.
                val audioTrackOptions = urlAudioStreams
                    .groupBy { it.audioLocale?.displayName ?: it.audioTrackId ?: "default" }
                    .map { (label, streams) ->
                        val best = streams.maxByOrNull { it.averageBitrate }!!
                        val isOriginal = best.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
                        AudioTrackOption(
                            label = if (isOriginal) "$label (original)" else label,
                            url = best.content,
                            isOriginal = isOriginal
                        )
                    }
                    .sortedByDescending { it.isOriginal }

                val qualities = (progressiveOptions + adaptiveOptions)
                    // Prefer the adaptive version of a resolution when both
                    // exist (adaptive is almost always better encoded), so
                    // sort adaptive first within the same label before
                    // distinctBy keeps the first occurrence.
                    .sortedByDescending { it.audioUrl != null }
                    .distinctBy { it.label }
                    .sortedByDescending { it.resolutionValue }

                val isLiveStream = streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM ||
                    streamType == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM

                val result = if (qualities.isNotEmpty()) {
                    // Default playback quality is 360p specifically (not the
                    // highest available, which could be 4K/8K on adaptive
                    // streams and burn a lot of data by default) — the full
                    // quality list is untouched, this only picks which entry
                    // auto-plays first. Falls back to the closest available
                    // resolution if this particular video doesn't have 360p.
                    val defaultQuality = qualities.minByOrNull { kotlin.math.abs(it.resolutionValue - 360) }
                        ?: qualities.first()
                    ResolvedStream(
                        url = defaultQuality.url,
                        title = streamName ?: "",
                        isHls = false,
                        durationSeconds = streamDuration,
                        availableQualities = qualities,
                        isLive = isLiveStream,
                        defaultAudioUrl = defaultQuality.audioUrl,
                        defaultQualityLabel = defaultQuality.label,
                        availableAudioTracks = if (audioTrackOptions.size > 1) audioTrackOptions else emptyList()
                    )
                } else {
                    // Fallback: HLS manifest if YouTube only offered adaptive/live
                    // streams (no per-quality picker possible here — HLS master
                    // playlist handles adaptive switching internally, which
                    // ExoPlayer follows automatically). This is also where actual
                    // live broadcasts land, since NewPipeExtractor exposes no
                    // progressive/adaptive video streams for those, only the HLS
                    // manifest.
                    val hlsUrl = streamHlsUrl
                    if (!hlsUrl.isNullOrEmpty()) {
                        ResolvedStream(
                            url = hlsUrl,
                            title = streamName ?: "",
                            isHls = true,
                            durationSeconds = streamDuration,
                            availableQualities = emptyList(),
                            isLive = isLiveStream
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
                    return result
                }
                // NewPipe parsed the page but found no usable streams at
                // all — rare, and retrying won't change that.
                return null
            } catch (e: Exception) {
                // Exponential backoff across up to 5 attempts — covers
                // transient mobile-network hiccups (timeout, connection
                // reset, brief connectivity loss) that a single short retry
                // wouldn't survive. Only the final attempt failing surfaces
                // as a real error to the user (age-gated, extractor needs
                // updating, etc.).
                if (attempt < maxAttempts - 1) {
                    val backoffMs = 200L * (attempt + 1)
                    kotlinx.coroutines.delay(backoffMs)
                }
            }
        }
        return null
    }

    data class ResolvedStream(
        val url: String,
        val title: String,
        val isHls: Boolean,
        val durationSeconds: Long,
        val availableQualities: List<QualityOption> = emptyList(),
        // True for an actual live broadcast (info.streamType == LIVE_STREAM),
        // not just "happens to be HLS" -- YouTube also serves HLS for some
        // ended/VOD content. Live streams skip native ExoPlayer takeover
        // entirely and stay on the WebView's own player (see MainActivity),
        // since the muted-background-WebView + visible-ExoPlayer trick used
        // for regular videos doesn't make sense for a live broadcast.
        val isLive: Boolean = false,
        // Audio URL to mux alongside the default quality's video-only URL.
        // Null when `url` is already a progressive (video+audio combined)
        // stream or an HLS manifest — only set when the default pick
        // is an adaptive video-only stream.
        val defaultAudioUrl: String? = null,
        // Which entry in availableQualities was actually picked as default
        // (360p when available — see doResolve) — MainActivity uses this to
        // correctly highlight the current selection in the quality picker
        // instead of assuming it's always the first/highest entry.
        val defaultQualityLabel: String? = null,
        // All available audio tracks for videos with multiple language
        // dubs, so the user can manually switch away from the
        // originally-selected (default: original-language) track.
        // Empty for progressive-only videos, since those bundle audio
        // into the video stream itself with no separate track to pick.
        val availableAudioTracks: List<AudioTrackOption> = emptyList()
    )

    data class QualityOption(
        val label: String,      // e.g. "1080p", "720p", "360p"
        val url: String,
        val resolutionValue: Int, // numeric part, for sorting
        // Progressive streams (YouTube caps these at 360p) already contain
        // audio, so this is null for those. Adaptive/DASH streams (the only
        // way to get 480p and above) are video-only and need this audio
        // track muxed in separately by ExoPlayer at playback time.
        val audioUrl: String? = null
    )

    data class AudioTrackOption(
        val label: String,   // e.g. "English (original)", "Hindi", or "Track 1" if unnamed
        val url: String,
        val isOriginal: Boolean
    )
}

/** Minimal blocking Downloader impl required by NewPipeExtractor. */
private object SparkyDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.requestMethod = request.httpMethod()
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

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
