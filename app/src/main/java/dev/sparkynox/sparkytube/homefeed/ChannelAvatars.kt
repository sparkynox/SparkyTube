package dev.sparkynox.sparkytube.homefeed

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Channel pictures for cards that don't carry one. Memory cache, three requests at a time. */
object ChannelAvatars {
    private val cache = ConcurrentHashMap<String, String>() // "" = this channel has none
    private val gate = Semaphore(3)

    suspend fun get(channelId: String): String? {
        cache[channelId]?.let { return it.ifEmpty { null } }
        val url = gate.withPermit {
            cache[channelId]?.let { return it.ifEmpty { null } }
            InnerTubeClient.fetchChannelAvatar(channelId)
        }
        cache[channelId] = url.orEmpty()
        return url
    }
}
