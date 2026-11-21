// LazyLuno — client_spoof.cpp
// Author: SparkyNox
//
// Hardcoded InnerTube client profiles.
// Sources: yt-dlp _base.py + NewPipe ClientsConstants.java (2026-11)
// FILL: re-derive clientVersion strings from a live YouTube page at build time
//       using: curl -s https://www.youtube.com | grep -o '"INNERTUBE_CLIENT_VERSION":"[^"]*"'

#include "lazyluno/spoof.h"
#include <nlohmann/json.hpp>
#include <stdexcept>
#include <string>

namespace lazyluno {

ClientProfile getClientProfile(ClientType type) {
    switch (type) {

    // ── WEB ──────────────────────────────────────────────────────────────────
    case ClientType::WEB:
        return {
            .type           = ClientType::WEB,
            .clientName     = "WEB",
            .clientVersion  = "2.20260805.01.00",
            .clientId       = "1",
            .osName         = "",
            .osVersion      = "",
            .platform       = "DESKTOP",
            .userAgent      = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                              "AppleWebKit/537.36 (KHTML, like Gecko) "
                              "Chrome/137.0.0.0 Safari/537.36",
            .apiKey         = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = true,
            .supportsAgeBypass = false,
            .needsEmbedUrl     = false,
        };

    // ── ANDROID ──────────────────────────────────────────────────────────────
    // No po_token required. Full adaptive formats. Use as primary non-auth fallback.
    case ClientType::ANDROID_APP:
        return {
            .type             = ClientType::ANDROID_APP,
            .clientName       = "ANDROID",
            .clientVersion    = "21.26.364",   // FILL: check yt-dlp _base.py for latest
            .clientId         = "3",
            .osName           = "Android",
            .osVersion        = "11",
            .platform         = "MOBILE",
            .androidSdkVersion= 30,
            .userAgent        = "com.google.android.youtube/21.26.364 "
                                "(Linux; U; Android 11) gzip",
            .apiKey           = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
            .innertubeHost    = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = false,
            .needsEmbedUrl     = false,
        };

    // ── ANDROID_VR ────────────────────────────────────────────────────────────
    // The Quest YouTube app. No po_token, no login, full adaptive ladder (144p up to 4K, h264/vp9/av1).
    // Does not play age-restricted or made-for-kids videos, those go through the TV clients.
    case ClientType::ANDROID_VR:
        return {
            .type             = ClientType::ANDROID_VR,
            .clientName       = "ANDROID_VR",
            .clientVersion    = "1.65.10",
            .clientId         = "28",
            .osName           = "Android",
            .osVersion        = "12L",
            .deviceMake       = "Oculus",
            .deviceModel      = "Quest 3",
            .platform         = "MOBILE",
            .androidSdkVersion= 32,
            .userAgent        = "com.google.android.apps.youtube.vr.oculus/1.65.10 "
                                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            .apiKey           = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
            .innertubeHost    = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = false,
            .needsEmbedUrl     = false,
        };

    // ── IOS ───────────────────────────────────────────────────────────────────
    case ClientType::IOS:
        return {
            .type           = ClientType::IOS,
            .clientName     = "IOS",
            .clientVersion  = "21.26.4",
            .clientId       = "5",
            .osName         = "iOS",
            .osVersion      = "18.3.2.22D82",
            .deviceMake     = "Apple",
            .deviceModel    = "iPhone16,2",   // iPhone 15 Pro Max
            .platform       = "MOBILE",
            .userAgent      = "com.google.ios.youtube/21.26.4 "
                              "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            .apiKey         = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = false,
            .needsEmbedUrl     = false,
        };

    // ── TVHTML5 ───────────────────────────────────────────────────────────────
    // No po_token. Limited age bypass (less reliable than TV_EMBEDDED).
    case ClientType::TVHTML5:
        return {
            .type           = ClientType::TVHTML5,
            .clientName     = "TVHTML5",
            .clientVersion  = "7.20260707.07.00",
            .clientId       = "7",
            .platform       = "TV",
            .userAgent      = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) "
                              "AppleWebKit/538.1 (KHTML, like Gecko) "
                              "Version/6.0 TV Safari/538.1",
            .apiKey         = "",              // TV clients don't need key
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = true,
            .needsEmbedUrl     = false,
        };

    // ── TV_EMBEDDED ───────────────────────────────────────────────────────────
    // TVHTML5_SIMPLY_EMBEDDED_PLAYER — primary age-gate bypass path.
    // Sets clientScreen=EMBED and thirdParty.embedUrl.
    // Works if uploader hasn't disabled embedding. No po_token needed.
    case ClientType::TV_EMBEDDED:
        return {
            .type           = ClientType::TV_EMBEDDED,
            .clientName     = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            .clientVersion  = "2.0",
            .clientId       = "85",
            .platform       = "TV",
            .userAgent      = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) "
                              "AppleWebKit/538.1 (KHTML, like Gecko) "
                              "Version/6.0 TV Safari/538.1",
            .apiKey         = "",
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = true,
            .needsEmbedUrl     = true,
            .embedUrl          = "https://www.google.com",  // any non-YT domain works
        };

