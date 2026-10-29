// checks the ed25519 signature on the remote config
#include <jni.h>
#include <cstdint>
#include <cstring>
#include "ed25519/ed25519.h"
#include "rc_pubkey.h"

namespace {
const unsigned char kPub[32] = RC_PUBKEY;

bool pubSet() {
    for (int i = 0; i < 32; i++) if (kPub[i] != 0) return true;
    return false;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_sparkynox_sparkytube_net_RemoteConfig_nativeConfigured(JNIEnv*, jobject) {
    return pubSet() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_sparkynox_sparkytube_net_RemoteConfig_nativeVerify(JNIEnv* env, jobject, jbyteArray msg, jbyteArray sig) {
    if (!pubSet() || msg == nullptr || sig == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(sig) != 64) return JNI_FALSE;
    jsize n = env->GetArrayLength(msg);
    if (n <= 0 || n > 512 * 1024) return JNI_FALSE;

    jbyte* m = env->GetByteArrayElements(msg, nullptr);
    jbyte* s = env->GetByteArrayElements(sig, nullptr);
    int ok = 0;
    if (m && s) ok = ed25519_verify((const unsigned char*) s, (const unsigned char*) m, (size_t) n, kPub);
    if (m) env->ReleaseByteArrayElements(msg, m, JNI_ABORT);
    if (s) env->ReleaseByteArrayElements(sig, s, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
