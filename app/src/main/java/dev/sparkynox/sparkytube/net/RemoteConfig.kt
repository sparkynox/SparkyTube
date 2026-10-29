package dev.sparkynox.sparkytube.net

import android.app.Activity
import android.content.Context
import android.util.Base64
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.fx.LunoFx
import dev.sparkynox.sparkytube.fx.LunoVideoFx
import dev.sparkynox.sparkytube.logs.LogRecorder
import dev.sparkynox.sparkytube.ui.Emote
import dev.sparkynox.sparkytube.ui.showMessagePopup
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

// admin switches: turn a feature off, or ban an install or account, without shipping a new app.
// the config file is signed with ed25519 (private key stays with the admin) and checked in C++,
// so nobody can fake it by editing the file on GitHub's CDN, a proxy or the saved copy on the phone.
// public key: app/src/main/cpp/rc_pubkey.h. while that is all zeros this whole thing stays off
object RemoteConfig {

    private const val URL = "https://raw.githubusercontent.com/sparkynox/SparkyTube/main/remote_config.json"
    private const val PREFS = "remote_config"
    private const val STALE_MS = 6 * 60 * 60 * 1000L

    private val libOk = try {
        System.loadLibrary("sparkynet")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeVerify(msg: ByteArray, sig: ByteArray): Boolean
    private external fun nativeConfigured(): Boolean

    private class Feature(val enabled: Boolean, val message: String)

    @Volatile private var features: Map<String, Feature> = emptyMap()
    @Volatile private var banned = false
    @Volatile private var banText = ""
    @Volatile private var banShown = false
    @Volatile private var inited = false

    lateinit var installId: String
        private set

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(): Boolean = libOk && nativeConfigured()

    fun init(c: Context) {
        if (inited) return
        inited = true
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        installId = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install_id", it).apply()
        }
        LogRecorder.i("RemoteConfig", "install id $installId, signing key ${if (isConfigured()) "set" else "not set (off)"}")
        // the saved copy is checked again every time, so editing it on the phone does nothing
        prefs.getString("raw", null)?.let { apply(c, it, store = false) }
    }

    fun refreshIfStale(c: Context) {
        if (!isConfigured()) return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("fetched", 0) < STALE_MS) return
        Thread {
            try {
                client.newCall(Request.Builder().url(URL).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val raw = r.body?.string().orEmpty()
                    if (raw.length < 600_000 && apply(c, raw, store = true)) {
                        prefs.edit().putLong("fetched", System.currentTimeMillis()).apply()
                    }
                }
            } catch (e: Exception) {
                LogRecorder.i("RemoteConfig", "couldn't fetch: ${e.javaClass.simpleName}")
            }
        }.start()
    }

    // true when the config was valid and is now in use
    private fun apply(c: Context, raw: String, store: Boolean): Boolean {
        try {
            if (!isConfigured()) return false
            val outer = JSONObject(raw)
            val payload = outer.getString("payload")
            val sig = Base64.decode(outer.getString("sig"), Base64.NO_WRAP)
            if (!nativeVerify(payload.toByteArray(Charsets.UTF_8), sig)) {
                LogRecorder.w("RemoteConfig", "signature check failed, config ignored")
                return false
            }
            val p = JSONObject(payload)
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seq = p.optLong("seq", 0)
            val exp = p.optLong("exp", 0)
            if (exp > 0 && System.currentTimeMillis() / 1000 > exp) return false      // expired
            if (store && seq < prefs.getLong("seq", 0)) return false                   // an older copy replayed

            val f = HashMap<String, Feature>()
            p.optJSONObject("features")?.let { fo ->
                for (k in fo.keys()) {
                    val e = fo.optJSONObject(k) ?: continue
                    f[k] = Feature(e.optBoolean("enabled", true), e.optString("message", ""))
                }
            }
            val b = p.optJSONObject("banned")
            var isBanned = false
            var text = ""
            if (b != null) {
                text = b.optString("message", "")
                val ids = b.optJSONArray("install_ids")
                for (i in 0 until (ids?.length() ?: 0)) if (ids!!.getString(i).equals(installId, true)) isBanned = true
                val handle = Session.accountHandle.trim().removePrefix("@").lowercase()
                val hs = b.optJSONArray("handles")
                if (handle.isNotEmpty()) for (i in 0 until (hs?.length() ?: 0)) {
                    if (hs!!.getString(i).trim().removePrefix("@").lowercase() == handle) isBanned = true
                }
            }
            features = f
            banned = isBanned
            banText = text
            if (store) prefs.edit().putString("raw", raw).putLong("seq", seq).apply()
            LogRecorder.i("RemoteConfig", "config #$seq in use, ${f.size} feature rules, banned=$isBanned")
            // things that are already running pick the change up now
            LunoFx.reapply()
            LunoVideoFx.reapply()
            return true
        } catch (e: Exception) {
            LogRecorder.w("RemoteConfig", "bad config", e)
            return false
        }
    }

    fun isEnabled(feature: String): Boolean = !banned && (features[feature]?.enabled ?: true)

    fun isBanned(): Boolean = banned

    private fun disabledMessage(feature: String): String =
        features[feature]?.message?.takeIf { it.isNotBlank() } ?: "This feature has been disabled by the admin."

    // true = go ahead. false = a popup is shown, onBlocked runs when it's closed
    fun gate(activity: Activity, feature: String, onBlocked: () -> Unit = {}): Boolean {
        if (isEnabled(feature)) return true
        val msg = if (banned) banMessage() else disabledMessage(feature)
        showMessagePopup(
            activity, Emote.FEATURE_DISABLED, if (banned) "Banned by admin" else "Feature disabled", msg,
            confirmText = "OK", onConfirm = onBlocked, cancelable = false,
        )
        return false
    }

    private fun banMessage(): String =
        (banText.ifBlank { "You have been banned by the admin." }) + "\n\nInstall ID: $installId"

    // shown once per app run, the app closes when it's confirmed
    fun checkBan(activity: Activity) {
        if (!banned || banShown) return
        banShown = true
        showMessagePopup(
            activity, Emote.FEATURE_DISABLED, "Banned by admin", banMessage(),
            confirmText = "Close app", onConfirm = { activity.finishAffinity() }, cancelable = false,
        )
    }
}
