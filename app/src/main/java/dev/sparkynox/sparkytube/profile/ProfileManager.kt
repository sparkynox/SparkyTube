package dev.sparkynox.sparkytube.profile

import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A profile — like Netflix/Crunchyroll's "who's watching" screen, but for
 * SparkyTube: each profile is its own YouTube login session (its own
 * cookie-jar, saved/restored on switch) plus its own copy of every
 * Settings toggle (SettingsPrefs), plus a display name and optional
 * custom PFP.
 *
 * id is a stable internal identifier used to build SettingsPrefs' backing
 * SharedPreferences file name and the cookie-jar file name — never shown
 * to the person and never reused once a profile is deleted (see
 * ProfileManager.createProfile), so a deleted profile's leftover files on
 * disk can never collide with a new one.
 */
data class Profile(
    val id: String,
    val name: String,
    val pfpPath: String? // absolute path under filesDir, or null for the default icon
)

/**
 * Owns the profile list, which one is active, and the mechanics of
 * switching between them. SettingsPrefs itself doesn't know profiles
 * exist — it just reads whatever SharedPreferences file name
 * currentSettingsPrefsName() currently returns, so switching the active
 * profile and reloading the WebView's cookies is all this class needs to
 * do for every existing Settings screen to transparently apply to the
 * new profile.
 */
object ProfileManager {

    private const val REGISTRY_PREFS_NAME = "sparkytube_profiles"
    private const val KEY_PROFILES_JSON = "profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_NEXT_PROFILE_NUM = "next_profile_num"

    private const val DEFAULT_PROFILE_ID = "default"

