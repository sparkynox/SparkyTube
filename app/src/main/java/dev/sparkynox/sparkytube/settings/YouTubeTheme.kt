package dev.sparkynox.sparkytube.settings

import android.content.Context
import android.content.res.Configuration
import android.webkit.CookieManager

// m.youtube.com keeps its dark/light choice in the PREF cookie, in the "f6"
// flags (hex bitfield). Without this the app chrome would go light while the
// YouTube page stays dark. Only runs once the user has picked a theme.
object YouTubeTheme {
    private const val FLAG_DARK = 0x400
    private const val FLAG_LIGHT = 0x80000

    fun apply(context: Context) {
        if (!SettingsPrefs.hasChosenTheme(context)) return
        try {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val cm = CookieManager.getInstance()
            val url = "https://m.youtube.com"
            val current = cm.getCookie(url).orEmpty()
            val pref = current.split(";").map { it.trim() }.firstOrNull { it.startsWith("PREF=") }
                ?.removePrefix("PREF=").orEmpty()

            val parts = pref.split("&").filter { it.isNotBlank() }
            val oldFlags = parts.firstOrNull { it.startsWith("f6=") }
                ?.removePrefix("f6=")?.toLongOrNull(16)?.toInt() ?: 0
            // keep every other bit YouTube stored there, only swap the theme ones
            val newFlags = (oldFlags and (FLAG_DARK or FLAG_LIGHT).inv()) or (if (night) FLAG_DARK else FLAG_LIGHT)
            if (newFlags == oldFlags) return

            val merged = (parts.filter { !it.startsWith("f6=") } + "f6=${Integer.toHexString(newFlags)}").joinToString("&")
            cm.setCookie(url, "PREF=$merged; Domain=.youtube.com; Path=/; Max-Age=31536000; Secure")
            cm.flush()
        } catch (e: Exception) {
            android.util.Log.w("YouTubeTheme", "couldn't sync YouTube theme", e)
        }
    }
}
