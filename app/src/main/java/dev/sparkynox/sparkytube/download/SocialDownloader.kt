package dev.sparkynox.sparkytube.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import dev.sparkynox.sparkytube.extractor.social.MediaKind
import dev.sparkynox.sparkytube.extractor.social.SocialMedia
import dev.sparkynox.sparkytube.extractor.social.SocialResult

/** Hands a resolved item to Android's DownloadManager (with the headers its site wants), so it shows up on the Downloads screen like the rest. */
object SocialDownloader {

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(60)

    fun enqueue(ctx: Context, r: SocialResult, m: SocialMedia): Long? {
        return try {
            val base = clean(listOf(r.site, r.author.ifBlank { r.title }).filter { it.isNotBlank() }.joinToString(" ")).ifBlank { "SparkyTube" }
            val stamp = (System.currentTimeMillis() / 1000) % 100000
            val name = "$base ${m.label.replace(Regex("[^A-Za-z0-9 ]"), "").trim()} $stamp.${m.ext}".replace(Regex("\\s+"), " ")
            val req = DownloadManager.Request(Uri.parse(m.url)).apply {
                setTitle(name)
                setDescription(r.site)
                setMimeType(when (m.kind) { MediaKind.VIDEO -> "video/mp4"; MediaKind.IMAGE -> "image/jpeg"; MediaKind.AUDIO -> "audio/mpeg" })
                m.headers.forEach { (k, v) -> if (v.isNotBlank()) addRequestHeader(k, v) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setAllowedOverMetered(true)
            }
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        } catch (e: Exception) {
            null
        }
    }
}
