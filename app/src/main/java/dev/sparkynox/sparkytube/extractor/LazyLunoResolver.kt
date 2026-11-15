package dev.sparkynox.sparkytube.extractor

import android.content.Context
import android.webkit.CookieManager
import dev.sparkynox.sparkytube.logs.LogRecorder
import dev.sparkynox.sparkytube.settings.SettingsPrefs
import org.json.JSONObject
import java.util.Locale

// LazyLuno (beta) -> ResolvedStream. the json comes from lazyluno_jni.cpp
object LazyLunoResolver {

    private const val TAG = "LazyLuno"

    @Volatile
    var lastErrorMessage: String? = null
        private set

    // streams of the fast pass, kept for a few videos so the background upgrade can merge into them
    private val fastStreams = java.util.concurrent.ConcurrentHashMap<String, List<S>>()
    private val upgradePool = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val UPGRADE_CLIENTS = listOf("IOS", "TV_EMBEDDED", "TVHTML5", "TVHTML5_SIMPLY")

    /** Blocking. Null = failed, see lastErrorMessage. */
    fun resolve(ctx: Context, videoId: String, clients: String = ""): StreamExtractor.ResolvedStream? {
        if (!LazyLuno.available()) {
            lastErrorMessage = "liblazyluno.so didn't load on this device"
            return null
        }
        val t0 = System.currentTimeMillis()
        // the logged-in YouTube session, only used by LazyLuno's auth fallback
        val cookie = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull().orEmpty()
        val raw = try {
            LazyLuno.resolveJson(videoId, cookie, "en", Locale.getDefault().country.ifBlank { "US" }, clients)
        } catch (t: Throwable) {
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message}"
            LogRecorder.e(TAG, "native call failed", t)
            return null
        } ?: return null

