// Adblock Plus / EasyList style network filter engine.
// supports: ||host anchors, | anchors, * and ^, @@ exceptions, $third-party/$~third-party,
// resource types, $domain=, $important. rules are indexed by host or by a token of the pattern
// so a request only looks at a handful of rules, not all of them
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>
#include <algorithm>
#include <new>

namespace {

enum : uint16_t {
    T_SCRIPT = 1, T_IMAGE = 2, T_STYLE = 4, T_OBJECT = 8, T_XHR = 16, T_SUBDOC = 32,
    T_PING = 64, T_MEDIA = 128, T_FONT = 256, T_OTHER = 512, T_POPUP = 1024, T_DOC = 2048, T_WS = 4096
};
enum : uint8_t { F_HOST = 1, F_START = 2, F_END = 4, F_IMP = 8, F_3P = 16, F_1P = 32 };

inline bool isAlnum(char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'); }
// what ^ matches: anything that isn't a letter, digit, _ - . %
inline bool isSep(char c) { return !(isAlnum(c) || c == '_' || c == '-' || c == '.' || c == '%'); }

uint32_t fnv(const char* s, size_t n) {
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < n; i++) { h ^= (uint8_t) s[i]; h *= 16777619u; }
    return h;
}

struct Rule {
    uint32_t pOff, pLen;     // pattern (for ||host rules: the part after the host)
    uint32_t hOff, hLen;     // host of a ||host rule
    uint32_t dOff;           // domain= entries in domPool
    uint16_t dCnt;
    uint16_t types;
    uint8_t flags;
};

struct Ctx {
    const char* url;
    int urlLen;
    int hostStart, hostEnd;
    const char* page;        // page host, may be empty
    int pageLen;
    uint16_t type;
    bool third;
};

// glob match: * any run, ^ one separator (or the end), everything else literal
bool glob(const char* p, int pl, const char* u, int ul, int ui, bool endAnchor) {
    int pi = 0, starP = -1, starU = -1;
    for (;;) {
        if (pi == pl) {
            if (!endAnchor || ui == ul) return true;
        } else {
            char c = p[pi];
            if (c == '*') { starP = pi; starU = ui; pi++; continue; }
            if (c == '^') {
                if (ui == ul) { pi++; continue; }
                if (isSep(u[ui])) { pi++; ui++; continue; }
            } else if (ui < ul && u[ui] == c) { pi++; ui++; continue; }
        }
        // mismatch: let the last * eat one more character
        if (starP >= 0 && starU < ul) { pi = starP + 1; ui = ++starU; continue; }
        return false;
    }
}

// site = last two labels (three for co.uk style), good enough for third-party checks
std::string siteOf(const char* h, int n) {
    int dots[3] = {-1, -1, -1};
    int found = 0;
    for (int i = n - 1; i >= 0 && found < 3; i--) if (h[i] == '.') dots[found++] = i;
    if (found < 2) return std::string(h, n);
    int tldLen = n - dots[0] - 1;
    int secondLen = dots[0] - dots[1] - 1;
    bool twoLevel = tldLen == 2 && secondLen <= 3 && found >= 3 &&
                    (secondLen == 2 || (secondLen == 3 && (!memcmp(h + dots[1] + 1, "com", 3) || !memcmp(h + dots[1] + 1, "org", 3) ||
                                                           !memcmp(h + dots[1] + 1, "net", 3) || !memcmp(h + dots[1] + 1, "edu", 3) ||
                                                           !memcmp(h + dots[1] + 1, "gov", 3))));
    int start = twoLevel ? dots[2] + 1 : dots[1] + 1;
    return std::string(h + start, n - start);
}

struct Index {
    std::vector<Rule> rules;
    std::unordered_map<uint32_t, std::vector<uint32_t>> byHost, byToken;
    std::vector<uint32_t> generic;
};

class Engine {
public:
    std::string pool;
    std::vector<uint32_t> dom;   // hash, top bit set = excluded
    Index block, allow;
    int count = 0;

