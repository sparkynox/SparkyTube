package dev.sparkynox.sparkytube.extractor.social

/** Facebook videos, reels and pictures from the public page (HD and SD). With the user's own sign in it also reads what that account can see. */
object FacebookSocial {
    fun resolve(url: String): SocialResult {
        return try {
            val cookie = SocialHttp.cookieFor("https://www.facebook.com")
            val h = HashMap<String, String>()
            h["Accept-Language"] = "en-US,en;q=0.9"
            h["Sec-Fetch-Mode"] = "navigate"
            if (cookie != null) h["Cookie"] = cookie
            val page = SocialHttp.get(url, h)
            val body = page.body

            val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(body)?.groupValues?.get(1)?.let { SocialHttp.htmlText(it) }.orEmpty()
            val thumb = Regex("""<meta property="og:image" content="([^"]*)"""").find(body)?.groupValues?.get(1)?.let { SocialHttp.htmlText(it) }

            val items = ArrayList<SocialMedia>()
            val seen = HashSet<String>()
            // hd patterns come first in the rules, so the first hit of each kind is the better one
            for (p in SocialRules.fbPatterns) {
                val m = Regex(p).find(body) ?: continue
                val u = SocialHttp.unescapeJson(m.groupValues[1])
                if (!u.startsWith("http") || !seen.add(u)) continue
                val hd = p.contains("hd", ignoreCase = true)
                items += SocialMedia(MediaKind.VIDEO, u, if (hd) "HD" else "SD", "mp4", if (hd) 720 else 360, mapOf("User-Agent" to SocialHttp.UA_DESKTOP))
            }
            if (items.isEmpty()) {
                // a picture post
                Regex(""""viewer_image":\{"height":(\d+),"width":(\d+),"uri":"([^"]+)"""").find(body)?.let { m ->
                    items += SocialMedia(MediaKind.IMAGE, SocialHttp.unescapeJson(m.groupValues[3]), "Image", "jpg", m.groupValues[1].toIntOrNull() ?: 0)
                }
            }
            if (items.isEmpty()) {
                val locked = cookie == null && (body.contains("login", true) || page.code in 400..499)
                return SocialResult("Facebook", title, "", thumb, needsLogin = locked,
                    error = if (locked) "This post needs a sign in. Sign in to Facebook in the settings and try again" else "No video or picture found (private, removed, or Facebook changed its page)")
            }
            SocialResult("Facebook", title, "", thumb, items.sortedByDescending { it.height })
        } catch (e: Exception) {
            SocialResult("Facebook", error = "Couldn't reach Facebook: ${e.message}")
        }
    }
}
