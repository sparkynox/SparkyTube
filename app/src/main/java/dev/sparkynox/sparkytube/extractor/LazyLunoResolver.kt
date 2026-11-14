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

    /** Blocking. Null = failed, see lastErrorMessage. */
    fun resolve(ctx: Context, videoId: String): StreamExtractor.ResolvedStream? {
        if (!LazyLuno.available()) {
            lastErrorMessage = "liblazyluno.so didn't load on this device"
            return null
        }
        val t0 = System.currentTimeMillis()
        // the logged-in YouTube session, only used by LazyLuno's auth fallback
        val cookie = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull().orEmpty()
        val raw = try {
            LazyLuno.resolveJson(videoId, cookie, "en", Locale.getDefault().country.ifBlank { "US" })
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
        val result = build(ctx, o)
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

    private fun build(ctx: Context, o: JSONObject): StreamExtractor.ResolvedStream? {
        val title = o.optString("title")
        val duration = o.optLong("durationMs") / 1000
        val live = o.optBoolean("live")
        val arr = o.optJSONArray("streams")
        val all = ArrayList<S>()
        if (arr != null) for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val url = s.optString("url")
            if (url.isNotEmpty()) all += S(url, s.optString("mime"), s.optInt("h"), s.optLong("bitrate"), s.optString("kind"))
        }
        val hls = o.optString("hls")
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
        )
    }

    /** LazyLuno first, the normal NewPipe chain if it comes back empty. Blocking. */
    fun resolveWithFallback(ctx: Context, videoId: String): StreamExtractor.ResolvedStream? {
        StreamExtractor.cached(videoId)?.let { return it }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val own = resolve(ctx, videoId)
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
