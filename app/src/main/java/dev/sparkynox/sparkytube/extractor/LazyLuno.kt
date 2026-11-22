package dev.sparkynox.sparkytube.extractor

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// LazyLuno is the C++ extractor in cpp/lazyluno. it has no network code of its own, every request it
// wants goes through request() below, so TLS, DNS and timeouts all come from OkHttp
object LazyLunoNet {
    private val base = OkHttpClient.Builder().followRedirects(true).build()

    // called from C++ (lazyluno_jni.cpp), the signature there has to match this one
    @JvmStatic
    fun request(method: String, url: String, headers: String, body: String, connectMs: Int, timeoutMs: Int): String {
        return try {
            val client = base.newBuilder()
                .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val rb = Request.Builder().url(url)
            for (line in headers.lineSequence()) {
                val i = line.indexOf(": ")
                if (i > 0) rb.header(line.substring(0, i), line.substring(i + 2))
            }
            if (method == "POST") rb.post(body.toRequestBody("application/json".toMediaType())) else rb.get()
            client.newCall(rb.build()).execute().use { "${it.code}\n${it.body?.string().orEmpty()}" }
        } catch (e: Exception) {
            "0\n${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

object LazyLuno {
    private val loaded = try {
        System.loadLibrary("lazyluno")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeResolve(videoId: String, cookie: String, hl: String, gl: String, clients: String, visitorData: String, poToken: String): String

    fun available() = loaded

    /** Raw json from the native side, null if the library didn't load. Blocking. */
    fun resolveJson(videoId: String, cookie: String, hl: String, gl: String, clients: String = "", visitorData: String = "", poToken: String = ""): String? =
        if (loaded) nativeResolve(videoId, cookie, hl, gl, clients, visitorData, poToken) else null

    /** Version of the native extractor, shown in About and the logs. Bump it when the C++ changes. */
    const val VERSION = "1.1.0"
}
