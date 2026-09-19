package dev.sparkynox.sparkytube.settings

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import dev.sparkynox.sparkytube.databinding.ActivitySettingsBinding
import dev.sparkynox.sparkytube.databinding.SettingsSwitchRowBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fade-up entrance for the whole settings list -- a small
        // polish touch so the screen doesn't feel like a flat instant
        // cut when it opens. Matches the same treatment on LocalServerActivity.
        binding.settingsScroll.alpha = 0f
        binding.settingsScroll.translationY = 24f
        binding.settingsScroll.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()

        binding.settingsBackBtn.setOnClickListener { finish() }

        setupRow(
            binding.rowAnimeStreaming, "Anime streaming",
            "Crunchyroll wrapper, reachable from the overflow menu",
            SettingsPrefs::isAnimeStreamingEnabled, SettingsPrefs::setAnimeStreamingEnabled
        )
        setupRow(
            binding.rowAdBlock, "Ad blocking",
            "Blocks ad/tracker domains and hides ad slots",
            SettingsPrefs::isAdBlockEnabled, SettingsPrefs::setAdBlockEnabled
        )
        setupRow(
            binding.rowDownload, "Downloads",
            "Shows the download button on videos",
            SettingsPrefs::isDownloadEnabled, SettingsPrefs::setDownloadEnabled
        )
        setupRow(
            binding.rowPopups, "Block all popups",
            "Hides the contact-reminder, update, and welcome dialogs",
            SettingsPrefs::arePopupsBlocked, SettingsPrefs::setPopupsBlocked
        )
        setupRow(
            binding.rowUpdater, "Check for updates",
            "Looks for a new SparkyTube version on app open",
            SettingsPrefs::isUpdaterEnabled, SettingsPrefs::setUpdaterEnabled
        )
        setupLogsRow()
        setupDownloadsManagerRow()
        setupOfflineLibraryRow()
        setupYtSettingsRow()
        setupRelatedFetcherRow()
        setupSponsorBlockRow()
        setupLunoVoiceRows()
        setupLocalServersRow()
        setupExtractorMethodRow()
        setupDefaultQualityRow()
        setupRow(
            binding.rowPipedFallback, "Piped fallback",
            "Fixes age-restricted videos by routing through a public Piped instance when NewPipe fails",
            SettingsPrefs::isPipedFallbackEnabled, SettingsPrefs::setPipedFallbackEnabled
        )
        setupYtDlpFallbackRow()
        setupDynamicColorRow()
        setupDataSaverRow()
        setupNativeHomeFeedRow()
        setupLumiAiRow()
        setupRow(
            binding.rowCustomCss, "Custom CSS",
            "Your own CSS, applied on top of SparkyTube's",
            SettingsPrefs::isCustomCssEnabled, SettingsPrefs::setCustomCssEnabled
        )

        binding.customCssInput.setText(SettingsPrefs.getCustomCss(this))
        binding.saveCustomCssBtn.setOnClickListener {
            SettingsPrefs.setCustomCss(this, binding.customCssInput.text?.toString().orEmpty())
            Toast.makeText(this, "CSS saved", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Not a toggle — a navigation shortcut straight to YouTube's own
     * settings page (https://m.youtube.com/select_site, confirmed by
     * actually tapping through YouTube's own menu — there's no
     * documented/stable URL for this, so this is what was found working
     * rather than a guess). Reuses the switch-row layout for consistent
     * styling but hides the switch itself since there's nothing to toggle
     * — the whole row is the tap target instead.
     */
    private fun setupYtSettingsRow() {
        binding.rowYtSettings.rowTitle.text = "YT Settings"
        val subtitleView: TextView = binding.rowYtSettings.rowSubtitle
        subtitleView.text = "Open YouTube's own settings page"
        subtitleView.visibility = TextView.VISIBLE
        binding.rowYtSettings.rowSwitch.visibility = android.view.View.GONE

        binding.rowYtSettings.root.setOnClickListener {
            val intent = android.content.Intent(this, dev.sparkynox.sparkytube.MainActivity::class.java).apply {
                putExtra(dev.sparkynox.sparkytube.MainActivity.EXTRA_OPEN_YT_SETTINGS, true)
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }

    /**
     * Which method the suggestions panel uses to fetch related videos —
     * see SettingsPrefs.RelatedVideosFetcher for what each option means.
     * A picker dialog rather than a switch since there are three options,
     * not two.
     */
    private fun setupSponsorBlockRow() {
        binding.rowSponsorBlock.rowTitle.text = "SponsorBlock"
        val subtitleView: TextView = binding.rowSponsorBlock.rowSubtitle
        subtitleView.text = "Auto-skip sponsor segments, self-promo, interaction reminders"
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = binding.rowSponsorBlock.rowSwitch
        switch.isChecked = SettingsPrefs.isSponsorBlockEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            SettingsPrefs.setSponsorBlockEnabled(this, isChecked)
        }
    }

    /**
     * Two rows: master on/off for the whole Luno Voice feature (needs
     * RECORD_AUDIO + unpacks the ~40MB Vosk model on first enable, so
     * kept off by default), and a trigger-mode picker that only makes
     * sense once voice is on — push-to-talk (tap a mic button, speak one
     * command) vs always-listening wake-word (mic stays open for "Luno"
     * the whole time the app's in the foreground, see LunoWakeWordLoop).
     * Mode row is disabled/greyed when the master toggle is off so it's
     * obvious the two are linked.
     */
    private fun setupLunoVoiceRows() {
        binding.rowLunoVoiceEnabled.rowTitle.text = "Enable Luno Voice"
        val enabledSubtitle: TextView = binding.rowLunoVoiceEnabled.rowSubtitle
        enabledSubtitle.text = "Offline voice commands (\"Luno search...\", \"Luno open library\", etc) — needs microphone access"
        enabledSubtitle.visibility = TextView.VISIBLE

        val modeSubtitle: TextView = binding.rowLunoVoiceMode.rowSubtitle
        fun refreshModeRowSubtitle() {
            modeSubtitle.text = if (SettingsPrefs.isLunoVoiceWakeWordModeEnabled(this))
                "Always listening for \"Luno\" while the app is open"
            else
                "Tap the mic button, then speak one command"
        }

        binding.rowLunoVoiceMode.rowTitle.text = "Voice trigger"
        modeSubtitle.visibility = TextView.VISIBLE
        binding.rowLunoVoiceMode.rowSwitch.visibility = android.view.View.GONE
        refreshModeRowSubtitle()

        fun refreshModeRowEnabledState() {
            val voiceEnabled = SettingsPrefs.isLunoVoiceEnabled(this)
            binding.rowLunoVoiceMode.root.isEnabled = voiceEnabled
            binding.rowLunoVoiceMode.root.alpha = if (voiceEnabled) 1f else 0.4f
        }
        refreshModeRowEnabledState()

        val enabledSwitch: SwitchCompat = binding.rowLunoVoiceEnabled.rowSwitch
        enabledSwitch.isChecked = SettingsPrefs.isLunoVoiceEnabled(this)
        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsPrefs.setLunoVoiceEnabled(this, isChecked)
            refreshModeRowEnabledState()
        }

        binding.rowLunoVoiceMode.root.setOnClickListener {
            if (!SettingsPrefs.isLunoVoiceEnabled(this)) return@setOnClickListener
            val options = arrayOf("Push-to-talk (tap mic button)", "Always listening (wake word \"Luno\")")
            val currentIndex = if (SettingsPrefs.isLunoVoiceWakeWordModeEnabled(this)) 1 else 0
            AlertDialog.Builder(this)
                .setTitle("Voice trigger")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    SettingsPrefs.setLunoVoiceWakeWordModeEnabled(this, which == 1)
                    refreshModeRowSubtitle()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupRelatedFetcherRow() {
        binding.rowRelatedFetcher.rowTitle.text = "Related videos fetcher"
        binding.rowRelatedFetcher.rowSwitch.visibility = android.view.View.GONE
        val subtitleView: TextView = binding.rowRelatedFetcher.rowSubtitle
        subtitleView.visibility = TextView.VISIBLE
        refreshRelatedFetcherSubtitle(subtitleView)

        binding.rowRelatedFetcher.root.setOnClickListener {
            val options = arrayOf("Auto (recommended)", "JavaScript (fastest)", "NewPipe (most reliable)")
            val values = arrayOf(
                SettingsPrefs.RelatedVideosFetcher.AUTO,
                SettingsPrefs.RelatedVideosFetcher.JAVASCRIPT,
                SettingsPrefs.RelatedVideosFetcher.NEWPIPE
            )
            val currentIndex = values.indexOf(SettingsPrefs.getRelatedVideosFetcher(this))

            AlertDialog.Builder(this)
                .setTitle("Related videos fetcher")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    SettingsPrefs.setRelatedVideosFetcher(this, values[which])
                    refreshRelatedFetcherSubtitle(subtitleView)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshRelatedFetcherSubtitle(subtitleView: TextView) {
        subtitleView.text = when (SettingsPrefs.getRelatedVideosFetcher(this)) {
            SettingsPrefs.RelatedVideosFetcher.AUTO -> "Auto — tries JavaScript first, falls back to NewPipe"
            SettingsPrefs.RelatedVideosFetcher.JAVASCRIPT -> "JavaScript — fastest, only works while the video's page is loaded"
            SettingsPrefs.RelatedVideosFetcher.NEWPIPE -> "NewPipe — always works, one extra network request"
        }
    }

    /**
     * Navigation row into LocalServerActivity, where Lumi Fetcher's
     * enable toggle, server URL, and ping/online diagnostics actually
     * live. This row itself is just a shortcut + status summary so it's
     * visible at a glance from the main Settings screen whether a local
     * server is currently in use.
     */
    /**
     * Navigation row into LogsActivity -- shows a small "crash found"
     * hint in the subtitle when the last session ended in a crash, so
     * it's visible from the main Settings screen without needing to
     * open Logs first to find out.
     */
    private fun setupLogsRow() {
        binding.rowLogs.rowTitle.text = "Logs"
        val subtitleView: TextView = binding.rowLogs.rowSubtitle
        subtitleView.visibility = android.view.View.VISIBLE
        binding.rowLogs.rowSwitch.visibility = android.view.View.GONE
        binding.rowLogs.rowChevron.visibility = android.view.View.VISIBLE

        binding.rowLogs.root.setOnClickListener {
            startActivity(android.content.Intent(this, dev.sparkynox.sparkytube.logs.LogsActivity::class.java))
        }
    }

    /**
     * Navigation row into DownloadsActivity -- VidMate-style task manager
     * showing every download's progress, size, and speed. Also reachable
     * by long-pressing the download button in the player, but that's not
     * discoverable on its own, so this is the primary entry point.
     */
    private fun setupDownloadsManagerRow() {
        binding.rowDownloadsManager.rowTitle.text = "Downloads"
        val subtitleView: TextView = binding.rowDownloadsManager.rowSubtitle
        subtitleView.text = "See progress, speed, and manage active downloads"
        subtitleView.visibility = android.view.View.VISIBLE
        binding.rowDownloadsManager.rowSwitch.visibility = android.view.View.GONE
        binding.rowDownloadsManager.rowChevron.visibility = android.view.View.VISIBLE

        binding.rowDownloadsManager.root.setOnClickListener {
            startActivity(android.content.Intent(this, dev.sparkynox.sparkytube.download.DownloadsActivity::class.java))
        }
    }

    private fun setupOfflineLibraryRow() {
        binding.rowOfflineLibrary.rowTitle.text = "Offline Library"
        val subtitleView: TextView = binding.rowOfflineLibrary.rowSubtitle
        subtitleView.text = "Play or delete videos you've already downloaded"
        subtitleView.visibility = android.view.View.VISIBLE
        binding.rowOfflineLibrary.rowSwitch.visibility = android.view.View.GONE
        binding.rowOfflineLibrary.rowChevron.visibility = android.view.View.VISIBLE

        binding.rowOfflineLibrary.root.setOnClickListener {
            startActivity(android.content.Intent(this, dev.sparkynox.sparkytube.download.OfflineLibraryActivity::class.java))
        }
    }

    private fun setupLocalServersRow() {
        binding.rowLocalServers.rowTitle.text = "Local Servers"
        val subtitleView: TextView = binding.rowLocalServers.rowSubtitle
        subtitleView.visibility = TextView.VISIBLE
        binding.rowLocalServers.rowSwitch.visibility = android.view.View.GONE
        binding.rowLocalServers.rowChevron.visibility = android.view.View.VISIBLE

        binding.rowLocalServers.root.setOnClickListener {
            startActivity(android.content.Intent(this, LocalServerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // The local-server enabled state can change on LocalServerActivity,
        // so refresh this summary line every time Settings comes back into
        // view rather than only once in onCreate.
        binding.rowLocalServers.rowSubtitle.text = if (SettingsPrefs.isLocalServerEnabled(this)) {
            "Enabled — ${SettingsPrefs.getLocalServerUrl(this)}"
        } else {
            "Off — using NewPipe/JavaScript extraction"
        }

        // Same reasoning -- whether a crash happened can change any time
        // Settings isn't in the foreground, so refresh on every resume.
        binding.rowLogs.rowSubtitle.text = if (dev.sparkynox.sparkytube.logs.LogRecorder.getLastCrash(this) != null) {
            "⚠ Last session crashed — tap to view"
        } else {
            "Session log + crash reports"
        }
    }

    /**
     * Which extraction method resolveAndPlayNative uses, in priority
     * order -- see SettingsPrefs.ExtractorMethod for what each option
     * actually does. Picker dialog, same shape as the related-videos
     * fetcher row above it.
     */
    /**
     * Which quality every video starts at by default. Each resolver
     * closest-matches this target against whatever qualities that
     * specific video actually has available (see
     * StreamExtractor.preferredQualityTarget()), so picking e.g. 720
     * here doesn't break on a video that only goes up to 480 -- it just
     * picks 480 for that one video.
     */
    private fun setupDefaultQualityRow() {
        binding.rowDefaultQuality.rowTitle.text = "Default video quality"
        binding.rowDefaultQuality.rowSwitch.visibility = android.view.View.GONE
        val subtitleView: TextView = binding.rowDefaultQuality.rowSubtitle
        subtitleView.visibility = TextView.VISIBLE
        refreshDefaultQualitySubtitle(subtitleView)

        binding.rowDefaultQuality.root.setOnClickListener {
            val labels = arrayOf("144p", "240p", "360p", "480p", "720p", "1080p")
            val values = intArrayOf(144, 240, 360, 480, 720, 1080)
            val currentIndex = values.indexOf(SettingsPrefs.getDefaultQualityValue(this)).coerceAtLeast(2)

            AlertDialog.Builder(this)
                .setTitle("Default video quality")
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    SettingsPrefs.setDefaultQualityValue(this, values[which])
                    refreshDefaultQualitySubtitle(subtitleView)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshDefaultQualitySubtitle(subtitleView: TextView) {
        subtitleView.text = "${SettingsPrefs.getDefaultQualityValue(this)}p — applies to new videos as they load"
    }

    private fun setupExtractorMethodRow() {
        binding.rowExtractorMethod.rowTitle.text = "Extractor Method"
        binding.rowExtractorMethod.rowSwitch.visibility = android.view.View.GONE
        val subtitleView: TextView = binding.rowExtractorMethod.rowSubtitle
        subtitleView.visibility = TextView.VISIBLE
        refreshExtractorMethodSubtitle(subtitleView)

        binding.rowExtractorMethod.root.setOnClickListener {
            val options = arrayOf("Auto (recommended)", "Local Servers", "NewPipe", "yt-dlp")
            val values = arrayOf(
                SettingsPrefs.ExtractorMethod.AUTO,
                SettingsPrefs.ExtractorMethod.LOCAL_SERVER,
                SettingsPrefs.ExtractorMethod.NEWPIPE,
                SettingsPrefs.ExtractorMethod.YT_DLP
            )
            val currentIndex = values.indexOf(SettingsPrefs.getExtractorMethod(this))

            AlertDialog.Builder(this)
                .setTitle("Extractor Method")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    SettingsPrefs.setExtractorMethod(this, values[which])
                    refreshExtractorMethodSubtitle(subtitleView)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshExtractorMethodSubtitle(subtitleView: TextView) {
        subtitleView.text = when (SettingsPrefs.getExtractorMethod(this)) {
            SettingsPrefs.ExtractorMethod.AUTO -> "Auto — local server (if enabled) → JavaScript → NewPipe"
            SettingsPrefs.ExtractorMethod.LOCAL_SERVER -> "Local Servers only — set up under Local Servers above"
            SettingsPrefs.ExtractorMethod.NEWPIPE -> "NewPipe only — skips the local server and JS fast path"
            SettingsPrefs.ExtractorMethod.YT_DLP -> "yt-dlp only — requires the yt-dlp fallback toggle below to be on"
        }
    }

    /**
     * yt-dlp fallback (youtubedl-android): last-resort extractor for
     * age-restricted videos when both NewPipe and Piped fail. Off by
     * default since it bundles a Python + yt-dlp runtime -- warns once
     * about that before letting the user turn it on, since it's not
     * obvious from the toggle label alone.
     */
    private fun setupYtDlpFallbackRow() {
        binding.rowYtDlpFallback.rowTitle.text = "yt-dlp fallback (heavy)"
        val subtitleView: TextView = binding.rowYtDlpFallback.rowSubtitle
        subtitleView.text = "Last-resort extractor for age-gated videos. Adds ~30-40MB to the app."
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = binding.rowYtDlpFallback.rowSwitch
        switch.isChecked = SettingsPrefs.isYtDlpFallbackEnabled(this)
        switch.setOnCheckedChangeListener { switchView, isChecked ->
            if (isChecked && !SettingsPrefs.isYtDlpFallbackEnabled(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Enable yt-dlp fallback?")
                    .setMessage(
                        "This downloads and runs a bundled yt-dlp binary on-device as a " +
                        "last-resort fallback when NewPipe and Piped both fail to play a " +
                        "video (usually age-restricted ones). It's slower than the other " +
                        "methods and uses more storage/battery, but is the most reliable."
                    )
                    .setPositiveButton("Enable") { _, _ ->
                        SettingsPrefs.setYtDlpFallbackEnabled(this, true)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        switchView.isChecked = false
                    }
                    .setOnCancelListener { switchView.isChecked = false }
                    .show()
            } else {
                SettingsPrefs.setYtDlpFallbackEnabled(this, isChecked)
            }
        }
    }

    /**
     * Material You dynamic color -- maps colorPrimary/etc to the phone's
     * wallpaper palette on Android 12+ (no-op on older versions, handled
     * silently by DynamicColors itself). Takes effect on next app launch
     * since it hooks in at Application.onCreate, not per-Activity, so the
     * subtitle sets expectations rather than the toggle silently doing
     * nothing until restart.
     */
    private fun setupDynamicColorRow() {
        binding.rowDynamicColor.rowTitle.text = "Dynamic color (Material You)"
        val subtitleView: TextView = binding.rowDynamicColor.rowSubtitle
        subtitleView.text = "Matches app colors to your wallpaper on Android 12+. Restart app to apply."
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = binding.rowDynamicColor.rowSwitch
        switch.isChecked = SettingsPrefs.isDynamicColorEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            SettingsPrefs.setDynamicColorEnabled(this, isChecked)
        }
    }

    /**
     * Data Saver: blocks YouTube's own telemetry/prefetch beacons (see
     * blocklist.json's dataSaverPaths, AdBlockEngine.shouldBlockForDataSaver),
     * stops feed thumbnails/avatars from auto-loading, and starts
     * playback at the lowest available quality instead of ~360p. All
     * three only kick in while this is on -- separate switch from ad
     * blocking above it, since none of this is about ads.
     */
    private fun setupDataSaverRow() {
        binding.rowDataSaver.rowTitle.text = "Data Saver Mode"
        val subtitleView: TextView = binding.rowDataSaver.rowSubtitle
        subtitleView.text = "Blocks YouTube telemetry, disables images, starts videos at the lowest quality"
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = binding.rowDataSaver.rowSwitch
        switch.isChecked = SettingsPrefs.isDataSaverEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            SettingsPrefs.setDataSaverEnabled(this, isChecked)
        }
    }

    /**
     * v1.8 test: swaps the Home tab's WebView-rendered feed for a
     * natively-rendered one built from a hand-written InnerTube client
     * (homefeed/InnerTubeClient.kt), reusing whatever YouTube login
     * session is already in the WebView's CookieManager. Deliberately
     * scoped to Home only for now -- if this holds up, the same approach
     * can extend to Search/Subs/other feeds later.
     */
    private fun setupNativeHomeFeedRow() {
        binding.rowNativeHomeFeed.rowTitle.text = "Native Home Feed (Beta)"
        val subtitleView: TextView = binding.rowNativeHomeFeed.rowSubtitle
        subtitleView.text = "Renders Home natively instead of via WebView. Needs a YouTube login. Home only for now."
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = binding.rowNativeHomeFeed.rowSwitch
        switch.isChecked = SettingsPrefs.isNativeHomeFeedEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            SettingsPrefs.setNativeHomeFeedEnabled(this, isChecked)
        }
    }

    /**
     * Lumi AI — an early idea, staff-only (Owner/Mod/Admin) for now. There's
     * no login or role system anywhere in the app yet, so there's no real
     * way to check who's actually staff. Rather than fake that check, the
     * switch is hardcoded to always snap back off and show a "staff only,
     * not available yet" popup instead — this stays until an actual
     * account/role system exists to check against for real.
     */
    private fun setupLumiAiRow() {
        binding.rowExperimental.rowTitle.text = "Experimental features (Lumi AI)"
        val subtitleView: TextView = binding.rowExperimental.rowSubtitle
        subtitleView.text = "Staff only for now — not available to regular users yet"
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = binding.rowExperimental.rowSwitch
        switch.isChecked = false
        switch.setOnCheckedChangeListener { switchView, isChecked ->
            if (isChecked) {
                switchView.isChecked = false
                AlertDialog.Builder(this)
                    .setTitle("Coming soon")
                    .setMessage(
                        "Lumi AI is still being worked on and is staff only for now " +
                        "(Owner/Mod/Admin). It isn't available to regular users yet."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * Wires one included settings_switch_row to a title/subtitle and a
     * SettingsPrefs getter/setter pair — every row in this screen follows
     * the exact same "load current value, flip it on toggle" shape, so
     * this is the one place that shape lives instead of being repeated
     * seven times.
     */
    private fun setupRow(
        row: SettingsSwitchRowBinding,
        title: String,
        subtitle: String,
        getter: (android.content.Context) -> Boolean,
        setter: (android.content.Context, Boolean) -> Unit
    ) {
        row.rowTitle.text = title
        val subtitleView: TextView = row.rowSubtitle
        subtitleView.text = subtitle
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = row.rowSwitch
        switch.isChecked = getter(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            setter(this, isChecked)
        }
    }
}
