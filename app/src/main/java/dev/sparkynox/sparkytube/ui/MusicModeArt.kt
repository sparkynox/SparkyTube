package dev.sparkynox.sparkytube.ui

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import coil.load
import dev.sparkynox.sparkytube.R

// Music mode shows a picture instead of video. Two things used to break it:
// the PlayerView's own black background sat on top of the picture, and the picture stayed full size
// at the top of the screen while the player shrank into the mini player.
// sync() fixes both: transparent player in music mode, and a copy of the picture inside the mini box.
class MusicModeArt(
    private val big: ImageView,
    private val mini: FrameLayout,
    private val player: PlayerView,
    private val musicOn: () -> Boolean,
    private val miniOn: () -> Boolean,
    private val nativeActive: () -> Boolean,
    private val videoId: () -> String?,
) {
    private val miniImage = ImageView(mini.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.BLACK)
    }

    fun sync() {
        val music = musicOn()
        val inMini = music && miniOn() && nativeActive()

        val bg = if (music) Color.TRANSPARENT else Color.BLACK
        player.setBackgroundColor(bg)
        player.setShutterBackgroundColor(bg)

        if (inMini) {
            big.visibility = View.GONE
            if (miniImage.parent == null) mini.addView(miniImage, 0, FrameLayout.LayoutParams(-1, -1))
            miniImage.visibility = View.VISIBLE
            val url = videoId()?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            if (url != null) {
                miniImage.load(url) {
                    placeholder(R.drawable.music_mode_bg)
                    error(R.drawable.music_mode_bg)
                }
            } else {
                miniImage.setImageResource(R.drawable.music_mode_bg)
            }
        } else {
            if (miniImage.parent != null) mini.removeView(miniImage)
            if (music && nativeActive()) big.visibility = View.VISIBLE
        }
    }
}
