package dev.sparkynox.sparkytube.extractor.social

import org.json.JSONObject

/** TikTok videos, photo posts and the sound, from the data the video page embeds. Short links (vm.tiktok.com) are followed first. */
object TikTokSocial {
    fun resolve(url: String): SocialResult {
        return try {
            val page = SocialHttp.get(url, mapOf("Accept-Language" to "en-US,en;q=0.9"))
            val item = findItem(page.body)
                ?: return SocialResult("TikTok", error = "Couldn't read that TikTok (private, removed, or TikTok changed its page)")

            val author = item.optJSONObject("author")?.optString("uniqueId").orEmpty()
            val title = item.optString("desc")
            val video = item.optJSONObject("video")
            val thumb = video?.optString("cover")?.takeIf { it.isNotBlank() }
            // the media urls only work with the cookies this page set and a TikTok referer
            val headers = mapOf("Referer" to "https://www.tiktok.com/", "Cookie" to page.cookies, "User-Agent" to SocialHttp.UA_DESKTOP)
            val items = ArrayList<SocialMedia>()

            val images = item.optJSONObject("imagePost")?.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    images.getJSONObject(i).optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0)
                        ?.takeIf { it.isNotBlank() }?.let { items += SocialMedia(MediaKind.IMAGE, it, "Image ${i + 1}", "jpg", headers = headers) }
                }
            } else if (video != null) {
                val ladder = video.optJSONArray("bitrateInfo")
                if (ladder != null) {
                    val list = (0 until ladder.length()).map { ladder.getJSONObject(it) }
                    for (b in list.sortedByDescending { it.optJSONObject("PlayAddr")?.optInt("Height") ?: 0 }) {
                        val pa = b.optJSONObject("PlayAddr") ?: continue
                        val u = pa.optJSONArray("UrlList")?.optString(0)?.takeIf { it.isNotBlank() } ?: continue
                        val h = pa.optInt("Height")
                        items += SocialMedia(MediaKind.VIDEO, u, if (h > 0) "${h}p" else b.optString("GearName", "Video"), "mp4", h, headers)
                    }
                }
                if (items.isEmpty()) {
                    val u = video.optString("playAddr").ifBlank { video.optString("downloadAddr") }
                    if (u.isNotBlank()) items += SocialMedia(MediaKind.VIDEO, u, "Video", "mp4", video.optInt("height"), headers)
                }
            }
            item.optJSONObject("music")?.optString("playUrl")?.takeIf { it.startsWith("http") }?.let {
                items += SocialMedia(MediaKind.AUDIO, it, "Original sound", "mp3", headers = headers)
            }
            if (items.isEmpty()) SocialResult("TikTok", title, author, thumb, error = "No media found in that TikTok")
            else SocialResult("TikTok", title, author, thumb, items)
        } catch (e: Exception) {
            SocialResult("TikTok", error = "Couldn't reach TikTok: ${e.message}")
        }
    }

    private fun findItem(html: String): JSONObject? {
        Regex("""<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.let { m ->
            runCatching {
                JSONObject(m.groupValues[1]).optJSONObject("__DEFAULT_SCOPE__")?.optJSONObject("webapp.video-detail")
                    ?.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            }.getOrNull()?.let { return it }
        }
        // older pages
        Regex("""<script id="SIGI_STATE"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.let { m ->
            runCatching {
                val mod = JSONObject(m.groupValues[1]).optJSONObject("ItemModule")
                mod?.keys()?.asSequence()?.firstOrNull()?.let { mod.optJSONObject(it) }
            }.getOrNull()?.let { return it }
        }
        return null
    }
}
