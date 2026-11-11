package dev.sparkynox.sparkytube.lunokit.ambient

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// settings of the live ambient mode (player menu > Ambient mode)
object AmbientPrefs {
    private lateinit var sp: SharedPreferences

    var enabled by mutableStateOf(false); private set
    var strength by mutableStateOf(70); private set      // percent
    var spreadDp by mutableStateOf(110); private set
    var blurDp by mutableStateOf(28); private set
    var saturation by mutableStateOf(125); private set   // percent
    var smooth by mutableStateOf(true); private set      // blend frames into each other
    var fps by mutableStateOf(12); private set

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("sparky_ambient", Context.MODE_PRIVATE)
        enabled = sp.getBoolean("enabled", false)
        strength = sp.getInt("strength", 70)
        spreadDp = sp.getInt("spread", 110)
        blurDp = sp.getInt("blur", 28)
        saturation = sp.getInt("saturation", 125)
        smooth = sp.getBoolean("smooth", true)
        fps = sp.getInt("fps", 12)
    }

    fun setEnabled(v: Boolean) { enabled = v; sp.edit().putBoolean("enabled", v).apply() }
    fun setSmooth(v: Boolean) { smooth = v; sp.edit().putBoolean("smooth", v).apply() }

    // sliders call these while dragging, saved when the finger lifts
    fun setStrength(v: Int) { strength = v }
    fun setSpread(v: Int) { spreadDp = v }
    fun setBlur(v: Int) { blurDp = v }
    fun setSaturation(v: Int) { saturation = v }
    fun setFps(v: Int) { fps = v }

    fun save() {
        sp.edit().putInt("strength", strength).putInt("spread", spreadDp).putInt("blur", blurDp)
            .putInt("saturation", saturation).putInt("fps", fps).apply()
    }
}
