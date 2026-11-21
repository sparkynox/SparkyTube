package dev.sparkynox.sparkytube.extractor.social

import android.content.Context
import dev.sparkynox.sparkytube.logs.LogRecorder
import java.net.URI

/**
 * LazyLuno v2 social resolver: a shared link (or any text with a link in it) in, what can be downloaded out.
 * Blocking, run it off the main thread. Never throws, problems come back in SocialResult.error.
 */
object SocialResolver {
    private val URL = Regex("""https?://[^\s<>"']+""")
    private val SHORT_HOSTS = setOf("vm.tiktok.com", "vt.tiktok.com", "t.co", "fb.watch", "bit.ly", "lnkd.in")

    enum class Site(val label: String) { INSTAGRAM("Instagram"), X("X"), TIKTOK("TikTok"), FACEBOOK("Facebook") }

    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

    fun siteOf(url: String): Site? {
        val host = try { URI(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.")?.removePrefix("web.") } catch (_: Exception) { null } ?: return null
        return when {
            host.endsWith("instagram.com") || host == "instagr.am" -> Site.INSTAGRAM
            host.endsWith("twitter.com") || host == "x.com" || host.endsWith("fxtwitter.com") || host.endsWith("vxtwitter.com") || host == "t.co" -> Site.X
            host.endsWith("tiktok.com") -> Site.TIKTOK
            host.endsWith("facebook.com") || host == "fb.watch" || host == "fb.com" -> Site.FACEBOOK
            else -> null
        }
    }

    fun resolve(ctx: Context, shared: String): SocialResult {
        SocialRules.ensure(ctx)
        var url = firstUrl(shared) ?: return SocialResult("", error = "I can't find a link in that")
        val t0 = System.currentTimeMillis()
        try {
            // short links and share links: follow them to the real address first
            val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
            if (host in SHORT_HOSTS || url.contains("/share/")) {
                val r = SocialHttp.get(url)
                if (r.url.startsWith("http")) url = r.url
            }
            url = url.replace("fxtwitter.com", "x.com").replace("vxtwitter.com", "x.com").replace("mobile.twitter.com", "twitter.com")

            val site = siteOf(url) ?: return SocialResult("", error = "That link isn't one I can download from (Instagram, X, TikTok and Facebook work)")
            val result = when (site) {
                Site.INSTAGRAM -> InstagramSocial.resolve(url)
                Site.X -> if (TwitterSocial.handles(url)) TwitterSocial.resolve(url) else SocialResult("X", error = "That X link has nothing I can download")
                Site.TIKTOK -> TikTokSocial.resolve(url)
                Site.FACEBOOK -> FacebookSocial.resolve(url)
            }
            LogRecorder.i("Social", "${site.label}: ${result.items.size} items in ${System.currentTimeMillis() - t0} ms ${result.error ?: ""}")
            return result
        } catch (e: Exception) {
            LogRecorder.w("Social", "resolve failed: ${e.message}")
            return SocialResult("", error = "Couldn't open that link: ${e.message}")
        }
    }
}