    bool loadFile(const char* path) {
        FILE* f = fopen(path, "rb");
        if (!f) return false;
        std::string line;
        char buf[1 << 16];
        size_t n;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
            for (size_t i = 0; i < n; i++) {
                if (buf[i] == '\n') { addLine(line); line.clear(); }
                else if (buf[i] != '\r') line.push_back(buf[i]);
            }
        }
        if (!line.empty()) addLine(line);
        fclose(f);
        return true;
    }

    void addLine(std::string& l) {
        size_t a = l.find_first_not_of(" \t");
        if (a == std::string::npos) return;
        size_t b = l.find_last_not_of(" \t");
        std::string s = l.substr(a, b - a + 1);
        if (s.empty() || s[0] == '!' || s[0] == '[') return;
        bool exc = false;
        if (s.compare(0, 2, "@@") == 0) { exc = true; s.erase(0, 2); }

        uint16_t types = 0;
        uint8_t flags = 0;
        std::vector<uint32_t> doms;
        size_t d = s.rfind('$');
        if (d != std::string::npos && d + 1 < s.size() && isOptions(s, d + 1)) {
            std::string opts = s.substr(d + 1);
            s.erase(d);
            size_t pos = 0;
            while (pos <= opts.size()) {
                size_t c = opts.find(',', pos);
                if (c == std::string::npos) c = opts.size();
                std::string o = opts.substr(pos, c - pos);
                pos = c + 1;
                if (o.empty()) continue;
                if (o == "third-party" || o == "3p") flags |= F_3P;
                else if (o == "~third-party" || o == "1p" || o == "first-party") flags |= F_1P;
                else if (o == "important") flags |= F_IMP;
                else if (o == "match-case") {}
                else if (o == "script") types |= T_SCRIPT;
                else if (o == "image") types |= T_IMAGE;
                else if (o == "stylesheet" || o == "css") types |= T_STYLE;
                else if (o == "object") types |= T_OBJECT;
                else if (o == "xmlhttprequest" || o == "xhr") types |= T_XHR;
                else if (o == "subdocument" || o == "frame") types |= T_SUBDOC;
                else if (o == "ping") types |= T_PING;
                else if (o == "media") types |= T_MEDIA;
                else if (o == "font") types |= T_FONT;
                else if (o == "other") types |= T_OTHER;
                else if (o == "popup") types |= T_POPUP;
                else if (o == "websocket") types |= T_WS;
                else if (o.compare(0, 7, "domain=") == 0) {
                    std::string list = o.substr(7);
                    size_t p2 = 0;
                    while (p2 <= list.size()) {
                        size_t q = list.find('|', p2);
                        if (q == std::string::npos) q = list.size();
                        std::string dm = list.substr(p2, q - p2);
                        p2 = q + 1;
                        if (dm.empty()) continue;
                        bool ex = dm[0] == '~';
                        if (ex) dm.erase(0, 1);
                        uint32_t h = fnv(dm.data(), dm.size());
                        doms.push_back(ex ? (h | 0x80000000u) : (h & 0x7fffffffu));
                    }
                } else return;   // something we don't support, drop the rule
            }
        }
        if (s.empty()) return;
        if (s.size() > 2 && s[0] == '/' && s.back() == '/') return;   // regex rules are not supported

        uint8_t pf = flags;
        if (s.compare(0, 2, "||") == 0) { pf |= F_HOST; s.erase(0, 2); }
        else if (s[0] == '|') { pf |= F_START; s.erase(0, 1); }
        if (!s.empty() && s.back() == '|') { pf |= F_END; s.pop_back(); }
        for (auto& ch : s) if (ch >= 'A' && ch <= 'Z') ch = (char) (ch + 32);

        Rule r;
        memset(&r, 0, sizeof(r));
        r.flags = pf;
        r.types = types;
        r.dOff = (uint32_t) dom.size();
        r.dCnt = (uint16_t) std::min<size_t>(doms.size(), 60000);
        for (uint32_t h : doms) dom.push_back(h);

        std::string host, rest = s;
        if (pf & F_HOST) {
            size_t e = s.find_first_of("/^*:?|");
            host = s.substr(0, e == std::string::npos ? s.size() : e);
            rest = e == std::string::npos ? "" : s.substr(e);
            if (host.empty() || host.find('.') == std::string::npos) return;
        }
        r.pOff = (uint32_t) pool.size();
        r.pLen = (uint32_t) rest.size();
        pool += rest;
        if (pf & F_HOST) {
            r.hOff = (uint32_t) pool.size();
            r.hLen = (uint32_t) host.size();
            pool += host;
        }

        Index& ix = exc ? allow : block;
        uint32_t id = (uint32_t) ix.rules.size();
        ix.rules.push_back(r);
        count++;
        if (pf & F_HOST) {
            ix.byHost[fnv(host.data(), host.size())].push_back(id);
        } else {
            uint32_t tok = bestToken(rest, pf);
            if (tok) ix.byToken[tok].push_back(id);
            else ix.generic.push_back(id);
        }
    }

