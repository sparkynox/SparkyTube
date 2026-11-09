package dev.sparkynox.sparkytube.lunokit.blur

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import coil.imageLoader
import coil.request.ImageRequest

// the frosted part of the glass mini player: the video's own thumbnail, tiny and blurred natively
object GlassBackdrop {
    fun load(view: ImageView, videoId: String?) {
        if (videoId.isNullOrBlank()) { view.setImageDrawable(null); return }
        val req = ImageRequest.Builder(view.context)
            .data("https://i.ytimg.com/vi/$videoId/default.jpg")
            .size(64, 48)
            .allowHardware(false)
            .target(onSuccess = { d ->
                val src = (d as? BitmapDrawable)?.bitmap ?: return@target
                val copy = src.copy(Bitmap.Config.ARGB_8888, true) ?: return@target
                LunoBlur.blur(copy, 5)
                view.setImageBitmap(copy)
            })
            .build()
        view.context.imageLoader.enqueue(req)
    }
}
