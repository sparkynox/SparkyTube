package dev.sparkynox.sparkytube.extractor

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Last-resort fallback for when NewPipeExtractor fails to resolve a video
 * on-device (YouTube changed something NewPipe hasn't caught up to yet).
 * Calls a small personal server (yt-dlp under the hood, see
 * sparkytube-resolver-server/ in the repo) that does the same job
 * server-side. Only used as a fallback — NewPipeExtractor stays the
 * primary path since it needs no server, no key, and no per-request cost.
 *
 * This is a personal, low-traffic server (Render free tier) — not built
 * to handle real load, so it's gated behind a shared secret key rather
 * than real auth. Sleep-mode cold starts (~30-50s) are expected and fine
 * for occasional fallback use.
 */
object ServerFallbackResolver {

    // Fill these in once you've deployed sparkytube-resolver-server (see
    // its README). Left blank means the fallback is a no-op — NewPipe
    // failures just fail the same way they did before this existed,
    // nothing breaks if you haven't set this up yet.
    private const val SERVER_BASE_URL = "" // e.g. "https://sparkytube-resolver.onrender.com"
    private const val SECRET_KEY = "" // must match SPARKYTUBE_SECRET_KEY on the server

    private const val CONNECT_TIMEOUT_MS = 45_000 // generous -- covers a Render free-tier cold start
    private const val READ_TIMEOUT_MS = 20_000

    fun isConfigured(): Boolean = SERVER_BASE_URL.isNotBlank() && SECRET_KEY.isNotBlank()

    /**
     * Returns the same ResolvedStream shape doResolve() produces, or null
     * if the server is unreachable/unconfigured/returns an error — callers
     * treat that identically to a NewPipe failure (surface the existing
     * "can't play this video" error rather than a special server-specific
     * one, since from the user's side there's nothing actionable
     * different about it).
     */
    fun resolve(videoId: String): StreamExtractor.ResolvedStream? {
        if (!isConfigured()) return null

        return try {
            val url = URL("$SERVER_BASE_URL/resolve?v=$videoId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-Api-Key", SECRET_KEY)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            if (connection.responseCode != 200) {
                connection.disconnect()
                return null
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()
            parseResponse(body)
        } catch (e: Exception) {
            // Network error, timeout, server asleep and never woke up in
            // time, malformed response, etc. -- fallback failing is not
            // itself an error worth surfacing differently; the caller
            // already has its own "couldn't play this video" handling.
            null
        }
    }

    private fun parseResponse(body: String): StreamExtractor.ResolvedStream? {
        val json = JSONObject(body)
        val qualitiesJson = json.optJSONArray("qualities") ?: return null
        if (qualitiesJson.length() == 0) return null

        val qualities = mutableListOf<StreamExtractor.QualityOption>()
        for (i in 0 until qualitiesJson.length()) {
            val q = qualitiesJson.getJSONObject(i)
            val hasAudio = q.optBoolean("has_audio", false)
            qualities.add(
                StreamExtractor.QualityOption(
                    label = q.optString("label", "unknown"),
                    url = q.getString("url"),
                    resolutionValue = q.optInt("resolution_value", 0),
                    // Same convention as NewPipe's path: null means this
                    // entry's own url already has audio baked in (progressive);
                    // non-null means it's video-only and needs this audio
                    // track muxed in by the player.
                    audioUrl = if (hasAudio) null else (if (q.isNull("audio_url")) null else q.optString("audio_url"))
                )
            )
        }

        // Same "closest to 360p" default-pick rule doResolve() uses for
        // NewPipe results, so behavior is consistent regardless of which
        // path actually resolved the video.
        val defaultQuality = qualities.minByOrNull { kotlin.math.abs(it.resolutionValue - 360) }
            ?: qualities.first()

        return StreamExtractor.ResolvedStream(
            url = defaultQuality.url,
            title = json.optString("title", ""),
            isHls = false,
            durationSeconds = json.optLong("duration_seconds", 0L),
            availableQualities = qualities,
            isLive = json.optBoolean("is_live", false),
            defaultAudioUrl = defaultQuality.audioUrl,
            defaultQualityLabel = defaultQuality.label,
            availableAudioTracks = emptyList()
        )
    }
}