        val o = try { JSONObject(raw) } catch (e: Exception) {
            lastErrorMessage = "unreadable answer from the native side"
            return null
        }
        if (!o.optBoolean("ok")) {
            lastErrorMessage = o.optString("error", "unknown error")
            LogRecorder.w(TAG, "failed in ${System.currentTimeMillis() - t0} ms: $lastErrorMessage")
            return null
        }
        val streams = readStreams(o)
        val result = build(ctx, o.optString("title"), o.optLong("durationMs") / 1000, o.optBoolean("live"),
            o.optString("hls"), streams)
        if (result != null && clients.isNotEmpty()) {
            fastStreams[videoId] = streams
            if (fastStreams.size > 6) fastStreams.keys.firstOrNull { it != videoId }?.let { fastStreams.remove(it) }
        }
        if (result == null) {
            lastErrorMessage = "no playable stream in the answer (client ${o.optString("client")})"
            LogRecorder.w(TAG, lastErrorMessage!!)
            return null
        }
        lastErrorMessage = null
        LogRecorder.i(
            TAG, "ok in ${System.currentTimeMillis() - t0} ms, client=${o.optString("client")}, " +
                "qualities: ${result.availableQualities.joinToString { it.label }}"
        )
        return result
    }

    private class S(val url: String, val mime: String, val h: Int, val bitrate: Long, val kind: String)

    private fun withPot(url: String, pot: String?): String =
        if (pot == null || !url.contains("googlevideo.com") || url.contains("pot=")) url
        else url + (if (url.contains('?')) "&" else "?") + "pot=" + android.net.Uri.encode(pot)

    private fun readStreams(o: JSONObject, pot: String? = null): List<S> {
        val arr = o.optJSONArray("streams") ?: return emptyList()
        val all = ArrayList<S>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val url = s.optString("url")
            if (url.isNotEmpty()) all += S(withPot(url, pot), s.optString("mime"), s.optInt("h"), s.optLong("bitrate"), s.optString("kind"))
        }
        return all
    }

    private fun build(
        ctx: Context, title: String, duration: Long, live: Boolean, hls: String, all: List<S>,
        partial: Boolean = false,
    ): StreamExtractor.ResolvedStream? {
        if (all.isEmpty() && hls.isNotEmpty()) {
            return StreamExtractor.ResolvedStream(url = hls, title = title, isHls = true, durationSeconds = duration, isLive = live)
        }

        val audios = all.filter { it.kind == "audio" }
        // m4a muxes cleanly into the download mp4, so it wins over opus when both are there
        val audio = audios.filter { it.mime.contains("mp4") }.maxByOrNull { it.bitrate }
            ?: audios.maxByOrNull { it.bitrate }

        val dataSaver = SettingsPrefs.isDataSaverEnabled(ctx)
        val wanted = StreamExtractor.preferredQualityTarget()
        val cap = if (dataSaver) minOf(wanted, 360) else Int.MAX_VALUE

        val byHeight = LinkedHashMap<Int, StreamExtractor.QualityOption>()
        if (audio != null) {
            // best h264 (mp4) first, it decodes on every phone
            all.filter { it.kind == "video" && it.h > 0 && it.h <= cap }
                .sortedWith(compareByDescending<S> { it.mime.contains("mp4") }.thenByDescending { it.bitrate })
                .forEach { v -> byHeight.putIfAbsent(v.h, StreamExtractor.QualityOption("${v.h}p", v.url, v.h, audio.url)) }
        }
        // muxed ones fill the heights the adaptive list doesn't have
        all.filter { it.kind == "muxed" && it.h > 0 && it.h <= cap }
            .forEach { m -> byHeight.putIfAbsent(m.h, StreamExtractor.QualityOption("${m.h}p", m.url, m.h, null)) }
        if (dataSaver && byHeight.size > 1) {
            val keep = byHeight.values.minByOrNull { kotlin.math.abs(it.resolutionValue - wanted) }!!
            byHeight.clear(); byHeight[keep.resolutionValue] = keep
        }
        if (byHeight.isEmpty()) return null

        val list = byHeight.values.sortedByDescending { it.resolutionValue }
        val def = list.minByOrNull { kotlin.math.abs(it.resolutionValue - wanted) } ?: list.first()

        val downloads = audios.sortedByDescending { it.bitrate }.mapNotNull { a ->
            val kbps = (a.bitrate / 1000).toInt()
            if (kbps <= 0) return@mapNotNull null
            val ext = if (a.mime.contains("webm")) "webm" else "m4a"
            StreamExtractor.DownloadAudioOption("$kbps kbps ($ext)", a.url, ext, kbps)
        }

        return StreamExtractor.ResolvedStream(
            url = def.url, title = title, isHls = false, durationSeconds = duration,
            availableQualities = list, isLive = live,
            defaultAudioUrl = def.audioUrl, defaultQualityLabel = def.label,
            downloadAudios = downloads,
            partial = partial,
        )
    }

    /**
     * Fast pass: ANDROID only (the client that works for us), so playback can start in a second or two.
     * If it hands back less than the wanted quality, the caller starts upgrade() in the background.
     */
    fun resolveFast(ctx: Context, videoId: String): StreamExtractor.ResolvedStream? {
        val fast = resolve(ctx, videoId, "ANDROID_APP")
        if (fast != null) {
            PoTokenService.warm(ctx) // no wait, the token is for the upgrade of this or the next video
            val dataSaver = SettingsPrefs.isDataSaverEnabled(ctx)
            val best = fast.availableQualities.maxOfOrNull { it.resolutionValue } ?: 0
            return if (!dataSaver && best < MAX_WANTED && fastStreams.containsKey(videoId)) fast.copy(partial = true) else fast
        }
        // ANDROID gave nothing, run the whole cascade
        return resolve(ctx, videoId, "")
    }

    private const val MAX_WANTED = 1080

    /** Blocking, run off the main thread. Other clients in parallel, merged with the fast pass. Null = nothing better. */
    fun upgrade(ctx: Context, videoId: String, first: StreamExtractor.ResolvedStream): StreamExtractor.ResolvedStream? {
        val base = fastStreams.remove(videoId) ?: return null
        val cookie = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull().orEmpty()
        val country = Locale.getDefault().country.ifBlank { "US" }
        val t0 = System.currentTimeMillis()
        val jobs = UPGRADE_CLIENTS.map { c ->
            upgradePool.submit<List<S>> {
                try {
                    val raw = LazyLuno.resolveJson(videoId, cookie, "en", country, c) ?: return@submit emptyList()
                    val o = JSONObject(raw)
                    if (o.optBoolean("ok")) readStreams(o).also {
                        LogRecorder.i(TAG, "upgrade $c: ${it.size} streams, tallest ${it.maxOfOrNull { s -> s.h } ?: 0}p")
                    } else {
                        LogRecorder.i(TAG, "upgrade $c: ${o.optString("error").take(120)}")
                        emptyList()
                    }
                } catch (e: Throwable) {
                    emptyList()
                }
            }
        }
        val merged = ArrayList<S>(base)
        for (j in jobs) {
            val remaining = 15_000 - (System.currentTimeMillis() - t0)
            val got = try { j.get(maxOf(remaining, 500), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { emptyList() }
            merged += got
        }
        var result = build(ctx, first.title, first.durationSeconds, first.isLive, "", merged) ?: return null

        // still nothing taller? try again with a PO token if one is ready (never waits for it)
        if ((result.availableQualities.maxOfOrNull { it.resolutionValue } ?: 0) <=
            (first.availableQualities.maxOfOrNull { it.resolutionValue } ?: 0)
        ) {
            val tok = PoTokenService.ready()
            if (tok == null) {
                PoTokenService.warm(ctx)
                LogRecorder.i(TAG, "no PO token yet, started getting one")
            } else {
                val (vd, pot) = tok
                for (c in listOf("ANDROID_APP", "IOS")) {
                    try {
                        val raw = LazyLuno.resolveJson(videoId, cookie, "en", country, c, vd) ?: continue
                        val o = JSONObject(raw)
                        if (!o.optBoolean("ok")) {
                            LogRecorder.i(TAG, "pot $c: ${o.optString("error").take(120)}")
                            continue
                        }
                        val got = readStreams(o, pot)
                        LogRecorder.i(TAG, "pot $c: ${got.size} streams, tallest ${got.maxOfOrNull { it.h } ?: 0}p")
                        merged += got
                    } catch (e: Throwable) {
                        LogRecorder.w(TAG, "pot $c failed: ${e.message}")
                    }
                }
                result = build(ctx, first.title, first.durationSeconds, first.isLive, "", merged) ?: return null
            }
        }
        val before = first.availableQualities.map { it.resolutionValue }.toSet()
        val after = result.availableQualities.map { it.resolutionValue }.toSet()
        LogRecorder.i(TAG, "upgrade done in ${System.currentTimeMillis() - t0} ms: ${before.sorted()} -> ${after.sorted()}")
        if (after.size <= before.size && after.maxOrNull() == before.maxOrNull()) return null
        return result.copy(description = first.description)
    }

    /** LazyLuno first, the normal NewPipe chain if it comes back empty. Blocking. */
    suspend fun resolveWithFallback(ctx: Context, videoId: String): StreamExtractor.ResolvedStream? {
        StreamExtractor.cached(videoId)?.let { return it }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val own = resolveFast(ctx, videoId)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        if (own != null) {
            StreamExtractor.cacheResolved(videoId, own)
            return own
        }
        val pipe = StreamExtractor.resolvePlayableUrl(videoId)
        LogRecorder.i("Resolve", "LazyLuno failed after $ms ms, NewPipe fallback got=${pipe != null} ($videoId)")
        return pipe
    }
}
