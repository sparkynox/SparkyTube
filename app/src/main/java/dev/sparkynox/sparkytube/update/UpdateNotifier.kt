package dev.sparkynox.sparkytube.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.sparkynox.sparkytube.R

object UpdateNotifier {

    private const val CHANNEL_ID = "sparkytube_updates"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SparkyTube Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a new SparkyTube version is available"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun notifyUpdateAvailable(context: Context, info: UpdateChecker.UpdateInfo) {
        ensureChannel(context)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SparkyTube v${info.versionTag} is out")
            .setContentText("Tap to download the update")
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.releaseNotes.ifBlank { "Tap to download the update" }))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // POST_NOTIFICATIONS is already declared in the manifest; on API 33+
        // this still needs to be granted at runtime — MainActivity requests
        // it once on first launch (see requestNotificationPermissionIfNeeded()).
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission not granted yet — the in-app dialog (shown alongside
            // this call in MainActivity) still covers the "tell the user" case.
        }
    }
}
