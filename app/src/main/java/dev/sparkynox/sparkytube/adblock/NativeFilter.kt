package dev.sparkynox.sparkytube.adblock

import android.content.Context
import dev.sparkynox.sparkytube.logs.LogRecorder
import java.io.File

// EasyList style rules (paths, wildcards, $third-party, $domain=...) matched in C++, see netfilter.cpp.
// the host-only rules live in blocklist_hosts.txt, this file has everything else
object NativeFilter {

    const val T_SCRIPT = 1
    const val T_IMAGE = 2
    const val T_STYLE = 4
    const val T_XHR = 16
    const val T_SUBDOC = 32
    const val T_MEDIA = 128
    const val T_FONT = 256
    const val T_OTHER = 512

    @Volatile
    var ready = false
        private set

    private val libOk = try {
        System.loadLibrary("sparkynet")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeLoad(path: String): Int
    private external fun nativeMatch(url: String, page: String?, type: Int): Int

    fun init(c: Context) {
        if (ready || !libOk) return
        try {
            val file = File(c.filesDir, "filters_network.txt")
            val code = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
            val prefs = c.getSharedPreferences("sparky_filters", Context.MODE_PRIVATE)
            if (!file.exists() || prefs.getLong("code", -1) != code) {
                c.assets.open("filters_network.txt").use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                prefs.edit().putLong("code", code).apply()
            }
            val n = nativeLoad(file.absolutePath)
            ready = n > 0
            LogRecorder.i("AdBlock", "native filter engine: $n rules")
        } catch (t: Throwable) {
            LogRecorder.w("AdBlock", "native filter engine failed to load", t)
        }
    }

    // 0 = no rule, 1 = blocked, 2 = allowed by an exception rule
    fun match(url: String, pageHost: String?, type: Int): Int =
        if (ready) nativeMatch(url, pageHost, type) else 0

    // best guess of what the request is, from the file ending and the Accept header
    fun typeOf(url: String, accept: String?): Int {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        val a = accept?.lowercase().orEmpty()
        return when {
            ext == "js" || ext == "mjs" || a.contains("javascript") -> T_SCRIPT
            ext in IMAGES || a.startsWith("image/") -> T_IMAGE
            ext == "css" || a.startsWith("text/css") -> T_STYLE
            ext in FONTS -> T_FONT
            ext in MEDIA -> T_MEDIA
            a.startsWith("text/html") -> T_SUBDOC
            a.contains("json") || a.contains("xml") -> T_XHR
            else -> T_OTHER
        }
    }

    private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
    private val FONTS = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIA = setOf("mp4", "webm", "m3u8", "mpd", "mp3", "m4a", "ogg", "ts")
}
