package dev.sparkynox.sparkytube.homefeed

import androidx.compose.runtime.mutableStateListOf

// videos the user marked "Not interested" this session, filtered out of every list
object HiddenVideos {
    val ids = mutableStateListOf<String>()
    fun visible(items: List<HomeFeedItem>): List<HomeFeedItem> =
        if (ids.isEmpty()) items else items.filter { it.videoId == null || it.videoId !in ids }
}
