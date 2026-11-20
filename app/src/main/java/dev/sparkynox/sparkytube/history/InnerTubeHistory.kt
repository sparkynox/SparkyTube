package dev.sparkynox.sparkytube.history

import android.content.Context
import android.os.SystemClock
import androidx.media3.session.MediaController
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import dev.sparkynox.sparkytube.logs.LogRecorder
import dev.sparkynox.sparkytube.settings.SettingsPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

// Saves what plays natively into the YouTube history through the same stats pings the web player sends,
// no hidden WebView involved. The /player response carries two urls: videostatsPlaybackUrl (this adds the
// video to the history) and videostatsWatchtimeUrl (keeps the "watched so far" bar up to date).
// Needs a signed in session, a video that was really played for a couple of seconds.
class InnerTubeHistory(
    private val controller: () -> MediaController?,
    private val videoId: () -> String?,
    private val resolving: () -> Boolean,
    private val enabled: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var seenId: String? = null
    private var playedMs = 0L
    private var lastTick = 0L
    private var lastPosSec = 0L
    private var lastSentSec = 0L
    private var lastWatchtimeAt = 0L
    private var retryAt = 0L

    @Volatile private var tracking: Api.Tracking? = null
    @Volatile private var started = false
    @Volatile private var fetching = false

    // called from the host's playback tick
    fun tick() {
        val c = controller() ?: return
        val id = videoId() ?: return
        if (resolving() || !enabled() || !Session.loggedIn) return
        val now = SystemClock.elapsedRealtime()
        if (seenId != id) {
            finish()
            seenId = id
            playedMs = 0; lastTick = now; lastPosSec = 0; lastSentSec = 0; lastWatchtimeAt = now; retryAt = 0
            tracking = null; started = false; fetching = false
            return
        }
        if (c.isPlaying) playedMs += (now - lastTick).coerceAtMost(2000)
        lastTick = now
        lastPosSec = (c.currentPosition / 1000).coerceAtLeast(0)

        if (!started && !fetching && playedMs >= 2_000 && now >= retryAt) {
            fetching = true
            scope.launch {
                val t = Api.fetch(id)
                if (t == null) {
                    LogRecorder.w("History", "InnerTube: no tracking urls for $id, will try again")
                    retryAt = SystemClock.elapsedRealtime() + 15_000
                } else if (seenId == id) {
                    tracking = t
                    val ok = Api.playback(t)
                    LogRecorder.i("History", "InnerTube: playback ping for $id ok=$ok")
                    if (ok) started = true else retryAt = SystemClock.elapsedRealtime() + 15_000
                }
                fetching = false
            }
        }
        if (started && c.isPlaying && now - lastWatchtimeAt >= 30_000) {
            lastWatchtimeAt = now
            sendWatchtime(final = false)
        }
    }

    // video closed or switched: one last watch time update with the final position
    fun stop() {
        finish()
        seenId = null
        started = false
        tracking = null
    }

    private fun finish() {
        if (started) sendWatchtime(final = true)
    }

    private fun sendWatchtime(final: Boolean) {
        val t = tracking ?: return
        val st = lastSentSec
        val et = lastPosSec.coerceAtLeast(st)
        lastSentSec = et
        scope.launch { Api.watchtime(t, st, et, final) }
    }

    object Api {
        class Tracking(val playbackUrl: String, val watchtimeUrl: String?, val cpn: String, val lengthSec: Long)

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        private fun cpn() = (1..16).map { ALPHABET.random() }.joinToString("")

        /** Blocking. Null when the player response had no tracking urls (logged out, blocked video). */
        fun fetch(videoId: String): Tracking? {
            for ((client, nameId) in listOf("WEB" to "1", "MWEB" to "2")) {
                try {
                    val body = JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
                    val root = InnerTubeClient.post("player", body, client, nameId)
                    val pt = root.optJSONObject("playbackTracking")
                    val pb = pt?.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                    if (pb == null) {
                        LogRecorder.d("History", "$client player response had no playbackTracking (${root.optJSONObject("playabilityStatus")?.optString("status")})")
                        continue
                    }
                    val wt = pt?.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                    val len = root.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
                    return Tracking(pb, wt, cpn(), len)
                } catch (e: Exception) {
                    LogRecorder.w("History", "$client player request failed: ${e.message}")
                }
            }
            return null
        }

        private fun join(base: String, params: String) = base + (if (base.contains('?')) "&" else "?") + params

        fun playback(t: Tracking): Boolean {
            val code = InnerTubeClient.rawGet(join(t.playbackUrl, "ver=2&cpn=${t.cpn}&cmt=0&c=WEB"))
            return code in 200..299
        }

        fun watchtime(t: Tracking, st: Long, et: Long, final: Boolean): Boolean {
            val base = t.watchtimeUrl ?: return false
            val p = StringBuilder("ver=2&cpn=${t.cpn}&cmt=$et&st=$st&et=$et&state=${if (final) "paused" else "playing"}&hl=en&c=WEB")
            if (t.lengthSec > 0) p.append("&len=${t.lengthSec}")
            if (final) p.append("&final=1")
            val code = InnerTubeClient.rawGet(join(base, p.toString()))
            LogRecorder.d("History", "InnerTube watchtime $st-$et final=$final -> $code")
            return code in 200..299
        }
    }

    companion object {
        private val shortsDone = LinkedHashSet<String>()
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** A Short that stayed on screen for a moment: add it to the history (once per Short per session). */
        fun markShort(ctx: Context, videoId: String) {
            if (!SettingsPrefs.isInnerTubeHistoryEnabled(ctx) || !Session.loggedIn) return
            synchronized(shortsDone) {
                if (!shortsDone.add(videoId)) return
                if (shortsDone.size > 300) shortsDone.remove(shortsDone.first())
            }
            ioScope.launch {
                val t = Api.fetch(videoId)
                val ok = t?.let { Api.playback(it) } ?: false
                LogRecorder.i("History", "InnerTube: short $videoId marked watched ok=$ok")
            }
        }
    }
}