    // 0 = no safe token, rule goes to the generic list
    static uint32_t bestToken(const std::string& p, uint8_t pf) {
        size_t bestLen = 0, bestPos = 0;
        size_t i = 0, n = p.size();
        while (i < n) {
            if (!isAlnum(p[i])) { i++; continue; }
            size_t j = i;
            while (j < n && isAlnum(p[j])) j++;
            bool leftOk = i > 0 ? p[i - 1] != '*' : (pf & F_START) != 0;
            bool rightOk = j < n ? p[j] != '*' : (pf & F_END) != 0;
            if (leftOk && rightOk && j - i >= 3 && j - i > bestLen) { bestLen = j - i; bestPos = i; }
            i = j;
        }
        return bestLen ? fnv(p.data() + bestPos, bestLen) : 0;
    }

    static bool isOptions(const std::string& s, size_t from) {
        for (size_t i = from; i < s.size(); i++) {
            char c = s[i];
            if (!(isAlnum(c) || c == '-' || c == '_' || c == ',' || c == '=' || c == '|' || c == '~' || c == '.')) return false;
        }
        return true;
    }

    bool matchRule(const Index& ix, const Rule& r, const Ctx& c) const {
        if ((r.flags & F_3P) && !c.third) return false;
        if ((r.flags & F_1P) && c.third) return false;
        if (r.types) {
            if (!(r.types & c.type)) return false;
        } else if (c.type & (T_POPUP | T_DOC)) {
            return false;
        }
        if (r.dCnt) {
            bool hasInc = false, incHit = false;
            for (uint32_t k = 0; k < r.dCnt; k++) {
                uint32_t e = dom[r.dOff + k];
                bool ex = (e & 0x80000000u) != 0;
                if (!ex) hasInc = true;
                // is the page host this domain or a subdomain of it
                bool hit = false;
                for (int i = 0; i <= c.pageLen && !hit; i++) {
                    if (i == 0 || c.page[i - 1] == '.') {
                        uint32_t h = fnv(c.page + i, c.pageLen - i);
                        if ((h & 0x7fffffffu) == (e & 0x7fffffffu)) hit = true;
                    }
                }
                if (hit) { if (ex) return false; incHit = true; }
            }
            if (hasInc && !incHit) return false;
        }
        const char* p = pool.data() + r.pOff;
        bool endA = (r.flags & F_END) != 0;
        if (r.flags & F_HOST) return glob(p, (int) r.pLen, c.url, c.urlLen, c.hostEnd, endA);
        if (r.flags & F_START) return glob(p, (int) r.pLen, c.url, c.urlLen, 0, endA);
        if (r.pLen == 0) return true;
        char first = p[0];
        bool lit = first != '*' && first != '^';
        for (int s = 0; s < c.urlLen; s++) {
            if (lit && c.url[s] != first) continue;
            if (glob(p, (int) r.pLen, c.url, c.urlLen, s, endA)) return true;
        }
        (void) ix;
        return false;
    }

