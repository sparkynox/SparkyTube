package dev.sparkynox.sparkytube.history

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.session.MediaController
import dev.sparkynox.sparkytube.adblock.AdBlockEngine
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.logs.LogRecorder

// Saves the video the user watches natively into their YouTube history.
// Once ExoPlayer has really played it for a couple of seconds, the real watch page loads in the hidden
// WebView (signed in, muted, 144p) and mirrors ExoPlayer: plays when it plays, pauses when it pauses.
// Closing the video closes the page. Everything the host activity has to give is passed in as lambdas.
class BackgroundHistory(
    private val webView: () -> WebView,
    private val container: FrameLayout,
    private val compose: View,
    private val exo: View,
    private val controller: () -> MediaController?,
    private val videoId: () -> String?,
    private val resolving: () -> Boolean,
    private val nativeActive: () -> Boolean,
    private val enabled: () -> Boolean,
) {
    var activeFor: String? = null
        private set
    val active: Boolean get() = activeFor != null

    private var seenId: String? = null
    private var playedMs = 0L
    private var lastTick = 0L
    private var lastJs = ""
    private var lastRaw = ""
    private var lastLoad = 0L
    private var loadTries = 0
    private var badSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val keepAlive = object : Runnable {
        override fun run() {
            step()
            if (activeFor != null) handler.postDelayed(this, 1000)
        }
    }

    // called from the host's playback tick
    fun tick() {
        val c = controller() ?: return
        val id = videoId() ?: return
        if (resolving() || !enabled()) return
        if (!Session.loggedIn) {
            if (seenId != id) {
                seenId = id
                LogRecorder.w("History", "not signed in (no SAPISID cookie), nothing to save")
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (seenId != id) {
            stop(blank = true)
            seenId = id
            playedMs = 0
            lastTick = now
            LogRecorder.d("History", "counting played time for $id")
            return
        }
        if (c.isPlaying) playedMs += (now - lastTick).coerceAtMost(2000)
        lastTick = now
        if (debug != null) refreshDebug()
        if (activeFor == null && playedMs >= 2_000 && (compose.visibility == View.VISIBLE || debug != null)) {
            activeFor = id
            lastJs = ""
            AdBlockEngine.historyPlayerActive = true
            LogRecorder.i("History", "playing, starting background player for $id")
            lastLoad = now
            loadTries = 1
            badSince = 0L
            webView().loadUrl("https://m.youtube.com/watch?v=$id")
            handler.removeCallbacks(keepAlive)
            handler.postDelayed(keepAlive, 700)
        }
    }

    private fun step() {
        val id = activeFor ?: return
        if (id != videoId() || !nativeActive()) {
            stop(blank = true)
            return
        }
        // the user opened a channel / Shorts page in the WebView, it belongs to them now
        if (compose.visibility != View.VISIBLE && debug == null) {
            stop(blank = false)
            return
        }
        val wv = webView()
        // only touch the page once it really is this video's watch page. The home page that sits there
        // before it loads plays muted feed previews, and those end up in the user's history
        val u = wv.url.orEmpty()
        if (!u.contains("/watch") || !u.contains(id)) {
            if (debug == null) wv.visibility = View.INVISIBLE
            lastRaw = "waiting for the watch page"
            refreshDebug()
            // the page never came (network blip, redirect, consent screen), ask again instead of giving up
            val now = SystemClock.elapsedRealtime()
            if (now - lastLoad > 10_000 && loadTries < 4) {
                loadTries++
                lastLoad = now
                LogRecorder.w("History", "watch page didn't load, try $loadTries")
                wv.loadUrl("https://m.youtube.com/watch?v=$id")
            }
            return
        }
        // a visible page keeps youtube from pausing the muted video; the compose screen covers it
        wv.visibility = View.VISIBLE
        val c = controller()
        val playing = c?.isPlaying == true
        val pos = (c?.currentPosition ?: 0L) / 1000
        wv.evaluateJavascript(
            "(function(play,pos){try{var v=document.querySelector('video');if(!v)return 'nov';" +
                "document.body.classList.add('sparkytube-native-active');" +
                "v.muted=true;v.volume=0;" +
                "var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player')||document.getElementById('player');" +
                "try{if(p&&p.setPlaybackQualityRange){p.setPlaybackQualityRange('tiny','tiny');}}catch(e){}" +
                "if(!window.__sparkyHistSynced&&v.readyState>0){if(pos>3)v.currentTime=pos;window.__sparkyHistSynced=true;}" +
                "if(play){if(v.paused)v.play();}else{if(!v.paused)v.pause();}" +
                "return v.paused?'paused':('playing '+Math.round(v.currentTime)+'s '+v.videoHeight+'p');}catch(e){return 'err';}})($playing,$pos)"
        ) { r ->
            lastRaw = r.orEmpty()
            refreshDebug()
            // page is up but there's no video (or the script failed) for a while: reload it
            val now = SystemClock.elapsedRealtime()
            if (lastRaw.contains("nov") || lastRaw.contains("err")) {
                if (badSince == 0L) badSince = now
                else if (now - badSince > 12_000 && loadTries < 4) {
                    loadTries++
                    badSince = 0L
                    lastLoad = now
                    LogRecorder.w("History", "no video in the page, reloading (try $loadTries)")
                    wv.loadUrl("https://m.youtube.com/watch?v=$id")
                }
            } else {
                badSince = 0L
            }
            // log only when the state changes, so the log stays readable
            val res = lastRaw.replace(Regex("\\d+s"), "Ns")
            if (res != lastJs) {
                lastJs = res
                LogRecorder.d("History", "background player: $r (url=${wv.url})")
            }
        }
    }

    fun stop(blank: Boolean) {
        val was = activeFor != null
        activeFor = null
        AdBlockEngine.historyPlayerActive = false
        handler.removeCallbacks(keepAlive)
        if (!was) return
        if (blank) webView().loadUrl("about:blank")
        if (compose.visibility == View.VISIBLE) webView().visibility = View.INVISIBLE
    }

    // what the host should set the WebView to when it shows the compose screen
    fun webViewVisibility(): Int =
        if (activeFor != null && webView().url.orEmpty().contains("/watch")) View.VISIBLE else View.INVISIBLE

    // ---- temporary debug view: hides the app, shows the hidden WebView with a status strip

    private var debug: TextView? = null
    private var exoVisibility = View.GONE

    fun showDebug() {
        if (debug != null) return
        exoVisibility = exo.visibility
        exo.visibility = View.INVISIBLE
        compose.visibility = View.INVISIBLE
        webView().visibility = View.VISIBLE
        debug = TextView(container.context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(16, 12, 16, 12)
            elevation = 80f
        }
        container.addView(
            debug,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        refreshDebug()
    }

    fun leaveDebug() {
        val d = debug ?: return
        debug = null
        container.removeView(d)
        exo.visibility = exoVisibility
    }

    private fun refreshDebug() {
        val tv = debug ?: return
        val c = controller()
        tv.text = "history for: ${activeFor ?: "(not started)"}\n" +
            "playing id: ${videoId()}  played=${playedMs / 1000}s\n" +
            "native: playing=${c?.isPlaying} pos=${(c?.currentPosition ?: 0) / 1000}s\n" +
            "page: ${webView().url}\n" +
            "js: $lastRaw\n" +
            "back button = return to the app"
    }
}
