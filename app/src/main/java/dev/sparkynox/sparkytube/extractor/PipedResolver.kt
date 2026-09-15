package dev.sparkynox.sparkytube.extractor

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fallback resolver that queries a public Piped instance's REST API
 * instead of extracting locally. Piped instances run server-side with
 * their own session/auth handling, so they can pull streams for
 * age-restricted videos that anonymous on-device NewPipeExtractor calls
 * get blocked on (YouTube refuses to hand over the real stream data
 * without some form of auth).
 *
 * Multiple instances are tried in order since public Piped instances go
 * down or get rate-limited fairly often -- a single hardcoded instance
 * would make this fallback flaky. First instance that returns a usable
 * response wins.
 */
object PipedResolver {

    // Public instances, roughly ordered by general reliability/uptime.
    // See https://piped-instances.kavin.rocks for the live list if these
    // start dying -- swap in whatever's currently green.
    private val INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.yt",
        "https://pipedapi.r4fo.com"
    )

    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 10000

    /**
     * Returns the same ResolvedStream shape everything else in
     * StreamExtractor uses, or null if every instance failed/returned
     * nothing usable. Caller treats this identically to any other
     * fallback miss.
     */
    suspend fun resolve(videoId: String): StreamExtractor.ResolvedStream? {
        for (base in INSTANCES) {
            val result = tryInstance(base, videoId)
            if (result != null) return result
        }
        return null
    }

    private fun tryInstance(base: String, videoId: String): StreamExtractor.ResolvedStream? {
        return try {
            val url = URL("$base/streams/$videoId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) SparkyTube")

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            parseResponse(body)
        } catch (e: Exception) {
            // This instance is down/timed out/rate-limited -- move on to
            // the next one, don't treat it as a hard failure yet.
            null
        }
    }

    private fun parseResponse(body: String): StreamExtractor.ResolvedStream? {
        val json = JSONObject(body)

        if (json.optBoolean("livestream")) return null

        val videoStreams = json.optJSONArray("videoStreams")
        val audioStreams = json.optJSONArray("audioStreams")

        // Best (highest bitrate) audio-only stream to mux with video-only
        // entries -- Piped separates video/audio the same way YouTube's
        // adaptive streams do above 360p.
        var bestAudioUrl: String? = null
        var bestAudioBitrate = -1
        if (audioStreams != null) {
            for (i in 0 until audioStreams.length()) {
                val a = audioStreams.optJSONObject(i) ?: continue
                val bitrate = a.optInt("bitrate", 0)
                val streamUrl = a.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (bitrate > bestAudioBitrate) {
                    bestAudioBitrate = bitrate
                    bestAudioUrl = streamUrl
                }
            }
        }

        val qualities = mutableListOf<StreamExtractor.QualityOption>()
        if (videoStreams != null) {
            for (i in 0 until videoStreams.length()) {
                val v = videoStreams.optJSONObject(i) ?: continue
                val streamUrl = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                val quality = v.optString("quality", "") // e.g. "720p60" or "360p"
                val resNum = Regex("""^(\d+)p""").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val isProgressive = !v.optBoolean("videoOnly", true)
                qualities.add(
                    StreamExtractor.QualityOption(
                        label = quality.ifBlank { "unknown" },
                        url = streamUrl,
                        resolutionValue = resNum,
                        audioUrl = if (isProgressive) null else bestAudioUrl
                    )
                )
            }
        }

        if (qualities.isEmpty()) return null

        // Same "closest to 360p" default as the rest of the app, and same
        // distinctBy-label dedup preferring the adaptive (audio-muxed)
        // entry when both a progressive and adaptive stream exist at the
        // same label, so behavior matches doResolve()'s NewPipe path.
        val dedupedQualities = qualities
            .sortedByDescending { it.audioUrl != null }
            .distinctBy { it.label }
            .sortedByDescending { it.resolutionValue }

        val defaultQuality = dedupedQualities.minByOrNull { kotlin.math.abs(it.resolutionValue - StreamExtractor.preferredQualityTarget()) }
            ?: dedupedQualities.first()

        return StreamExtractor.ResolvedStream(
            url = defaultQuality.url,
            title = json.optString("title", ""),
            isHls = false,
            durationSeconds = json.optLong("duration", 0L),
            availableQualities = dedupedQualities,
            isLive = false,
            defaultAudioUrl = defaultQuality.audioUrl,
            defaultQualityLabel = defaultQuality.label,
            availableAudioTracks = emptyList()
        )
    }
}
