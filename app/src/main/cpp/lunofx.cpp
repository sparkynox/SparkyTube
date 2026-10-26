// LunoFX: realtime audio engine for the player. 10 band EQ, bass shelf, volume
// normalizer, 8D auto-pan, stereo width, reverb and a limiter, all on 16-bit PCM.
// params are plain atomics so the UI thread can change them while audio is running
#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>
#include <algorithm>

namespace {

const int kBands = 10;
const double kFreqs[kBands] = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
const double kPi = 3.14159265358979323846;

struct Params {
    std::atomic<bool> on{false};
    std::atomic<float> eq[kBands];
    std::atomic<float> bass{0}, width{1}, rvMix{0}, rvRoom{0.5f}, rvDamp{0.5f};
    std::atomic<bool> p8On{false};
    std::atomic<float> p8Period{8}, p8Depth{1};
    std::atomic<bool> normOn{false};
    std::atomic<float> normTarget{-16};
    std::atomic<uint32_t> version{1};
    Params() { for (auto& e : eq) e.store(0.f); }
};

Params gP;

struct Snap {
    bool on, p8On, normOn;
    float eq[kBands];
    float bass, width, rvMix, rvRoom, rvDamp, p8Period, p8Depth, normTarget;
};

void takeSnap(Snap& s) {
    s.on = gP.on.load(std::memory_order_relaxed);
    s.p8On = gP.p8On.load(std::memory_order_relaxed);
    s.normOn = gP.normOn.load(std::memory_order_relaxed);
    for (int i = 0; i < kBands; i++) s.eq[i] = gP.eq[i].load(std::memory_order_relaxed);
    s.bass = gP.bass.load(std::memory_order_relaxed);
    s.width = gP.width.load(std::memory_order_relaxed);
    s.rvMix = gP.rvMix.load(std::memory_order_relaxed);
    s.rvRoom = gP.rvRoom.load(std::memory_order_relaxed);
    s.rvDamp = gP.rvDamp.load(std::memory_order_relaxed);
    s.p8Period = gP.p8Period.load(std::memory_order_relaxed);
    s.p8Depth = gP.p8Depth.load(std::memory_order_relaxed);
    s.normTarget = gP.normTarget.load(std::memory_order_relaxed);
}

// ---- filters (RBJ cookbook) ----

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
};
struct BiState {
    double z1 = 0, z2 = 0;
};

inline double run(const Biquad& c, BiState& s, double x) {
    double y = c.b0 * x + s.z1;
    s.z1 = c.b1 * x - c.a1 * y + s.z2;
    s.z2 = c.b2 * x - c.a2 * y;
    return y;
}

Biquad norm(double b0, double b1, double b2, double a0, double a1, double a2) {
    Biquad q;
    q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0;
    q.a1 = a1 / a0; q.a2 = a2 / a0;
    return q;
}

