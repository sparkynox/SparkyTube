#pragma once
// LazyLuno — error.h
// Author: SparkyNox

#include <stdexcept>
#include <string>

namespace lazyluno {

enum class ErrorCode {
    Ok = 0,
    NetworkError,
    ParseError,
    AgeRestricted,          // content age-gated and no bypass succeeded
    LoginRequired,          // endpoint demanded auth, no cookies supplied
    VideoUnavailable,       // deleted / private / regional block
    SignatureCipherFailed,  // couldn't decipher stream URL
    NoStreamsFound,
    RateLimited,
    Unknown,
};

struct Error : std::runtime_error {
    ErrorCode code;
    explicit Error(ErrorCode c, std::string msg)
        : std::runtime_error(std::move(msg)), code(c) {}
};

} // namespace lazyluno
