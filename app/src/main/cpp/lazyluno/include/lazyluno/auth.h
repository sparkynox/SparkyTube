#pragma once
// LazyLuno — auth.h
// Author: SparkyNox
//
// Cookie-based auth flow — mirrors yt-dlp's SAPISID/3PAPISID approach.
// No password login; no OAuth. Cookies exported from a logged-in browser
// (Netscape format or JSON) are the only supported path.

#include <optional>
#include <string>
#include <unordered_map>

namespace lazyluno {

struct AuthCookies {
    // Core session identifiers
    std::string SAPISID;
    std::string SAPISID_1P;   // __Secure-1PAPISID
    std::string SAPISID_3P;   // __Secure-3PAPISID
    std::string SID;
    std::string HSID;
    std::string SSID;
    std::string APISID;

    // Session cookies
    std::string LOGIN_INFO;
    std::string VISITOR_INFO1_LIVE;
    std::string PREF;
    std::string SOCS;         // consent — set to "CAI" to bypass consent gate

    // Remaining raw cookies (anything not explicitly extracted)
    std::unordered_map<std::string, std::string> extra;

    bool hasAuth() const {
        return !SAPISID.empty() || !SAPISID_3P.empty();
    }

    // Build from raw cookie string (HTTP Cookie: header value)
    static AuthCookies fromCookieString(const std::string& cookie);
};

// Computes: "SAPISIDHASH {ts}_{sha1(ts + " " + sapisid + " " + origin)}"
// Called internally by HttpClient when cookies are set.
std::string buildSapisidHash(const std::string& sapisid,
                             const std::string& origin = "https://www.youtube.com");

// Build the full Authorization header value
// (SAPISIDHASH + SAPISID1PHASH + SAPISID3PHASH where available)
std::string buildAuthorizationHeader(const AuthCookies& cookies,
                                     const std::string& origin = "https://www.youtube.com");

std::string debugSha1(const std::string& s); // for tests

} // namespace lazyluno
