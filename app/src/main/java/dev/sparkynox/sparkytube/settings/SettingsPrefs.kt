package dev.sparkynox.sparkytube.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * One place for every v1.7 settings toggle instead of scattering raw
 * SharedPreferences keys/gets across MainActivity. Everything here reads
 * live off SharedPreferences (no caching), since SettingsActivity and
 * MainActivity are separate screens and prefs need to reflect whatever
 * was last saved, not a stale in-memory copy.
 */
object SettingsPrefs {

    private const val PREFS_NAME = "sparkytube_settings"

    private const val KEY_POPUPS_BLOCKED = "popups_blocked"
    private const val KEY_UPDATER_ENABLED = "updater_enabled"
    private const val KEY_EXPERIMENTAL_FEATURES = "experimental_features"
    private const val KEY_ANIME_STREAMING_ENABLED = "anime_streaming_enabled"
    private const val KEY_ADBLOCK_ENABLED = "adblock_enabled"
    private const val KEY_DOWNLOAD_ENABLED = "download_enabled"
    private const val KEY_CUSTOM_CSS = "custom_css"
    private const val KEY_CUSTOM_CSS_ENABLED = "custom_css_enabled"
    private const val KEY_RELATED_VIDEOS_FETCHER = "related_videos_fetcher"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Blocks the every-launch contact-reminder popup, the update-available
    // dialog, and the first-launch welcome dialog. Does NOT block error
    // dialogs (download failed, can't play this video, etc.) — those are
    // direct feedback about something the user just did, not unsolicited
    // messaging, so silencing them would hide real problems instead of
    // just reducing noise.
    fun arePopupsBlocked(context: Context) =
        prefs(context).getBoolean(KEY_POPUPS_BLOCKED, false)

    fun setPopupsBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_POPUPS_BLOCKED, blocked).apply()
    }

    fun isUpdaterEnabled(context: Context) =
        prefs(context).getBoolean(KEY_UPDATER_ENABLED, true)

    fun setUpdaterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_UPDATER_ENABLED, enabled).apply()
    }

    // Gate for features still being tried out — off by default so nobody
    // gets an experimental feature without opting in first.
    fun areExperimentalFeaturesEnabled(context: Context) =
        prefs(context).getBoolean(KEY_EXPERIMENTAL_FEATURES, false)

    fun setExperimentalFeaturesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPERIMENTAL_FEATURES, enabled).apply()
    }

    fun isAnimeStreamingEnabled(context: Context) =
        prefs(context).getBoolean(KEY_ANIME_STREAMING_ENABLED, true)

    fun setAnimeStreamingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANIME_STREAMING_ENABLED, enabled).apply()
    }

    fun isAdBlockEnabled(context: Context) =
        prefs(context).getBoolean(KEY_ADBLOCK_ENABLED, true)

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADBLOCK_ENABLED, enabled).apply()
    }

    fun isDownloadEnabled(context: Context) =
        prefs(context).getBoolean(KEY_DOWNLOAD_ENABLED, true)

    fun setDownloadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DOWNLOAD_ENABLED, enabled).apply()
    }

    fun isCustomCssEnabled(context: Context) =
        prefs(context).getBoolean(KEY_CUSTOM_CSS_ENABLED, false)

    fun setCustomCssEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_CSS_ENABLED, enabled).apply()
    }

    fun getCustomCss(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_CSS, "") ?: ""

    fun setCustomCss(context: Context, css: String) {
        prefs(context).edit().putString(KEY_CUSTOM_CSS, css).apply()
    }

    /**
     * Which method the suggestions panel uses to fetch related videos.
     * JAVASCRIPT reads the WebView's own already-rendered DOM (fast, no
     * extra network call, but only works when the watch page is actually
     * loaded there — fails in mini-player mode with WebView elsewhere).
     * NEWPIPE always uses NewPipeExtractor's getRelatedItems() (works
     * everywhere, slower, one more network round-trip). AUTO tries
     * JavaScript first and falls back to NewPipe only if that comes back
     * empty — the default, since it gets the JS speed win in the common
     * case without breaking the mini-player scenario.
     */
    enum class RelatedVideosFetcher { JAVASCRIPT, NEWPIPE, AUTO }

    fun getRelatedVideosFetcher(context: Context): RelatedVideosFetcher {
        val stored = prefs(context).getString(KEY_RELATED_VIDEOS_FETCHER, RelatedVideosFetcher.AUTO.name)
        return try {
            RelatedVideosFetcher.valueOf(stored ?: RelatedVideosFetcher.AUTO.name)
        } catch (e: IllegalArgumentException) {
            RelatedVideosFetcher.AUTO
        }
    }

    fun setRelatedVideosFetcher(context: Context, fetcher: RelatedVideosFetcher) {
        prefs(context).edit().putString(KEY_RELATED_VIDEOS_FETCHER, fetcher.name).apply()
    }
}
