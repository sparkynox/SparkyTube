package dev.sparkynox.sparkytube.homefeed

import android.os.SystemClock
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A filter chip above the feed ("All", "Music", "Gaming"...). Selecting one is a continuation request. */
data class FeedChip(val title: String, val token: String?, val selected: Boolean)

data class AccountInfo(val name: String, val handle: String?, val photo: String?)

/**
 * Small client for YouTube's private InnerTube API (WEB client), for the native Home feed.
 *
 * Reworked along the lines of LunoMusic's InnerTube client:
 *  - the session (cookie, visitorData, datasync id, client version) comes from [Session]
 *  - every request carries the same headers the web client sends
 *  - failures are logged and returned as an error message instead of being swallowed, so the UI can
 *    tell "not logged in" apart from "request failed"
 *  - a logged out request still returns the generic feed rather than nothing
 */
object InnerTubeClient {
    private const val ORIGIN = Session.ORIGIN
    private const val BASE_URL = "$ORIGIN/youtubei/v1"

    // Public key the web frontend embeds in its page source, same for everyone.
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    // Only used until a login has stored the real version out of the page's ytcfg.
    private const val FALLBACK_CLIENT_VERSION = "2.20250101.00.00"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private const val TAG = "InnerTube"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class HomeFeedResult(
        val items: List<HomeFeedItem>,
        val isLoggedIn: Boolean,
        val continuationToken: String? = null,
        val chips: List<FeedChip> = emptyList(),
        /** null on success; a short message when the request failed */
        val error: String? = null,
    )

    /** First page of the home feed. */
    suspend fun fetchHomeFeed(): HomeFeedResult = load { post("browse", JSONObject().put("browseId", "FEwhat_to_watch")) }

    /** Next page, or the feed for a selected chip (chips are continuation tokens too). */
    suspend fun fetchHomeFeedContinuation(token: String): HomeFeedResult =
        load { post("browse", JSONObject().put("continuation", token)) }

    private suspend fun load(request: () -> JSONObject): HomeFeedResult = withContext(Dispatchers.IO) {
        val loggedIn = Session.loggedIn
        try {
            val root = request()
            val items = ArrayList<HomeFeedItem>()
            collect(root, items)
            val result = HomeFeedResult(
                items = items.distinctBy { it.videoId ?: it.playlistId },
                isLoggedIn = loggedIn,
                continuationToken = findContinuationToken(root),
                chips = parseChips(root),
            )
            LogRecorder.d(TAG, "feed: ${result.items.size} items, ${result.chips.size} chips, more=${result.continuationToken != null}, auth=$loggedIn")
            if (result.items.isEmpty()) LogRecorder.w(TAG, "feed came back with 0 items (response shape may have changed)")
            result
        } catch (e: IOException) {
            HomeFeedResult(emptyList(), loggedIn, error = e.message ?: "network error")
        } catch (e: Exception) {
            LogRecorder.e(TAG, "feed parse failed", e)
            HomeFeedResult(emptyList(), loggedIn, error = "couldn't read the feed")
        }
    }

