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
        setupRelatedFetcherRow()
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
