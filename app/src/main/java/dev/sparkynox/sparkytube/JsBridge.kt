package dev.sparkynox.sparkytube

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Bridge object exposed into the WebView's JS context as `window.SparkyBridge`.
 * injected.js calls onVideoState(...) continuously (every ~800ms + on SPA nav),
 * so this is the "24/7 background communication" channel between the page
 * and native Kotlin/ExoPlayer side.
 */
class JsBridge(private val listener: VideoStateListener) {

    interface VideoStateListener {
        fun onVideoState(streamUrl: String, title: String, isPaused: Boolean, adShowing: Boolean, currentTime: Double, duration: Double)
    }

    @JavascriptInterface
    fun onVideoState(json: String) {
        try {
            val obj = JSONObject(json)
            listener.onVideoState(
                streamUrl = obj.optString("src"),
                title = obj.optString("title"),
                isPaused = obj.optBoolean("paused", true),
                adShowing = obj.optBoolean("adShowing", false),
                currentTime = obj.optDouble("currentTime", 0.0),
                duration = obj.optDouble("duration", 0.0)
            )
        } catch (_: Exception) {
            // Malformed payload from a page transition mid-write — just skip this tick,
            // next 800ms interval will report fresh state.
        }
    }
}
