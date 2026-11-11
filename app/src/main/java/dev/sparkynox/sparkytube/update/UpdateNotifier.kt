package dev.sparkynox.sparkytube.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object UpdateNotifier {

    private const val CHANNEL_ID = "sparkytube_updates"
    private const val UPDATE_ID = 1001
    private const val PROGRESS_ID = 1002
    private const val PROGRESS_CHANNEL = "sparkytube_update_progress"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SparkyTube Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New versions and messages from the developer"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun openScreen(context: Context, tab: String, req: Int): PendingIntent {
        val i = Intent(context, UpdateActivity::class.java)
            .putExtra(UpdateActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // notification permission not granted, nothing to do
        }
    }

    fun notifyUpdate(context: Context, versionName: String, notes: String) {
        ensureChannel(context)
        val text = notes.ifBlank { "Tap to update" }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SparkyTube $versionName is out")
            .setContentText("Tap to update")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openScreen(context, "update", 1))
            .setAutoCancel(true)
            .build()
        post(context, UPDATE_ID, n)
    }

    // ongoing notification while the apk downloads: file name, percent, size and speed
    fun progress(context: Context, fileName: String, done: Long, total: Long, speed: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(PROGRESS_CHANNEL, "Update download", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val text = (if (total > 0) "$pct%  •  " else "") + fmt(done) + (if (total > 0) " / " + fmt(total) else "") +
            (if (speed > 0) "  •  " + fmt(speed) + "/s" else "")
        val n = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(text)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openScreen(context, "update", 3))
            .build()
        post(context, PROGRESS_ID, n)
    }

    fun progressDone(context: Context, fileName: String, ok: Boolean, error: String = "") {
        NotificationManagerCompat.from(context).cancel(PROGRESS_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(PROGRESS_CHANNEL, "Update download", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "Update ready" else "Update download failed")
            .setContentText(if (ok) "$fileName, tap to install" else error)
            .setContentIntent(openScreen(context, "update", 3))
            .setAutoCancel(true)
            .build()
        post(context, PROGRESS_ID, n)
    }

    private fun fmt(b: Long): String = when {
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun notifyMessage(context: Context, m: UpdateManager.Message) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(m.title)
            .setContentText(m.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.body))
            .setContentIntent(openScreen(context, "messages", 2))
            .setAutoCancel(true)
            .build()
        post(context, 2000 + m.id, n)
    }
}
