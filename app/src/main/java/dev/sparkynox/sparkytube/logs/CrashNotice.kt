package dev.sparkynox.sparkytube.logs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

// Shows the last recorded crash once, with a plain system dialog (no Compose on purpose, this has to
// work even when a Compose screen is what crashed). Copy puts the full report on the clipboard.
object CrashNotice {

    fun showIfAny(activity: Activity) {
        val report = LogRecorder.getLastCrash(activity)?.takeIf { it.isNotBlank() } ?: return
        val prefs = activity.getSharedPreferences("crash_notice", Context.MODE_PRIVATE)
        val id = report.hashCode()
        if (prefs.getInt("shown", 0) == id) return
        prefs.edit().putInt("shown", id).apply()

        val lines = report.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        AlertDialog.Builder(activity)
            .setTitle("Last crash")
            .setMessage(lines.take(30).joinToString("\n").take(3500))
            .setPositiveButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SparkyTube crash", report))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
