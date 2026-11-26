package dev.sparkynox.sparkytube.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// notification / lock screen artwork. the default loader tries once and gives up,
// this one steps down hq -> medium -> low and retries on a bad connection
@androidx.annotation.OptIn(UnstableApi::class)
class ThumbBitmapLoader : BitmapLoader {
    private val pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = pool.submit(Callable {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("bad image data")
    })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = pool.submit(Callable { fetch(uri) })

    private fun fetch(uri: Uri): Bitmap {
        val urls = candidates(uri)
        for (round in 1..4) {
            for (u in urls) {
                try {
                    http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                        if (r.isSuccessful) {
                            val bytes = r.body?.bytes()
                            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            if (bmp != null) return bmp
                        }
                    }
                } catch (_: IOException) { }
            }
            Thread.sleep(1500L * round)
        }
        throw IOException("no artwork for $uri")
    }

    private fun candidates(uri: Uri): List<String> {
        val s = uri.toString()
        val id = Regex("/vi/([\\w-]{11})/").find(s)?.groupValues?.get(1) ?: return listOf(s)
        return listOf("hqdefault", "mqdefault", "default").map { "https://i.ytimg.com/vi/$id/$it.jpg" }
    }
}
