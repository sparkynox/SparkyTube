package dev.sparkynox.sparkytube.homefeed

import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Comment(
    val id: String,
    val author: String,
    val avatar: String?,
    val text: String,
    val time: String,
    val likes: String,
    val replyCount: String?,
    val repliesToken: String?,
    val pinned: Boolean = false,
    val byCreator: Boolean = false,
)

data class CommentSort(val title: String, val token: String, val selected: Boolean)

data class CommentsPage(
    val comments: List<Comment>,
    val next: String?,
    val total: String? = null,
    val sorts: List<CommentSort> = emptyList(),
    val error: String? = null,
)

/**
 * Comments through the same InnerTube `next` endpoint the web client uses.
 * YouTube serves them in two shapes (old commentThreadRenderer with the text inline, new
 * commentViewModel with the text in frameworkUpdates), both are read here.
 */
object CommentsApi {
    private const val TAG = "Comments"

    /** The continuation token that opens the comments, found in a `next` response of a watch page. */
    fun findToken(root: JSONObject): String? {
        fun walk(n: Any?): String? {
            when (n) {
                is JSONObject -> {
                    if (n.optString("sectionIdentifier") == "comment-item-section") {
                        token(n)?.let { return it }
                    }
                    // engagement panel version
                    if (n.optString("panelIdentifier") == "engagement-panel-comments-section") {
                        token(n)?.let { return it }
                    }
                    n.keys().forEach { k -> walk(n.opt(k))?.let { return it } }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun token(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                    ?.optString("token")?.takeIf { it.isNotBlank() }?.let { return it }
                node.keys().forEach { k -> token(node.opt(k))?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) token(node.opt(i))?.let { return it }
        }
        return null
    }

    /** For videos that did not come through fetchWatch (Shorts). One extra request. */
    suspend fun initialToken(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            findToken(InnerTubeClient.post("next", JSONObject().put("videoId", videoId)))
        } catch (e: Exception) {
            LogRecorder.w(TAG, "no comments token for $videoId: ${e.message}")
            null
        }
    }

    suspend fun load(token: String): CommentsPage = withContext(Dispatchers.IO) {
        try {
            val root = InnerTubeClient.post("next", JSONObject().put("continuation", token))
            val page = parse(root)
            LogRecorder.d(TAG, "page: ${page.comments.size} comments, more=${page.next != null}, sorts=${page.sorts.size}")
            page
        } catch (e: Exception) {
            LogRecorder.w(TAG, "load failed: ${e.message}")
            CommentsPage(emptyList(), null, error = e.message ?: "couldn't load comments")
        }
    }

    // ---------- parsing

    private fun parse(root: JSONObject): CommentsPage {
        // new layout: comment bodies live in the mutations, keyed by the commentKey of the thread item
        val entities = HashMap<String, JSONObject>()
        root.optJSONObject("frameworkUpdates")?.optJSONObject("entityBatchUpdate")?.optJSONArray("mutations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val e = m.optJSONObject("payload")?.optJSONObject("commentEntityPayload") ?: continue
                m.optString("entityKey").takeIf { it.isNotBlank() }?.let { entities[it] = e }
                e.optString("key").takeIf { it.isNotBlank() }?.let { entities[it] = e }
            }
        }

        val items = ArrayList<JSONObject>()
        collectItems(root, items)

        val out = ArrayList<Comment>()
        var next: String? = null
        var total: String? = null
        val sorts = ArrayList<CommentSort>()

        for (item in items) {
            item.optJSONObject("commentsHeaderRenderer")?.let { h ->
                total = text(h.optJSONObject("countText"))?.takeIf { s -> s.isNotBlank() }
                    ?: text(h.optJSONObject("commentsCount"))
                h.optJSONObject("sortMenu")?.optJSONObject("sortFilterSubMenuRenderer")?.optJSONArray("subMenuItems")?.let { sm ->
                    for (i in 0 until sm.length()) {
                        val o = sm.optJSONObject(i) ?: continue
                        val t = o.optString("title").takeIf { s -> s.isNotBlank() } ?: continue
                        val tok = o.optJSONObject("serviceEndpoint")?.optJSONObject("continuationCommand")?.optString("token")
                            ?.takeIf { s -> s.isNotBlank() } ?: continue
                        sorts.add(CommentSort(t, tok, o.optBoolean("selected")))
                    }
                }
            }
            item.optJSONObject("commentThreadRenderer")?.let { t -> parseThread(t, entities)?.let(out::add) }
            // replies come as bare items
            item.optJSONObject("commentViewModel")?.let { v -> parseViewModel(v, null, entities)?.let(out::add) }
            item.optJSONObject("commentRenderer")?.let { r -> parseLegacy(r, null)?.let(out::add) }
            item.optJSONObject("continuationItemRenderer")?.let { c ->
                val tok = c.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")
                    ?: c.optJSONObject("button")?.optJSONObject("buttonRenderer")?.optJSONObject("command")
                        ?.optJSONObject("continuationCommand")?.optString("token")
                if (!tok.isNullOrBlank()) next = tok
            }
        }
        // a header-less first page that only has sort tokens still needs the first sort to be loaded
        return CommentsPage(out, next, total, sorts)
    }

    // every continuationItems array in the response (reload for the first page, append for the next ones)
    private fun collectItems(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONArray("continuationItems")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(out::add)
                }
                node.keys().forEach { k -> if (k != "continuationItems") collectItems(node.opt(k), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectItems(node.opt(i), out)
        }
    }

    private fun parseThread(t: JSONObject, entities: Map<String, JSONObject>): Comment? {
        val repliesToken = t.optJSONObject("replies")?.optJSONObject("commentRepliesRenderer")
            ?.optJSONArray("contents")?.optJSONObject(0)
            ?.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }

        t.optJSONObject("comment")?.optJSONObject("commentRenderer")?.let { return parseLegacy(it, repliesToken) }

        val vm = t.optJSONObject("commentViewModel")?.let { it.optJSONObject("commentViewModel") ?: it } ?: return null
        return parseViewModel(vm, repliesToken, entities)
    }

