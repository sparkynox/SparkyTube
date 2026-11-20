package dev.sparkynox.sparkytube.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Saves a video's thumbnail as a jpg in Pictures/SparkyTube. Tries the biggest size YouTube has
 * (maxres) and steps down until one exists.
 */
object ThumbnailDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val SIZES = listOf("maxresdefault", "sddefault", "hqdefault", "mqdefault")

    fun save(ctx: Context, videoId: String, title: String) {
        val app = ctx.applicationContext
        scope.launch {
            val bytes = fetch(videoId)
            val ok = bytes != null && write(app, bytes, fileName(title, videoId))
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    app, if (ok) "Thumbnail saved to Pictures/SparkyTube" else "Couldn't save the thumbnail",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun fileName(title: String, id: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(60).ifBlank { "thumbnail" }
        return "$clean [$id].jpg"
    }

    private fun fetch(videoId: String): ByteArray? {
        for (size in SIZES) {
            try {
                val req = Request.Builder().url("https://i.ytimg.com/vi/$videoId/$size.jpg").build()
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val b = r.body?.bytes()
                        // a missing maxres sometimes answers with a tiny grey placeholder instead of a 404
                        if (b != null && b.size > 4_000) return b
                    }
                }
            } catch (e: Exception) {
                LogRecorder.w("Thumbnail", "$size failed: ${e.message}")
            }
        }
        return null
    }

    private fun write(ctx: Context, bytes: ByteArray, name: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SparkyTube")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SparkyTube")
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(bytes)
                MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
                true
            }
        } catch (e: Exception) {
            LogRecorder.w("Thumbnail", "write failed: ${e.message}")
            false
        }
    }
}
