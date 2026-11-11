#include <jni.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <mutex>
#include <shared_mutex>
#include <cstdint>
#include <cstring>

// blocked hosts as a sorted array of 64 bit hashes, mmap'd from a file.
// layout: "STHB" | u32 version | u32 count | u32 0 | count * u64 (little endian, sorted)
// pages get loaded when a lookup touches them, nothing sits on the java heap

namespace {

struct Map {
    void* base = nullptr;
    size_t len = 0;
    const uint64_t* items = nullptr;
    uint32_t count = 0;
};

std::shared_mutex gLock;
Map gMap;

inline uint64_t fnv(const char* s, size_t n) {
    uint64_t h = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char) s[i];
        if (c >= 'A' && c <= 'Z') c += 32;
        h ^= c;
        h *= 0x100000001b3ULL;
    }
    return h;
}

bool has(const Map& m, uint64_t h) {
    uint32_t lo = 0, hi = m.count;
    while (lo < hi) {
        uint32_t mid = lo + (hi - lo) / 2;
        uint64_t v = m.items[mid];
        if (v == h) return true;
        if (v < h) lo = mid + 1; else hi = mid;
    }
    return false;
}

// returns false if the file isn't a valid index, m is untouched then
bool mapFile(const char* path, Map& m) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 16) { close(fd); return false; }
    size_t len = (size_t) st.st_size;
    void* p = mmap(nullptr, len, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (p == MAP_FAILED) return false;

    const uint8_t* b = (const uint8_t*) p;
    uint32_t ver, cnt;
    memcpy(&ver, b + 4, 4);
    memcpy(&cnt, b + 8, 4);
    bool ok = memcmp(b, "STHB", 4) == 0 && ver == 1 && len == 16 + (size_t) cnt * 8;
    if (ok) {
        const uint64_t* it = (const uint64_t*) (b + 16);
        for (uint32_t i = 1; i < cnt; i++) {
            if (it[i - 1] >= it[i]) { ok = false; break; }
        }
    }
    if (!ok) { munmap(p, len); return false; }
    m.base = p;
    m.len = len;
    m.items = (const uint64_t*) (b + 16);
    m.count = cnt;
    return true;
}

// a.b.example.com -> tries a.b.example.com, b.example.com, example.com
bool blockedHost(const Map& m, const char* h, size_t n) {
    if (!m.items || n == 0) return false;
    size_t start = 0;
    while (true) {
        if (has(m, fnv(h + start, n - start))) return true;
        const char* dot = (const char*) memchr(h + start, '.', n - start);
        if (!dot) return false;
        start = (size_t) (dot - h) + 1;
        if (start >= n) return false;
    }
}

}  // namespace

// -1 = bad file (old index stays active), otherwise the number of hosts
extern "C" JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_adblock_HostIndex_nativeOpen(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    Map fresh;
    bool ok = mapFile(path, fresh);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ok) return -1;
    std::unique_lock<std::shared_mutex> w(gLock);
    Map old = gMap;
    gMap = fresh;
    if (old.base) munmap(old.base, old.len);
    return (jint) fresh.count;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sparkynox_sparkytube_adblock_HostIndex_nativeBlocked(JNIEnv* env, jobject, jstring jhost) {
    jsize n = env->GetStringUTFLength(jhost);
    if (n <= 0 || n > 253) return JNI_FALSE;
    char buf[256];
    env->GetStringUTFRegion(jhost, 0, env->GetStringLength(jhost), buf);
    std::shared_lock<std::shared_mutex> r(gLock);
    return blockedHost(gMap, buf, (size_t) n) ? JNI_TRUE : JNI_FALSE;
}

// exact name only, no parent walk. used to make sure a downloaded list doesn't contain youtube.com etc
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sparkynox_sparkytube_adblock_HostIndex_nativeHasExact(JNIEnv* env, jobject, jstring jpath, jstring jhost) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* host = env->GetStringUTFChars(jhost, nullptr);
    Map m;
    bool found = false;
    if (mapFile(path, m)) {
        found = has(m, fnv(host, strlen(host)));
        munmap(m.base, m.len);
    }
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jhost, host);
    return found ? JNI_TRUE : JNI_FALSE;
}
