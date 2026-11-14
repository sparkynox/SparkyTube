// LazyLuno - http.cpp: the backend hook, nothing else
#include "lazyluno/http.h"

#include <mutex>

namespace lazyluno {
namespace http {

static std::mutex gMu;
static Backend gBackend;

void setBackend(Backend b) {
    std::lock_guard<std::mutex> l(gMu);
    gBackend = std::move(b);
}

Response perform(const Request& r) {
    Backend b;
    {
        std::lock_guard<std::mutex> l(gMu);
        b = gBackend;
    }
    if (!b) return {0, {}, "no http backend installed"};
    return b(r);
}

} // namespace http
} // namespace lazyluno
