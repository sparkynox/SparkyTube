// LazyLuno - stream_parser.cpp
// InnerTube /player response -> VideoInfo. nlohmann::json, so field order doesn't matter
// and a missing or differently typed field just stays at its default.

#include "lazyluno/lazyluno.h"

#include <nlohmann/json.hpp>

#include <string>

namespace lazyluno {
namespace parser {

using nlohmann::json;

static ContainerFormat mimeToContainer(const std::string& m) {
    if (m.find("mp4") != std::string::npos) return ContainerFormat::MP4;
    if (m.find("webm") != std::string::npos) return ContainerFormat::WEBM;
    return ContainerFormat::Unknown;
}

static StreamType mimeToStreamType(const std::string& m) {
    if (m.rfind("video", 0) == 0) return StreamType::Video;
    if (m.rfind("audio", 0) == 0) return StreamType::Audio;
    return StreamType::Muxed;
}

static std::string extractCodec(const std::string& mime) {
    auto pos = mime.find("codecs=\"");
    if (pos == std::string::npos) return {};
    pos += 8;
    auto end = mime.find('"', pos);
    return end == std::string::npos ? std::string() : mime.substr(pos, end - pos);
}

// numbers come back as numbers or as strings depending on the field
static long long num(const json& o, const char* key, long long def = 0) {
    auto it = o.find(key);
    if (it == o.end()) return def;
    if (it->is_number()) return it->get<long long>();
    if (it->is_string()) {
        try { return std::stoll(it->get<std::string>()); } catch (...) { return def; }
    }
    return def;
}

static std::string str(const json& o, const char* key) {
    auto it = o.find(key);
    return (it != o.end() && it->is_string()) ? it->get<std::string>() : std::string();
}

static Stream parseFormat(const json& f) {
    Stream s;
    s.itag = (int) num(f, "itag");
    s.mimeType = str(f, "mimeType");
    s.container = mimeToContainer(s.mimeType);
    s.type = mimeToStreamType(s.mimeType);
    s.codec = extractCodec(s.mimeType);

    // a signatureCipher is kept as is, the extractor deciphers it later (url starts with "s=")
    s.url = str(f, "url");
    if (s.url.empty()) s.url = str(f, "signatureCipher");
    if (s.url.empty()) s.url = str(f, "cipher");

    s.bitrate = num(f, "averageBitrate", num(f, "bitrate"));
    s.contentLength = num(f, "contentLength");
    s.approxDurationMs = num(f, "approxDurationMs");
    s.width = (int) num(f, "width");
    s.height = (int) num(f, "height");
    s.fps = (double) num(f, "fps");
    s.qualityLabel = str(f, "qualityLabel");
    s.audioSampleRate = (int) num(f, "audioSampleRate");
    s.audioChannels = (int) num(f, "audioChannels");
    if (s.type == StreamType::Audio) s.audioBitrate = (int) s.bitrate;

    if (f.contains("initRange")) {
        s.initStart = (int) num(f["initRange"], "start");
        s.initEnd = (int) num(f["initRange"], "end");
        s.isDashStream = true;
    }
    if (f.contains("indexRange")) {
        s.indexStart = (int) num(f["indexRange"], "start");
        s.indexEnd = (int) num(f["indexRange"], "end");
    }
    return s;
}

VideoInfo parsePlayerResponse(const std::string& body) {
    json doc = json::parse(body, nullptr, false);
    if (doc.is_discarded() || !doc.is_object())
        throw Error(ErrorCode::ParseError, "player response is not valid json");

    VideoInfo info;

    if (doc.contains("videoDetails") && doc["videoDetails"].is_object()) {
        const json& d = doc["videoDetails"];
        info.id = str(d, "videoId");
        info.title = str(d, "title");
        info.channelName = str(d, "author");
        info.channelId = str(d, "channelId");
        info.description = str(d, "shortDescription");
        info.durationMs = num(d, "lengthSeconds") * 1000;
        info.viewCount = num(d, "viewCount");
        if (d.contains("isLiveContent") && d["isLiveContent"].is_boolean()) info.isLive = d["isLiveContent"];
        if (d.contains("isLive") && d["isLive"].is_boolean() && d["isLive"].get<bool>()) info.isLive = true;
        if (d.contains("keywords") && d["keywords"].is_array())
            for (auto& k : d["keywords"]) if (k.is_string()) info.tags.push_back(k);
        if (d.contains("thumbnail") && d["thumbnail"].contains("thumbnails"))
            for (auto& t : d["thumbnail"]["thumbnails"]) {
                Thumbnail th;
                th.url = str(t, "url");
                th.width = (int) num(t, "width");
                th.height = (int) num(t, "height");
                if (!th.url.empty()) info.thumbnails.push_back(th);
            }
    }

    if (doc.contains("microformat") && doc["microformat"].contains("playerMicroformatRenderer")) {
        const json& m = doc["microformat"]["playerMicroformatRenderer"];
        info.uploadDate = str(m, "publishDate");
        info.category = str(m, "category");
    }

    if (doc.contains("streamingData") && doc["streamingData"].is_object()) {
        const json& sd = doc["streamingData"];
        if (sd.contains("dashManifestUrl") && sd["dashManifestUrl"].is_string()) info.dashManifestUrl = sd["dashManifestUrl"];
        if (sd.contains("hlsManifestUrl") && sd["hlsManifestUrl"].is_string()) info.hlsManifestUrl = sd["hlsManifestUrl"];
        if (sd.contains("formats") && sd["formats"].is_array())
            for (auto& f : sd["formats"]) {
                Stream s = parseFormat(f);
                s.type = StreamType::Muxed;
                info.muxedStreams.push_back(s);
            }
        if (sd.contains("adaptiveFormats") && sd["adaptiveFormats"].is_array())
            for (auto& f : sd["adaptiveFormats"]) info.adaptiveStreams.push_back(parseFormat(f));
    }

    if (doc.contains("captions") && doc["captions"].contains("playerCaptionsTracklistRenderer")) {
        const json& c = doc["captions"]["playerCaptionsTracklistRenderer"];
        if (c.contains("captionTracks") && c["captionTracks"].is_array())
            for (auto& t : c["captionTracks"]) {
                SubtitleTrack tr;
                tr.url = str(t, "baseUrl");
                tr.langCode = str(t, "languageCode");
                std::string vss = str(t, "vssId");
                tr.isAutoGenerated = !vss.empty() && vss[0] == 'a';
                if (t.contains("name") && t["name"].contains("simpleText")) tr.langName = str(t["name"], "simpleText");
                if (!tr.url.empty()) info.subtitles.push_back(tr);
            }
    }
    return info;
}

} // namespace parser
} // namespace lazyluno