    /** Name and photo of the logged in account, null when logged out or on failure. */
    suspend fun fetchAccount(): AccountInfo? = withContext(Dispatchers.IO) {
        if (!Session.loggedIn) return@withContext null
        try {
            val root = post("account/account_menu", JSONObject())
            val h = findFirst(root, "activeAccountHeaderRenderer") ?: return@withContext null
            val name = text(h.optJSONObject("accountName")) ?: return@withContext null
            AccountInfo(
                name = name,
                handle = text(h.optJSONObject("channelHandle")),
                photo = lastThumb(h.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")),
            )
        } catch (e: Exception) {
            LogRecorder.w(TAG, "couldn't load the account info", e)
            null
        }
    }

    /** Any browse page: FEsubscriptions, FEhistory, VLLL (liked), VLWL (watch later), VL<playlist id>... */
    suspend fun fetchBrowse(browseId: String): HomeFeedResult =
        load { post("browse", JSONObject().put("browseId", browseId)) }

    /** More related videos under a watch page (continuation tokens there belong to the `next` endpoint). */
    suspend fun fetchNextContinuation(token: String): HomeFeedResult =
        load { post("next", JSONObject().put("continuation", token)) }

    suspend fun fetchSearch(query: String): HomeFeedResult =
        load { post("search", JSONObject().put("query", query)) }

    suspend fun fetchSearchContinuation(token: String): HomeFeedResult =
        load { post("search", JSONObject().put("continuation", token)) }

    /**
     * Shorts for the Shorts tab. YouTube has no plain "shorts home" browse id for the web client, so
     * this harvests the Shorts shelves out of the home feed (up to 3 pages) and falls back to a
     * "#shorts" search when the feed has none.
     */
    suspend fun fetchShorts(): HomeFeedResult = withContext(Dispatchers.IO) {
        val loggedIn = Session.loggedIn
        val found = LinkedHashMap<String, HomeFeedItem>()
        fun harvest(root: JSONObject) {
            val tmp = ArrayList<HomeFeedItem>()
            collectShorts(root, tmp)
            tmp.forEach { s -> s.videoId?.let { found.putIfAbsent(it, s) } }
        }
        try {
            var root = post("browse", JSONObject().put("browseId", "FEwhat_to_watch"))
            var pages = 0
            while (true) {
                harvest(root)
                val token = findContinuationToken(root)
                if (found.size >= 12 || token == null || ++pages >= 3) break
                root = post("browse", JSONObject().put("continuation", token))
            }
            if (found.size < 6) harvest(post("search", JSONObject().put("query", "#shorts")))
            LogRecorder.d(TAG, "shorts: ${found.size} found")
            HomeFeedResult(found.values.toList(), loggedIn)
        } catch (e: IOException) {
            HomeFeedResult(found.values.toList(), loggedIn, error = e.message ?: "network error")
        } catch (e: Exception) {
            LogRecorder.e(TAG, "shorts parse failed", e)
            HomeFeedResult(emptyList(), loggedIn, error = "couldn't read Shorts")
        }
    }

    data class WatchInfo(
        val title: String?,
        val meta: String?,
        val channelName: String?,
        val channelAvatar: String?,
        val subscribers: String?,
        val description: String?,
        val related: List<HomeFeedItem>,
        val continuation: String?,
        val error: String? = null,
        val channelId: String? = null,
        val subscribed: Boolean = false,
        val queue: List<HomeFeedItem> = emptyList(),
        val queueTitle: String? = null,
    )

    /** Everything under the player on a watch page: title, channel, description and the related list. */
    suspend fun fetchWatch(videoId: String, playlistId: String? = null): WatchInfo = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("videoId", videoId)
            if (playlistId != null) body.put("playlistId", playlistId)
            val root = post("next", body)
            val primary = findFirst(root, "videoPrimaryInfoRenderer")
            val secondary = findFirst(root, "videoSecondaryInfoRenderer")
            val owner = secondary?.optJSONObject("owner")?.optJSONObject("videoOwnerRenderer")

            val views = text(primary?.optJSONObject("viewCount")?.optJSONObject("videoViewCountRenderer")?.optJSONObject("viewCount"))
            val date = text(primary?.optJSONObject("dateText"))
            val desc = secondary?.optJSONObject("attributedDescription")?.optString("content")?.takeIf { it.isNotBlank() }
                ?: text(secondary?.optJSONObject("description"))

            val results = findFirst(root, "secondaryResults")
            val related = ArrayList<HomeFeedItem>()
            collect(results, related)

            val info = WatchInfo(
                title = text(primary?.optJSONObject("title")),
                meta = listOfNotNull(views, date).joinToString(" \u2022 ").ifBlank { null },
                channelName = text(owner?.optJSONObject("title")),
                channelAvatar = lastThumb(owner?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")),
                subscribers = text(owner?.optJSONObject("subscriberCountText")),
                description = desc,
                related = related.filter { it.videoId != videoId }.distinctBy { it.videoId ?: it.playlistId },
                continuation = findContinuationToken(results),
                channelId = owner?.let { findChannelId(it) },
                subscribed = owner?.let { findFirst(it, "subscribeButtonRenderer") }?.optBoolean("subscribed", false) ?: false,
                queue = if (playlistId != null) parseQueue(root, playlistId) else emptyList(),
                queueTitle = if (playlistId != null) findFirst(root, "playlist")?.optJSONObject("playlist")?.optString("title")?.takeIf { it.isNotBlank() } else null,
            )
            LogRecorder.d(TAG, "watch $videoId: title=${info.title != null}, channel=${info.channelName != null}, related=${info.related.size}")
            info
        } catch (e: Exception) {
            LogRecorder.w(TAG, "watch info failed for $videoId", e)
            WatchInfo(null, null, null, null, null, null, emptyList(), null, e.message ?: "couldn't load")
        }
    }

