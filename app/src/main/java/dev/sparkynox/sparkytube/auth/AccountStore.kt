package dev.sparkynox.sparkytube.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sparkynox.sparkytube.logs.LogRecorder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Several YouTube accounts on one phone. Each account is a saved copy of what Session holds
 * (cookies + visitor data + profile). Switching writes that copy back into Session and the
 * WebView cookie jar, so the rest of the app doesn't know anything changed.
 */
object AccountStore {

    class Account(
        val id: String,
        val name: String,
        val handle: String,
        val photo: String,
        val cookie: String,
        val visitor: String,
        val sync: String,
        val clientVersion: String,
    ) {
        fun toSnapshot() = Session.Snapshot(cookie, visitor, sync, clientVersion, name, handle, photo)
        fun toJson() = JSONObject()
            .put("id", id).put("name", name).put("handle", handle).put("photo", photo)
            .put("cookie", cookie).put("visitor", visitor).put("sync", sync).put("cv", clientVersion)
    }

    private lateinit var sp: SharedPreferences

    var accounts by mutableStateOf<List<Account>>(emptyList()); private set
    var activeId by mutableStateOf<String?>(null); private set

    // set while the "add account" login is running, so a cancelled login can put the old account back
    private var pendingAdd = false

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("sparky_accounts", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(sp.getString("list", "[]")) }.getOrDefault(JSONArray())
        accounts = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Account(
                o.optString("id"), o.optString("name"), o.optString("handle"), o.optString("photo"),
                o.optString("cookie"), o.optString("visitor"), o.optString("sync"), o.optString("cv"),
            ).takeIf { it.id.isNotEmpty() && it.cookie.isNotEmpty() }
        }
        activeId = sp.getString("active", null)
        // app was killed and the cookie jar lost its session cookies: put the active account back
        if (activeId != null && !Session.jarLoggedIn()) {
            accounts.firstOrNull { it.id == activeId }?.let { Session.restore(it.toSnapshot()) }
        }
        syncActive()
    }

    private fun save() {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        sp.edit().putString("list", arr.toString()).putString("active", activeId).apply()
    }

    private fun idOf(s: Session.Snapshot): String {
        if (s.sync.isNotBlank()) return "sync_" + s.sync
        val sap = Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(s.cookie)?.groupValues?.get(1).orEmpty()
        return "sap_" + sap.hashCode().toUInt().toString(16)
    }

    /** Copies whatever Session holds right now into the active account's record (cookies rotate). */
    fun syncActive() {
        val s = Session.snapshot() ?: return
        val id = idOf(s)
        val old = accounts.firstOrNull { it.id == id }
        val merged = Account(
            id,
            s.name.ifBlank { old?.name.orEmpty() },
            s.handle.ifBlank { old?.handle.orEmpty() },
            s.photo.ifBlank { old?.photo.orEmpty() },
            s.cookie, s.visitor.ifBlank { old?.visitor.orEmpty() },
            s.sync.ifBlank { old?.sync.orEmpty() },
            s.clientVersion.ifBlank { old?.clientVersion.orEmpty() },
        )
        accounts = if (old != null) accounts.map { if (it.id == id) merged else it } else accounts + merged
        activeId = id
        save()
    }

    /** Called once the feed knows the account's name / photo. */
    fun updateProfile(name: String, handle: String, photo: String) {
        val id = activeId ?: return
        val me = accounts.firstOrNull { it.id == id } ?: return
        val updated = Account(id, name, handle, photo, me.cookie, me.visitor, me.sync, me.clientVersion)
        // same channel handle under a different id means the same person logged in twice
        val h = handle.trim().lowercase()
        accounts = accounts
            .filter { it.id == id || h.isEmpty() || it.handle.trim().lowercase() != h }
            .map { if (it.id == id) updated else it }
        save()
    }

    /** Switches to [id]. False when nothing changed. */
    fun switchTo(id: String): Boolean {
        if (id == activeId && Session.loggedIn) return false
        syncActive()
        val a = accounts.firstOrNull { it.id == id } ?: return false
        Session.restore(a.toSnapshot())
        activeId = id
        save()
        LogRecorder.i("Accounts", "switched account")
        return true
    }

    /** Removes [id]. If it was the active one the next account (or signed-out) takes over. */
    fun remove(id: String) {
        val wasActive = id == activeId
        accounts = accounts.filter { it.id != id }
        if (wasActive) {
            val next = accounts.firstOrNull()
            if (next != null) {
                Session.restore(next.toSnapshot()); activeId = next.id
            } else {
                Session.logout(); activeId = null
            }
        }
        save()
        LogRecorder.i("Accounts", "account removed")
    }

    // ---------- add account

    // dataSyncId of the account that was active when "Add account" was pressed
    var previousSync: String? = null; private set

    /** Saves the current account; the login then runs through Google's own account chooser. */
    fun beginAdd() {
        syncActive()
        previousSync = Session.dataSyncId?.takeIf { it.isNotBlank() }
        pendingAdd = true
        addStarted = true
    }

    // stays true until finishAdd, because the login watcher cancels (pendingAdd = false) just before saving
    private var addStarted = false

    fun finishAdd() {
        pendingAdd = false
        if (addStarted) {
            addStarted = false
            // Session still shows the old account's profile, the new one is filled in by the feed
            Session.accountName = ""; Session.accountHandle = ""; Session.accountPhoto = ""
        }
        syncActive()
    }

    /** Add flow ended without a new account. The old one was never touched, nothing to restore. */
    fun cancelAdd() { pendingAdd = false }

    val adding: Boolean get() = pendingAdd
}
