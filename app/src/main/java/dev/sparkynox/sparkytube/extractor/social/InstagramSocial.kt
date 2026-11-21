package dev.sparkynox.sparkytube.extractor.social

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLEncoder

/**
 * Instagram: reels, videos, pictures and carousels, stories, profile pictures.
 * Public posts need no sign in (query + embed page). Stories and private-ish things use the user's own sign in
 * (SocialLoginActivity) with the mobile API. Query ids and app ids come from SocialRules so they can be changed without an update.
 */
object InstagramSocial {
    private val POST = Regex("""instagram\.com/(?:[\w.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
    private val STORY = Regex("""instagram\.com/stories/([\w.]+)(?:/(\d+))?""")
    private val PROFILE = Regex("""instagram\.com/([\w.]+)/?(?:\?.*)?$""")
    private val RESERVED = setOf("explore", "accounts", "direct", "reels", "p", "reel", "tv", "stories", "about", "legal")
    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun resolve(url: String): SocialResult {
        val cookie = SocialHttp.cookieFor("https://www.instagram.com")
        return try {
            STORY.find(url)?.let { return story(it.groupValues[1], it.groupValues[2].ifBlank { null }, cookie) }
            POST.find(url)?.let { return post(it.groupValues[1], cookie) }
            PROFILE.find(url)?.groupValues?.get(1)?.takeIf { it.lowercase() !in RESERVED }?.let { return profile(it, cookie) }
            SocialResult("Instagram", error = "That Instagram link has nothing I can download")
        } catch (e: Exception) {
            SocialResult("Instagram", error = "Couldn't reach Instagram: ${e.message}")
        }
    }

    private fun webHeaders(cookie: String?, referer: String): Map<String, String> {
        val h = HashMap<String, String>()
        h["X-IG-App-ID"] = SocialRules.igAppId
        h["X-Requested-With"] = "XMLHttpRequest"
        h["Referer"] = referer
        h["Accept-Language"] = "en-US,en;q=0.9"
        if (cookie != null) h["Cookie"] = cookie
        return h
    }

    private fun apiHeaders(cookie: String?): Map<String, String> {
        val h = HashMap<String, String>()
        h["User-Agent"] = SocialRules.igMobileUa
        h["X-IG-App-ID"] = SocialRules.igAppId
        h["Accept-Language"] = "en-US"
        if (cookie != null) {
            h["Cookie"] = cookie
            SocialHttp.cookieValue(cookie, "csrftoken")?.let { h["X-CSRFToken"] = it }
        }
        return h
    }

    // ---------- posts

    private fun post(code: String, cookie: String?): SocialResult {
        // 1. the web query, public posts, no sign in
        try {
            val vars = URLEncoder.encode("""{"shortcode":"$code","fetch_tagged_user_count":null,"hoisted_comment_id":null,"hoisted_reply_id":null}""", "UTF-8")
            val r = SocialHttp.get(
                "https://www.instagram.com/graphql/query/?doc_id=${SocialRules.igDocId}&variables=$vars",
                webHeaders(cookie, "https://www.instagram.com/p/$code/"),
            )
            if (r.code == 200) {
                val d = JSONObject(r.body).optJSONObject("data")
                val node = d?.optJSONObject("xdt_shortcode_media") ?: d?.optJSONObject("shortcode_media")
                if (node != null) {
                    val items = ArrayList<SocialMedia>()
                    fromWebNode(node, items)
                    if (items.isNotEmpty()) return result(node.optJSONObject("owner")?.optString("username").orEmpty(),
                        node.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.optString("text").orEmpty(),
                        node.optString("display_url").takeIf { it.isNotBlank() }, items)
                }
            }
        } catch (_: Exception) { }

        // 2. signed in: the mobile API gives every quality
        if (cookie != null) try {
            val r = SocialHttp.get("https://i.instagram.com/api/v1/media/${shortcodeToId(code)}/info/", apiHeaders(cookie))
            val item = JSONObject(r.body).optJSONArray("items")?.optJSONObject(0)
            if (item != null) {
                val items = ArrayList<SocialMedia>()
                fromApiItem(item, items)
                if (items.isNotEmpty()) return result(item.optJSONObject("user")?.optString("username").orEmpty(),
                    item.optJSONObject("caption")?.optString("text").orEmpty(), items.firstOrNull { it.kind == MediaKind.IMAGE }?.url, items)
            }
        } catch (_: Exception) { }

        // 3. the embed page of a public post
        try {
            val r = SocialHttp.get("https://www.instagram.com/p/$code/embed/captioned/", mapOf("Accept-Language" to "en-US,en;q=0.9"))
            val items = ArrayList<SocialMedia>()
            Regex(""""video_url":"([^"]+)"""").find(r.body)?.let {
                items += SocialMedia(MediaKind.VIDEO, SocialHttp.unescapeJson(it.groupValues[1]), "Video", "mp4")
            }
            if (items.isEmpty()) Regex("""class="EmbeddedMediaImage"[^>]*src="([^"]+)"""").find(r.body)?.let {
                items += SocialMedia(MediaKind.IMAGE, SocialHttp.htmlText(it.groupValues[1]), "Image", "jpg")
            }
            if (items.isNotEmpty()) return result("", "", items.firstOrNull { it.kind == MediaKind.IMAGE }?.url, items)
        } catch (_: Exception) { }

        return SocialResult(
            "Instagram", needsLogin = cookie == null,
            error = if (cookie == null) "Instagram wants a sign in for this one. Sign in to Instagram in the settings and try again"
            else "Couldn't read that post (private, removed, or Instagram changed something)",
        )
    }

    private fun result(author: String, caption: String, thumb: String?, items: List<SocialMedia>) =
        SocialResult("Instagram", caption.take(120), author, thumb, items.sortedWith(compareBy({ it.kind }, { -it.height })))

    private fun fromWebNode(node: JSONObject, out: MutableList<SocialMedia>) {
        val kids = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (kids != null) {
            for (i in 0 until kids.length()) kids.optJSONObject(i)?.optJSONObject("node")?.let { one(it, i + 1, out) }
        } else one(node, null, out)
    }

    private fun one(n: JSONObject, index: Int?, out: MutableList<SocialMedia>) {
        val h = n.optJSONObject("dimensions")?.optInt("height") ?: 0
        val tag = index?.let { " $it" } ?: ""
        if (n.optBoolean("is_video") && n.optString("video_url").isNotBlank()) {
            out += SocialMedia(MediaKind.VIDEO, n.optString("video_url"), if (h > 0) "Video$tag ${h}p" else "Video$tag", "mp4", h)
        } else if (n.optString("display_url").isNotBlank()) {
            out += SocialMedia(MediaKind.IMAGE, n.optString("display_url"), "Image$tag", "jpg", h)
        }
    }

    private fun fromApiItem(item: JSONObject, out: MutableList<SocialMedia>) {
        val carousel = item.optJSONArray("carousel_media")
        if (carousel != null) {
            for (i in 0 until carousel.length()) carousel.optJSONObject(i)?.let { apiOne(it, i + 1, out) }
        } else apiOne(item, null, out)
    }

    private fun apiOne(m: JSONObject, index: Int?, out: MutableList<SocialMedia>) {
        val tag = index?.let { " $it" } ?: ""
        val videos = m.optJSONArray("video_versions")
        if (videos != null && videos.length() > 0) {
            val list = (0 until videos.length()).map { videos.getJSONObject(it) }.sortedByDescending { it.optInt("height") }
            // one quality is enough per slide of a carousel, the whole ladder only for a single video
            for (v in if (index == null) list else list.take(1)) {
                val h = v.optInt("height")
                out += SocialMedia(MediaKind.VIDEO, v.optString("url"), if (h > 0) "Video$tag ${h}p" else "Video$tag", "mp4", h)
            }
        } else {
            m.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)?.let {
                out += SocialMedia(MediaKind.IMAGE, it.optString("url"), "Image$tag", "jpg", it.optInt("height"))
            }
        }
    }

    // Instagram's shortcode is the media id written in a 64 letter alphabet
    private fun shortcodeToId(code: String): String {
        var id = BigInteger.ZERO
        for (c in code.take(11)) id = id.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(ALPHA.indexOf(c).toLong().coerceAtLeast(0)))
        return id.toString()
    }

    // ---------- stories and profile pictures

    private fun profileInfo(user: String, cookie: String?): JSONObject? {
        val r = SocialHttp.get("https://i.instagram.com/api/v1/users/web_profile_info/?username=$user", apiHeaders(cookie))
        return JSONObject(r.body).optJSONObject("data")?.optJSONObject("user")
    }

    private fun story(user: String, storyId: String?, cookie: String?): SocialResult {
        if (cookie == null) return SocialResult("Instagram", needsLogin = true, error = "Stories need a sign in. Sign in to Instagram in the settings and try again")
        val info = profileInfo(user, cookie) ?: return SocialResult("Instagram", error = "Couldn't find @$user")
        val uid = info.optString("id")
        val r = SocialHttp.get("https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=$uid", apiHeaders(cookie))
        val reel = JSONObject(r.body).optJSONArray("reels_media")?.optJSONObject(0)
        val stories: JSONArray? = reel?.optJSONArray("items")
        if (stories == null || stories.length() == 0) return SocialResult("Instagram", "", user, null, error = "@$user has no story right now")

        val items = ArrayList<SocialMedia>()
        for (i in 0 until stories.length()) {
            val s = stories.getJSONObject(i)
            if (storyId != null && s.optString("pk") != storyId && s.optString("id").substringBefore('_') != storyId) continue
            val before = items.size
            apiOne(s, i + 1, items)
            // a story is one slide, only its best quality
            val added = items.subList(before, items.size)
            if (added.size > 1) { val keep = added.first(); while (items.size > before) items.removeAt(items.size - 1); items += keep }
        }
        if (items.isEmpty()) return SocialResult("Instagram", "", user, null, error = "That story is gone (stories last 24 hours)")
        return SocialResult("Instagram", "Stories", user, items.firstOrNull { it.kind == MediaKind.IMAGE }?.url, items)
    }

    private fun profile(user: String, cookie: String?): SocialResult {
        val info = try { profileInfo(user, cookie) } catch (_: Exception) { null }
        val pic = info?.optString("profile_pic_url_hd")?.takeIf { it.isNotBlank() }
        if (pic == null) return SocialResult(
            "Instagram", needsLogin = cookie == null,
            error = if (cookie == null) "Instagram wants a sign in to show that profile. Sign in to Instagram in the settings" else "Couldn't read @$user",
        )
        return SocialResult("Instagram", info.optString("full_name"), user, pic, listOf(SocialMedia(MediaKind.IMAGE, pic, "Profile picture", "jpg")))
    }
}
