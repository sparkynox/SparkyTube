#include <jni.h>
#include <android/log.h>
#include "lunojs.h"

static std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_sparkynox_sparkytube_extractor_LunoJs_nativeCreate(JNIEnv* env, jobject, jstring ua) {
    LunoJs* js = new LunoJs(str(env, ua));
    if (!js->ok()) {
        __android_log_print(ANDROID_LOG_WARN, "LunoJs", "start failed: %s", js->takeLog().c_str());
        delete js;
        return 0;
    }
    return reinterpret_cast<jlong>(js);
}

// "OK:" + result or "ERR:" + message, so nothing has to throw across jni
JNIEXPORT jstring JNICALL Java_dev_sparkynox_sparkytube_extractor_LunoJs_nativeRun(JNIEnv* env, jobject, jlong h, jstring code, jlong timeoutMs) {
    LunoJs* js = reinterpret_cast<LunoJs*>(h);
    if (!js) return env->NewStringUTF("ERR:engine is closed");
    std::string out, err;
    bool ok = js->runAsync(str(env, code), (long)timeoutMs, out, err);
    std::string res = ok ? "OK:" + out : "ERR:" + err;
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL Java_dev_sparkynox_sparkytube_extractor_LunoJs_nativeTakeLog(JNIEnv* env, jobject, jlong h) {
    LunoJs* js = reinterpret_cast<LunoJs*>(h);
    return env->NewStringUTF(js ? js->takeLog().c_str() : "");
}

JNIEXPORT void JNICALL Java_dev_sparkynox_sparkytube_extractor_LunoJs_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<LunoJs*>(h);
}

}
