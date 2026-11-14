// LazyLuno — cipher.cpp
// Author: SparkyNox
//
// YouTube signature cipher decoder — C++ reimplementation.
// No JS engine needed. Same approach as yt-dlp + pytube.
//
// Flow:
//   1. Download player base.js  (~2.5 MB)  — cached by playerId
//   2. Find the main decipher function name via regex
//   3. Extract the helper object (3 operations: reverse, splice, swap)
//   4. Build an operation list from the main function body
//   5. Apply operations to the scrambled signature string
//   6. Append deciphered sig to the stream URL
//
// signatureCipher URL param format:
//   s=<scrambled>&sp=<sig_param_name>&url=<percent_encoded_url>

#include "lazyluno/error.h"

#include <algorithm>
#include <functional>
#include <regex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace lazyluno {
namespace cipher {

// ── Operation types ───────────────────────────────────────────────────────────
struct Op {
    enum class Type { Reverse, Splice, Swap } type;
    int arg = 0;
};

using OpList = std::vector<Op>;

// ── Parse helper JS object to map function-name → operation ───────────────────
// YouTube generates names like "DE", "AJ" etc.
// Pattern matching on the function body:
//   function(a){a.reverse()}          → Reverse
//   function(a,b){a.splice(0,b)}      → Splice
//   function(a,b){var c=a[0];a[0]=... → Swap
static std::unordered_map<std::string, Op::Type>
parseHelperFunctions(const std::string& playerJs) {
    // Find the helper object: an object literal containing these three functions
    // Typical form: var XX={AA:function(a){a.reverse()},BB:function(a,b){...},...};
    static const std::regex helperRe(
        R"(var\s+(\w{2,3})\s*=\s*\{(\w+:function\([\s\S]*?)\}\s*;)",
        std::regex::ECMAScript);

    std::unordered_map<std::string, Op::Type> map;

    std::sregex_iterator it(playerJs.begin(), playerJs.end(), helperRe);
    std::sregex_iterator end;
    if (it == end) return map;

    std::string body = (*it)[2].str();

    // Three sub-patterns for each operation body
    static const std::regex revRe (R"((\w+):function\(a\)\{a\.reverse\(\)\})");
    static const std::regex splRe (R"((\w+):function\(a,b\)\{a\.splice\(0,b\)\})");
    static const std::regex swpRe (R"((\w+):function\(a,b\)\{var c=a\[0\])");

    for (auto& [re, opType] : std::vector<std::pair<std::regex,Op::Type>>{
            {revRe, Op::Type::Reverse},
            {splRe, Op::Type::Splice},
            {swpRe, Op::Type::Swap}}) {
        std::smatch m;
        if (std::regex_search(body, m, re))
            map[m[1].str()] = opType;
    }

    return map;
}

// ── Extract the main decipher function name ───────────────────────────────────
// Looks for: .sig||XX(   where XX is the decipher function name
static std::string findDecipherFuncName(const std::string& playerJs) {
    static const std::regex re(R"(\.sig\|\|([a-zA-Z0-9$]{2,})\()");
    std::smatch m;
    if (!std::regex_search(playerJs, m, re))
        throw Error(ErrorCode::SignatureCipherFailed,
                    "LazyLuno: could not find decipher function in player JS");
    return m[1].str();
}

// ── Extract helper object name from main decipher function ────────────────────
// Main function body typically: var a=...;XX.AB(a,N);...
static std::string findHelperObjName(const std::string& playerJs,
                                      const std::string& funcName) {
    // Grab ~2000 chars of the function body
    auto pos = playerJs.find(funcName + "=function(");
    if (pos == std::string::npos)
        throw Error(ErrorCode::SignatureCipherFailed,
                    "LazyLuno: decipher function body not found");

    std::string slice = playerJs.substr(pos, 2000);
    // First var reference before a dot: "var a=...;XX.
    static const std::regex re(R"(;([a-zA-Z0-9$]{2,})\.)");
    std::smatch m;
    if (!std::regex_search(slice, m, re))
        throw Error(ErrorCode::SignatureCipherFailed,
                    "LazyLuno: could not identify helper object name");
    return m[1].str();
}

// ── Build operation list from main function body ───────────────────────────────
static OpList buildOpList(const std::string& playerJs,
                           const std::string& funcName,
                           const std::unordered_map<std::string, Op::Type>& helperMap) {
    auto pos = playerJs.find(funcName + "=function(");
    if (pos == std::string::npos)
        throw Error(ErrorCode::SignatureCipherFailed, "LazyLuno: decipher body not found");

    // Grab the function body (between first { and matching })
    auto open  = playerJs.find('{', pos);
    auto close = open;
    int  depth = 0;
    for (auto i = open; i < open + 4000 && i < playerJs.size(); ++i) {
        if (playerJs[i] == '{') ++depth;
        if (playerJs[i] == '}') { --depth; if (!depth) { close = i; break; } }
    }
    std::string body = playerJs.substr(open + 1, close - open - 1);

    // Each statement: OBJ.FN(a,N) or OBJ.FN(a)
    static const std::regex callRe(R"(\w+\.(\w+)\(a(?:,(\d+))?\))");
    OpList ops;
    for (auto it = std::sregex_iterator(body.begin(), body.end(), callRe);
         it != std::sregex_iterator(); ++it) {
        std::string fn  = (*it)[1].str();
        int         arg = (*it)[2].matched ? std::stoi((*it)[2].str()) : 0;

        auto typeIt = helperMap.find(fn);
        if (typeIt == helperMap.end()) continue;  // unknown helper, skip

        ops.push_back({typeIt->second, arg});
    }
    return ops;
}

// ── Apply op list to scrambled signature ─────────────────────────────────────
static std::string applyOps(std::string sig, const OpList& ops) {
    for (auto& op : ops) {
        switch (op.type) {
        case Op::Type::Reverse:
            std::reverse(sig.begin(), sig.end());
            break;
        case Op::Type::Splice:
            sig = sig.substr(op.arg);
            break;
        case Op::Type::Swap: {
            int idx = op.arg % static_cast<int>(sig.size());
            if (idx > 0) std::swap(sig[0], sig[idx]);
            break;
        }
        }
    }
    return sig;
}

// ── URL decode helper ─────────────────────────────────────────────────────────
static std::string urlDecode(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '%' && i + 2 < s.size()) {
            int ch = std::stoi(s.substr(i + 1, 2), nullptr, 16);
            out += static_cast<char>(ch);
            i += 2;
        } else if (s[i] == '+') {
            out += ' ';
        } else {
            out += s[i];
        }
    }
    return out;
}