    // the playlist / mix panel next to a watch page
    private fun parseQueue(root: JSONObject, playlistId: String): List<HomeFeedItem> {
        val out = ArrayList<HomeFeedItem>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("playlistPanelVideoRenderer")?.let { r ->
                        val id = r.optString("videoId").takeIf { it.isNotBlank() } ?: return@let
                        val title = text(r.optJSONObject("title")) ?: return@let
                        out.add(
                            HomeFeedItem(
                                videoId = id, playlistId = playlistId, title = title,
                                channelName = text(r.optJSONObject("shortBylineText")) ?: "",
                                thumbnailUrl = lastThumb(r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")) ?: "",
                                durationText = text(r.optJSONObject("lengthText")) ?: "",
                                viewCountText = "",
                                channelId = findChannelId(r),
                            )
                        )
                    }
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        return out.distinctBy { it.videoId }
    }

    // ---------- actions (need a signed in session)

    private suspend fun action(path: String, body: JSONObject): Boolean = withContext(Dispatchers.IO) {
        if (!Session.loggedIn) return@withContext false
        try {
            post(path, body)
            true
        } catch (e: Exception) {
            LogRecorder.w(TAG, "$path failed: ${e.message}")
            false
        }
    }

    suspend fun like(videoId: String) = action("like/like", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    suspend fun dislike(videoId: String) = action("like/dislike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    suspend fun removeLike(videoId: String) = action("like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    suspend fun subscribe(channelId: String) =
        action("subscription/subscribe", JSONObject().put("channelIds", JSONArray().put(channelId)).put("params", "EgIIAhgA"))
    suspend fun unsubscribe(channelId: String) =
        action("subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId)).put("params", "CgIIAhgA"))

    // ---------- subscribed channels (the avatar strip on the Subs tab)

    data class ChannelItem(val id: String, val name: String, val avatar: String?)

    suspend fun fetchSubscribedChannels(): List<ChannelItem> = withContext(Dispatchers.IO) {
        if (!Session.loggedIn) return@withContext emptyList()
        try {
            val root = post("browse", JSONObject().put("browseId", "FEchannels"))
            val out = LinkedHashMap<String, ChannelItem>()
            fun walk(n: Any?) {
                when (n) {
                    is JSONObject -> {
                        n.optJSONObject("channelRenderer")?.let { r ->
                            val id = r.optString("channelId").takeIf { it.isNotBlank() }
                            val name = text(r.optJSONObject("title"))
                            if (id != null && name != null) {
                                val av = lastThumb(r.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                                    ?.let { if (it.startsWith("//")) "https:$it" else it }
                                out[id] = ChannelItem(id, name, av)
                            }
                        }
                        n.keys().forEach { walk(n.opt(it)) }
                    }
                    is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
                }
            }
            walk(root)
            LogRecorder.d(TAG, "subscribed channels: ${out.size}")
            out.values.toList()
        } catch (e: Exception) {
            LogRecorder.w(TAG, "channel list failed: ${e.message}")
            emptyList()
        }
    }

    // ---------- request

    private fun post(path: String, body: JSONObject): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", Session.clientVersion ?: FALLBACK_CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", Locale.getDefault().country.ifBlank { "US" })
        Session.visitorData?.let { client.put("visitorData", it) }

        val ctx = JSONObject().put("client", client)
        // brand accounts: act as the account that was picked at login
        if (Session.loggedIn) Session.dataSyncId?.let { ctx.put("user", JSONObject().put("onBehalfOfUser", it)) }
        body.put("context", ctx)

        val rb = Request.Builder()
            .url("$BASE_URL/$path?key=$API_KEY&prettyPrint=false")
            .post(body.toString().toRequestBody(jsonType))
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", Session.clientVersion ?: FALLBACK_CLIENT_VERSION)
        Session.visitorData?.let { rb.header("X-Goog-Visitor-Id", it) }

        val cookie = Session.cookie
        val sapisid = Session.sapisid()
        if (cookie != null && sapisid != null) {
            rb.header("Cookie", cookie)
            rb.header("X-Goog-AuthUser", "0")
            rb.header("Authorization", sapisidHash(sapisid))
        }

        val t0 = SystemClock.elapsedRealtime()
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            val ms = SystemClock.elapsedRealtime() - t0
            if (!r.isSuccessful) {
                LogRecorder.e(TAG, "$path -> HTTP ${r.code} in ${ms}ms (auth=${Session.loggedIn}) body=${text.take(300)}")
                throw IOException("YouTube answered ${r.code}")
            }
            LogRecorder.d(TAG, "$path -> ${r.code} in ${ms}ms, ${text.length} bytes")
            return JSONObject(text)
        }
    }

    // SHA1("{timestamp} {sapisid} {origin}"), computed locally from the cookie, nothing is fetched for it
    private fun sapisidHash(sapisid: String): String {
        val t = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$t $sapisid $ORIGIN".toByteArray(Charsets.UTF_8))
        return "SAPISIDHASH ${t}_${digest.joinToString("") { "%02x".format(it) }}"
    }

    // ---------- parsing
    // The tree is walked for known renderers instead of following fixed paths, YouTube keeps
    // moving the wrappers around them.

    private fun collect(node: Any?, out: MutableList<HomeFeedItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parseVideoRenderer(it)?.let(out::add) }
                node.optJSONObject("gridVideoRenderer")?.let { parseVideoRenderer(it)?.let(out::add) }
                node.optJSONObject("lockupViewModel")?.let { parseLockup(it)?.let(out::add) }
                node.optJSONObject("compactVideoRenderer")?.let { parseVideoRenderer(it)?.let(out::add) }
                node.optJSONObject("playlistVideoRenderer")?.let { parseVideoRenderer(it)?.let(out::add) }
                node.optJSONObject("radioRenderer")?.let { parsePlaylistLike(it)?.let(out::add) }
                (node.optJSONObject("playlistRenderer") ?: node.optJSONObject("compactPlaylistRenderer"))
                    ?.let { parsePlaylistLike(it)?.let(out::add) }
                node.keys().forEach { collect(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun parseVideoRenderer(r: JSONObject): HomeFeedItem? {
        val videoId = r.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = text(r.optJSONObject("title"))?.takeIf { it.isNotBlank() } ?: return null
        val channel = text(r.optJSONObject("ownerText")) ?: text(r.optJSONObject("shortBylineText"))
            ?: text(r.optJSONObject("longBylineText")) ?: ""

        // lengthText is missing on many shelves, the time status overlay has it too
        val duration = text(r.optJSONObject("lengthText"))
            ?: r.optJSONArray("thumbnailOverlays")?.objs()
                ?.firstNotNullOfOrNull { it.optJSONObject("thumbnailOverlayTimeStatusRenderer") }
                ?.let { text(it.optJSONObject("text")) }
            ?: ""

        val views = text(r.optJSONObject("shortViewCountText")) ?: text(r.optJSONObject("viewCountText"))
            ?: text(r.optJSONObject("videoInfo")) ?: ""
        val age = text(r.optJSONObject("publishedTimeText")) ?: ""

        val avatar = r.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }

        return HomeFeedItem(
            videoId = videoId,
            playlistId = null,
            title = title,
            channelName = channel,
            thumbnailUrl = lastThumb(r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")) ?: "",
            channelAvatarUrl = avatar,
            durationText = duration,
            viewCountText = listOf(views, age).filter { it.isNotBlank() }.joinToString(" • "),
            channelId = findChannelId(r),
        )
    }

    // first browseEndpoint under the node that points at a channel (UC...)
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

    // YouTube's newer card layout. Written from the response shape, so it is deliberately lenient.
    private fun parseLockup(l: JSONObject): HomeFeedItem? {
        val type = l.optString("contentType")
        val id = l.optString("contentId").takeIf { it.isNotBlank() } ?: return null
        val isVideo = type.endsWith("_VIDEO")
        val isPlaylist = type.endsWith("_PLAYLIST") || type.endsWith("_MIX")
        if (!isVideo && !isPlaylist) return null

        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content")?.takeIf { it.isNotBlank() } ?: return null

        val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
            ?.objs()?.map { row ->
                row.optJSONArray("metadataParts")?.objs()
                    ?.mapNotNull { it.optJSONObject("text")?.optString("content")?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
            } ?: emptyList()
        val channel = rows.firstOrNull()?.joinToString(" • ") ?: ""
        val rest = rows.drop(1).flatten().joinToString(" • ")

        val thumbRoot = l.optJSONObject("contentImage")
        val thumb = findFirst(thumbRoot, "image")?.optJSONArray("sources")?.let { lastThumb(it) } ?: ""
        val badge = findFirst(thumbRoot, "thumbnailBadgeViewModel")?.optString("text")?.takeIf { it.isNotBlank() }

        val avatar = findFirst(meta.optJSONObject("image"), "avatarViewModel")
            ?.optJSONObject("image")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }

        return if (isVideo) {
            HomeFeedItem(id, null, title, channel, thumb, avatar, badge ?: "", rest, channelId = findChannelId(l))
        } else {
            val firstVideo = findFirst(l, "watchEndpoint")?.optString("videoId")?.takeIf { it.length == 11 }
            HomeFeedItem(firstVideo, id, title, channel, thumb, avatar, "", rest, itemCountText = badge, channelId = findChannelId(l))
        }
    }

    private fun parsePlaylistLike(r: JSONObject): HomeFeedItem? {
        val playlistId = r.optString("playlistId").takeIf { it.isNotBlank() } ?: return null
        val title = text(r.optJSONObject("title"))?.takeIf { it.isNotBlank() } ?: return null
        val channel = text(r.optJSONObject("longBylineText")) ?: text(r.optJSONObject("shortBylineText")) ?: ""
        val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: r.optJSONObject("thumbnailRenderer")?.optJSONObject("showCustomThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val count = text(r.optJSONObject("videoCountShortText")) ?: text(r.optJSONObject("videoCountText"))
        return HomeFeedItem(
            videoId = r.optString("videoId").takeIf { it.isNotBlank() }
                ?: r.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.length == 11 },
            playlistId = playlistId,
            title = title,
            channelName = channel,
            thumbnailUrl = lastThumb(thumbs) ?: "",
            durationText = "",
            viewCountText = "",
            itemCountText = count?.takeIf { it.isNotBlank() },
        )
    }

    // Shorts come as shortsLockupViewModel (current) or reelItemRenderer (older), never as a normal video card
    private fun collectShorts(node: Any?, out: MutableList<HomeFeedItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("shortsLockupViewModel")?.let { parseShortLockup(it)?.let(out::add) }
                node.optJSONObject("reelItemRenderer")?.let { parseReelItem(it)?.let(out::add) }
                node.keys().forEach { collectShorts(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectShorts(node.opt(i), out)
        }
    }

    private fun parseShortLockup(l: JSONObject): HomeFeedItem? {
        val id = findFirst(l, "reelWatchEndpoint")?.optString("videoId")?.takeIf { it.length == 11 }
            ?: l.optString("entityId").removePrefix("shorts-shelf-item-").takeIf { it.length == 11 }
            ?: return null
        val overlay = l.optJSONObject("overlayMetadata")
        val title = overlay?.optJSONObject("primaryText")?.optString("content")?.takeIf { it.isNotBlank() } ?: "Short"
        val views = overlay?.optJSONObject("secondaryText")?.optString("content") ?: ""
        val thumb = lastThumb(l.optJSONObject("thumbnail")?.optJSONArray("sources"))
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return HomeFeedItem(id, null, title, "", thumb, null, "", views, isShort = true)
    }

    private fun parseReelItem(r: JSONObject): HomeFeedItem? {
        val id = r.optString("videoId").takeIf { it.length == 11 } ?: return null
        val title = text(r.optJSONObject("headline")) ?: "Short"
        val thumb = lastThumb(r.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return HomeFeedItem(id, null, title, "", thumb, null, "", text(r.optJSONObject("viewCountText")) ?: "", isShort = true)
    }

    private fun parseChips(root: JSONObject): List<FeedChip> {
        val bar = findFirst(root, "feedFilterChipBarRenderer") ?: return emptyList()
        return bar.optJSONArray("contents")?.objs()?.mapNotNull { c ->
            val r = c.optJSONObject("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = text(r.optJSONObject("text")) ?: return@mapNotNull null
            val token = r.optJSONObject("navigationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")
                ?.takeIf { it.isNotBlank() }
            FeedChip(title, token, r.optBoolean("isSelected"))
        } ?: emptyList()
    }

    private fun findContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                    ?.optString("token")?.takeIf { it.isNotBlank() }?.let { return it }
                node.keys().forEach { k -> findContinuationToken(node.opt(k))?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findContinuationToken(node.opt(i))?.let { return it }
        }
        return null
    }

    // ---------- json helpers

    private fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }.takeIf { it.isNotBlank() }
    }

    private fun lastThumb(a: JSONArray?): String? =
        a?.let { it.optJSONObject(it.length() - 1)?.optString("url")?.takeIf { u -> u.isNotBlank() } }

    private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /** Depth first search for the first object stored under [key]. */
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
}
