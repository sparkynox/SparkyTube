#include "lunojs.h"
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <algorithm>

extern const char* LUNOJS_SHIM;

double lunoNowMs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

static LunoJs* self(JSContext* c) { return static_cast<LunoJs*>(JS_GetContextOpaque(c)); }

static JSValue jsLog(JSContext* c, JSValueConst, int argc, JSValueConst* argv) {
    if (argc > 0) {
        const char* s = JS_ToCString(c, argv[0]);
        if (s) { self(c)->addLog(s); JS_FreeCString(c, s); }
    }
    return JS_UNDEFINED;
}

static JSValue jsSetTimer(JSContext* c, JSValueConst, int argc, JSValueConst* argv) {
    if (argc < 3 || !JS_IsFunction(c, argv[0])) return JS_NewInt32(c, 0);
    double ms = 0;
    JS_ToFloat64(c, &ms, argv[1]);
    int id = self(c)->addTimer(JS_DupValue(c, argv[0]), ms < 0 ? 0 : ms, JS_ToBool(c, argv[2]) > 0);
    return JS_NewInt32(c, id);
}

static JSValue jsClearTimer(JSContext* c, JSValueConst, int argc, JSValueConst* argv) {
    int32_t id = 0;
    if (argc > 0 && JS_ToInt32(c, &id, argv[0]) == 0) self(c)->clearTimer(id);
    return JS_UNDEFINED;
}

static JSValue jsNow(JSContext* c, JSValueConst, int, JSValueConst*) {
    static const double start = lunoNowMs();
    return JS_NewFloat64(c, lunoNowMs() - start);
}

static JSValue jsRandom(JSContext* c, JSValueConst, int, JSValueConst*) {
    uint32_t v = 0;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) { if (read(fd, &v, sizeof v) != (ssize_t)sizeof v) v = 0; close(fd); }
    return JS_NewUint32(c, v);
}

static int interrupted(JSContext*, void* opaque) {
    LunoJs* js = static_cast<LunoJs*>(opaque);
    return js->deadline > 0 && lunoNowMs() > js->deadline;
}

LunoJs::LunoJs(const std::string& userAgent) {
    rt = JS_NewRuntime();
    if (!rt) return;
    JS_SetMemoryLimit(rt, 256u * 1024 * 1024);
    JS_SetMaxStackSize(rt, 1024 * 1024);
    JS_SetInterruptHandler(rt, interrupted, this);
    ctx = JS_NewContext(rt);
    if (!ctx) return;
    JS_SetContextOpaque(ctx, this);

    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__nativeLog", JS_NewCFunction(ctx, jsLog, "__nativeLog", 1));
    JS_SetPropertyStr(ctx, g, "__nativeSetTimer", JS_NewCFunction(ctx, jsSetTimer, "__nativeSetTimer", 3));
    JS_SetPropertyStr(ctx, g, "__nativeClearTimer", JS_NewCFunction(ctx, jsClearTimer, "__nativeClearTimer", 1));
    JS_SetPropertyStr(ctx, g, "__nativeNow", JS_NewCFunction(ctx, jsNow, "__nativeNow", 0));
    JS_SetPropertyStr(ctx, g, "__nativeRandom", JS_NewCFunction(ctx, jsRandom, "__nativeRandom", 0));
    JS_SetPropertyStr(ctx, g, "__UA", JS_NewString(ctx, userAgent.c_str()));
    JS_FreeValue(ctx, g);

    JSValue r = JS_Eval(ctx, LUNOJS_SHIM, strlen(LUNOJS_SHIM), "shim.js", JS_EVAL_TYPE_GLOBAL);
    bool bad = JS_IsException(r);
    if (bad) addLog("shim failed: " + errorText(JS_GetException(ctx)));
    JS_FreeValue(ctx, r);
    if (bad) {
        JS_FreeContext(ctx);
        ctx = nullptr;
    }
}

LunoJs::~LunoJs() {
    if (ctx) {
        for (auto& t : timers) JS_FreeValue(ctx, t.fn);
        timers.clear();
        JS_FreeContext(ctx);
    }
    if (rt) JS_FreeRuntime(rt);
}