// ── Public entry point ─────────────────────────────────────────────────────────
// signatureCipher: raw value of the "signatureCipher" or "cipher" URL param
// playerJs:        full text of the YouTube player base.js
std::string decipher(const std::string& signatureCipher,
                     const std::string& playerJs) {
    // Parse s=, sp=, url= from signatureCipher string
    std::string scrambled, sigParam, streamUrl;
    static const std::regex paramRe(R"(([^&=]+)=([^&]*))");
    for (auto it = std::sregex_iterator(signatureCipher.begin(),
                                        signatureCipher.end(), paramRe);
         it != std::sregex_iterator(); ++it) {
        std::string key = (*it)[1].str();
        std::string val = urlDecode((*it)[2].str());
        if      (key == "s")   scrambled = val;
        else if (key == "sp")  sigParam  = val;
        else if (key == "url") streamUrl = val;
    }

    if (streamUrl.empty())
        throw Error(ErrorCode::SignatureCipherFailed, "LazyLuno: no url in signatureCipher");
    if (scrambled.empty())
        return streamUrl;  // already plain URL, nothing to decipher

    // Parse cipher logic from player JS (expensive — caller should cache playerJs)
    auto funcName  = findDecipherFuncName(playerJs);
    auto helperMap = parseHelperFunctions(playerJs);
    auto ops       = buildOpList(playerJs, funcName, helperMap);

    std::string sig = applyOps(scrambled, ops);

    // Append deciphered sig to URL
    char sep = (streamUrl.find('?') != std::string::npos) ? '&' : '?';
    if (sigParam.empty()) sigParam = "sig";
    return streamUrl + sep + sigParam + "=" + sig;
}

} // namespace cipher
} // namespace lazyluno
