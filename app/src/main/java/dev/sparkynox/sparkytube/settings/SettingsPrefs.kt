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

    // Single shared-prefs file for everyone -- the per-profile system that
    // used to swap this file based on the active profile has been removed
    // entirely, so this is now just a normal fixed-name prefs file. The
    // old default profile's file was "sparkytube_settings_default" --
    // migrated once below so existing users don't lose their toggles.
    private const val PREFS_FILE_NAME = "sparkytube_settings"
    private const val OLD_DEFAULT_PROFILE_PREFS_NAME = "sparkytube_settings_default"
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
    private const val KEY_PIPED_FALLBACK_ENABLED = "piped_fallback_enabled"
    private const val KEY_YTDLP_FALLBACK_ENABLED = "ytdlp_fallback_enabled"
    private const val KEY_DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"
    private const val KEY_SPONSORBLOCK_ENABLED = "sponsorblock_enabled"
    private const val KEY_SPONSORBLOCK_CATEGORIES = "sponsorblock_categories"
    private const val KEY_DEFAULT_QUALITY = "default_quality_value"
    private const val KEY_MUSIC_MODE_ACTIVE = "music_mode_active"
    private const val KEY_LUNO_VOICE_ENABLED = "luno_voice_enabled"
    private const val KEY_LUNO_VOICE_WAKE_WORD_MODE = "luno_voice_wake_word_mode"

    @Volatile
    private var migrated = false

    private fun prefs(context: Context): SharedPreferences {
        val current = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        if (!migrated) {
            migrateFromOldProfilePrefsIfNeeded(context, current)
            migrated = true
        }
        return current
    }

    /**
     * One-time copy of every key from the old "sparkytube_settings_default"
     * file (the default profile's settings, back when the profile system
     * existed) into the new fixed file, so people upgrading from a version
     * with profiles don't have all their toggles silently reset. Only
     * copies keys the new file doesn't already have -- safe to call every
     * process start, effectively a no-op after the first run since the new
     * file will already contain everything.
     */
    private fun migrateFromOldProfilePrefsIfNeeded(context: Context, current: SharedPreferences) {
        if (current.getBoolean("__migrated_from_profile_prefs", false)) return

        val old = context.getSharedPreferences(OLD_DEFAULT_PROFILE_PREFS_NAME, Context.MODE_PRIVATE)
        val oldEntries = old.all
        if (oldEntries.isNotEmpty()) {
            current.edit().apply {
                oldEntries.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
                putBoolean("__migrated_from_profile_prefs", true)
                apply()
            }
        } else {
            current.edit().putBoolean("__migrated_from_profile_prefs", true).apply()
        }
    }

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
    enum class ExtractorMethod { AUTO, LOCAL_SERVER, NEWPIPE, YT_DLP }

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

    // Piped fallback: on by default -- it's a free network call to a
    // public instance, no extra APK weight, so there's no real downside
    // to leaving it on for everyone. Mainly fixes age-restricted videos
    // that NewPipeExtractor's anonymous scraping can't get past.
    fun isPipedFallbackEnabled(context: Context) =
        prefs(context).getBoolean(KEY_PIPED_FALLBACK_ENABLED, true)

    fun setPipedFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PIPED_FALLBACK_ENABLED, enabled).apply()
    }

    // yt-dlp (youtubedl-android) fallback: OFF by default. Unlike Piped,
    // this bundles a real Python + yt-dlp runtime into the APK (~30-40MB)
    // and spins up an actual process per call, so it's opt-in from
    // Settings rather than always-on -- people who don't need the extra
    // reliability shouldn't pay the size/perf cost for it.
    fun isYtDlpFallbackEnabled(context: Context) =
        prefs(context).getBoolean(KEY_YTDLP_FALLBACK_ENABLED, false)

    fun setYtDlpFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_YTDLP_FALLBACK_ENABLED, enabled).apply()
    }

    // Dynamic Color (Material You wallpaper theming, Android 12+). Off by
    // default -- see SparkyTubeApp.onCreate for why. Requires an app
    // restart to take/undo effect since DynamicColors.applyToActivitiesIfAvailable
    // hooks in at Application startup, not per-Activity.
    fun isDynamicColorEnabled(context: Context) =
        prefs(context).getBoolean(KEY_DYNAMIC_COLOR_ENABLED, false)

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR_ENABLED, enabled).apply()
    }

    // Default video quality -- every extractor's "which quality do I pick
    // when the video first loads" logic reads this instead of a hardcoded
    // 360. Stored as the target resolution number (e.g. 144/240/360/480/
    // 720/1080); each resolver still does closest-match against whatever
    // qualities that particular video actually has available, same as
    // before, just against this target instead of a fixed 360.
    fun getDefaultQualityValue(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_QUALITY, 360)

    fun setDefaultQualityValue(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_QUALITY, value).apply()
    }

    // Music/audio mode is a global toggle -- once turned on, it applies
    // to every video played until the user turns it off again, not just
    // the current one. Persisted so it survives app restarts too.
    fun isMusicModeActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUSIC_MODE_ACTIVE, false)

    fun setMusicModeActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUSIC_MODE_ACTIVE, active).apply()
    }

    // Luno Voice (Beta) -- offline Vosk-based voice commands ("Luno search
    // X", "Luno open library", etc). Master toggle off by default since
    // it needs RECORD_AUDIO permission + unpacks a ~40MB model on first
    // enable. wakeWordMode picks between push-to-talk (user taps a mic
    // button to speak one command) and always-listening (LunoVoiceService
    // keeps the mic open for the "Luno" wake word the whole time the app
    // is in the foreground) -- see LunoVoiceService.kt for the mode itself.
    fun isLunoVoiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LUNO_VOICE_ENABLED, false)

    fun setLunoVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LUNO_VOICE_ENABLED, enabled).apply()
    }

    // true = always-listening wake-word mode, false = push-to-talk (default,
    // since it doesn't need the mic running constantly in the background)
    fun isLunoVoiceWakeWordModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LUNO_VOICE_WAKE_WORD_MODE, false)

    fun setLunoVoiceWakeWordModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LUNO_VOICE_WAKE_WORD_MODE, enabled).apply()
    }

    // SponsorBlock: auto-skips sponsor/self-promo/intro/outro/etc segments
    // using the community-maintained sponsor.ajay.app database, same as
    // the browser extension. Default categories match the extension's
    // own defaults (sponsor + self-promo + interaction-reminder on;
    // intro/outro/preview/filler/music-offtopic off, since those are
    // more subjective about what counts as "skippable").
    private val DEFAULT_SPONSORBLOCK_CATEGORIES = setOf(
        dev.sparkynox.sparkytube.sponsorblock.SponsorBlockManager.Category.SPONSOR,
        dev.sparkynox.sparkytube.sponsorblock.SponsorBlockManager.Category.SELFPROMO,
        dev.sparkynox.sparkytube.sponsorblock.SponsorBlockManager.Category.INTERACTION
    )

    fun isSponsorBlockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPONSORBLOCK_ENABLED, true)

    fun setSponsorBlockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPONSORBLOCK_ENABLED, enabled).apply()
    }

    fun getSponsorBlockCategories(context: Context): Set<dev.sparkynox.sparkytube.sponsorblock.SponsorBlockManager.Category> {
        val stored = prefs(context).getStringSet(KEY_SPONSORBLOCK_CATEGORIES, null) ?: return DEFAULT_SPONSORBLOCK_CATEGORIES
        return stored.mapNotNull { name ->
            dev.sparkynox.sparkytube.sponsorblock.SponsorBlockManager.Category.entries.firstOrNull { it.name == name }
        }.toSet()
    }

    fun setSponsorBlockCategories(context: Context, categories: Set<dev.sparkynox.sparkytube.sponsorblock.SponsorBlockManager.Category>) {
        prefs(context).edit().putStringSet(KEY_SPONSORBLOCK_CATEGORIES, categories.map { it.name }.toSet()).apply()
    }
}