    private fun registryPrefs(context: Context) =
        context.getSharedPreferences(REGISTRY_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The SharedPreferences file name SettingsPrefs.prefs() should use for
     * whichever profile is currently active. SettingsPrefs calls this on
     * every read/write (same "no caching, always live" philosophy it
     * already documents for its own values) so a profile switch takes
     * effect for the very next settings read, no restart needed.
     */
    fun currentSettingsPrefsName(context: Context): String {
        return "sparkytube_settings_${getActiveProfileId(context)}"
    }

    fun getActiveProfileId(context: Context): String {
        return registryPrefs(context).getString(KEY_ACTIVE_PROFILE_ID, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
    }

    fun getProfiles(context: Context): List<Profile> {
        val raw = registryPrefs(context).getString(KEY_PROFILES_JSON, null)
        if (raw == null) {
            // First-ever launch under the profile system: seed a single
            // "Profile 1" so there's always at least one profile and the
            // person's existing settings/cookies (saved under the
            // pre-profile SharedPreferences names) keep working under it
            // without needing an explicit migration step -- DEFAULT_PROFILE_ID
            // matches what SettingsPrefs' old un-suffixed behavior
            // effectively was, so nothing is lost.
            val seeded = listOf(Profile(DEFAULT_PROFILE_ID, "Profile 1", null))
            saveProfiles(context, seeded)
            return seeded
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Profile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    pfpPath = obj.optString("pfpPath").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            listOf(Profile(DEFAULT_PROFILE_ID, "Profile 1", null))
        }
    }

    private fun saveProfiles(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("pfpPath", p.pfpPath ?: "")
            })
        }
        registryPrefs(context).edit { putString(KEY_PROFILES_JSON, arr.toString()) }
    }

    fun getActiveProfile(context: Context): Profile {
        val activeId = getActiveProfileId(context)
        return getProfiles(context).firstOrNull { it.id == activeId } ?: getProfiles(context).first()
    }

    /**
     * Creates a new empty profile (default settings, logged-out WebView
     * session) and returns it -- does NOT switch to it. Caller decides
     * whether/when to call switchToProfile with the returned id.
     */
    fun createProfile(context: Context, name: String): Profile {
        val num = registryPrefs(context).getInt(KEY_NEXT_PROFILE_NUM, 2)
        registryPrefs(context).edit { putInt(KEY_NEXT_PROFILE_NUM, num + 1) }

        // Timestamp-suffixed rather than just the incrementing number
        // alone, so a profile id can never collide with a deleted
        // profile's old id even if the counter were somehow reset --
        // deleteProfile() intentionally never reclaims/reuses ids.
        val id = "profile_${num}_${System.currentTimeMillis()}"
        val profile = Profile(id, name.ifBlank { "Profile $num" }, null)

        val updated = getProfiles(context) + profile
        saveProfiles(context, updated)
        return profile
    }

    fun renameProfile(context: Context, profileId: String, newName: String) {
        val updated = getProfiles(context).map {
            if (it.id == profileId) it.copy(name = newName) else it
        }
        saveProfiles(context, updated)
    }

    fun setProfilePfp(context: Context, profileId: String, pfpPath: String?) {
        val updated = getProfiles(context).map {
            if (it.id == profileId) it.copy(pfpPath = pfpPath) else it
        }
        saveProfiles(context, updated)
    }

    /**
     * Deletes a profile's registry entry, its Settings SharedPreferences
     * file, its saved cookie-jar, and its PFP image file. Refuses to
     * delete the last remaining profile -- there must always be at least
     * one to switch into. If the deleted profile was the active one,
     * switches to whichever profile is now first in the list.
     */
    fun deleteProfile(context: Context, profileId: String) {
        val profiles = getProfiles(context)
        if (profiles.size <= 1) return
        val remaining = profiles.filter { it.id != profileId }
        saveProfiles(context, remaining)

        context.getSharedPreferences("sparkytube_settings_$profileId", Context.MODE_PRIVATE)
            .edit { clear() }
        cookieJarFile(context, profileId).delete()

        profiles.firstOrNull { it.id == profileId }?.pfpPath?.let { path ->
            File(path).delete()
        }

        if (getActiveProfileId(context) == profileId) {
            // Not saving the outgoing profile's cookies here since it's
            // the one being deleted -- no session worth preserving for a
            // profile that no longer exists. Move the active pointer to
            // whichever profile is next, then load its jar into the
            // WebView's cookie store immediately (rather than waiting for
            // an app restart) so the currently-open WebView's session
            // matches whichever profile is now active.
            registryPrefs(context).edit { putString(KEY_ACTIVE_PROFILE_ID, remaining.first().id) }
            val cookieManager = android.webkit.CookieManager.getInstance()
            clearActiveCookies(cookieManager)
            restoreCookiesForProfile(context, remaining.first().id, cookieManager)
        }
    }

    private fun cookieJarFile(context: Context, profileId: String): File =
        File(context.filesDir, "cookiejar_$profileId.txt")

    /**
     * Saves the given CookieManager's current cookies for youtube.com
     * (and its subdomains) to this profile's cookie-jar file on disk.
     * Call this right before switching away from a profile so its login
     * session is captured as of the moment of switching, not as of
     * whenever it was last explicitly saved.
     *
     * CookieManager has no "export all cookies for a domain as one blob"
     * API, so this reads getCookie() for each known relevant host --
     * that call returns a single "; "-joined "name=value" string covering
     * every cookie set for that host, which is exactly the format
     * setCookie() takes one pair at a time, so splitting and restoring
     * per-pair round-trips cleanly.
     */
    fun saveCookiesForProfile(context: Context, profileId: String, cookieManager: CookieManager) {
        val hosts = listOf(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://youtube.com",
            "https://accounts.google.com",
            "https://google.com"
        )
        val jar = JSONObject()
        hosts.forEach { host ->
            val cookieString = cookieManager.getCookie(host)
            if (!cookieString.isNullOrBlank()) {
                jar.put(host, cookieString)
            }
        }
        cookieJarFile(context, profileId).writeText(jar.toString())
    }

    /**
     * Restores a profile's saved cookie-jar into the given CookieManager,
     * replacing whatever session is currently loaded there. Call this
     * right after switching to a profile, before the WebView (re)loads
     * YouTube -- setCookie() takes effect for the next page load, not
     * retroactively for an already-rendered page.
     *
     * A profile with no saved cookie-jar yet (brand new, never logged in)
     * is a no-op here -- the WebView just loads YouTube logged out, same
     * as a fresh install.
     */
    fun restoreCookiesForProfile(context: Context, profileId: String, cookieManager: CookieManager) {
        val file = cookieJarFile(context, profileId)
        if (!file.exists()) return

        try {
            val jar = JSONObject(file.readText())
            jar.keys().forEach { host ->
                val cookieString = jar.getString(host)
                // Each "name=value" pair needs its own setCookie() call --
                // the API doesn't accept the whole "; "-joined string at once.
                cookieString.split(";").forEach { pair ->
                    val trimmed = pair.trim()
                    if (trimmed.isNotEmpty()) {
                        cookieManager.setCookie(host, trimmed)
                    }
                }
            }
            cookieManager.flush()
        } catch (e: Exception) {
            // Corrupt or unreadable jar -- fall through to a logged-out
            // session rather than crashing the profile switch over it.
        }
    }

    /**
     * Clears every cookie currently loaded in the WebView -- used before
     * restoring a different profile's jar, so the outgoing profile's
     * session cookies can't leak into the incoming profile's WebView
     * (e.g. still being logged into profile A's Google account while
     * profile B's jar only adds cookies on top instead of replacing them).
     */
    fun clearActiveCookies(cookieManager: CookieManager) {
        cookieManager.removeAllCookies(null)
    }

    /**
     * Switches the active profile. If currentWebViewCookieManager is
     * non-null, saves the outgoing profile's cookies first (capturing
     * its login session as of right now) -- pass null when the outgoing
     * session shouldn't be captured (e.g. deleteProfile() switching away
     * from a profile that's being deleted anyway).
     *
     * Does NOT restore the new profile's cookies or reload the WebView
     * itself -- that's MainActivity's job right after this returns
     * (needs the WebView instance, which this object deliberately
     * doesn't hold a reference to).
     */
    fun switchToProfile(context: Context, newProfileId: String, currentWebViewCookieManager: CookieManager?) {
        if (currentWebViewCookieManager != null) {
            saveCookiesForProfile(context, getActiveProfileId(context), currentWebViewCookieManager)
        }
        registryPrefs(context).edit { putString(KEY_ACTIVE_PROFILE_ID, newProfileId) }
    }
}
