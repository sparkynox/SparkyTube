package dev.sparkynox.sparkytube.net

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

// googlevideo throttles one big open-ended request (bytes=0-) down to roughly playback
// speed, which is why long videos sit on the spinner. asking for the file in small
// range= pieces (same thing yt-dlp does) keeps it at full speed.
@UnstableApi
class ChunkedDataSource(private val upstream: DataSource) : DataSource {

    private var spec: DataSpec? = null
    private var passthrough = false
    private var pos = 0L
    private var end = 0L
    private var chunkEnd = 0L
    private var chunkOpen = false
    private var counter = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val uri = dataSpec.uri
        val total = uri.getQueryParameter("clen")?.toLongOrNull()
        if (!shouldChunk(uri) || total == null || total <= 0) {
            passthrough = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(total, pos + dataSpec.length) else total
        if (pos >= end) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        openChunk()
        return end - pos
    }

    private fun shouldChunk(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!host.endsWith("googlevideo.com")) return false
        // these are already served at full speed
        if (uri.getQueryParameter("ratebypass") == "yes") return false
        return true
    }

    private fun chunkSize(): Long = if (counter == 0) FIRST_CHUNK else CHUNK

    private fun openChunk() {
        val s = spec ?: throw IOException("not opened")
        chunkEnd = minOf(end, pos + chunkSize())
        val b = Uri.Builder().scheme(s.uri.scheme).encodedAuthority(s.uri.encodedAuthority).encodedPath(s.uri.encodedPath)
        for (name in s.uri.queryParameterNames) {
            if (name == "range" || name == "rn") continue
            for (v in s.uri.getQueryParameters(name)) b.appendQueryParameter(name, v)
        }
        b.appendQueryParameter("range", "$pos-${chunkEnd - 1}")
        b.appendQueryParameter("rn", (++counter).toString())
        val chunkSpec = DataSpec.Builder()
            .setUri(b.build())
            .setHttpRequestHeaders(s.httpRequestHeaders)
            .setFlags(s.flags)
            .build()
        upstream.open(chunkSpec)
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (pos >= end) return C.RESULT_END_OF_INPUT
        if (pos >= chunkEnd) {
            upstream.close()
            chunkOpen = false
            openChunk()
        }
        val want = minOf(length.toLong(), chunkEnd - pos).toInt()
        val n = upstream.read(buffer, offset, want)
        if (n == C.RESULT_END_OF_INPUT) throw IOException("chunk ended early at $pos of $chunkEnd")
        pos += n
        return n
    }

    override fun getUri(): Uri? = if (passthrough) upstream.uri else spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            if (passthrough || chunkOpen) upstream.close()
        } finally {
            chunkOpen = false
            spec = null
        }
    }

    private companion object {
        const val FIRST_CHUNK = 512L * 1024
        const val CHUNK = 2L * 1024 * 1024
    }
}