    // ── TVHTML5_SIMPLY ────────────────────────────────────────────────────────
    case ClientType::TVHTML5_SIMPLY:
        return {
            .type           = ClientType::TVHTML5_SIMPLY,
            .clientName     = "TVHTML5_SIMPLY",
            .clientVersion  = "1.0",
            .clientId       = "75",
            .platform       = "TV",
            .userAgent      = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) "
                              "AppleWebKit/538.1 (KHTML, like Gecko) "
                              "Version/6.0 TV Safari/538.1",
            .apiKey         = "",
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = true,
            .needsEmbedUrl     = false,
        };

    // ── MWEB ──────────────────────────────────────────────────────────────────
    // Includes "ultralow" quality formats not available on other clients.
    case ClientType::MWEB:
        return {
            .type           = ClientType::MWEB,
            .clientName     = "MWEB",
            .clientVersion  = "2.20260708.05.00",
            .clientId       = "2",
            .platform       = "MOBILE",
            .userAgent      = "Mozilla/5.0 (Linux; Android 11; Pixel 5) "
                              "AppleWebKit/537.36 (KHTML, like Gecko) "
                              "Chrome/137.0.0.0 Mobile Safari/537.36",
            .apiKey         = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = true,   // added in 2024, required now
            .supportsAgeBypass = false,
            .needsEmbedUrl     = false,
        };

    // ── VISIONOS ──────────────────────────────────────────────────────────────
    case ClientType::VISIONOS:
        return {
            .type           = ClientType::VISIONOS,
            .clientName     = "VISIONOS",
            .clientVersion  = "1.04",
            .clientId       = "101",
            .osName         = "visionOS",
            .osVersion      = "26.6.0.23O770",
            .deviceMake     = "Apple",
            .deviceModel    = "RealityDevice17,1",
            .platform       = "MOBILE",
            .userAgent      = "com.google.ios.youtube/1.04 "
                              "(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X;)",
            .apiKey         = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = false,
            .needsEmbedUrl     = false,
        };

    // ── WEB_CREATOR ───────────────────────────────────────────────────────────
    case ClientType::WEB_CREATOR:
        return {
            .type           = ClientType::WEB_CREATOR,
            .clientName     = "WEB_CREATOR",
            .clientVersion  = "1.20260804.01.00",
            .clientId       = "62",
            .platform       = "DESKTOP",
            .userAgent      = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                              "AppleWebKit/537.36 (KHTML, like Gecko) "
                              "Chrome/137.0.0.0 Safari/537.36",
            .apiKey         = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
            .innertubeHost  = "www.youtube.com",
            .requiresPoToken   = false,
            .supportsAgeBypass = false,
            .needsEmbedUrl     = false,
        };

    default:
        throw std::invalid_argument("Unknown ClientType");
    }
}

// ── Build InnerTube context JSON ──────────────────────────────────────────────
std::string buildInnertubeContext(const ClientProfile& p,
                                  const std::string&   visitorData,
                                  const std::string&   poToken,
                                  const std::string&   hl,
                                  const std::string&   gl) {
    nlohmann::json ctx;
    auto& client = ctx["context"]["client"];

    client["clientName"]    = p.clientName;
    client["clientVersion"] = p.clientVersion;
    client["hl"]            = hl;
    client["gl"]            = gl;

    if (!p.osName.empty())     client["osName"]     = p.osName;
    if (!p.osVersion.empty())  client["osVersion"]  = p.osVersion;
    if (!p.deviceMake.empty()) client["deviceMake"] = p.deviceMake;
    if (!p.deviceModel.empty())client["deviceModel"]= p.deviceModel;
    if (!p.platform.empty())   client["platform"]   = p.platform;
    if (p.androidSdkVersion)   client["androidSdkVersion"] = p.androidSdkVersion;
    if (!visitorData.empty())  client["visitorData"]= visitorData;

    // TV_EMBEDDED specific: announce this is an embedded player
    if (p.needsEmbedUrl && !p.embedUrl.empty()) {
        client["clientScreen"] = "EMBED";
        ctx["context"]["thirdParty"]["embedUrl"] = p.embedUrl;
    }

    // po_token: injected into serviceIntegrityDimensions
    if (!poToken.empty()) {
        ctx["serviceIntegrityDimensions"]["poToken"] = poToken;
    }

    return ctx.dump();
}

} // namespace lazyluno
