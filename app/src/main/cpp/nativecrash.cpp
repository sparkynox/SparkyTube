// catches native crashes (SIGSEGV and friends) that the Kotlin crash handler never sees.
// writes a small report to a file, then hands the signal back to the system so the
// normal crash flow (and the previous handler) still run. next start the app reads the file
#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <unwind.h>
#include <sys/syscall.h>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <ctime>

namespace {

char gPath[512];
struct sigaction gOld[NSIG];
volatile sig_atomic_t gBusy = 0;
unsigned char gAltStack[64 * 1024];

const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL};

// only write(), no malloc or stdio inside the handler
void put(int fd, const char* s) { if (s) { ssize_t r = write(fd, s, strlen(s)); (void) r; } }

void putHex(int fd, uintptr_t v) {
    char buf[2 + sizeof(uintptr_t) * 2 + 1];
    int i = 0;
    buf[i++] = '0'; buf[i++] = 'x';
    bool started = false;
    for (int shift = (int) sizeof(uintptr_t) * 8 - 4; shift >= 0; shift -= 4) {
        int d = (int) ((v >> shift) & 0xf);
        if (d || started || shift == 0) { buf[i++] = "0123456789abcdef"[d]; started = true; }
    }
    buf[i] = 0;
    put(fd, buf);
}

void putDec(int fd, long v) {
    char buf[24];
    int i = 23;
    buf[i] = 0;
    bool neg = v < 0;
    unsigned long u = neg ? (unsigned long) (-v) : (unsigned long) v;
    do { buf[--i] = (char) ('0' + u % 10); u /= 10; } while (u && i > 1);
    if (neg) buf[--i] = '-';
    put(fd, buf + i);
}

const char* sigName(int s) {
    switch (s) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        default: return "?";
    }
}

struct Trace {
    uintptr_t pc[40];
    int n;
};

_Unwind_Reason_Code onFrame(struct _Unwind_Context* ctx, void* arg) {
    Trace* t = (Trace*) arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) {
        if (t->n >= 40) return _URC_END_OF_STACK;
        t->pc[t->n++] = pc;
    }
    return _URC_NO_REASON;
}

void handler(int sig, siginfo_t* info, void* ctx) {
    if (!gBusy) {
        gBusy = 1;
        int fd = open(gPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            put(fd, "=== SparkyTube native crash ===\n");
            put(fd, "signal: "); putDec(fd, sig); put(fd, " ("); put(fd, sigName(sig)); put(fd, ")");
            if (info) { put(fd, "  code: "); putDec(fd, info->si_code); put(fd, "  fault addr: "); putHex(fd, (uintptr_t) info->si_addr); }
            put(fd, "\nthread id: "); putDec(fd, (long) syscall(SYS_gettid));
            put(fd, "\ntime: "); putDec(fd, (long) time(nullptr));
            put(fd, "\n\nbacktrace:\n");
            Trace t;
            t.n = 0;
            _Unwind_Backtrace(onFrame, &t);
            for (int i = 0; i < t.n; i++) {
                put(fd, "  #"); putDec(fd, i); put(fd, "  pc "); putHex(fd, t.pc[i]);
                Dl_info di;
                if (dladdr((void*) t.pc[i], &di) && di.dli_fname) {
                    put(fd, "  "); put(fd, di.dli_fname);
                    put(fd, " (+"); putHex(fd, t.pc[i] - (uintptr_t) di.dli_fbase); put(fd, ")");
                    if (di.dli_sname) { put(fd, "  "); put(fd, di.dli_sname); }
                }
                put(fd, "\n");
            }
            close(fd);
        }
    }
    // give the signal back: previous handler (ART, the system) deals with it from here
    sigaction(sig, &gOld[sig], nullptr);
    if (!info || info->si_code <= 0) {
        // raised by abort()/kill, not a hardware fault, so it has to be sent again
        syscall(SYS_tgkill, getpid(), (pid_t) syscall(SYS_gettid), sig);
    }
    // a hardware fault just returns and the faulting instruction runs again into the restored handler
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_sparkynox_sparkytube_logs_NativeCrash_nativeInit(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    strncpy(gPath, p, sizeof(gPath) - 1);
    gPath[sizeof(gPath) - 1] = 0;
    env->ReleaseStringUTFChars(path, p);

    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = gAltStack;
    ss.ss_size = sizeof(gAltStack);
    sigaltstack(&ss, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (int s : kSignals) sigaction(s, &sa, &gOld[s]);
    return JNI_TRUE;
}

// for testing the whole flow on purpose
JNIEXPORT void JNICALL
Java_dev_sparkynox_sparkytube_logs_NativeCrash_nativeCrashNow(JNIEnv*, jobject) {
    *(volatile int*) 0 = 1;
}

}  // extern "C"
