// work on the tiny video frame that feeds the ambient glow: finds black bars (letterbox) and
// applies saturation / brightness / contrast in place. the frame is ~64x36 so this is microseconds.
#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>
#include <algorithm>

namespace {

inline int luma(int r, int g, int b) { return (r * 77 + g * 150 + b * 29) >> 8; }

int rowAverage(const uint8_t* row, int w) {
    int sum = 0;
    for (int x = 0; x < w; x++) sum += luma(row[x * 4], row[x * 4 + 1], row[x * 4 + 2]);
    return sum / w;
}

} // namespace

// returns (rows of black bar on top << 16) | rows of black bar at the bottom
extern "C" JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_lunokit_ambient_LunoAmbient_nativeProcess(
        JNIEnv* env, jobject, jobject bitmap, jint saturation, jint brightness, jint contrast) {
    if (bitmap == nullptr) return 0;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 2 || info.height < 4) return 0;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) return 0;

    const int w = (int) info.width, h = (int) info.height;
    uint8_t* base = (uint8_t*) pixels;
    auto rowPtr = [&](int y) { return base + (size_t) y * info.stride; };

    // bars: dark rows from the edge inwards, never more than a third of the frame
    const int maxBar = h / 3;
    int top = 0, bottom = 0;
    while (top < maxBar && rowAverage(rowPtr(top), w) < 14) top++;
    while (bottom < maxBar && rowAverage(rowPtr(h - 1 - bottom), w) < 14) bottom++;

    if (saturation != 100 || brightness != 100 || contrast != 100) {
        for (int y = 0; y < h; y++) {
            uint8_t* row = rowPtr(y);
            for (int x = 0; x < w; x++) {
                uint8_t* p = row + x * 4;
                int r = p[0], g = p[1], b = p[2];
                const int ly = luma(r, g, b);
                r = ly + (r - ly) * saturation / 100;
                g = ly + (g - ly) * saturation / 100;
                b = ly + (b - ly) * saturation / 100;
                r = ((r - 128) * contrast / 100 + 128) * brightness / 100;
                g = ((g - 128) * contrast / 100 + 128) * brightness / 100;
                b = ((b - 128) * contrast / 100 + 128) * brightness / 100;
                p[0] = (uint8_t) std::clamp(r, 0, 255);
                p[1] = (uint8_t) std::clamp(g, 0, 255);
                p[2] = (uint8_t) std::clamp(b, 0, 255);
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return (top << 16) | bottom;
}
