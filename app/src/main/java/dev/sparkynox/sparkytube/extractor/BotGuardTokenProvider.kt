package dev.sparkynox.sparkytube.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// PO tokens through BotGuard in a hidden WebView, same idea as NewPipe's PoTokenWebView:
//  1. ask youtube for a BotGuard challenge (jnn/v1/Create)
//  2. run its interpreter in a WebView and take a snapshot
//  3. trade the snapshot for an integrity token (jnn/v1/GenerateIT)
//  4. the integrity token gives a "minter" that signs a content binding (visitorData / videoId) -> PO token
// the WebView is only created when InnerTubeX needs it and is thrown away after 2 idle minutes (RAM).
// token values are never logged
class BotGuardTokenProvider(context: Context) : TokenProvider {

    override val capabilities = TokenProviderCapabilities(
        providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
        usesWebView = true,
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val minted = ConcurrentHashMap<String, Pair<String, Long>>()

    @Volatile private var webView: WebView? = null
    @Volatile private var pageLoaded = CompletableDeferred<Unit>()
    @Volatile private var minterReady = false
    @Volatile private var validUntil = 0L

    private val destroyer = Runnable {
        webView?.destroy()
        webView = null
        minterReady = false
        LogRecorder.i(TAG, "webview released (idle)")
    }

    inner class Bridge {
        @JavascriptInterface
        fun done(id: Int, result: String, error: String) {
            val d = pending.remove(id) ?: return
            if (error.isNotEmpty()) d.completeExceptionally(RuntimeException(error.take(400))) else d.complete(result)
        }
    }

    override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
        return try {
            mutex.withLock {
                try {
                    tokens(videoId, visitorData)
                } catch (e: Exception) {
                    // stale minter or dead webview: one clean retry
                    LogRecorder.w(TAG, "mint failed (${e.message}), retrying with a fresh attestation")
                    reset()
                    tokens(videoId, visitorData)
                }
            }
        } catch (e: Exception) {
            LogRecorder.w(TAG, "po token unavailable: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            scheduleIdleRelease()
        }
    }

    override suspend fun prewarm(cookie: String?) {
        try {
            mutex.withLock { ensureReady() }
        } catch (e: Exception) {
            LogRecorder.w(TAG, "prewarm failed: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            scheduleIdleRelease()
        }
    }

    override suspend fun invalidateAttestation() {
        mutex.withLock { reset() }
    }

    override suspend fun close() {
        mutex.withLock { reset() }
    }

    private suspend fun reset() {
        minterReady = false
        validUntil = 0
        minted.clear()
        withContext(Dispatchers.Main) {
            main.removeCallbacks(destroyer)
            webView?.destroy()
            webView = null
        }
    }

    private suspend fun tokens(videoId: String, visitorData: String): PoTokenResult {
        ensureReady()
        // player request token is bound to the video, the streaming (GVS) one to the visitor
        val player = mint(videoId)
        val gvs = mint(visitorData)
        return PoTokenResult(player, gvs, visitorData)
    }

    private suspend fun mint(identifier: String): String {
        val now = System.currentTimeMillis()
        minted[identifier]?.let { if (it.second > now) return it.first }
        val b64 = Base64.encodeToString(identifier.toByteArray(), Base64.NO_WRAP)
        val token = js("return mintToken(${JSONObject.quote(b64)})", 10_000)
        if (token.length < 20) throw IllegalStateException("minter returned a short token")
        minted[identifier] = token to validUntil
        return token
    }

    // ---- attestation ----

    private suspend fun ensureReady() {
        if (minterReady && System.currentTimeMillis() < validUntil && webView != null) return
        val t0 = System.currentTimeMillis()
        minterReady = false
        minted.clear()
        startWebView()

        val challenge = parseChallenge(post("Create", "[\"$REQUEST_KEY\"]"))
        LogRecorder.i(TAG, "challenge ok in ${System.currentTimeMillis() - t0} ms")

        js(
            "return await initVm(${JSONObject.quote(challenge.interpreter)}, " +
                "${JSONObject.quote(challenge.program)}, ${JSONObject.quote(challenge.globalName)})",
            15_000
        )
        val snapshot = js("return await takeSnapshot()", 20_000)
        LogRecorder.i(TAG, "snapshot ok in ${System.currentTimeMillis() - t0} ms")

        val it = JSONArray(post("GenerateIT", JSONArray().put(REQUEST_KEY).put(snapshot).toString()))
        val integrity = it.getString(0)
        val ttlSec = it.optLong(1, 3600)
        js("return await setupMinter(${JSONObject.quote(integrity)})", 15_000)

        // refresh a bit before it really expires
        validUntil = System.currentTimeMillis() + (ttlSec - 600).coerceAtLeast(300) * 1000
        minterReady = true
        LogRecorder.i(TAG, "BotGuard ready in ${System.currentTimeMillis() - t0} ms (ttl ${ttlSec}s)")
    }

    private class Challenge(val interpreter: String, val program: String, val globalName: String)

    private fun parseChallenge(raw: String): Challenge {
        val outer = JSONArray(raw)
        val data = if (outer.length() > 1 && outer.get(1) is String) {
            // scrambled: base64, every byte + 97
            val bytes = Base64.decode(outer.getString(1), Base64.DEFAULT)
            for (i in bytes.indices) bytes[i] = ((bytes[i].toInt() + 97) and 0xFF).toByte()
            JSONArray(String(bytes, Charsets.UTF_8))
        } else {
            outer.getJSONArray(0)
        }
        // [messageId, [..script..], url, hash, program, globalName, ...]
        val scriptParts = data.getJSONArray(1)
        var interpreter: String? = null
        for (i in 0 until scriptParts.length()) {
            val v = scriptParts.opt(i)
            if (v is String && v.isNotEmpty()) { interpreter = v; break }
        }
        return Challenge(
            interpreter ?: throw IllegalStateException("challenge has no interpreter script"),
            data.getString(4),
            data.getString(5),
        )
    }

    private suspend fun post(method: String, body: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.youtube.com/api/jnn/v1/$method")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("x-goog-api-key", API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .post(body.toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("BotGuard $method returned HTTP ${r.code}")
            r.body?.string() ?: throw IllegalStateException("BotGuard $method returned nothing")
        }
    }

    // ---- webview ----

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun startWebView() {
        withContext(Dispatchers.Main) {
            main.removeCallbacks(destroyer)
            if (webView != null) return@withContext
            pageLoaded = CompletableDeferred()
            val loaded = pageLoaded
            val wv = WebView(app)
            wv.settings.javaScriptEnabled = true
            wv.addJavascriptInterface(Bridge(), "Android")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }
            }
            // youtube origin so the interpreter sees a normal looking page
            wv.loadDataWithBaseURL("https://www.youtube.com/", HTML, "text/html", "utf-8", null)
            webView = wv
        }
        withTimeout(10_000) { pageLoaded.await() }
    }

    private suspend fun js(body: String, timeoutMs: Long): String {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<String>()
        pending[id] = d
        try {
            withContext(Dispatchers.Main) {
                val wv = webView ?: throw IllegalStateException("webview is gone")
                wv.evaluateJavascript("run($id, async function(){ $body })", null)
            }
            return withTimeout(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun scheduleIdleRelease() {
        main.removeCallbacks(destroyer)
        main.postDelayed(destroyer, IDLE_MS)
    }

    private companion object {
        const val TAG = "PoToken"
        const val IDLE_MS = 120_000L
        const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

        // no dollar signs in here, it's a kotlin raw string
        val HTML = """
<!doctype html><html><head><meta charset="utf-8"></head><body><script>
var vmFn = null, snap = null, signalOut = [], minter = null;

function run(id, fn) {
  Promise.resolve().then(fn).then(
    function (r) { Android.done(id, String(r), ''); },
    function (e) { Android.done(id, '', String((e && (e.stack || e.message)) || e)); }
  );
}
function b64ToU8(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  var b = atob(s), u = new Uint8Array(b.length);
  for (var i = 0; i < b.length; i++) u[i] = b.charCodeAt(i);
  return u;
}
function u8ToB64Url(u) {
  var s = '';
  for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_');
}
async function initVm(interpreter, program, globalName) {
  (0, eval)(interpreter);
  vmFn = window[globalName];
  if (!vmFn || !vmFn.a) throw new Error('BotGuard vm not found: ' + globalName);
  await new Promise(function (resolve, reject) {
    try {
      vmFn.a(program, function (asyncSnapshot, shutdown, passEvent, checkCamera) {
        snap = asyncSnapshot;
        resolve();
      }, true, undefined, function () {}, [[], []]);
    } catch (e) { reject(e); }
  });
  if (!snap) throw new Error('no snapshot function');
  return 'ok';
}
async function takeSnapshot() {
  signalOut = [];
  return await new Promise(function (resolve, reject) {
    try {
      snap([undefined, undefined, signalOut, undefined], function (resp) { resolve(resp); });
    } catch (e) { reject(e); }
  });
}
async function setupMinter(integrityB64) {
  var getMinter = signalOut[0];
  if (!getMinter) throw new Error('no minter in signal output');
  minter = await getMinter(b64ToU8(integrityB64));
  if (!minter) throw new Error('minter is empty');
  return 'ok';
}
function mintToken(idB64) {
  return u8ToB64Url(minter(b64ToU8(idB64)));
}
</script></body></html>
"""
    }
}
