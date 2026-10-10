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
