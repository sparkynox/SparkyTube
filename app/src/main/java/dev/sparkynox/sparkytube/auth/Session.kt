package dev.sparkynox.sparkytube.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager

/**
 * YouTube login state, modelled on LunoMusic's Session.
 *
 * The WebView's CookieManager is always the freshest source (YouTube rotates cookies while you
 * browse), so [cookie] reads it first and only falls back to what LoginActivity stored. That also
 * means people who were already logged in through the old WebView keep working without logging in
 * again; they just won't have visitorData until they go through LoginActivity once.
 */
object Session {
    const val ORIGIN = "https://www.youtube.com"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("sparky_session", Context.MODE_PRIVATE)
    }

    private fun stored(key: String) = sp.getString(key, null)?.ifBlank { null }

    val cookie: String?
        get() {
            val live = runCatching { CookieManager.getInstance().getCookie(ORIGIN) }.getOrNull()
            return if (live != null && cookieValue(live, "SAPISID") != null) live else stored("cookie")
        }

    val visitorData: String? get() = stored("visitor")
    val dataSyncId: String? get() = stored("sync")
    val clientVersion: String? get() = stored("client_version")

    var accountName: String
        get() = sp.getString("account_name", "") ?: ""
        set(v) = sp.edit().putString("account_name", v).apply()
    var accountHandle: String
        get() = sp.getString("account_handle", "") ?: ""
        set(v) = sp.edit().putString("account_handle", v).apply()
    var accountPhoto: String
        get() = sp.getString("account_photo", "") ?: ""
        set(v) = sp.edit().putString("account_photo", v).apply()

    val loggedIn: Boolean get() = sapisid() != null

    fun sapisid(): String? = cookie?.let { cookieValue(it, "SAPISID") ?: cookieValue(it, "__Secure-3PAPISID") }

    fun save(cookie: String, visitor: String, sync: String, clientVersion: String) {
        sp.edit()
            .putString("cookie", cookie)
            .putString("visitor", visitor)
            .putString("sync", sync)
            .putString("client_version", clientVersion)
            .apply()
    }

    fun logout() {
        sp.edit().clear().apply()
        // the WebView shares this cookie jar, so it is logged out as well
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private fun cookieValue(header: String, name: String): String? =
        header.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
}
