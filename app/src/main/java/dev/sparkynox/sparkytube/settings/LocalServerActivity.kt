package dev.sparkynox.sparkytube.settings

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import dev.sparkynox.sparkytube.databinding.ActivityLocalServerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * "Local Servers" screen — lets the person point SparkyTube's stream
 * extraction at a local server (currently only Lumi Fetcher, the
 * youtubei.js-based Node.js server meant to run in Termux on the same
 * phone — see /sparkytube-server) instead of always going through
 * NewPipeExtractor. Also holds the ping/online diagnostics so it's easy
 * to tell whether the server is actually reachable before relying on it.
 */
class LocalServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalServerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Simple fade-up entrance so the screen doesn't feel like a flat
        // instant-cut -- matches the same treatment SettingsActivity uses.
        binding.localServerScroll.alpha = 0f
        binding.localServerScroll.translationY = 24f
        binding.localServerScroll.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()

        binding.localServerBackBtn.setOnClickListener { finish() }

        setupServerEnabledRow()
        setupSelectServerRow()
        setupServerTypeCards()
        setupUrlField()
        setupApiKeyField()
        setupPingChecker()
        setupOnlineChecker()
    }

    private fun setupServerEnabledRow() {
        binding.rowServerEnabled.rowTitle.text = "Use local server"
        val subtitleView: TextView = binding.rowServerEnabled.rowSubtitle
        subtitleView.text = "Try the local server before NewPipe when resolving streams"
        subtitleView.visibility = TextView.VISIBLE

        val switch: SwitchCompat = binding.rowServerEnabled.rowSwitch
        switch.isChecked = SettingsPrefs.isLocalServerEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            SettingsPrefs.setLocalServerEnabled(this, isChecked)
        }
    }

    /**
     * Not a toggle -- shows the currently selected server type as a
     * read-only summary row above the actual picker cards below it, same
     * "informational row, no switch" pattern SettingsActivity's YT
     * Settings row uses.
     */
    private fun setupSelectServerRow() {
        binding.rowSelectServer.rowTitle.text = "Select server"
        val subtitleView: TextView = binding.rowSelectServer.rowSubtitle
        subtitleView.visibility = TextView.VISIBLE
        binding.rowSelectServer.rowSwitch.visibility = android.view.View.GONE
        refreshSelectedServerSubtitle()
    }

    private fun refreshSelectedServerSubtitle() {
        binding.rowSelectServer.rowSubtitle.text = when (SettingsPrefs.getLocalServerType(this)) {
            SettingsPrefs.LocalServerType.LUMI_FETCHER -> "Lumi Fetcher"
            SettingsPrefs.LocalServerType.SPARKYTUBE_SERVER -> "SparkyTube-Server (coming soon)"
        }
    }

    private fun setupServerTypeCards() {
        updateCardSelection()

        binding.cardLumiFetcher.setOnClickListener {
            SettingsPrefs.setLocalServerType(this, SettingsPrefs.LocalServerType.LUMI_FETCHER)
            updateCardSelection()
            refreshSelectedServerSubtitle()
        }

        // SparkyTube-Server card is intentionally not selectable yet — no
        // backend exists for it. Tapping it explains that instead of
        // silently doing nothing or half-selecting a server that isn't real.
        binding.cardSparkyTubeServer.setOnClickListener {
            Toast.makeText(this, "SparkyTube-Server is coming soon — use Lumi Fetcher for now", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCardSelection() {
        val isLumiSelected = SettingsPrefs.getLocalServerType(this) == SettingsPrefs.LocalServerType.LUMI_FETCHER
        binding.cardLumiFetcher.alpha = 1f
        binding.lumiFetcherLabel.setTextColor(
            resources.getColor(if (isLumiSelected) dev.sparkynox.sparkytube.R.color.accent else dev.sparkynox.sparkytube.R.color.text_primary, theme)
        )
    }

    private fun setupUrlField() {
        binding.serverUrlInput.setText(SettingsPrefs.getLocalServerUrl(this))
        binding.saveServerUrlBtn.setOnClickListener {
            val url = binding.serverUrlInput.text?.toString()?.trim().orEmpty()
            if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                Toast.makeText(this, "Enter a valid URL starting with http:// or https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SettingsPrefs.setLocalServerUrl(this, url)
            Toast.makeText(this, "Server address saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupApiKeyField() {
        binding.apiKeyInput.setText(SettingsPrefs.getLocalServerApiKey(this))
        binding.saveApiKeyBtn.setOnClickListener {
            val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            SettingsPrefs.setLocalServerApiKey(this, key)
            Toast.makeText(
                this,
                if (key.isEmpty()) "API key cleared" else "API key saved",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupPingChecker() {        binding.pingCheckBtn.setOnClickListener {
            binding.pingResultText.text = "…"
            lifecycleScope.launch {
                val pingMs = withContext(Dispatchers.IO) { measurePingMs(currentHealthUrl()) }
                binding.pingResultText.text = if (pingMs != null) "${pingMs}ms" else "timeout"
            }
        }
    }

    private fun setupOnlineChecker() {
        binding.onlineCheckBtn.setOnClickListener {
            binding.onlineStatusText.visibility = android.view.View.VISIBLE
            binding.onlineStatusText.text = "Checking..."
            binding.onlineStatusText.setTextColor(resources.getColor(dev.sparkynox.sparkytube.R.color.text_secondary, theme))

            lifecycleScope.launch {
                val isOnline = withContext(Dispatchers.IO) { checkServerOnline(currentHealthUrl()) }
                if (isOnline) {
                    binding.onlineStatusText.text = "● Server is online"
                    binding.onlineStatusText.setTextColor(0xFF4CAF50.toInt())
                } else {
                    binding.onlineStatusText.text = "● Server is unreachable"
                    binding.onlineStatusText.setTextColor(resources.getColor(dev.sparkynox.sparkytube.R.color.accent, theme))
                }
            }
        }
    }

    private fun currentHealthUrl(): String {
        val base = binding.serverUrlInput.text?.toString()?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: SettingsPrefs.getLocalServerUrl(this).trimEnd('/')
        return "$base/health"
    }

    /**
     * Round-trip time to the server's /health endpoint, in milliseconds.
     * Null on timeout/connection failure -- same "server not reachable"
     * outcome checkServerOnline() reports, kept as a separate function
     * since the ping card and the online-check card show the result
     * differently (a number vs. a plain online/offline label).
     */
    private fun measurePingMs(healthUrl: String): Long? {
        return try {
            val start = System.currentTimeMillis()
            val conn = URL(healthUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) System.currentTimeMillis() - start else null
        } catch (e: Exception) {
            null
        }
    }

    private fun checkServerOnline(healthUrl: String): Boolean {
        return try {
            val conn = URL(healthUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
