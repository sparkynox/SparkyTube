package dev.sparkynox.sparkytube.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

// reads the scroll direction of whatever list is under the finger, so the top and bottom bar can
// slide away on the way down and come back on the way up, like the real app.
// AppHost puts [connection] on its root, MainActivity sets onDirection
object BarsScroll {

    var onDirection: ((hide: Boolean) -> Unit)? = null

    // false while a video is open or search is up, the top bar has the quality / audio buttons then
    var enabled: () -> Boolean = { true }

    private var acc = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!enabled()) return Offset.Zero
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            // a change of direction starts counting again
            if ((dy > 0f) != (acc > 0f)) acc = 0f
            acc += dy
            if (acc < -36f) { onDirection?.invoke(true); acc = 0f }
            else if (acc > 18f) { onDirection?.invoke(false); acc = 0f }
            return Offset.Zero
        }
    }
}
