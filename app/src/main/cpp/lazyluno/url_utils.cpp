// LazyLuno — url_utils.cpp
// Author: SparkyNox
//
// YouTube URL parsing utilities.
// Handles all known URL patterns:
//   https://www.youtube.com/watch?v=XXXXXXXXXXX
//   https://youtu.be/XXXXXXXXXXX
//   https://www.youtube.com/shorts/XXXXXXXXXXX
//   https://www.youtube.com/embed/XXXXXXXXXXX
//   https://m.youtube.com/watch?v=XXXXXXXXXXX
//   https://music.youtube.com/watch?v=XXXXXXXXXXX

#include "lazyluno/lazyluno.h"

#include <optional>
#include <regex>
#include <string>

namespace lazyluno {

static const std::regex VIDEO_URL_RE(
    // Group 1: watch?v= form
    R"((?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/watch\?.*[?&]v=([A-Za-z0-9_-]{11}))"
    R"(|)"
    // Group 2: youtu.be short link
    R"((?:https?://)?youtu\.be/([A-Za-z0-9_-]{11}))"
    R"(|)"
    // Group 3: /shorts/, /embed/, /v/
    R"((?:https?://)?(?:www\.)?youtube\.com/(?:shorts|embed|v)/([A-Za-z0-9_-]{11}))",
    std::regex::ECMAScript
);

// 11-char video ID pattern
static const std::regex BARE_ID_RE(R"(^[A-Za-z0-9_-]{11}$)");

std::optional<std::string> Extractor::parseVideoId(const std::string& input) {
    // Already a bare ID
    if (std::regex_match(input, BARE_ID_RE)) return input;

    std::smatch m;
    if (!std::regex_search(input, m, VIDEO_URL_RE)) return std::nullopt;

    // Return first non-empty capture group
    for (size_t i = 1; i < m.size(); ++i) {
        if (m[i].matched && !m[i].str().empty())
            return m[i].str();
    }
    return std::nullopt;
}

} // namespace lazyluno
