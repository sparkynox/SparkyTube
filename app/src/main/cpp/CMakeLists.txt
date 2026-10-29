cmake_minimum_required(VERSION 3.22.1)
project(sparkynet C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(sparkynet SHARED
    sparkynet.cpp      # speed estimate + DNS warm
    lunofx.cpp         # audio engine
    netfilter.cpp      # ad filter engine
    nativecrash.cpp    # native crash catcher
    fastio.cpp         # downloader file io
    rcverify.cpp       # remote config signature check
    ed25519/fe.c
    ed25519/ge.c
    ed25519/sc.c
    ed25519/sha512.c
    ed25519/verify.c)
find_library(log-lib log)

target_compile_options(sparkynet PRIVATE -O3 -Wall
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>)
# 16 KB page size, needed for newer devices
target_link_options(sparkynet PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(sparkynet ${log-lib})
