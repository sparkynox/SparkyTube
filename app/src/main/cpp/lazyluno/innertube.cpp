// LazyLuno - innertube.cpp
// POST /youtubei/v1/player with a client cascade (TV_EMBEDDED -> TVHTML5 -> ANDROID -> IOS),
// auth header injection and a check of what came back.

#include "lazyluno/lazyluno.h"

#include <string>
#include <vector>

namespace lazyluno {
namespace innertube {

static constexpr auto INNERTUBE_BASE = "https://www.youtube.com/youtubei/v1";

static std::vector<std::pair<std::string, std::string>>
buildHeaders(const ClientProfile& client, const AuthCookies* auth, const std::string& visitorData) {
    std::vector<std::pair<std::string, std::string>> h;
    h.push_back({"Content-Type", "application/json"});
    h.push_back({"X-YouTube-Client-Name", client.clientId});
    h.push_back({"X-YouTube-Client-Version", client.clientVersion});
    h.push_back({"User-Agent", client.userAgent});
    h.push_back({"Origin", "https://www.youtube.com"});
    h.push_back({"X-Origin", "https://www.youtube.com"});
    h.push_back({"Referer", "https://www.youtube.com/"});
    if (!visitorData.empty()) h.push_back({"X-Goog-Visitor-Id", visitorData});

    if (auth && auth->hasAuth()) {
        h.push_back({"Authorization", buildAuthorizationHeader(*auth)});
        h.push_back({"X-Goog-AuthUser", "0"});
        std::string c;
        auto add = [&](const std::string& n, const std::string& v) {
            if (v.empty()) return;
            if (!c.empty()) c += "; ";
            c += n + "=" + v;
        };
        add("SAPISID", auth->SAPISID);
        add("__Secure-1PAPISID", auth->SAPISID_1P);
        add("__Secure-3PAPISID", auth->SAPISID_3P);
        add("SID", auth->SID);
        add("HSID", auth->HSID);
        add("SSID", auth->SSID);
        add("APISID", auth->APISID);
        add("LOGIN_INFO", auth->LOGIN_INFO);
        add("VISITOR_INFO1_LIVE", auth->VISITOR_INFO1_LIVE);
        add("PREF", auth->PREF);
        add("SOCS", auth->SOCS.empty() ? "CAI" : auth->SOCS);
        for (auto& kv : auth->extra) add(kv.first, kv.second);
        h.push_back({"Cookie", c});
    } else {
        h.push_back({"Cookie", "SOCS=CAI"});
    }
    return h;
}

static bool isVideoUnavailable(const std::string& j) {
    return j.find("\"VIDEO_NOT_FOUND\"") != std::string::npos || j.find("\"REMOVED\"") != std::string::npos;
}

static bool hasStreams(const std::string& j) {
    return j.find("\"streamingData\"") != std::string::npos;
}

std::string playerRequest(const std::string& videoId, const SpoofConfig& spoof, AuthCookies* auth,
                          const std::string& visitorData, int connectTimeoutMs, int requestTimeoutMs,
                          const std::string& hl, const std::string& gl, std::string* usedClient) {
    std::string lastNote;

    auto tryClient = [&](ClientType t, bool withAuth) -> std::string {
        auto profile = getClientProfile(t);
        std::string body = buildInnertubeContext(profile, visitorData, {}, hl, gl);
        body.pop_back();  // drop the closing } and add the rest of the request
        body += ",\"videoId\":\"" + videoId + "\"";
        body += ",\"contentCheckOk\":true,\"racyCheckOk\":true";
        body += ",\"playbackContext\":{\"contentPlaybackContext\":{\"html5Preference\":\"HTML5_PREF_WANTS\"}}}";

        std::string url = std::string(INNERTUBE_BASE) + "/player";
        url += profile.apiKey.empty() ? "?prettyPrint=false" : "?key=" + profile.apiKey + "&prettyPrint=false";

        http::Request req;
        req.method = "POST";
        req.url = url;
        req.body = body;
        req.headers = buildHeaders(profile, withAuth ? auth : nullptr, visitorData);
        req.connectTimeoutMs = connectTimeoutMs;
        req.timeoutMs = requestTimeoutMs;

        auto resp = http::perform(req);
        if (!resp.ok()) {
            lastNote += profile.clientName + ":HTTP" + std::to_string(resp.status) +
                        (resp.error.empty() ? "" : "(" + resp.error + ")") + " ";
            return {};
        }
        if (isVideoUnavailable(resp.body))
            throw Error(ErrorCode::VideoUnavailable, "video " + videoId + " is unavailable");
        if (!hasStreams(resp.body)) {
            lastNote += profile.clientName + ":no-streams ";
            return {};
        }
        if (usedClient) *usedClient = profile.clientName;
        return resp.body;
    };

    if (spoof.forceClient) {
        auto r = tryClient(*spoof.forceClient, auth && auth->hasAuth());
        if (!r.empty()) return r;
        throw Error(ErrorCode::NoStreamsFound, "forced client returned no streams. " + lastNote);
    }
    for (auto t : spoof.clientOrder) {
        auto r = tryClient(t, false);
        if (!r.empty()) return r;
    }
    if (spoof.appendAuthFallback && auth && auth->hasAuth()) {
        for (auto t : {ClientType::ANDROID, ClientType::TV_EMBEDDED, ClientType::WEB}) {
            auto r = tryClient(t, true);
            if (!r.empty()) return r;
        }
    }
    throw Error(ErrorCode::AgeRestricted,
                "all clients failed for " + videoId + " (age restricted, login or PO token needed). " + lastNote);
}

} // namespace innertube
} // namespace lazyluno
