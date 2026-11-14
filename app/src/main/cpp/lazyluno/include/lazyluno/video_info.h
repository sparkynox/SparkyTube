#pragma once
// LazyLuno — video_info.h
// Author: SparkyNox

#include "stream.h"
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace lazyluno {

struct Thumbnail {
    std::string url;
    int         width  = 0;
    int         height = 0;
};

struct Chapter {
    std::string title;
    int64_t     startMs = 0;
};

struct VideoInfo {
    // Identity
    std::string id;
    std::string title;
    std::string description;
    std::string channelId;
    std::string channelName;
    std::string channelUrl;

    // Metadata
    int64_t     viewCount       = 0;
    int64_t     likeCount       = 0;    // -1 if hidden
    int64_t     durationMs      = 0;
    std::string uploadDate;             // "YYYYMMDD"
    std::string category;
    bool        isLive          = false;
    bool        isAgeRestricted = false;
    bool        isPrivate       = false;
    bool        isMembersOnly   = false;

    std::vector<std::string> tags;
    std::vector<Thumbnail>   thumbnails;
    std::vector<Chapter>     chapters;

    // Streams
    std::vector<Stream>        adaptiveStreams; // DASH-style (video-only + audio-only)
    std::vector<Stream>        muxedStreams;    // combined (lower quality, easy to use)
    std::vector<SubtitleTrack> subtitles;

    // DASH manifest URL (pre-built MPD from YouTube, present on some videos)
    std::optional<std::string> dashManifestUrl;

    // HLS (live streams)
    std::optional<std::string> hlsManifestUrl;

    // Best stream helpers
    const Stream* bestVideo(int maxHeight = 0) const;
    const Stream* bestAudio() const;
    const Stream* bestMuxed(int maxHeight = 0) const;

    bool hasStreams() const {
        return !adaptiveStreams.empty() || !muxedStreams.empty();
    }
};

} // namespace lazyluno
