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
        setupYtSettingsRow()
        setupProfilesRow()
        setupRelatedFetcherRow()
        setupLocalServersRow()
        setupExtractorMethodRow()
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
     * Navigation row into ProfilePickerActivity -- shows the active
     * profile's name so it's visible at a glance, same "summary line,
     * refreshed on resume" pattern rowLocalServers below already uses.
     */
    private fun setupProfilesRow() {
        binding.rowProfiles.rowTitle.text = "Profiles"
        val subtitleView: TextView = binding.rowProfiles.rowSubtitle
        subtitleView.visibility = TextView.VISIBLE
        binding.rowProfiles.rowSwitch.visibility = android.view.View.GONE
        binding.rowProfiles.rowChevron.visibility = android.view.View.VISIBLE

        binding.rowProfiles.root.setOnClickListener {
            startActivity(android.content.Intent(this, dev.sparkynox.sparkytube.profile.ProfilePickerActivity::class.java))
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

        // Same reasoning -- the active profile can change on
        // ProfilePickerActivity, refresh on every return to Settings.
        val activeProfile = dev.sparkynox.sparkytube.profile.ProfileManager.getActiveProfile(this)
        binding.rowProfiles.rowSubtitle.text = activeProfile.name
    }

    /**
     * Which extraction method resolveAndPlayNative uses, in priority
     * order -- see SettingsPrefs.ExtractorMethod for what each option
     * actually does. Picker dialog, same shape as the related-videos
     * fetcher row above it.
     */
    private fun setupExtractorMethodRow() {
        binding.rowExtractorMethod.rowTitle.text = "Extractor Method"
        binding.rowExtractorMethod.rowSwitch.visibility = android.view.View.GONE
        val subtitleView: TextView = binding.rowExtractorMethod.rowSubtitle
        subtitleView.visibility = TextView.VISIBLE
        refreshExtractorMethodSubtitle(subtitleView)

        binding.rowExtractorMethod.root.setOnClickListener {
            val options = arrayOf("Auto (recommended)", "Local Servers", "NewPipe")
            val values = arrayOf(
                SettingsPrefs.ExtractorMethod.AUTO,
                SettingsPrefs.ExtractorMethod.LOCAL_SERVER,
                SettingsPrefs.ExtractorMethod.NEWPIPE
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
