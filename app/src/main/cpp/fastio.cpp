// file helper for the downloader: preallocate once, then many threads pwrite into it
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdint>

extern "C" {

JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_net_NativeIo_nativeOpen(JNIEnv* env, jobject, jstring path, jlong size) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return -ENOMEM;
    int fd = open(p, O_RDWR | O_CREAT | O_CLOEXEC, 0644);
    int err = errno;
    env->ReleaseStringUTFChars(path, p);
    if (fd < 0) return -err;
    if (size > 0) {
        // reserve the space up front so the file isn't fragmented and a full disk fails now, not at 99%
        if (posix_fallocate(fd, 0, (off_t) size) != 0) {
            if (ftruncate(fd, (off_t) size) != 0) {
                int e = errno;
                close(fd);
                return -e;
            }
        }
    }
    return fd;
}

JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_net_NativeIo_nativeWrite(JNIEnv* env, jobject, jint fd, jbyteArray buf, jint len, jlong off) {
    jbyte* b = env->GetByteArrayElements(buf, nullptr);
    if (!b) return -ENOMEM;
    jint done = 0;
    while (done < len) {
        ssize_t n = pwrite(fd, b + done, (size_t) (len - done), (off_t) (off + done));
        if (n < 0) {
            if (errno == EINTR) continue;
            int e = errno;
            env->ReleaseByteArrayElements(buf, b, JNI_ABORT);
            return -e;
        }
        done += (jint) n;
    }
    env->ReleaseByteArrayElements(buf, b, JNI_ABORT);
    return done;
}

JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_net_NativeIo_nativeSync(JNIEnv*, jobject, jint fd) {
    return fsync(fd) == 0 ? 0 : -errno;
}

JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_net_NativeIo_nativeClose(JNIEnv*, jobject, jint fd) {
    if (fd >= 0) close(fd);
}

}  // extern "C"
