package dev.sparkynox.sparkytube.extractor.social

import android.webkit.CookieManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SocialHttp {
    const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    /** [url] is where the request ended up after redirects, [cookies] what that answer set. */
    class Resp(val code: Int, val body: String, val url: String, val cookies: String)

    private val client = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): Resp {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (!headers.containsKey("User-Agent")) rb.header("User-Agent", UA_DESKTOP)
        client.newCall(rb.build()).execute().use { r ->
            val cookies = r.headers("Set-Cookie").map { it.substringBefore(';') }.filter { it.contains('=') }.joinToString("; ")
            return Resp(r.code, r.body?.string().orEmpty(), r.request.url.toString(), cookies)
        }
    }

    /** The cookies of the site's sign in that the user did in SocialLoginActivity (or any WebView of the app). */
    fun cookieFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun cookieValue(cookie: String?, name: String): String? =
        cookie?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    /** Text out of a JSON string literal body: \u0025, \/ and friends. */
    fun unescapeJson(s: String): String =
        runCatching { org.json.JSONTokener("\"$s\"").nextValue() as String }.getOrDefault(s.replace("\\/", "/"))

    fun htmlText(s: String): String = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
}
