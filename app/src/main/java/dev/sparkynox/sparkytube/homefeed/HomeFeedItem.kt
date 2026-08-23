package dev.sparkynox.sparkytube.homefeed

data class HomeFeedItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val durationText: String,
    val viewCountText: String
)
