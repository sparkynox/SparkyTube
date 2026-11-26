package dev.sparkynox.sparkytube.ui

import android.widget.ImageView
import coil.load
import java.util.WeakHashMap

// video thumbnails that don't give up: hq first, then medium, then low,
// and if everything fails (offline / very slow) it asks again a few times
object ThumbLoader {
    private val sizes = listOf("hqdefault", "mqdefault", "default")
    private val current = WeakHashMap<ImageView, String>()

    fun load(view: ImageView, videoId: String?, fallbackRes: Int) {
        if (videoId == null) {
            current.remove(view)
            view.setImageResource(fallbackRes)
            return
        }
        current[view] = videoId
        attempt(view, videoId, fallbackRes, 0, 0)
    }

    private fun attempt(view: ImageView, id: String, fallbackRes: Int, size: Int, round: Int) {
        if (current[view] != id) return
        view.load("https://i.ytimg.com/vi/$id/${sizes[size]}.jpg") {
            crossfade(true)
            placeholder(fallbackRes)
            error(fallbackRes)
            listener(onError = { _, _ ->
                if (current[view] == id) {
                    if (size < sizes.lastIndex) {
                        attempt(view, id, fallbackRes, size + 1, round)
                    } else if (round < 6) {
                        val wait = 2500L * (round + 1).coerceAtMost(4)
                        view.postDelayed({ attempt(view, id, fallbackRes, 0, round + 1) }, wait)
                    }
                }
            })
        }
    }
}
