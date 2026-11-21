package dev.sparkynox.sparkytube.extractor.social

enum class MediaKind { VIDEO, IMAGE, AUDIO }

/** One thing that can be downloaded from a link: a video quality, one image of a carousel, a profile picture... */
class SocialMedia(
    val kind: MediaKind,
    val url: String,
    val label: String,                       // "HD 1080p", "Image 2", "Profile picture"
    val ext: String,                         // mp4, jpg, m4a
    val height: Int = 0,
    val headers: Map<String, String> = emptyMap(), // sent with the download (Referer, Cookie, User-Agent)
)

class SocialResult(
    val site: String,                        // "Instagram"
    val title: String = "",
    val author: String = "",
    val thumbnail: String? = null,
    val items: List<SocialMedia> = emptyList(),
    val needsLogin: Boolean = false,
    val error: String? = null,
) {
    val ok get() = items.isNotEmpty()

    /** What the instant download takes: the best video, else every picture. */
    fun best(): List<SocialMedia> {
        items.firstOrNull { it.kind == MediaKind.VIDEO }?.let { return listOf(it) }
        val pics = items.filter { it.kind == MediaKind.IMAGE }
        return pics.ifEmpty { items.take(1) }
    }
}
