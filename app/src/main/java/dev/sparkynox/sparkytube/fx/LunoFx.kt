package dev.sparkynox.sparkytube.fx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// settings + presets for the LunoFX audio engine. one global state, the native side reads it live
object LunoFx {

    data class Params(
        val on: Boolean = false,
        val eq: List<Float> = List(10) { 0f },
        val bass: Float = 0f,
        val width: Float = 1f,
        val rvMix: Float = 0f,
        val rvRoom: Float = 0.5f,
        val rvDamp: Float = 0.5f,
        val p8On: Boolean = false,
        val p8Period: Float = 8f,
        val p8Depth: Float = 1f,
        val normOn: Boolean = false,
        val normTarget: Float = -16f
    )

    class Preset(val name: String, val params: Params, val speed: Float? = null)

    val BANDS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    val presets = listOf(
        Preset("Flat", Params(), 1f),
        Preset("Bass Boost", Params(on = true, eq = listOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f), bass = 6f), 1f),
        Preset("Lofi Chill", Params(on = true, eq = listOf(2f, 2f, 1f, 0f, 0f, -1f, -2f, -4f, -6f, -8f), width = 1.15f, rvMix = 0.18f, rvRoom = 0.55f, rvDamp = 0.7f), 1f),
        Preset("Slowed + Reverb", Params(on = true, eq = listOf(3f, 3f, 2f, 0f, 0f, 0f, -1f, -2f, -3f, -4f), bass = 3f, width = 1.3f, rvMix = 0.4f, rvRoom = 0.85f, rvDamp = 0.5f), 0.85f),
        Preset("Nightcore", Params(on = true, eq = listOf(2f, 1f, 0f, 0f, 0f, 1f, 2f, 3f, 3f, 2f)), 1.25f),
        Preset("8D Audio", Params(on = true, p8On = true, p8Period = 8f, p8Depth = 1f, rvMix = 0.12f, rvRoom = 0.5f), 1f),
        Preset("Vocal Boost", Params(on = true, eq = listOf(-3f, -2f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f)), 1f),
        Preset("Loud + Even", Params(on = true, eq = listOf(2f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 0f), bass = 2f, normOn = true, normTarget = -14f), 1f),
    )

    @Volatile
    var params = Params()
        private set

    // set by PlaybackService, changes speed + pitch of the running player
    @Volatile
    var speedHook: ((Float, Float) -> Unit)? = null

    private const val PREFS = "sparky_fx"
    @Volatile private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        params = load(c)
        NativeFx.setAll(effective(params))
    }

    // the admin can switch the engine off from remote_config.json
    private fun effective(p: Params) = if (dev.sparkynox.sparkytube.net.RemoteConfig.isEnabled("audio_fx")) p else p.copy(on = false)

    fun reapply() = NativeFx.setAll(effective(params))

    fun update(c: Context, p: Params) {
        params = p
        NativeFx.setAll(effective(p))
        save(c, p)
    }

    private fun save(c: Context, p: Params) {
        val j = JSONObject()
            .put("on", p.on).put("eq", JSONArray(p.eq.map { it.toDouble() }))
            .put("bass", p.bass.toDouble()).put("width", p.width.toDouble())
            .put("rvMix", p.rvMix.toDouble()).put("rvRoom", p.rvRoom.toDouble()).put("rvDamp", p.rvDamp.toDouble())
            .put("p8On", p.p8On).put("p8Period", p.p8Period.toDouble()).put("p8Depth", p.p8Depth.toDouble())
            .put("normOn", p.normOn).put("normTarget", p.normTarget.toDouble())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("params", j.toString()).apply()
    }

    private fun load(c: Context): Params {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("params", null) ?: return Params()
        return try {
            val j = JSONObject(raw)
            val arr = j.getJSONArray("eq")
            Params(
                on = j.optBoolean("on"),
                eq = List(10) { arr.optDouble(it, 0.0).toFloat() },
                bass = j.optDouble("bass", 0.0).toFloat(),
                width = j.optDouble("width", 1.0).toFloat(),
                rvMix = j.optDouble("rvMix", 0.0).toFloat(),
                rvRoom = j.optDouble("rvRoom", 0.5).toFloat(),
                rvDamp = j.optDouble("rvDamp", 0.5).toFloat(),
                p8On = j.optBoolean("p8On"),
                p8Period = j.optDouble("p8Period", 8.0).toFloat(),
                p8Depth = j.optDouble("p8Depth", 1.0).toFloat(),
                normOn = j.optBoolean("normOn"),
                normTarget = j.optDouble("normTarget", -16.0).toFloat()
            )
        } catch (e: Exception) {
            Params()
        }
    }
}
