// LazyLuno - JNI bridge for SparkyTube
// One call: nativeResolve(videoId, cookie, hl, gl) -> json string. HTTP goes back out to
// LazyLunoNet.request() in Kotlin (OkHttp), so there is no TLS stack in here.

#include <jni.h>

#include <nlohmann/json.hpp>

#include "lazyluno/lazyluno.h"

#include <android/log.h>

#include <memory>
#include <mutex>

using nlohmann::json;
using namespace lazyluno;

namespace {

thread_local JNIEnv* tEnv = nullptr;
jclass gNet = nullptr;
jmethodID gRequest = nullptr;
std::once_flag gInit;

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

http::Response callKotlin(const http::Request& r) {
    JNIEnv* env = tEnv;
    if (!env || !gNet || !gRequest) return {0, {}, "jni not ready"};

    std::string hdrs;
    for (auto& h : r.headers) hdrs += h.first + ": " + h.second + "\n";

    jstring jm = env->NewStringUTF(r.method.c_str());
    jstring ju = env->NewStringUTF(r.url.c_str());
    jstring jh = env->NewStringUTF(hdrs.c_str());
    jstring jb = env->NewStringUTF(r.body.c_str());
    jstring res = (jstring) env->CallStaticObjectMethod(gNet, gRequest, jm, ju, jh, jb, r.connectTimeoutMs, r.timeoutMs);
    env->DeleteLocalRef(jm); env->DeleteLocalRef(ju); env->DeleteLocalRef(jh); env->DeleteLocalRef(jb);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {0, {}, "kotlin http threw"};
    }
    if (!res) return {0, {}, "no response"};
    std::string raw = jstr(env, res);
    env->DeleteLocalRef(res);

    // "<status>\n<body>"
    auto nl = raw.find('\n');
    http::Response out;
    out.status = std::atoi(raw.substr(0, nl).c_str());
    out.body = nl == std::string::npos ? std::string() : raw.substr(nl + 1);
    if (out.status == 0) out.error = out.body.substr(0, 120);
    return out;
}

json streamJson(const Stream& s) {
    return {
        {"itag", s.itag}, {"url", s.url}, {"mime", s.mimeType}, {"codec", s.codec},
        {"w", s.width}, {"h", s.height}, {"fps", s.fps}, {"q", s.qualityLabel},
        {"bitrate", s.bitrate}, {"len", s.contentLength},
        {"kind", s.isVideoOnly() ? "video" : s.isAudioOnly() ? "audio" : "muxed"},
    };
}

} // namespace

namespace lazyluno { namespace innertube { bool ageGateSeen(); } }

extern "C" JNIEXPORT jstring JNICALL
Java_dev_sparkynox_sparkytube_extractor_LazyLuno_nativeResolve(JNIEnv* env, jobject, jstring jvid, jstring jcookie,
                                                               jstring jhl, jstring jgl, jstring jclients,
                                                               jstring jvisitor, jstring jpot) {
    std::call_once(gInit, [&] {
        jclass c = env->FindClass("dev/sparkynox/sparkytube/extractor/LazyLunoNet");
        if (c) {
            gNet = (jclass) env->NewGlobalRef(c);
            gRequest = env->GetStaticMethodID(gNet, "request",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)Ljava/lang/String;");
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        http::setBackend(callKotlin);
    });
    tEnv = env;

    json out;
    try {
        ExtractorConfig cfg;
        cfg.hl = jstr(env, jhl).empty() ? "en" : jstr(env, jhl);
        cfg.gl = jstr(env, jgl).empty() ? "US" : jstr(env, jgl);
        // "IOS,TV_EMBEDDED" = only those, in that order. empty = the default cascade
        std::string list = jstr(env, jclients);
        if (!list.empty()) {
            cfg.spoof.clientOrder.clear();
            cfg.spoof.appendAuthFallback = false;
            size_t i = 0;
            while (i <= list.size()) {
                size_t j = list.find(',', i);
                if (j == std::string::npos) j = list.size();
                std::string n = list.substr(i, j - i);
                if (n == "ANDROID_APP" || n == "ANDROID") cfg.spoof.clientOrder.push_back(ClientType::ANDROID_APP);
                else if (n == "IOS") cfg.spoof.clientOrder.push_back(ClientType::IOS);
                else if (n == "TV_EMBEDDED") cfg.spoof.clientOrder.push_back(ClientType::TV_EMBEDDED);
                else if (n == "TVHTML5") cfg.spoof.clientOrder.push_back(ClientType::TVHTML5);
                else if (n == "TVHTML5_SIMPLY") cfg.spoof.clientOrder.push_back(ClientType::TVHTML5_SIMPLY);
                else if (n == "MWEB") cfg.spoof.clientOrder.push_back(ClientType::MWEB);
                else if (n == "ANDROID_VR" || n == "VR") cfg.spoof.clientOrder.push_back(ClientType::ANDROID_VR);
                else if (n == "WEB_CREATOR") cfg.spoof.clientOrder.push_back(ClientType::WEB_CREATOR);
                else if (n == "WEB") cfg.spoof.clientOrder.push_back(ClientType::WEB);
                else if (n == "AGE") cfg.spoof.authFirst = true;  // age-restricted: signed in clients first
                i = j + 1;
            }
            // only "AGE" given: keep the normal order behind the signed in pass
            if (cfg.spoof.clientOrder.empty()) {
                cfg.spoof.clientOrder = SpoofConfig::defaultConfig().clientOrder;
                cfg.spoof.appendAuthFallback = true;
            }
        }
        cfg.visitorData = jstr(env, jvisitor);
        cfg.spoof.poToken = jstr(env, jpot);
        Extractor ex(cfg);
        std::string cookie = jstr(env, jcookie);
        if (!cookie.empty()) ex.setAuth(AuthCookies::fromCookieString(cookie));

        VideoInfo info = ex.fetchVideo(jstr(env, jvid));

        std::string client;
        for (auto& t : info.tags) if (t.rfind("client:", 0) == 0) client = t.substr(7);

        out["ok"] = true;
        out["client"] = client;
        out["title"] = info.title;
        out["author"] = info.channelName;
        out["durationMs"] = info.durationMs;
        out["live"] = info.isLive;
        out["age"] = lazyluno::innertube::ageGateSeen();
        out["description"] = info.description;
        if (info.hlsManifestUrl) out["hls"] = *info.hlsManifestUrl;
        out["streams"] = json::array();
        for (auto& s : info.adaptiveStreams) out["streams"].push_back(streamJson(s));
        for (auto& s : info.muxedStreams) out["streams"].push_back(streamJson(s));
    } catch (const Error& e) {
        out = {{"ok", false}, {"error", e.what()}, {"code", (int) e.code}, {"age", lazyluno::innertube::ageGateSeen()}};
    } catch (const std::exception& e) {
        out = {{"ok", false}, {"error", std::string("lazyluno: ") + e.what()}, {"code", -1}};
    } catch (...) {
        out = {{"ok", false}, {"error", "lazyluno: unknown error"}, {"code", -1}};
    }
    tEnv = nullptr;
    return env->NewStringUTF(out.dump().c_str());
}
