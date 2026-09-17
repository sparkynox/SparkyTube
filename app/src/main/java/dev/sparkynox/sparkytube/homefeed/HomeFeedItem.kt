package dev.sparkynox.sparkytube.homefeed

data class HomeFeedItem(
    val videoId: String?,
    val playlistId: String?,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val channelAvatarUrl: String? = null,
    val durationText: String,
    val viewCountText: String,
    // Non-null only for Mix/Playlist cards -- "12 videos" style text,
    // shown as a badge over the thumbnail instead of a duration (a
    // playlist doesn't have a single duration the way one video does).
    val itemCountText: String? = null
)
