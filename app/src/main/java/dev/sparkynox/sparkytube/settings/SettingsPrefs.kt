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

    // Per-profile: ProfileManager.currentSettingsPrefsName() returns
    // "sparkytube_settings_<profileId>" for whichever profile is
    // currently active, so switching profiles transparently swaps which
    // SharedPreferences file every getter/setter below reads and writes,
    // with no other change needed anywhere in this file.
    private const val KEY_POPUPS_BLOCKED = "popups_blocked"
    private const val KEY_UPDATER_ENABLED = "updater_enabled"
    private const val KEY_EXPERIMENTAL_FEATURES = "experimental_features"
    private const val KEY_ANIME_STREAMING_ENABLED = "anime_streaming_enabled"
    private const val KEY_ADBLOCK_ENABLED = "adblock_enabled"
    private const val KEY_DATA_SAVER_ENABLED = "data_saver_enabled"
    private const val KEY_NATIVE_HOME_FEED_ENABLED = "native_home_feed_enabled"
    private const val KEY_DOWNLOAD_ENABLED = "download_enabled"
    private const val KEY_CUSTOM_CSS = "custom_css"
    private const val KEY_CUSTOM_CSS_ENABLED = "custom_css_enabled"
    private const val KEY_RELATED_VIDEOS_FETCHER = "related_videos_fetcher"
    private const val KEY_EXTRACTOR_METHOD = "extractor_method"
    private const val KEY_LOCAL_SERVER_TYPE = "local_server_type"
    private const val KEY_LOCAL_SERVER_URL = "local_server_url"
    private const val KEY_LOCAL_SERVER_ENABLED = "local_server_enabled"
    private const val KEY_LOCAL_SERVER_API_KEY = "local_server_api_key"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(
            dev.sparkynox.sparkytube.profile.ProfileManager.currentSettingsPrefsName(context),
            Context.MODE_PRIVATE
        )

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

    // Data Saver: blocks YouTube's own telemetry/prefetch beacons (see
    // blocklist.json's dataSaverPaths) and defaults the default playback
    // quality lower -- separate switch from ad-blocking above, since
    // these aren't ads and some people may want them for smoother
    // scrubbing/hover-preview even with ads blocked.
    fun isDataSaverEnabled(context: Context) =
        prefs(context).getBoolean(KEY_DATA_SAVER_ENABLED, false)

    fun setDataSaverEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DATA_SAVER_ENABLED, enabled).apply()
    }

    // v1.8 test flag: native (authenticated InnerTube) Home feed instead
    // of the WebView-rendered one. Off by default -- this is a fresh,
    // hand-written client (see homefeed/InnerTubeClient.kt) being tested
    // on Home only before any decision to expand it further.
    fun isNativeHomeFeedEnabled(context: Context) =
        prefs(context).getBoolean(KEY_NATIVE_HOME_FEED_ENABLED, false)

    fun setNativeHomeFeedEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NATIVE_HOME_FEED_ENABLED, enabled).apply()
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

    /**
     * Which method resolveAndPlayNative uses to get a playable stream URL.
     * AUTO tries the local server first (if enabled and reachable), then
     * the in-app JS fast path, then NewPipeExtractor -- same layered
     * fallback shape as RelatedVideosFetcher.AUTO above. LOCAL_SERVER and
     * NEWPIPE force a single method with no fallback, for people who want
     * predictable behavior (e.g. always use the phone's local server, or
     * always use NewPipe because the local server isn't running).
     */
    enum class ExtractorMethod { AUTO, LOCAL_SERVER, NEWPIPE }

    fun getExtractorMethod(context: Context): ExtractorMethod {
        val stored = prefs(context).getString(KEY_EXTRACTOR_METHOD, ExtractorMethod.AUTO.name)
        return try {
            ExtractorMethod.valueOf(stored ?: ExtractorMethod.AUTO.name)
        } catch (e: IllegalArgumentException) {
            ExtractorMethod.AUTO
        }
    }

    fun setExtractorMethod(context: Context, method: ExtractorMethod) {
        prefs(context).edit().putString(KEY_EXTRACTOR_METHOD, method.name).apply()
    }

    /**
     * Which local-server backend is selected. LUMI_FETCHER is the
     * youtubei.js-based Node.js server (see /sparkytube-server). SPARKYTUBE_SERVER
     * is reserved for a possible future first-party backend -- shown as
     * "Coming Soon" in the picker and not selectable yet.
     */
    enum class LocalServerType { LUMI_FETCHER, SPARKYTUBE_SERVER }

    fun getLocalServerType(context: Context): LocalServerType {
        val stored = prefs(context).getString(KEY_LOCAL_SERVER_TYPE, LocalServerType.LUMI_FETCHER.name)
        return try {
            LocalServerType.valueOf(stored ?: LocalServerType.LUMI_FETCHER.name)
        } catch (e: IllegalArgumentException) {
            LocalServerType.LUMI_FETCHER
        }
    }

    fun setLocalServerType(context: Context, type: LocalServerType) {
        prefs(context).edit().putString(KEY_LOCAL_SERVER_TYPE, type.name).apply()
    }

    // Defaults to the phone's own loopback address -- the common case is
    // Termux running right there on the same device. A LAN IP only needs
    // to be entered if the person is deliberately running the server on
    // a different device on the same Wi-Fi.
    fun getLocalServerUrl(context: Context): String =
        prefs(context).getString(KEY_LOCAL_SERVER_URL, "http://127.0.0.1:8420") ?: "http://127.0.0.1:8420"

    fun setLocalServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_LOCAL_SERVER_URL, url.trim()).apply()
    }

    fun isLocalServerEnabled(context: Context) =
        prefs(context).getBoolean(KEY_LOCAL_SERVER_ENABLED, false)

    fun setLocalServerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCAL_SERVER_ENABLED, enabled).apply()
    }

    // The key printed in the server's console log on startup (see
    // server.js's app.listen callback) -- sent as the x-api-key header on
    // every /extract request. Empty string means "no key entered yet",
    // which the server will reject with 401 once its own requireApiKey
    // middleware is active.
    fun getLocalServerApiKey(context: Context): String =
        prefs(context).getString(KEY_LOCAL_SERVER_API_KEY, "") ?: ""

    fun setLocalServerApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_LOCAL_SERVER_API_KEY, apiKey.trim()).apply()
    }
}
