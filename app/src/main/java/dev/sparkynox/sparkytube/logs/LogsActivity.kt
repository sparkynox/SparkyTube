package dev.sparkynox.sparkytube.logs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import dev.sparkynox.sparkytube.databinding.ActivityLogsBinding
import java.io.File

/**
 * Shows the current session's recorded logs (see LogRecorder) plus a
 * banner for the last crash if one happened, with actions to share the
 * log file (e.g. to send to Sparky for debugging) or clear it.
 *
 * Reachable two ways: normally from Settings > Logs, or directly from
 * CrashReportActivity's "Check Logs" button right after a crash --
 * either way it's the same screen showing the same data.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.logsBackBtn.setOnClickListener { finish() }

        val crash = LogRecorder.getLastCrash(this)
        if (crash != null) {
            binding.logsCrashBanner.visibility = android.view.View.VISIBLE
            // First real line of the trace (skips the header/timestamp
            // line) as a one-glance summary -- the full thing is still
            // right there in the scrollable log below it.
            val summaryLine = crash.lineSequence().drop(1).firstOrNull { it.isNotBlank() } ?: ""
            binding.logsCrashSummary.text = summaryLine
            binding.logsCrashBanner.setOnClickListener {
                binding.logsScroll.post {
                    binding.logsScroll.fullScroll(android.view.View.FOCUS_UP)
                }
            }
        } else {
            binding.logsCrashBanner.visibility = android.view.View.GONE
        }

        refreshLogText(crash)

        binding.logsShareBtn.setOnClickListener { shareLogs() }

        binding.logsClearBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear logs?")
                .setMessage("This deletes the current session log and last crash report.")
                .setPositiveButton("Clear") { _, _ ->
                    LogRecorder.clearSessionLog(this)
                    LogRecorder.clearLastCrash(this)
                    binding.logsCrashBanner.visibility = android.view.View.GONE
                    refreshLogText(null)
                    Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshLogText(crash: String?) {
        val sessionLog = LogRecorder.getSessionLog(this)
        val combined = buildString {
            if (crash != null) {
                append("=== LAST CRASH ===\n")
                append(crash)
                append("\n\n=== CURRENT SESSION LOG ===\n")
            }
            append(sessionLog.ifBlank { "(no log lines recorded yet this session)" })
        }
        binding.logsText.text = combined
    }

    /**
     * Shares the session log file (and crash file, if present) as
     * plain-text attachments via a FileProvider content:// URI, since
     * app-private files under filesDir can't be shared as a raw file://
     * path to another app on modern Android.
     */
    private fun shareLogs() {
        try {
            val sessionFile = File(filesDir, "sparky_session_log.txt")
            val crashFile = File(filesDir, "sparky_last_crash.txt")

            val uris = ArrayList<Uri>()
            val authority = "$packageName.fileprovider"

            if (sessionFile.exists()) {
                uris.add(FileProvider.getUriForFile(this, authority, sessionFile))
            }
            if (crashFile.exists()) {
                uris.add(FileProvider.getUriForFile(this, authority, crashFile))
            }

            if (uris.isEmpty()) {
                Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share SparkyTube logs"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't share logs", Toast.LENGTH_SHORT).show()
        }
    }
}
