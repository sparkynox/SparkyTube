#pragma once
#include <string>
#include <vector>
#include "quickjs.h"

// tiny headless JS runner for the BotGuard po token, no WebView. QuickJS plus a browser shim (lunojs_shim.cpp)
class LunoJs {
public:
    explicit LunoJs(const std::string& userAgent);
    ~LunoJs();
    bool ok() const { return ctx != nullptr; }

    // runs `code`, then keeps running promise jobs and timers until the result settles
    bool runAsync(const std::string& code, long timeoutMs, std::string& out, std::string& err);
    std::string takeLog();

    void addLog(const std::string& line);
    int addTimer(JSValue fn, double ms, bool repeat);
    void clearTimer(int id);

    double deadline = 0;

private:
    struct Timer { int id; double due; double every; JSValue fn; };

    JSRuntime* rt = nullptr;
    JSContext* ctx = nullptr;
    std::vector<Timer> timers;
    std::string logBuf;
    int nextId = 1;

    bool pumpJobs(std::string& err);
    std::string errorText(JSValue ex);
    std::string toStr(JSValue v);
};

double lunoNowMs();
