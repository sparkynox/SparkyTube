package dev.sparkynox.sparkytube.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import dev.sparkynox.sparkytube.MainActivity
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.homefeed.InnerTubeClient
import dev.sparkynox.sparkytube.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home screen widget: cover, title, like and play / pause for what SparkyTube plays.
 * The service's player is the source of truth (PlaybackService.currentPlayer), the service calls refresh()
 * whenever play state or metadata changes. The video id is read back from the artwork url.
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val p = PlaybackService.currentPlayer
                if (p == null) openApp(context) else p.playWhenReady = !p.playWhenReady
            }
            ACTION_LIKE -> like(context, goAsync())
        }
    }

    private fun like(context: Context, pending: PendingResult) {
        val id = videoIdOf(PlaybackService.currentPlayer?.mediaMetadata?.artworkUri)
        if (id == null || !Session.loggedIn) {
            Toast.makeText(context, if (id == null) "Nothing is playing" else "Sign in first (open SparkyTube)", Toast.LENGTH_SHORT).show()
            pending.finish()
            return
        }
        scope.launch {
            val was = id in liked
            val ok = if (was) InnerTubeClient.removeLike(id) else InnerTubeClient.like(id)
            if (ok) { if (was) liked.remove(id) else liked.add(id) }
            refresh(context)
            pending.finish()
        }
    }

    private fun openApp(context: Context) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val ACTION_TOGGLE = "dev.sparkynox.sparkytube.widget.TOGGLE"
        const val ACTION_LIKE = "dev.sparkynox.sparkytube.widget.LIKE"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val liked = HashSet<String>()
        @Volatile private var lastArtUrl: String? = null
        @Volatile private var lastArt: Bitmap? = null

        private fun videoIdOf(art: Uri?): String? =
            art?.pathSegments?.getOrNull(1)?.takeIf { art.host == "i.ytimg.com" && it.length == 11 }

        fun refresh(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return

            val p = PlaybackService.currentPlayer
            val md = p?.mediaMetadata
            val title = md?.title?.toString()
            val art = md?.artworkUri
            val playing = p?.isPlaying == true
            val id = videoIdOf(art)

            fun views(bitmap: Bitmap?): RemoteViews {
                val v = RemoteViews(app.packageName, R.layout.widget_now_playing)
                v.setTextViewText(R.id.widget_title, title ?: "Nothing playing")
                v.setTextViewText(R.id.widget_sub, if (title == null) "Open SparkyTube and play a video" else "SparkyTube")
                v.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                v.setInt(R.id.widget_play, "setColorFilter", 0xFFFFFFFF.toInt())
                v.setInt(R.id.widget_like, "setColorFilter", if (id != null && id in liked) 0xFFFF4D5E.toInt() else 0xFFFFFFFF.toInt())
                if (bitmap != null) v.setImageViewBitmap(R.id.widget_cover, bitmap)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
                    app, code, Intent(app, NowPlayingWidget::class.java).setAction(action), flags,
                )
                v.setOnClickPendingIntent(R.id.widget_play, broadcast(ACTION_TOGGLE, 1))
                v.setOnClickPendingIntent(R.id.widget_like, broadcast(ACTION_LIKE, 2))
                val open = PendingIntent.getActivity(
                    app, 3, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
                )
                v.setOnClickPendingIntent(R.id.widget_cover, open)
                v.setOnClickPendingIntent(R.id.widget_title, open)
                return v
            }

            val url = art?.toString()
            val cached = if (url != null && url == lastArtUrl) lastArt else null
            mgr.updateAppWidget(ids, views(cached))
            if (url != null && cached == null) {
                // the cover is fetched small, a RemoteViews bitmap has a size limit
                scope.launch {
                    try {
                        val r = ImageLoader(app).execute(ImageRequest.Builder(app).data(url).size(256).allowHardware(false).build())
                        val bmp = r.drawable?.toBitmap(256, 256)
                        if (bmp != null) {
                            lastArtUrl = url
                            lastArt = bmp
                            mgr.updateAppWidget(ids, views(bmp))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
