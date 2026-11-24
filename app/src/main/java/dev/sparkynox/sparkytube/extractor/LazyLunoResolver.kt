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
    // the TV clients always answered "no streams" without a PO token in testing, so only IOS is worth a try here
    // ANDROID_VR needs neither a PO token nor a login and answers with the whole 144p-4K ladder
    // the direct clients, in EchoMusic's measured order: VISIONOS serves whole files, ANDROID_VR 1.65.10 the full ladder.
    // IOS is not asked any more: its urls are a ~1 MiB preview that googlevideo cuts with a 403
    private val UPGRADE_CLIENTS = listOf("VISIONOS", "ANDROID_VR")

    // googlevideo refused the streams of these videos: no direct clients for 10 minutes, NewPipe covers them
    private val refused = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun onRefused(videoId: String) {
        refused[videoId] = System.currentTimeMillis() + 10 * 60_000
        fastStreams.remove(videoId)
        LogRecorder.i(TAG, "googlevideo refused the streams of $videoId, direct clients skipped for 10 min")
    }

    private fun isRefused(videoId: String) = (refused[videoId] ?: 0L) > System.currentTimeMillis()

    private fun visitorFor(ctx: Context): String =
        dev.sparkynox.sparkytube.auth.Session.visitorData ?: PoTokenService.visitorDataBlocking(ctx) ?: ""

    /** Called a moment after app start: fetches visitorData and starts the PO token, so the first video does not wait for them. */
    fun prewarm(ctx: Context) {
        Thread {
            try { visitorFor(ctx) } catch (_: Throwable) { }
            PoTokenService.warm(ctx)
        }.start()
    }

    // live broadcasts only answer with an HLS manifest, and the first client that has one is IOS
    private const val LIVE_CLIENTS = "IOS,ANDROID_VR,TVHTML5,MWEB"
    private val liveIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** Called by the feed when a card says LIVE, so the resolve can skip the ANDROID try that never has an HLS manifest. */
    fun markLive(videoId: String) {
        liveIds.add(videoId)
        if (liveIds.size > 200) synchronized(liveIds) { liveIds.remove(liveIds.first()) }
    }

    /** Blocking. Null = failed, see lastErrorMessage. */
    fun resolve(ctx: Context, videoId: String, clients: String = ""): StreamExtractor.ResolvedStream? {
        if (!LazyLuno.available()) {
            lastErrorMessage = "liblazyluno.so didn't load on this device"
            return null
        }
        val t0 = System.currentTimeMillis()
        // the logged-in YouTube session, only used by LazyLuno's auth fallback
        val cookie = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull().orEmpty()
        // a video that already showed an age gate goes straight to the signed in clients
        val known18 = dev.sparkynox.sparkytube.history.AgeRestrictedStore.has(ctx, videoId)
        val useClients = if (clients.isEmpty() && known18 && cookie.isNotEmpty()) "AGE" else clients
        // age-restricted: the TV clients want a PO token, a fresh one for this video (a few seconds, only for these)
        var pot: String? = null
        var vd = ""
        var playerTok = ""
        if (useClients == "AGE") {
            PoTokenService.forVideo(ctx, videoId)?.let { (v, pt, gvs) -> vd = v; playerTok = pt; pot = gvs }
            LogRecorder.i(TAG, "age-restricted $videoId, PO token ${if (pot != null) "ready" else "not available"}")
        }
        val raw = try {
            LazyLuno.resolveJson(videoId, cookie, "en", Locale.getDefault().country.ifBlank { "US" }, useClients, vd, playerTok)
        } catch (t: Throwable) {
            lastErrorMessage = "${t.javaClass.simpleName}: ${t.message}"
            LogRecorder.e(TAG, "native call failed", t)
            return null
        } ?: return null

        val o = try { JSONObject(raw) } catch (e: Exception) {
            lastErrorMessage = "unreadable answer from the native side"
            return null
        }
        if (o.optBoolean("age")) dev.sparkynox.sparkytube.history.AgeRestrictedStore.mark(ctx, videoId)
        if (!o.optBoolean("ok")) {
            lastErrorMessage = o.optString("error", "unknown error")
            if (o.optBoolean("age") && cookie.isEmpty()) {
                lastErrorMessage = "This video is age-restricted. Sign in with an adult account (Home tab) to watch it."
            }
            // age-restricted and the native clients found nothing: read the signed in MWEB answer ourselves
            if (useClients == "AGE" && cookie.isNotEmpty()) ageViaKotlin(ctx, videoId, playerTok, pot)?.let { return it }
            LogRecorder.w(TAG, "failed in ${System.currentTimeMillis() - t0} ms: $lastErrorMessage")
            return null
        }
        val streams = readStreams(o, pot)
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

    /**
     * Age-restricted videos, second try: the same /player answer the history code already gets (signed in, MWEB, with the
     * video's PO token), read here. Whatever it holds is logged (status, how many formats have a plain url, how many are
     * ciphered, whether it is SABR only), so the next log shows exactly what is in the way.
     */
    private fun ageViaKotlin(ctx: Context, videoId: String, playerTok: String, pot: String?): StreamExtractor.ResolvedStream? {
        for ((client, id) in listOf("MWEB" to "2", "WEB" to "1")) {
            try {
                val body = JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
                if (playerTok.isNotEmpty()) body.put("serviceIntegrityDimensions", JSONObject().put("poToken", playerTok))
                val root = dev.sparkynox.sparkytube.homefeed.InnerTubeClient.post("player", body, client, id)
                val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                val sd = root.optJSONObject("streamingData")
                val muxed = sd?.optJSONArray("formats")
                val adaptive = sd?.optJSONArray("adaptiveFormats")
                val all = ArrayList<S>()
                var withUrl = 0
                var ciphered = 0
                fun scan(a: org.json.JSONArray?, isMuxed: Boolean) {
                    if (a == null) return
                    for (i in 0 until a.length()) {
                        val f = a.getJSONObject(i)
                        val url = f.optString("url")
                        if (url.isEmpty()) { if (f.has("signatureCipher") || f.has("cipher")) ciphered++; continue }
                        withUrl++
                        val mime = f.optString("mimeType")
                        val kind = if (isMuxed) "muxed" else if (mime.startsWith("audio")) "audio" else "video"
                        all += S(withPot(url, pot), mime, f.optInt("height"), f.optLong("bitrate"), kind)
                    }
                }
                scan(muxed, true)
                scan(adaptive, false)
                val hls = sd?.optString("hlsManifestUrl").orEmpty()
                LogRecorder.i(TAG, "age fallback $client: status=$status, formats=${muxed?.length() ?: 0}+${adaptive?.length() ?: 0}, " +
                    "plain url=$withUrl, ciphered=$ciphered, sabr=${sd?.has("serverAbrStreamingUrl") == true}, hls=${hls.isNotEmpty()}")
                if (all.isEmpty() && hls.isEmpty()) continue
                val d = root.optJSONObject("videoDetails")
                build(
                    ctx, d?.optString("title").orEmpty(), d?.optString("lengthSeconds")?.toLongOrNull() ?: 0L,
                    d?.optBoolean("isLive") == true, hls, all,
                )?.let { return it }
            } catch (e: Exception) {
                LogRecorder.w(TAG, "age fallback $client failed: ${e.message}")
            }
        }
        return null
    }

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
        // live broadcast: the HLS manifest is the stream, ExoPlayer follows the live edge by itself
        if ((all.isEmpty() || live) && hls.isNotEmpty()) {
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
        if (videoId in liveIds) {
            resolve(ctx, videoId, LIVE_CLIENTS)?.let { return it }
            return resolve(ctx, videoId, "")
        }
        val fast = resolve(ctx, videoId, "ANDROID_APP")
        if (fast != null) {
            PoTokenService.warm(ctx) // no wait, the token is for the upgrade of this or the next video
            val dataSaver = SettingsPrefs.isDataSaverEnabled(ctx)
            val best = fast.availableQualities.maxOfOrNull { it.resolutionValue } ?: 0
            return if (!dataSaver && best < MAX_WANTED && fastStreams.containsKey(videoId)) fast.copy(partial = true) else fast
        }
        // ANDROID gave nothing, run the whole cascade
        return resolve(ctx, videoId, "")?.also { if (it.isLive) markLive(videoId) }
    }

    private const val MAX_WANTED = 1080

    // from this length on playback starts from the adaptive streams (see resolveWithFallback)
    private const val LONG_VIDEO_SECONDS = 15 * 60L

    /** Blocking, run off the main thread. Other clients in parallel, merged with the fast pass. Null = nothing better. */
    fun upgrade(ctx: Context, videoId: String, first: StreamExtractor.ResolvedStream): StreamExtractor.ResolvedStream? {
        val base = fastStreams.remove(videoId) ?: return null
        if (isRefused(videoId)) return null
        val vd = visitorFor(ctx)
        val cookie = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull().orEmpty()
        val country = Locale.getDefault().country.ifBlank { "US" }
        val t0 = System.currentTimeMillis()
        val jobs = UPGRADE_CLIENTS.map { c ->
            upgradePool.submit<List<S>> {
                try {
                    val raw = LazyLuno.resolveJson(videoId, cookie, "en", country, c, vd) ?: return@submit emptyList()
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
                for (c in listOf("ANDROID_APP")) {
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
        var own = resolveFast(ctx, videoId)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        if (own != null && own.partial && own.durationSeconds >= LONG_VIDEO_SECONDS) {
            // The quick ANDROID answer is a plain progressive mp4 whose index (the moov box) lists every frame of the video:
            // a 2 h video makes that several MB the player has to read before the first frame, a 10 h one minutes.
            // VISIONOS / ANDROID_VR answer with fragmented mp4 / webm that has a tiny index at the start, so for long videos
            // the upgrade (about 350 ms) is waited for and the video starts from those.
            val up = upgrade(ctx, videoId, own)
            if (up != null) {
                LogRecorder.i("Resolve", "long video (${own.durationSeconds / 60} min): starting from the adaptive streams, ${own.availableQualities.size} -> ${up.availableQualities.size} qualities")
                own = up
            }
        }
        if (own != null) {
            StreamExtractor.cacheResolved(videoId, own)
            return own
        }
        val pipe = StreamExtractor.resolvePlayableUrl(videoId)
        LogRecorder.i("Resolve", "LazyLuno failed after $ms ms, NewPipe fallback got=${pipe != null} ($videoId)")
        return pipe
    }
}
