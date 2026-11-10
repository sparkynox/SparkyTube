package dev.sparkynox.sparkytube.lunokit.glass

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Luno Glass: frosted sheets and a blurred video backdrop. It needs real window blur, which only
 * exists on Android 12+ and only while the phone allows it, so on anything else it refuses to turn on.
 */
object LunoGlass {
    private lateinit var sp: SharedPreferences

    var wanted by mutableStateOf(false); private set
    var blurOk by mutableStateOf(false); private set

    /** what the UI checks: switched on AND the phone can really blur right now */
    val active: Boolean get() = wanted && blurOk

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("sparky_glass", Context.MODE_PRIVATE)
        refresh(ctx)
        // a restored backup can say "on" on a phone that can't do it
        wanted = sp.getBoolean("on", false) && blurOk
    }

    fun isOn(): Boolean = wanted

    fun refresh(ctx: Context) {
        blurOk = supported(ctx)
    }

    private fun supported(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        return wm.isCrossWindowBlurEnabled
    }

    /** False when it was refused, the popup explaining why has already been shown. */
    fun setEnabled(ctx: Context, on: Boolean): Boolean {
        if (on && !supported(ctx)) {
            val msg = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                "Luno Glass needs real glass blur, which only exists on Android 12 and newer. " +
                    "This phone runs Android ${Build.VERSION.RELEASE}, so it can't be turned on here."
            else
                "Your phone has window blur switched off right now (battery saver, or a system setting). " +
                    "Turn it back on and try again."
            AlertDialog.Builder(ctx)
                .setTitle("Not available on this phone")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
            return false
        }
        wanted = on
        sp.edit().putBoolean("on", on).apply()
        refresh(ctx)
        return true
    }
}