    // true if some rule in the index matches. imp gets set when the rule is $important
    bool find(const Index& ix, const Ctx& c, bool* imp) const {
        // rules for a host: try the request host and every parent of it
        for (int i = c.hostStart; i < c.hostEnd; i++) {
            if (i != c.hostStart && c.url[i - 1] != '.') continue;
            auto it = ix.byHost.find(fnv(c.url + i, c.hostEnd - i));
            if (it == ix.byHost.end()) continue;
            for (uint32_t id : it->second) {
                const Rule& r = ix.rules[id];
                if ((int) r.hLen != c.hostEnd - i || memcmp(pool.data() + r.hOff, c.url + i, r.hLen) != 0) continue;
                if (matchRule(ix, r, c)) { if (imp) *imp = (r.flags & F_IMP) != 0; return true; }
            }
        }
        // rules by token: every alphanumeric run in the url
        int tokens = 0;
        for (int i = 0; i < c.urlLen && tokens < 160;) {
            if (!isAlnum(c.url[i])) { i++; continue; }
            int j = i;
            while (j < c.urlLen && isAlnum(c.url[j])) j++;
            if (j - i >= 3) {
                tokens++;
                auto it = ix.byToken.find(fnv(c.url + i, j - i));
                if (it != ix.byToken.end()) {
                    for (uint32_t id : it->second) {
                        const Rule& r = ix.rules[id];
                        if (matchRule(ix, r, c)) { if (imp) *imp = (r.flags & F_IMP) != 0; return true; }
                    }
                }
            }
            i = j;
        }
        for (uint32_t id : ix.generic) {
            const Rule& r = ix.rules[id];
            if (matchRule(ix, r, c)) { if (imp) *imp = (r.flags & F_IMP) != 0; return true; }
        }
        return false;
    }

    // 0 = nothing, 1 = block, 2 = allowed by an exception rule
    int match(const std::string& url, const std::string& page, uint16_t type) const {
        size_t sch = url.find("://");
        if (sch == std::string::npos || sch > 6) return 0;
        Ctx c;
        c.url = url.data();
        c.urlLen = (int) url.size();
        c.hostStart = (int) sch + 3;
        int e = c.hostStart;
        while (e < c.urlLen && url[e] != '/' && url[e] != ':' && url[e] != '?' && url[e] != '#') e++;
        c.hostEnd = e;
        if (c.hostEnd <= c.hostStart) return 0;
        c.page = page.data();
        c.pageLen = (int) page.size();
        c.type = type ? type : (uint16_t) T_OTHER;
        if (page.empty()) c.third = true;
        else c.third = siteOf(url.data() + c.hostStart, c.hostEnd - c.hostStart) != siteOf(page.data(), (int) page.size());

        bool imp = false;
        if (!find(block, c, &imp)) return 0;
        if (imp) return 1;
        if (find(allow, c, nullptr)) return 2;
        return 1;
    }
};

Engine* gEngine = nullptr;

std::string lower(const char* s) {
    std::string r(s);
    for (auto& ch : r) if (ch >= 'A' && ch <= 'Z') ch = (char) (ch + 32);
    return r;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_adblock_NativeFilter_nativeLoad(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return -1;
    Engine* e = new (std::nothrow) Engine();
    if (!e) { env->ReleaseStringUTFChars(path, p); return -1; }
    bool ok = e->loadFile(p);
    env->ReleaseStringUTFChars(path, p);
    if (!ok) { delete e; return -1; }
    Engine* old = gEngine;
    gEngine = e;          // the old one is left alone on purpose, a request may still be using it
    (void) old;
    return e->count;
}

JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_adblock_NativeFilter_nativeMatch(JNIEnv* env, jobject, jstring url, jstring page, jint type) {
    Engine* e = gEngine;
    if (!e || !url) return 0;
    const char* u = env->GetStringUTFChars(url, nullptr);
    if (!u) return 0;
    std::string us = lower(u);
    env->ReleaseStringUTFChars(url, u);
    std::string ps;
    if (page) {
        const char* pg = env->GetStringUTFChars(page, nullptr);
        if (pg) { ps = lower(pg); env->ReleaseStringUTFChars(page, pg); }
    }
    return e->match(us, ps, (uint16_t) type);
}

}  // extern "C"
