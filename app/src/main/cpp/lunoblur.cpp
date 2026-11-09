// blurs an Android bitmap in place. three box blur passes look close to a gaussian
// and cost O(pixels), so even on the weak phone this is a few milliseconds for a thumbnail.
#include <jni.h>
#include <android/bitmap.h>
#include <vector>
#include <cstdint>
#include <algorithm>

namespace {

void boxH(const uint8_t* in, uint8_t* out, int w, int h, int r) {
    const int win = 2 * r + 1;
    for (int y = 0; y < h; y++) {
        const uint8_t* row = in + (size_t) y * w * 4;
        uint8_t* o = out + (size_t) y * w * 4;
        for (int c = 0; c < 4; c++) {
            int sum = 0;
            for (int i = -r; i <= r; i++) sum += row[std::clamp(i, 0, w - 1) * 4 + c];
            for (int x = 0; x < w; x++) {
                o[x * 4 + c] = (uint8_t) (sum / win);
                sum += row[std::min(x + r + 1, w - 1) * 4 + c] - row[std::max(x - r, 0) * 4 + c];
            }
        }
    }
}

void boxV(const uint8_t* in, uint8_t* out, int w, int h, int r) {
    const int win = 2 * r + 1;
    const size_t stride = (size_t) w * 4;
    for (int x = 0; x < w; x++) {
        for (int c = 0; c < 4; c++) {
            const uint8_t* col = in + x * 4 + c;
            uint8_t* o = out + x * 4 + c;
            int sum = 0;
            for (int i = -r; i <= r; i++) sum += col[(size_t) std::clamp(i, 0, h - 1) * stride];
            for (int y = 0; y < h; y++) {
                o[(size_t) y * stride] = (uint8_t) (sum / win);
                sum += col[(size_t) std::min(y + r + 1, h - 1) * stride] - col[(size_t) std::max(y - r, 0) * stride];
            }
        }
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sparkynox_sparkytube_lunokit_blur_LunoBlur_nativeBlur(JNIEnv* env, jobject, jobject bitmap, jint radius) {
    if (bitmap == nullptr || radius < 1) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 2 || info.height < 2) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) return JNI_FALSE;

    const int w = (int) info.width, h = (int) info.height;
    const int r = std::min((int) radius, std::min(w, h) / 2 - 1 > 0 ? std::min(w, h) / 2 - 1 : 1);
    std::vector<uint8_t> a((size_t) w * h * 4), b((size_t) w * h * 4);

    // the bitmap rows can have padding, copy into a tight buffer first
    for (int y = 0; y < h; y++) {
        const uint8_t* src = (const uint8_t*) pixels + (size_t) y * info.stride;
        std::copy(src, src + (size_t) w * 4, a.begin() + (size_t) y * w * 4);
    }
    for (int pass = 0; pass < 3; pass++) {
        boxH(a.data(), b.data(), w, h, r);
        boxV(b.data(), a.data(), w, h, r);
    }
    for (int y = 0; y < h; y++) {
        uint8_t* dst = (uint8_t*) pixels + (size_t) y * info.stride;
        std::copy(a.begin() + (size_t) y * w * 4, a.begin() + (size_t) (y + 1) * w * 4, dst);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
