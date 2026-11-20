package dev.sparkynox.sparkytube.homefeed

import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The endless Shorts list, from the same reel endpoints the web Shorts player uses.
 * start() asks for the sequence that follows one short, more() carries on with the token it returned.
 */
object ShortsApi {
    private const val TAG = "ShortsApi"

    data class Page(val items: List<HomeFeedItem>, val token: String?, val error: String? = null)

    suspend fun start(videoId: String): Page = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("playerRequest", JSONObject().put("videoId", videoId).put("params", "8AEB"))
                .put("params", "CA8%3D")
            val root = InnerTubeClient.post("reel/reel_item_watch", body)
            val token = root.optString("sequenceContinuation").takeIf { it.isNotBlank() }
            if (token == null) {
                LogRecorder.w(TAG, "no sequence token for $videoId")
                Page(emptyList(), null)
            } else {
                loadSequence(token)
            }
        } catch (e: Exception) {
            LogRecorder.w(TAG, "start failed: ${e.message}")
            Page(emptyList(), null, e.message)
        }
    }

    suspend fun more(token: String): Page = withContext(Dispatchers.IO) {
        try {
            loadSequence(token)
        } catch (e: Exception) {
            LogRecorder.w(TAG, "more failed: ${e.message}")
            Page(emptyList(), null, e.message)
        }
    }

    private fun loadSequence(token: String): Page {
        val root = InnerTubeClient.post("reel/reel_watch_sequence", JSONObject().put("sequenceParams", token))
        val out = LinkedHashMap<String, HomeFeedItem>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("reelWatchEndpoint")?.let { e -> parseEntry(e)?.let { out.putIfAbsent(it.videoId!!, it) } }
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root.optJSONArray("entries") ?: root)
        val next = root.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
            ?.optString("token")?.takeIf { it.isNotBlank() }
        LogRecorder.d(TAG, "sequence: ${out.size} shorts, more=${next != null}")
        return Page(out.values.toList(), next)
    }

    private fun parseEntry(e: JSONObject): HomeFeedItem? {
        val id = e.optString("videoId").takeIf { it.length == 11 } ?: return null
        val title = findText(e, "reelTitleText") ?: "Short"
        val channel = findText(e, "channelTitleText") ?: ""
        val avatar = findFirst(e, "channelThumbnail")?.optJSONArray("thumbnails")?.let { lastUrl(it) }
        val thumb = lastUrl(e.optJSONObject("thumbnail")?.optJSONArray("thumbnails")) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return HomeFeedItem(
            videoId = id, playlistId = null, title = title, channelName = channel, thumbnailUrl = thumb,
            channelAvatarUrl = avatar, durationText = "", viewCountText = findText(e, "viewCountText") ?: "",
            isShort = true, channelId = findChannelId(e),
        )
    }

    private fun lastUrl(a: JSONArray?): String? =
        a?.let { it.optJSONObject(it.length() - 1)?.optString("url")?.takeIf { u -> u.isNotBlank() } }

    private fun findText(node: Any?, key: String): String? {
        val o = findFirst(node, key) ?: return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }.takeIf { it.isNotBlank() }
    }

    private fun findFirst(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                node.keys().forEach { k -> findFirst(node.opt(k), key)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findFirst(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun findChannelId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("UC") }?.let { return it }
                node.keys().forEach { k -> findChannelId(node.opt(k))?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findChannelId(node.opt(i))?.let { return it }
        }
        return null
    }
}
