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
