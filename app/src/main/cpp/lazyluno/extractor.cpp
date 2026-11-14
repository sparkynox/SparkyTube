// LazyLuno - extractor.cpp
// Ties it together: player request -> parse -> decipher if a stream came back with signatureCipher.

#include "lazyluno/lazyluno.h"

#include <algorithm>
#include <mutex>
#include <unordered_map>

namespace lazyluno {

namespace {

const char* kDesktopUa =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/137.0.0.0 Safari/537.36";

std::string httpGet(const std::string& url, int connectMs, int timeoutMs, const char* what) {
    http::Request r;
    r.url = url;
    r.connectTimeoutMs = connectMs;
    r.timeoutMs = timeoutMs;
    r.headers = {{"User-Agent", kDesktopUa}, {"Cookie", "SOCS=CAI"}};
    auto resp = http::perform(r);
    if (!resp.ok())
        throw Error(ErrorCode::NetworkError, std::string("failed to fetch ") + what + " (HTTP " +
                                                 std::to_string(resp.status) + ")");
    return resp.body;
}

// "PLAYER_JS_URL":"\/s\/player\/xxxx\/player_ias.vflset\/en_US\/base.js"
std::string findPlayerJsUrl(const std::string& page) {
    std::string path;
    static const std::string marker = "\"PLAYER_JS_URL\":\"";
    auto pos = page.find(marker);
    if (pos != std::string::npos) {
        pos += marker.size();
        path = page.substr(pos, page.find('"', pos) - pos);
    } else {
        pos = page.find("/s/player/");
        if (pos == std::string::npos) throw Error(ErrorCode::ParseError, "player JS url not found in page");
        auto start = page.rfind('"', pos) + 1;
        path = page.substr(start, page.find('"', pos) - start);
    }
    std::string url;
    for (size_t i = 0; i < path.size(); ++i) {
        if (path[i] == '\\' && i + 1 < path.size() && path[i + 1] == '/') { url += '/'; ++i; }
        else url += path[i];
    }
    if (url.compare(0, 4, "http") != 0) url = "https://www.youtube.com" + url;
    return url;
}

} // namespace

struct Extractor::Impl {
    ExtractorConfig cfg;
    AuthCookies auth;
    bool authenticated = false;
    std::mutex mu;
    std::string playerJsUrl;
    std::unordered_map<std::string, std::string> jsCache;

    explicit Impl(ExtractorConfig c) : cfg(std::move(c)) {}

    std::string playerJs() {
        std::lock_guard<std::mutex> l(mu);
        if (playerJsUrl.empty())
            playerJsUrl = findPlayerJsUrl(httpGet("https://www.youtube.com", cfg.connectTimeoutMs,
                                                  cfg.requestTimeoutMs, "youtube homepage"));
        auto it = jsCache.find(playerJsUrl);
        if (it != jsCache.end()) return it->second;
        auto js = httpGet(playerJsUrl, cfg.connectTimeoutMs, cfg.requestTimeoutMs, "player js");
        jsCache[playerJsUrl] = js;
        return js;
    }

    VideoInfo doFetch(const std::string& videoId) {
        std::string client;
        auto json = innertube::playerRequest(videoId, cfg.spoof, authenticated ? &auth : nullptr, {},
                                             cfg.connectTimeoutMs, cfg.requestTimeoutMs, cfg.hl, cfg.gl, &client);
        auto info = parser::parsePlayerResponse(json);

        auto needs = [](const Stream& s) { return s.url.compare(0, 2, "s=") == 0; };
        bool needsCipher = false;
        for (auto& s : info.adaptiveStreams) needsCipher |= needs(s);
        for (auto& s : info.muxedStreams) needsCipher |= needs(s);

        if (needsCipher && cfg.fetchPlayerJs) {
            // a cipher that can't be solved only costs those streams, the plain ones are still fine
            try {
                auto js = playerJs();
                auto fix = [&](Stream& s) {
                    if (!needs(s)) return;
                    try { s.url = cipher::decipher(s.url, js); } catch (const std::exception&) {}
                };
                for (auto& s : info.adaptiveStreams) fix(s);
                for (auto& s : info.muxedStreams) fix(s);
            } catch (const std::exception&) {}
        }
        // anything still ciphered is unusable, drop it instead of handing the player a broken url
        auto dead = [&](const Stream& s) { return s.url.empty() || needs(s); };
        info.adaptiveStreams.erase(std::remove_if(info.adaptiveStreams.begin(), info.adaptiveStreams.end(), dead),
                                   info.adaptiveStreams.end());
        info.muxedStreams.erase(std::remove_if(info.muxedStreams.begin(), info.muxedStreams.end(), dead),
                                info.muxedStreams.end());
        info.tags.push_back("client:" + client);  // read back by the JNI layer
        return info;
    }
};

Extractor::Extractor(ExtractorConfig cfg) : d(std::make_unique<Impl>(std::move(cfg))) {}
Extractor::~Extractor() = default;

void Extractor::setAuth(AuthCookies c) { d->auth = std::move(c); d->authenticated = d->auth.hasAuth(); }
void Extractor::clearAuth() { d->auth = {}; d->authenticated = false; }
bool Extractor::isAuthenticated() const { return d->authenticated; }
VideoInfo Extractor::fetchVideo(const std::string& id) { return d->doFetch(id); }

const Stream* VideoInfo::bestVideo(int maxHeight) const {
    const Stream* best = nullptr;
    for (auto& s : adaptiveStreams) {
        if (!s.isVideoOnly()) continue;
        if (maxHeight > 0 && s.height > maxHeight) continue;
        if (!best || s.height > best->height || (s.height == best->height && s.bitrate > best->bitrate)) best = &s;
    }
    return best;
}

const Stream* VideoInfo::bestAudio() const {
    const Stream* best = nullptr;
    for (auto& s : adaptiveStreams)
        if (s.isAudioOnly() && (!best || s.bitrate > best->bitrate)) best = &s;
    return best;
}

const Stream* VideoInfo::bestMuxed(int maxHeight) const {
    const Stream* best = nullptr;
    for (auto& s : muxedStreams) {
        if (maxHeight > 0 && s.height > maxHeight) continue;
        if (!best || s.height > best->height) best = &s;
    }
    return best;
}

} // namespace lazyluno