Biquad peaking(double fs, double f0, double dB, double Q) {
    double A = std::pow(10.0, dB / 40.0);
    double w0 = 2 * kPi * f0 / fs, al = std::sin(w0) / (2 * Q), c = std::cos(w0);
    return norm(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
}

Biquad lowShelf(double fs, double f0, double dB) {
    double A = std::pow(10.0, dB / 40.0);
    double w0 = 2 * kPi * f0 / fs, c = std::cos(w0);
    double al = std::sin(w0) / 2 * std::sqrt((A + 1 / A) * (1 / 1.0 - 1) + 2);
    double sa = 2 * std::sqrt(A) * al;
    return norm(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c),
                A * ((A + 1) - (A - 1) * c - sa), (A + 1) + (A - 1) * c + sa,
                -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
}

Biquad highPass(double fs, double f0, double Q) {
    double w0 = 2 * kPi * f0 / fs, al = std::sin(w0) / (2 * Q), c = std::cos(w0);
    return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
}

// ---- reverb (freeverb layout) ----

struct Comb {
    std::vector<float> buf;
    int idx = 0;
    float store = 0;
    void init(int n) { buf.assign(std::max(n, 1), 0.f); idx = 0; store = 0; }
    inline float tick(float in, float fb, float damp) {
        float out = buf[idx];
        store = out * (1.f - damp) + store * damp;
        buf[idx] = in + store * fb;
        if (++idx >= (int) buf.size()) idx = 0;
        return out;
    }
};

struct Allpass {
    std::vector<float> buf;
    int idx = 0;
    void init(int n) { buf.assign(std::max(n, 1), 0.f); idx = 0; }
    inline float tick(float in) {
        float b = buf[idx];
        buf[idx] = in + b * 0.5f;
        if (++idx >= (int) buf.size()) idx = 0;
        return b - in;
    }
};

const int kCombT[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
const int kAllT[4] = {556, 441, 341, 225};
const int kSpread = 23;

class Engine {
public:
    void configure(int rate, int channels) {
        sr = rate > 8000 ? rate : 48000;
        nch = channels == 1 ? 1 : 2;
        double k = sr / 44100.0;
        for (int i = 0; i < 8; i++) {
            combL[i].init((int) (kCombT[i] * k));
            combR[i].init((int) ((kCombT[i] + kSpread) * k));
        }
        for (int i = 0; i < 4; i++) {
            allL[i].init((int) (kAllT[i] * k));
            allR[i].init((int) ((kAllT[i] + kSpread) * k));
        }
        delay.assign(512, 0.f);
        reset();
        seen = 0;
    }

    void reset() {
        for (auto& ch : eqS) for (auto& s : ch) s = BiState();
        for (auto& s : bassS) s = BiState();
        nhpS = BiState();
        ms = 0; ng = 1; ngTarget = 1; normCount = 0;
        phase = 0; wp = 0; lpL = lpR = 0; lim = 1;
        std::fill(delay.begin(), delay.end(), 0.f);
        for (int i = 0; i < 8; i++) { combL[i].init(combL[i].buf.size()); combR[i].init(combR[i].buf.size()); }
        for (int i = 0; i < 4; i++) { allL[i].init(allL[i].buf.size()); allR[i].init(allR[i].buf.size()); }
    }

    void process(const int16_t* in, int16_t* out, int frames) {
        Snap s;
        takeSnap(s);
        uint32_t v = gP.version.load(std::memory_order_relaxed);
        if (v != seen) { seen = v; rebuild(s); }

        if (!s.on || !anyEffect) {
            std::memcpy(out, in, (size_t) frames * nch * sizeof(int16_t));
            return;
        }

        const float inv = 1.f / 32768.f;
        const bool st = nch == 2;
        float noise = 1e-18f;

        for (int i = 0; i < frames; i++) {
            noise = -noise;
            float l = in[i * nch] * inv;
            float r = st ? in[i * nch + 1] * inv : l;

            // volume normalizer: slow level detector, gain rides toward the target
            if (normOn) {
                double d = run(nhpC, nhpS, 0.5 * (l + r));
                ms += msK * (d * d - ms);
                if (++normCount >= 512) {
                    normCount = 0;
                    double db = 10.0 * std::log10(ms + 1e-12);
                    if (db > -55.0) {
                        double g = normTargetDb - db;
                        g = std::min(12.0, std::max(-12.0, g));
                        ngTarget = std::pow(10.0, g / 20.0);
                    }
                }
                ng += (ngTarget - ng) * (ngTarget < ng ? atk : rel);
                l = (float) (l * ng);
                r = (float) (r * ng);
            }

            // tone
            double xl = l + noise, xr = r - noise;
            for (int b = 0; b < kBands; b++) {
                if (!eqOn[b]) continue;
                xl = run(eqC[b], eqS[0][b], xl);
                if (st) xr = run(eqC[b], eqS[1][b], xr);
            }
            if (bassOn) {
                xl = run(bassC, bassS[0], xl);
                if (st) xr = run(bassC, bassS[1], xr);
            }
            l = (float) xl;
            r = st ? (float) xr : l;

            // 8D: slow pan with ear delay and a duller far ear
            if (st && p8On) {
                phase += p8Inc;
                if (phase > 2 * kPi) phase -= 2 * kPi;
                float p = (float) std::sin(phase);
                float m = 0.5f * (l + r);
                delay[wp] = m;
                float ang = (p + 1.f) * (float) kPi * 0.25f;
                float gl = std::cos(ang) * 1.41421356f, gr = std::sin(ang) * 1.41421356f;
                float dl = (p > 0 ? p : 0.f) * maxDelay, dr = (p < 0 ? -p : 0.f) * maxDelay;
                float mL = tap(dl), mR = tap(dr);
                wp = (wp + 1) & 511;
                float fcL = 18000.f - 15500.f * (p > 0 ? p : 0.f);
                float fcR = 18000.f - 15500.f * (p < 0 ? -p : 0.f);
                float aL = std::exp(-2.f * (float) kPi * fcL / sr), aR = std::exp(-2.f * (float) kPi * fcR / sr);
                lpL = (1.f - aL) * (mL * gl) + aL * lpL;
                lpR = (1.f - aR) * (mR * gr) + aR * lpR;
                l = l * (1.f - p8Depth) + lpL * p8Depth;
                r = r * (1.f - p8Depth) + lpR * p8Depth;
            }

            // stereo width
            if (st && widthOn) {
                float m = 0.5f * (l + r), sd = 0.5f * (l - r) * widthV;
                l = m + sd;
                r = m - sd;
            }

            // reverb
            if (rvOn) {
                float inp = (st ? 0.5f * (l + r) : l) * 0.015f + noise * 1e-3f * 1e-15f;
                float wl = 0, wr = 0;
                for (int c = 0; c < 8; c++) {
                    wl += combL[c].tick(inp, rvFb, rvDamp);
                    if (st) wr += combR[c].tick(inp, rvFb, rvDamp);
                }
                for (int a = 0; a < 4; a++) {
                    wl = allL[a].tick(wl);
                    if (st) wr = allR[a].tick(wr);
                }
                l = l * rvDry + wl * rvWet;
                r = st ? r * rvDry + wr * rvWet : l;
            }

            // limiter: instant attack, slow release, then a hard clip as the last guard
            float peak = std::max(std::fabs(l), std::fabs(r));
            float tg = peak > 0.97f ? 0.97f / peak : 1.f;
            if (tg < lim) lim = tg; else lim += (1.f - lim) * limRel;
            l *= lim; r *= lim;
            l = std::min(1.f, std::max(-1.f, l));
            r = std::min(1.f, std::max(-1.f, r));

            out[i * nch] = (int16_t) std::lrintf(l * 32767.f);
            if (st) out[i * nch + 1] = (int16_t) std::lrintf(r * 32767.f);
        }
    }

private:
    int sr = 48000, nch = 2;
    uint32_t seen = 0;
    bool anyEffect = false;

    Biquad eqC[kBands];
    bool eqOn[kBands] = {};
    BiState eqS[2][kBands];
    Biquad bassC;
    bool bassOn = false;
    BiState bassS[2];

    Biquad nhpC;
    BiState nhpS;
    bool normOn = false;
    double normTargetDb = -16, ms = 0, ng = 1, ngTarget = 1, msK = 0, atk = 0, rel = 0;
    int normCount = 0;

    bool p8On = false, widthOn = false, rvOn = false;
    double phase = 0, p8Inc = 0;
    float p8Depth = 1, maxDelay = 30, widthV = 1;
    std::vector<float> delay;
    int wp = 0;
    float lpL = 0, lpR = 0;

    Comb combL[8], combR[8];
    Allpass allL[4], allR[4];
    float rvFb = 0.84f, rvDamp = 0.2f, rvWet = 0, rvDry = 1;

    float lim = 1, limRel = 0.0002f;

    float tap(float d) {
        int i = (int) d;
        float f = d - i;
        float a = delay[(wp - i) & 511], b = delay[(wp - i - 1) & 511];
        return a + (b - a) * f;
    }

    void rebuild(const Snap& s) {
        bool any = false;
        for (int b = 0; b < kBands; b++) {
            eqOn[b] = std::fabs(s.eq[b]) > 0.05f && kFreqs[b] < 0.45 * sr;
            if (eqOn[b]) { eqC[b] = peaking(sr, kFreqs[b], s.eq[b], 1.41); any = true; }
        }
        bassOn = s.bass > 0.05f;
        if (bassOn) { bassC = lowShelf(sr, 100.0, s.bass); any = true; }

        normOn = s.normOn;
        if (normOn) {
            normTargetDb = s.normTarget;
            nhpC = highPass(sr, 100.0, 0.707);
            msK = 1.0 / (sr * 3.0);
            atk = 1.0 - std::exp(-1.0 / (sr * 0.6));
            rel = 1.0 - std::exp(-1.0 / (sr * 2.5));
            any = true;
        }

        p8On = s.p8On && nch == 2;
        if (p8On) {
            p8Inc = 2 * kPi / (sr * std::max(2.0f, s.p8Period));
            p8Depth = std::min(1.f, std::max(0.f, s.p8Depth));
            maxDelay = std::min(200.f, 0.00065f * sr);
            any = true;
        }

        widthV = std::min(3.f, std::max(0.f, s.width));
        widthOn = nch == 2 && std::fabs(widthV - 1.f) > 0.01f;
        any = any || widthOn;

        rvOn = s.rvMix > 0.005f;
        if (rvOn) {
            float room = std::min(1.f, std::max(0.f, s.rvRoom));
            rvFb = 0.7f + room * 0.28f;
            rvDamp = std::min(1.f, std::max(0.f, s.rvDamp)) * 0.4f;
            float mix = std::min(1.f, s.rvMix);
            rvWet = mix * 3.f;
            rvDry = 1.f - mix * 0.5f;
            any = true;
        }
        limRel = (float) (1.0 - std::exp(-1.0 / (sr * 0.1)));
        anyEffect = any;
    }
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_sparkynox_sparkytube_fx_NativeFx_nativeCreate(JNIEnv*, jobject) {
    return (jlong) new (std::nothrow) Engine();
}

JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_fx_NativeFx_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete (Engine*) h;
}

JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_fx_NativeFx_nativeConfigure(JNIEnv*, jobject, jlong h, jint rate, jint ch) {
    if (h) ((Engine*) h)->configure(rate, ch);
}

JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_fx_NativeFx_nativeReset(JNIEnv*, jobject, jlong h) {
    if (h) ((Engine*) h)->reset();
}

JNIEXPORT jint JNICALL
Java_dev_sparkynox_sparkytube_fx_NativeFx_nativeProcess(JNIEnv* env, jobject, jlong h, jobject in, jint inOff,
                                                        jobject out, jint outOff, jint bytes, jint channels) {
    if (!h) return 0;
    uint8_t* ip = (uint8_t*) env->GetDirectBufferAddress(in);
    uint8_t* op = (uint8_t*) env->GetDirectBufferAddress(out);
    if (!ip || !op) return 0;
    int frameBytes = 2 * (channels == 1 ? 1 : 2);
    int frames = bytes / frameBytes;
    ip += inOff;
    op += outOff;
    ((Engine*) h)->process((const int16_t*) ip, (int16_t*) op, frames);
    int rest = bytes - frames * frameBytes;
    if (rest > 0) std::memcpy(op + frames * frameBytes, ip + frames * frameBytes, (size_t) rest);
    return bytes;
}

JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_fx_NativeFx_nativeSetAll(JNIEnv* env, jobject, jboolean on, jfloatArray eq,
                                                       jfloat bass, jfloat width, jfloat rvMix, jfloat rvRoom,
                                                       jfloat rvDamp, jboolean p8On, jfloat p8Period, jfloat p8Depth,
                                                       jboolean normOn, jfloat normTarget) {
    float e[kBands] = {};
    if (eq != nullptr && env->GetArrayLength(eq) >= kBands) env->GetFloatArrayRegion(eq, 0, kBands, e);
    gP.on.store(on != JNI_FALSE);
    for (int i = 0; i < kBands; i++) gP.eq[i].store(e[i]);
    gP.bass.store(bass);
    gP.width.store(width);
    gP.rvMix.store(rvMix);
    gP.rvRoom.store(rvRoom);
    gP.rvDamp.store(rvDamp);
    gP.p8On.store(p8On != JNI_FALSE);
    gP.p8Period.store(p8Period);
    gP.p8Depth.store(p8Depth);
    gP.normOn.store(normOn != JNI_FALSE);
    gP.normTarget.store(normTarget);
    gP.version.fetch_add(1);
}

}  // extern "C"
