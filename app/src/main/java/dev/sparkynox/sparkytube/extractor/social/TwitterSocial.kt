package dev.sparkynox.sparkytube.extractor.social

import org.json.JSONObject

/** X / Twitter: tweet videos, GIFs and pictures through the embed API, with fxtwitter as the second source. Profile pictures too. */
object TwitterSocial {
    private val STATUS = Regex("""(?:twitter|x)\.com/(?:[^/]+|i/web)/status(?:es)?/(\d+)""")
    private val PROFILE = Regex("""(?:twitter|x)\.com/([A-Za-z0-9_]{1,15})/?(?:\?.*)?$""")
    private val RESERVED = setOf("home", "explore", "search", "i", "settings", "notifications", "messages", "compose")

    fun handles(url: String) = STATUS.containsMatchIn(url) || PROFILE.find(url)?.groupValues?.get(1)?.lowercase()?.let { it !in RESERVED } == true

    fun resolve(url: String): SocialResult {
        STATUS.find(url)?.let { return tweet(it.groupValues[1]) }
        PROFILE.find(url)?.let { return profile(it.groupValues[1]) }
        return SocialResult("X", error = "That X link has nothing I can download")
    }

    private fun token(id: String): String {
        val d = id.toDouble() / 1e15 * Math.PI
        val whole = d.toLong()
        var frac = d - whole
        val sb = StringBuilder(java.lang.Long.toString(whole, 36))
        if (frac > 0) {
            sb.append('.')
            repeat(10) { frac *= 36; val x = frac.toInt(); sb.append(Character.forDigit(x, 36)); frac -= x }
        }
        return sb.toString().replace(Regex("(0+|\\.)"), "")
    }

    private fun tweet(id: String): SocialResult {
        val items = ArrayList<SocialMedia>()
        var title = ""; var author = ""; var thumb: String? = null
        try {
            val r = SocialHttp.get("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${token(id)}")
            if (r.code == 200 && r.body.isNotBlank()) {
                val o = JSONObject(r.body)
                title = o.optString("text"); author = o.optJSONObject("user")?.optString("screen_name").orEmpty()
                val media = o.optJSONArray("mediaDetails")
                if (media != null) for (i in 0 until media.length()) {
                    val m = media.getJSONObject(i)
                    if (thumb == null) thumb = m.optString("media_url_https").takeIf { it.isNotBlank() }
                    when (m.optString("type")) {
                        "photo" -> m.optString("media_url_https").takeIf { it.isNotBlank() }?.let {
                            items += SocialMedia(MediaKind.IMAGE, "$it:orig", "Image ${items.count { x -> x.kind == MediaKind.IMAGE } + 1}", "jpg")
                        }
                        else -> {
                            val vs = m.optJSONObject("video_info")?.optJSONArray("variants")
                            val mp4 = (0 until (vs?.length() ?: 0)).map { j -> vs!!.getJSONObject(j) }
                                .filter { v -> v.optString("content_type") == "video/mp4" }
                                .sortedByDescending { v -> v.optLong("bitrate") }
                            for (v in mp4) {
                                val h = Regex("""/(\d+)x(\d+)/""").find(v.optString("url"))?.groupValues?.get(2)?.toIntOrNull() ?: 0
                                items += SocialMedia(MediaKind.VIDEO, v.optString("url"), if (h > 0) "${h}p" else "Video", "mp4", h)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        if (items.isEmpty()) fx("https://api.fxtwitter.com/status/$id")?.let { (t, a, th, list) ->
            title = t; author = a; thumb = th; items += list
        }
        if (items.isEmpty()) return SocialResult("X", title, author, thumb, error = "No video or picture found in that post (it may be text only, protected or removed)")
        return SocialResult("X", title, author, thumb, items.sortedWith(compareBy({ it.kind }, { -it.height })))
    }

    private data class Fx(val title: String, val author: String, val thumb: String?, val items: List<SocialMedia>)

    private fun fx(url: String): Fx? = try {
        val r = SocialHttp.get(url)
        if (r.code != 200) null else {
            val t = JSONObject(r.body).optJSONObject("tweet")
            val media = t?.optJSONObject("media")
            val list = ArrayList<SocialMedia>()
            media?.optJSONArray("videos")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { v ->
                    list += SocialMedia(MediaKind.VIDEO, v.optString("url"), v.optInt("height").takeIf { h -> h > 0 }?.let { h -> "${h}p" } ?: "Video", "mp4", v.optInt("height"))
                }
            }
            media?.optJSONArray("photos")?.let { a ->
                for (i in 0 until a.length()) list += SocialMedia(MediaKind.IMAGE, a.getJSONObject(i).optString("url"), "Image ${i + 1}", "jpg")
            }
            Fx(t?.optString("text").orEmpty(), t?.optJSONObject("author")?.optString("screen_name").orEmpty(),
                list.firstOrNull { it.kind == MediaKind.IMAGE }?.url, list.filter { it.url.isNotBlank() })
        }
    } catch (_: Exception) { null }

    private fun profile(handle: String): SocialResult {
        return try {
            val r = SocialHttp.get("https://api.fxtwitter.com/$handle")
            val u = JSONObject(r.body).optJSONObject("user") ?: return SocialResult("X", error = "Couldn't find @$handle")
            val items = ArrayList<SocialMedia>()
            u.optString("avatar_url").takeIf { it.isNotBlank() }?.let {
                items += SocialMedia(MediaKind.IMAGE, it.replace("_normal", "_400x400"), "Profile picture", "jpg")
            }
            u.optString("banner_url").takeIf { it.isNotBlank() }?.let { items += SocialMedia(MediaKind.IMAGE, it, "Banner", "jpg") }
            SocialResult("X", u.optString("name"), handle, items.firstOrNull()?.url, items, error = if (items.isEmpty()) "No profile picture found" else null)
        } catch (e: Exception) {
            SocialResult("X", error = "Couldn't reach X: ${e.message}")
        }
    }
}
