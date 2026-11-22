package dev.sparkynox.sparkytube.extractor

import android.content.Context
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// keeps one GVS PO token + the visitorData it is bound to, for LazyLuno.
// warm() runs the BotGuard attestation in the background, ready() is a cheap check that never waits.
// googlevideo wants the token as &pot=... on the stream urls, and the player request has to carry the same visitorData
object PoTokenService {

    private const val TAG = "PoToken"
    private const val VALID_MS = 20 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warming = AtomicBoolean(false)
    private var provider: BotGuardTokenProvider? = null

    @Volatile private var visitorData: String? = null
    @Volatile private var token: String? = null
    @Volatile private var until = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** visitorData to put in the player request + the token for the stream urls, or null if not ready yet. */
    fun ready(): Pair<String, String>? {
        val v = visitorData
        val t = token
        return if (v != null && t != null && System.currentTimeMillis() < until) v to t else null
    }

    /** Starts the attestation if there is no valid token. Returns right away. */
    fun warm(context: Context) {
        if (ready() != null || !warming.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val p = provider ?: BotGuardTokenProvider(ctx).also { provider = it }
                val vd = visitorData ?: loadVisitorData(ctx) ?: run {
                    LogRecorder.w(TAG, "no visitorData, can't make a token")
                    return@launch
                }
                val r = p.getPoToken("jNQXAC9IVRw", vd, null)
                val tok = r?.streamingDataToken
                if (tok.isNullOrEmpty()) {
                    LogRecorder.w(TAG, "attestation gave no token")
                    return@launch
                }
                visitorData = vd
                token = tok
                until = System.currentTimeMillis() + VALID_MS
                LogRecorder.i(TAG, "GVS token ready in ${System.currentTimeMillis() - t0} ms")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                LogRecorder.w(TAG, "warm failed: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                warming.set(false)
            }
        }
    }

    /**
     * Blocking (call from a background thread, waits up to 12 s): visitorData + player token + GVS token for one video.
     * The age-restricted path needs the player token, the streams need the GVS one as &pot=.
     */
    fun forVideo(context: Context, videoId: String): Triple<String, String, String>? = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(12_000) {
            val ctx = context.applicationContext
            val p = provider ?: BotGuardTokenProvider(ctx).also { provider = it }
            val vd = visitorData ?: loadVisitorData(ctx) ?: return@withTimeoutOrNull null
            p.mintPair(videoId, vd)?.let { (player, gvs) -> Triple(vd, player, gvs) }
        }
    }

    /** Blocking: visitorData from the memory / prefs, else one request. ANDROID_VR and VISIONOS refuse a request without it. */
    fun visitorDataBlocking(context: Context): String? = visitorData ?: loadVisitorData(context.applicationContext)

    private fun loadVisitorData(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("sparky_potoken", Context.MODE_PRIVATE)
        prefs.getString("visitor", null)?.let { return it }
        return try {
            val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20250101.00.00","hl":"en","gl":"US"}}}"""
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    LogRecorder.w(TAG, "visitor_id HTTP ${r.code}")
                    return null
                }
                val vd = JSONObject(r.body?.string().orEmpty()).optJSONObject("responseContext")?.optString("visitorData")
                if (vd.isNullOrEmpty()) null else vd.also { prefs.edit().putString("visitor", it).apply() }
            }
        } catch (e: Exception) {
            LogRecorder.w(TAG, "visitor_id failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }
}
