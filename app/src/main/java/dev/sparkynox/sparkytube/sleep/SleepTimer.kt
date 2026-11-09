package dev.sparkynox.sparkytube.sleep

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player

/** Pauses playback after a while, or just before the current video ends. */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var task: Runnable? = null

    // read by the Compose sheet, 0 means no timer is running
    var endsAt by mutableStateOf(0L); private set
    var atVideoEnd by mutableStateOf(false); private set

    val active: Boolean get() = endsAt > 0L

    fun cancel() {
        task?.let { handler.removeCallbacks(it) }
        task = null
        endsAt = 0L
        atVideoEnd = false
    }

    private fun schedule(ms: Long, videoEnd: Boolean, fire: () -> Unit) {
        cancel()
        endsAt = System.currentTimeMillis() + ms
        atVideoEnd = videoEnd
        val r = Runnable { task = null; endsAt = 0L; atVideoEnd = false; fire() }
        task = r
        handler.postDelayed(r, ms)
    }

    fun startMinutes(minutes: Int, pause: () -> Unit) = schedule(minutes * 60_000L, false, pause)

    /** False when the length of the current video isn't known yet. */
    fun startAtVideoEnd(player: Player?, pause: () -> Unit): Boolean {
        if (player == null || player.duration <= 0) return false
        val left = player.duration - player.currentPosition - 400
        if (left <= 0) return false
        schedule(left, true, pause)
        return true
    }
}
