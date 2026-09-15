package dev.sparkynox.sparkytube.logs

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.sparkynox.sparkytube.MainActivity
import dev.sparkynox.sparkytube.R

/**
 * Launched instead of letting the system's default "SparkyTube keeps
 * stopping" dialog take over, right after CrashHandler catches an
 * uncaught exception and restarts the process (see
 * CrashHandler.uncaughtException). Shows one clear dialog -- "App
 * Crashed — Check Logs" -- and offers to open the Logs screen (where
 * the actual stack trace + session log leading up to it are shown) or
 * just continue into the app normally.
 */
class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_SparkyTube_NoActionBar)

        AlertDialog.Builder(this)
            .setTitle("App Crashed")
            .setMessage("SparkyTube ran into an error and had to restart. Check Logs for details.")
            .setCancelable(false)
            .setPositiveButton("Check Logs") { _, _ ->
                startActivity(Intent(this, LogsActivity::class.java))
                finish()
            }
            .setNegativeButton("Continue") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .show()
    }
}
