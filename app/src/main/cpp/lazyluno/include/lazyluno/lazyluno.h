#pragma once
// LazyLuno - main public header
// YouTube extractor library for SparkyTube
// Author: SparkyNox

#include "auth.h"
#include "error.h"
#include "http.h"
#include "spoof.h"
#include "stream.h"
#include "video_info.h"

#include <memory>
#include <optional>
#include <string>

namespace lazyluno {

struct ExtractorConfig {
    SpoofConfig spoof             = SpoofConfig::defaultConfig();
    int         connectTimeoutMs  = 5'000;
    int         requestTimeoutMs  = 15'000;
    bool        fetchPlayerJs     = true;   // only needed when a stream comes back with signatureCipher
    std::string hl                = "en";
    std::string gl                = "US";
};

class Extractor {
public:
    explicit Extractor(ExtractorConfig cfg = {});
    ~Extractor();
    Extractor(const Extractor&)            = delete;
    Extractor& operator=(const Extractor&) = delete;

    void setAuth(AuthCookies cookies);
    void clearAuth();
    bool isAuthenticated() const;

    // blocking, throws lazyluno::Error
    VideoInfo fetchVideo(const std::string& videoId);

    static std::optional<std::string> parseVideoId(const std::string& url);

private:
    struct Impl;
    std::unique_ptr<Impl> d;
};

// internal pieces, exposed so they can be tested on their own
namespace innertube {
std::string playerRequest(const std::string& videoId, const SpoofConfig& spoof, AuthCookies* auth,
                          const std::string& visitorData, int connectTimeoutMs, int requestTimeoutMs,
                          const std::string& hl, const std::string& gl, std::string* usedClient = nullptr);
}
namespace parser { VideoInfo parsePlayerResponse(const std::string& json); }
namespace cipher { std::string decipher(const std::string& signatureCipher, const std::string& playerJs); }

} // namespace lazyluno
