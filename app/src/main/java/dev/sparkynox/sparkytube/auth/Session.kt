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

    /** Everything about the signed-in account as it is right now (null when signed out). */
    class Snapshot(
        val cookie: String, val visitor: String, val sync: String, val clientVersion: String,
        val name: String, val handle: String, val photo: String,
    )

    fun snapshot(): Snapshot? {
        val c = cookie ?: return null
        if (cookieValue(c, "SAPISID") == null && cookieValue(c, "__Secure-3PAPISID") == null) return null
        return Snapshot(
            c, visitorData.orEmpty(), dataSyncId.orEmpty(), clientVersion.orEmpty(),
            accountName, accountHandle, accountPhoto,
        )
    }

    /** Makes [a] the signed-in account: stored fields plus the WebView cookie jar. */
    fun restore(a: Snapshot) {
        sp.edit().clear()
            .putString("cookie", a.cookie)
            .putString("visitor", a.visitor)
            .putString("sync", a.sync)
            .putString("client_version", a.clientVersion)
            .putString("account_name", a.name)
            .putString("account_handle", a.handle)
            .putString("account_photo", a.photo)
            .apply()
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies {
                a.cookie.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
                    val name = pair.substringBefore("=")
                    val domain = if (name.startsWith("__Host-")) "" else "; Domain=.youtube.com"
                    cm.setCookie(ORIGIN, "$pair$domain; Path=/; Secure; Max-Age=31536000")
                }
                cm.flush()
            }
        }
    }

    /**
     * Deletes only the youtube.com cookies. Google's own cookies stay, so the account chooser still
     * lists the accounts on this phone, but YouTube starts with an empty session. One account's
     * cookies mixed with another's makes YouTube answer 400.
     */
    fun clearYoutubeCookies() {
        runCatching {
            val cm = CookieManager.getInstance()
            val names = listOf("https://www.youtube.com", "https://m.youtube.com")
                .flatMap { u -> cm.getCookie(u).orEmpty().split(";").map { it.trim().substringBefore("=") } }
                .filter { it.isNotEmpty() }.toSet()
            for (n in names) {
                for (u in listOf("https://www.youtube.com", "https://m.youtube.com")) {
                    cm.setCookie(u, "$n=; Max-Age=0; Domain=.youtube.com; Path=/")
                    cm.setCookie(u, "$n=; Max-Age=0; Path=/")
                }
            }
            cm.flush()
        }
    }

    /** True when the WebView jar has a YouTube login. */
    fun jarLoggedIn(): Boolean = runCatching {
        CookieManager.getInstance().getCookie(ORIGIN)?.let { cookieValue(it, "SAPISID") != null } == true
    }.getOrDefault(false)

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
