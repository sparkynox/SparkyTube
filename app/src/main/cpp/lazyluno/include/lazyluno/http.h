#pragma once
// LazyLuno - http.h
// The library does no networking itself. The host (the Android app) installs a backend,
// so TLS, proxies, HTTP/2 and so on all come from the app's own HTTP stack.

#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace lazyluno {
namespace http {

struct Request {
    std::string method = "GET";
    std::string url;
    std::string body;
    std::vector<std::pair<std::string, std::string>> headers;
    int connectTimeoutMs = 5000;
    int timeoutMs        = 15000;
};

struct Response {
    int         status = 0;   // 0 = network failure
    std::string body;
    std::string error;
    bool ok() const { return status == 200; }
};

using Backend = std::function<Response(const Request&)>;

void     setBackend(Backend b);
Response perform(const Request& r);

} // namespace http
} // namespace lazyluno