    private fun parseLegacy(r: JSONObject, repliesToken: String?): Comment? {
        val id = r.optString("commentId").takeIf { it.isNotBlank() } ?: return null
        val body = text(r.optJSONObject("contentText")) ?: return null
        val likes = r.optJSONObject("voteCount")?.let { text(it) }
            ?: r.optString("likeCount").takeIf { it.isNotBlank() && it != "0" } ?: ""
        val replies = r.optInt("replyCount", 0).takeIf { it > 0 }?.toString()
        return Comment(
            id = id,
            author = text(r.optJSONObject("authorText")) ?: "",
            avatar = lastThumb(r.optJSONObject("authorThumbnail")?.optJSONArray("thumbnails")),
            text = body,
            time = text(r.optJSONObject("publishedTimeText")) ?: "",
            likes = likes,
            replyCount = replies,
            repliesToken = repliesToken,
            pinned = r.has("pinnedCommentBadge"),
            byCreator = r.optBoolean("authorIsChannelOwner"),
        )
    }

    private fun parseViewModel(vm: JSONObject, repliesToken: String?, entities: Map<String, JSONObject>): Comment? {
        val key = vm.optString("commentKey").takeIf { it.isNotBlank() } ?: return null
        val e = entities[key] ?: return null
        val props = e.optJSONObject("properties") ?: return null
        val body = props.optJSONObject("content")?.optString("content")?.takeIf { it.isNotBlank() } ?: return null
        val author = e.optJSONObject("author")
        val toolbar = e.optJSONObject("toolbar")
        val likes = toolbar?.optString("likeCountNotliked")?.takeIf { it.isNotBlank() && it != "0" } ?: ""
        val replies = toolbar?.optString("replyCount")?.takeIf { it.isNotBlank() && it != "0" }
        return Comment(
            id = props.optString("commentId").ifBlank { key },
            author = author?.optString("displayName") ?: "",
            avatar = author?.optString("avatarThumbnailUrl")?.takeIf { it.isNotBlank() },
            text = body,
            time = props.optString("publishedTime"),
            likes = likes,
            replyCount = replies,
            repliesToken = repliesToken,
            pinned = vm.has("pinnedText") || vm.optString("pinnedText").isNotBlank(),
            byCreator = author?.optBoolean("isCreator") ?: false,
        )
    }

    private fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }.takeIf { it.isNotBlank() }
    }

    private fun lastThumb(a: JSONArray?): String? =
        a?.let { it.optJSONObject(it.length() - 1)?.optString("url")?.takeIf { u -> u.isNotBlank() } }
}
