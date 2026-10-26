#include <jni.h>
#include <netdb.h>
#include <sys/socket.h>
#include <mutex>
#include <cstdint>
#include <cstring>

// throughput estimate (bytes/sec), smoothed. fed from the player's transfer listener
static std::mutex gLock;
static double gBps = 0.0;
static int64_t gSamples = 0;

static const double kAlpha = 0.25;

extern "C" JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_net_NativeNet_nativeRecord(JNIEnv*, jobject, jlong bytes, jlong nanos) {
    if (bytes <= 0 || nanos <= 0) return;
    double inst = (double) bytes * 1e9 / (double) nanos;
    std::lock_guard<std::mutex> g(gLock);
    if (gSamples == 0) gBps = inst;
    else gBps = kAlpha * inst + (1.0 - kAlpha) * gBps;
    gSamples++;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_sparkynox_sparkytube_net_NativeNet_nativeBps(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(gLock);
    return (jlong) gBps;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_net_NativeNet_nativeReset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(gLock);
    gBps = 0.0;
    gSamples = 0;
}

// resolves the host so the system resolver cache is hot before the player connects.
// blocking, call it from a background thread
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sparkynox_sparkytube_net_NativeNet_nativePrewarmDns(JNIEnv* env, jobject, jstring host) {
    if (host == nullptr) return JNI_FALSE;
    const char* h = env->GetStringUTFChars(host, nullptr);
    if (h == nullptr) return JNI_FALSE;

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    struct addrinfo* res = nullptr;
    int rc = getaddrinfo(h, "443", &hints, &res);
    if (res != nullptr) freeaddrinfo(res);
    env->ReleaseStringUTFChars(host, h);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}
