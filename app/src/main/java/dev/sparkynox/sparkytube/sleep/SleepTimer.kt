package dev.sparkynox.sparkytube.sleep

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.Player

/** Pauses playback after a while, or just before the current video ends. */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var task: Runnable? = null
    private var endsAt = 0L

    val active: Boolean get() = task != null

    private fun minutesLeft() = ((endsAt - System.currentTimeMillis()) / 60000L).coerceAtLeast(0) + 1

    fun cancel() {
        task?.let { handler.removeCallbacks(it) }
        task = null
    }

    private fun schedule(ms: Long, fire: () -> Unit) {
        cancel()
        endsAt = System.currentTimeMillis() + ms
        val r = Runnable { task = null; fire() }
        task = r
        handler.postDelayed(r, ms)
    }

    /** [player] gives the current player, [pause] pauses it the way the app's own pause button does. */
    fun showDialog(activity: Activity, player: () -> Player?, pause: () -> Unit) {
        val labels = mutableListOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "End of this video")
        if (active) labels += "Turn off (${minutesLeft()} min left)"
        AlertDialog.Builder(activity)
            .setTitle("Sleep timer")
            .setItems(labels.toTypedArray()) { _, i ->
                when (i) {
                    0, 1, 2, 3 -> {
                        val min = intArrayOf(15, 30, 45, 60)[i]
                        schedule(min * 60_000L, pause)
                        Toast.makeText(activity, "Pausing in $min min", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        val p = player()
                        val left = if (p != null && p.duration > 0) p.duration - p.currentPosition - 400 else -1L
                        if (left > 0) {
                            schedule(left, pause)
                            Toast.makeText(activity, "Pausing when this video ends", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Play a video first", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> { cancel(); Toast.makeText(activity, "Sleep timer off", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
