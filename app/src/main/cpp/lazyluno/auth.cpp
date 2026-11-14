// LazyLuno - auth.cpp
// Cookie string parsing and the SAPISIDHASH header (same scheme yt-dlp uses).
// SHA-1 is written out here so the library doesn't need OpenSSL. It is only a replay nonce, not security.

#include "lazyluno/auth.h"

#include <chrono>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>

namespace lazyluno {

// ---- sha1 ----
namespace {

inline uint32_t rol(uint32_t v, int s) { return (v << s) | (v >> (32 - s)); }

std::string sha1Hex(const std::string& in) {
    uint32_t h[5] = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
    std::string msg = in;
    uint64_t bits = (uint64_t) in.size() * 8;
    msg.push_back((char) 0x80);
    while (msg.size() % 64 != 56) msg.push_back(0);
    for (int i = 7; i >= 0; i--) msg.push_back((char) ((bits >> (i * 8)) & 0xFF));

    for (size_t off = 0; off < msg.size(); off += 64) {
        uint32_t w[80];
        for (int i = 0; i < 16; i++) {
            const unsigned char* p = (const unsigned char*) msg.data() + off + i * 4;
            w[i] = ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | p[3];
        }
        for (int i = 16; i < 80; i++) w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; i++) {
            uint32_t f, k;
            if (i < 20)      { f = (b & c) | (~b & d);           k = 0x5A827999; }
            else if (i < 40) { f = b ^ c ^ d;                    k = 0x6ED9EBA1; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d);  k = 0x8F1BBCDC; }
            else             { f = b ^ c ^ d;                    k = 0xCA62C1D6; }
            uint32_t t = rol(a, 5) + f + e + k + w[i];
            e = d; d = c; c = rol(b, 30); b = a; a = t;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    }
    static const char* hex = "0123456789abcdef";
    std::string out;
    for (int i = 0; i < 5; i++)
        for (int s = 28; s >= 0; s -= 4) out.push_back(hex[(h[i] >> s) & 0xF]);
    return out;
}

std::string makeToken(const std::string& scheme, const std::string& sid, const std::string& origin) {
    if (sid.empty()) return {};
    auto ts = std::chrono::duration_cast<std::chrono::seconds>(
                  std::chrono::system_clock::now().time_since_epoch()).count();
    return scheme + " " + std::to_string(ts) + "_" + sha1Hex(std::to_string(ts) + " " + sid + " " + origin);
}

void assignCookie(AuthCookies& out, const std::string& name, const std::string& value) {
    if      (name == "SAPISID")            out.SAPISID = value;
    else if (name == "__Secure-1PAPISID")  out.SAPISID_1P = value;
    else if (name == "__Secure-3PAPISID")  out.SAPISID_3P = value;
    else if (name == "SID")                out.SID = value;
    else if (name == "HSID")               out.HSID = value;
    else if (name == "SSID")               out.SSID = value;
    else if (name == "APISID")             out.APISID = value;
    else if (name == "LOGIN_INFO")         out.LOGIN_INFO = value;
    else if (name == "VISITOR_INFO1_LIVE") out.VISITOR_INFO1_LIVE = value;
    else if (name == "PREF")               out.PREF = value;
    else if (name == "SOCS")               out.SOCS = value;
    else                                   out.extra[name] = value;
}

} // namespace

std::string debugSha1(const std::string& s) { return sha1Hex(s); }

AuthCookies AuthCookies::fromCookieString(const std::string& cookie) {
    AuthCookies out;
    std::istringstream ss(cookie);
    std::string token;
    while (std::getline(ss, token, ';')) {
        auto start = token.find_first_not_of(" \t");
        if (start == std::string::npos) continue;
        token = token.substr(start);
        auto eq = token.find('=');
        if (eq == std::string::npos) continue;
        assignCookie(out, token.substr(0, eq), token.substr(eq + 1));
    }
    if (out.SOCS.empty()) out.SOCS = "CAI";
    return out;
}

std::string buildSapisidHash(const std::string& sapisid, const std::string& origin) {
    return makeToken("SAPISIDHASH", sapisid, origin);
}

std::string buildAuthorizationHeader(const AuthCookies& c, const std::string& origin) {
    std::string parts;
    auto append = [&](const std::string& t) {
        if (t.empty()) return;
        if (!parts.empty()) parts += ' ';
        parts += t;
    };
    const auto& primary = c.SAPISID.empty() ? c.SAPISID_3P : c.SAPISID;
    append(makeToken("SAPISIDHASH", primary, origin));
    append(makeToken("SAPISID1PHASH", c.SAPISID_1P, origin));
    append(makeToken("SAPISID3PHASH", c.SAPISID_3P, origin));
    return parts;
}

} // namespace lazyluno