void LunoJs::addLog(const std::string& line) {
    if (logBuf.size() < 64 * 1024) { logBuf += line; logBuf += '\n'; }
}

std::string LunoJs::takeLog() {
    std::string s;
    s.swap(logBuf);
    return s;
}

int LunoJs::addTimer(JSValue fn, double ms, bool repeat) {
    int id = nextId++;
    timers.push_back({id, lunoNowMs() + ms, repeat ? std::max(ms, 1.0) : -1.0, fn});
    return id;
}

void LunoJs::clearTimer(int id) {
    for (size_t i = 0; i < timers.size(); i++) {
        if (timers[i].id == id) {
            JS_FreeValue(ctx, timers[i].fn);
            timers.erase(timers.begin() + i);
            return;
        }
    }
}

std::string LunoJs::toStr(JSValue v) {
    const char* s = JS_ToCString(ctx, v);
    std::string out = s ? s : "";
    if (s) JS_FreeCString(ctx, s);
    return out;
}

std::string LunoJs::errorText(JSValue ex) {
    std::string out;
    if (JS_IsObject(ex)) {
        JSValue stack = JS_GetPropertyStr(ctx, ex, "stack");
        JSValue msg = JS_GetPropertyStr(ctx, ex, "message");
        out = toStr(msg);
        if (!JS_IsUndefined(stack)) out += " | " + toStr(stack);
        JS_FreeValue(ctx, stack);
        JS_FreeValue(ctx, msg);
    } else {
        out = toStr(ex);
    }
    JS_FreeValue(ctx, ex);
    return out;
}

bool LunoJs::pumpJobs(std::string& err) {
    for (;;) {
        JSContext* c1 = nullptr;
        int r = JS_ExecutePendingJob(rt, &c1);
        if (r == 0) return true;
        if (r < 0) {
            err = errorText(JS_GetException(c1 ? c1 : ctx));
            return false;
        }
    }
}

bool LunoJs::runAsync(const std::string& code, long timeoutMs, std::string& out, std::string& err) {
    if (!ctx) { err = "engine not started"; return false; }
    deadline = lunoNowMs() + timeoutMs;
    JSValue p = JS_Eval(ctx, code.c_str(), code.size(), "<luno>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(p)) {
        err = errorText(JS_GetException(ctx));
        deadline = 0;
        return false;
    }
    bool ok = false;
    for (;;) {
        std::string jobErr;
        if (!pumpJobs(jobErr)) { err = "job failed: " + jobErr; break; }

        JSPromiseStateEnum st = JS_PromiseState(ctx, p);
        if ((int)st < 0) { out = toStr(p); ok = true; break; }
        if (st == JS_PROMISE_FULFILLED) {
            JSValue r = JS_PromiseResult(ctx, p);
            out = toStr(r);
            JS_FreeValue(ctx, r);
            ok = true;
            break;
        }
        if (st == JS_PROMISE_REJECTED) {
            err = errorText(JS_PromiseResult(ctx, p));
            break;
        }

        if (timers.empty()) { err = "promise never settled and nothing is scheduled"; break; }
        size_t best = 0;
        for (size_t i = 1; i < timers.size(); i++) if (timers[i].due < timers[best].due) best = i;
        double now = lunoNowMs();
        if (timers[best].due > deadline) { err = "timed out"; break; }
        if (timers[best].due > now) usleep((useconds_t)((timers[best].due - now) * 1000));

        Timer t = timers[best];
        if (t.every >= 0) timers[best].due = lunoNowMs() + t.every;
        else timers.erase(timers.begin() + best);
        JSValue res = JS_Call(ctx, t.fn, JS_UNDEFINED, 0, nullptr);
        if (JS_IsException(res)) addLog("timer threw: " + errorText(JS_GetException(ctx)));
        JS_FreeValue(ctx, res);
        if (t.every < 0) JS_FreeValue(ctx, t.fn);
    }
    JS_FreeValue(ctx, p);
    deadline = 0;
    return ok;
}
