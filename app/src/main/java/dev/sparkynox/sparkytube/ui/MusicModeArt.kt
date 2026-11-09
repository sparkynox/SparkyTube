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
    private val lyrics: View,
    private val lyricsUi: dev.sparkynox.sparkytube.lunokit.lyrics.LyricsUiState,
) {
    private val miniImage = ImageView(mini.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.BLACK)
    }

    fun sync() {
        val music = musicOn()
        val inMini = music && miniOn() && nativeActive()

        // lyrics sit over the picture, they draw nothing when the song has none
        val showLyrics = music && nativeActive() && !miniOn()
        lyricsUi.active = showLyrics
        lyrics.visibility = if (showLyrics) View.VISIBLE else View.GONE

        val bg = if (music) Color.TRANSPARENT else Color.BLACK
        player.setBackgroundColor(bg)
        player.setShutterBackgroundColor(bg)

        if (inMini) {
            big.visibility = View.GONE
            if (miniImage.parent == null) {
                // same spot and size as the video in the glass bar, not the whole bar
                val d = mini.resources.displayMetrics.density
                mini.addView(miniImage, 0, FrameLayout.LayoutParams((96 * d).toInt(), (54 * d).toInt()).apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    marginStart = (6 * d).toInt()
                })
                miniImage.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, 12 * d)
                    }
                }
                miniImage.clipToOutline = true
            }
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
