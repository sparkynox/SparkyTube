package dev.sparkynox.sparkytube.fx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.Effect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import dev.sparkynox.sparkytube.logs.LogRecorder
import dev.sparkynox.sparkytube.net.RemoteConfig

// anime upscaler settings. bigger-than-source output + AnimeSharpEffect on the GPU, applied to the running player
@UnstableApi
object LunoVideoFx {

    enum class Mode(val label: String, val scale: Float, val push: Float, val sharp: Float) {
        OFF("Off", 1f, 0f, 0f),
        LIGHT("Light", 1.5f, 0.5f, 0.25f),
        STRONG("Strong", 2f, 0.8f, 0.4f)
    }

    data class Params(val mode: Mode = Mode.OFF, val push: Float = 0.5f, val sharp: Float = 0.25f)

    @Volatile var params = Params()
        private set

    private const val PREFS = "sparky_videofx"
    private var inited = false
    private var player: ExoPlayer? = null
    private var appContext: Context? = null
    private var lastKey = ""
    private var effectsActive = false
    private val main = Handler(Looper.getMainLooper())

    fun init(c: Context) {
        if (inited) return
        inited = true
        appContext = c.applicationContext
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = try { Mode.valueOf(sp.getString("mode", "OFF")!!) } catch (_: Exception) { Mode.OFF }
        params = Params(mode, sp.getFloat("push", mode.push), sp.getFloat("sharp", mode.sharp))
    }

    fun update(c: Context, p: Params) {
        params = p
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", p.mode.name).putFloat("push", p.push).putFloat("sharp", p.sharp).apply()
        apply()
    }

    fun reapply() = apply()

    fun attach(exo: ExoPlayer) {
        player = exo
        exo.addListener(object : Player.Listener {
            // the source size comes from the track format, not the output, so applying effects can't loop
            override fun onVideoSizeChanged(videoSize: VideoSize) = apply()

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED && params.mode != Mode.OFF) {
                    LogRecorder.w("VideoFx", "effect failed, turning it off", error)
                    appContext?.let { c ->
                        params = params.copy(mode = Mode.OFF)
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", "OFF").apply()
                        Toast.makeText(c, "Anime upscaler turned off, this phone couldn't run it", Toast.LENGTH_LONG).show()
                    }
                    lastKey = ""
                    effectsActive = false
                    exo.setVideoEffects(emptyList())
                    exo.prepare()
                }
            }
        })
        apply()
    }

    fun detach() {
        player = null
        lastKey = ""
        effectsActive = false
    }

    private fun apply() {
        main.post {
            val exo = player ?: return@post
            val p = params
            val h = exo.videoFormat?.height ?: 0
            val on = p.mode != Mode.OFF && RemoteConfig.isEnabled("video_fx")
            val key = if (on) "${p.mode}/$h/${p.push}/${p.sharp}" else "off"
            if (key == lastKey) return@post
            lastKey = key
            val list = effectsFor(p, h, on)
            // even an empty list moves ExoPlayer onto its effects pipeline, and on some phones that means a black
            // video or endless buffering. so with the upscaler off the player is never touched, an empty list is
            // only sent to take effects off again after they were on
            if (list.isEmpty() && !effectsActive) return@post
            effectsActive = list.isNotEmpty()
            exo.setVideoEffects(list)
        }
    }

    private fun effectsFor(p: Params, srcH: Int, on: Boolean): List<Effect> {
        if (!on || srcH <= 0 || srcH >= 1080) return emptyList()
        val target = (srcH * p.mode.scale).toInt().coerceAtMost(1080)
        if (target <= srcH) return emptyList()
        return listOf(Presentation.createForHeight(target), AnimeSharpEffect(p.push, p.sharp))
    }
}
