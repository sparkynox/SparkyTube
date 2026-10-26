package dev.sparkynox.sparkytube.net

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.sparkynox.sparkytube.extractor.StreamExtractor
import dev.sparkynox.sparkytube.logs.LogRecorder
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@UnstableApi
object NetCache {

    private const val TAG = "NetCache"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) SparkyTube"
    private const val MAX_CACHE_BYTES = 256L * 1024 * 1024

    @Volatile private var appContext: Context? = null
    @Volatile private var cache: SimpleCache? = null
    private val cacheLock = Any()

    // one client for all playback, keeps connections to googlevideo alive between videos
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val worker = Executors.newFixedThreadPool(2)
    private val warmedHosts = HashMap<String, Long>()

    // counts bytes in ~300ms windows and feeds the native estimator
    private val meter = object : TransferListener {
        private var windowStart = 0L
        private var windowBytes = 0L

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

        @Synchronized
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!isNetwork) return
            val now = System.nanoTime()
            if (windowStart == 0L) windowStart = now
            windowBytes += bytesTransferred
            val elapsed = now - windowStart
            if (elapsed >= 300_000_000L) {
                NativeNet.record(windowBytes, elapsed)
                windowStart = now
                windowBytes = 0
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    private fun getCache(): SimpleCache? {
        cache?.let { return it }
        val ctx = appContext ?: return null
        synchronized(cacheLock) {
            cache?.let { return it }
            return try {
                val dir = File(ctx.cacheDir, "media_cache")
                SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES), StandaloneDatabaseProvider(ctx)).also { cache = it }
            } catch (t: Throwable) {
                LogRecorder.w(TAG, "cache init failed, playing without it", t)
                null
            }
        }
    }

    // googlevideo urls change on every resolve (expire/sig/ip...), so the default
    // url key would never hit twice. build the key from what identifies the file
    private fun keyFor(spec: DataSpec): String {
        spec.key?.let { return it }
        val u: Uri = spec.uri
        val host = u.host ?: return u.toString()
        if (!host.endsWith("googlevideo.com")) return u.toString()
        val id = u.getQueryParameter("id")
        val itag = u.getQueryParameter("itag")
        if (id == null || itag == null) return u.toString()
        return buildString {
            append("gv:").append(id).append(':').append(itag)
            append(':').append(u.getQueryParameter("mime") ?: "")
            append(':').append(u.getQueryParameter("lmt") ?: "")
            append(':').append(u.getQueryParameter("clen") ?: "")
            append(':').append(u.getQueryParameter("xtags") ?: "")
            append(':').append(u.getQueryParameter("range") ?: "")
        }
    }

    private fun networkFactory(context: Context): DataSource.Factory {
        val http = OkHttpDataSource.Factory(client)
            .setUserAgent(UA)
            .setTransferListener(meter)
        return DefaultDataSource.Factory(context, http)
    }

    /** no disk cache, used for HLS where manifests must stay fresh */
    fun plainFactory(context: Context): DataSource.Factory {
        init(context)
        return networkFactory(context.applicationContext)
    }

    /** disk cached, used for progressive/adaptive files */
    fun cachedFactory(context: Context): DataSource.Factory {
        init(context)
        val upstream = networkFactory(context.applicationContext)
        val c = getCache() ?: return upstream
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> keyFor(spec) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** resolve the stream host ahead of time, cheap and safe to call often */
    fun warm(url: String?) {
        val host = try { Uri.parse(url ?: return).host } catch (_: Throwable) { null } ?: return
        val now = System.currentTimeMillis()
        synchronized(warmedHosts) {
            val last = warmedHosts[host]
            if (last != null && now - last < 60_000) return
            warmedHosts[host] = now
        }
        worker.execute { NativeNet.prewarmDns(host) }
    }

    // how much of the start of the next video to grab, scaled to the measured speed
    private fun prefetchBytes(): Long {
        val bps = NativeNet.bytesPerSecond()
        if (bps <= 0) return 768L * 1024
        return (bps * 3).coerceIn(384L * 1024, 2L * 1024 * 1024)
    }

    /** downloads the first chunk of the next video (and its audio) into the disk cache */
    fun prefetch(stream: StreamExtractor.ResolvedStream) {
        val ctx = appContext ?: return
        if (stream.isHls || stream.isLive) return
        val urls = listOfNotNull(stream.url, stream.defaultAudioUrl)
        val len = prefetchBytes()
        for (u in urls) {
            warm(u)
            worker.execute {
                try {
                    val ds = (cachedFactory(ctx) as? CacheDataSource.Factory)?.createDataSource() ?: return@execute
                    val spec = DataSpec.Builder().setUri(u).setPosition(0).setLength(len).build()
                    CacheWriter(ds, spec, null, null).cache()
                } catch (t: Throwable) {
                    // partial data stays in the cache, nothing to do
                    LogRecorder.d(TAG, "prefetch stopped: ${t.javaClass.simpleName}")
                }
            }
        }
        LogRecorder.d(TAG, "prefetch ${len / 1024} KB x${urls.size} (net ${NativeNet.bytesPerSecond() / 1024} KB/s)")
    }

    fun clear() {
        worker.execute {
            try {
                val c = getCache() ?: return@execute
                for (k in c.keys.toList()) c.removeResource(k)
            } catch (_: Throwable) {}
        }
    }

    fun sizeBytes(): Long = cache?.cacheSpace ?: 0L
}
