cmake_minimum_required(VERSION 3.22.1)
project(sparkynet C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(sparkynet SHARED
    sparkynet.cpp      # speed estimate + DNS warm
    lunofx.cpp         # audio engine
    netfilter.cpp      # ad filter engine
    hostindex.cpp      # blocked host list, mmap + binary search
    nativecrash.cpp    # native crash catcher
    fastio.cpp         # downloader file io
    lunoblur.cpp       # bitmap blur for the glass mini player
    lunoambient.cpp    # frame bars + colour adjust for the live ambient glow
    rcverify.cpp       # remote config signature check
    ed25519/fe.c
    ed25519/ge.c
    ed25519/sc.c
    ed25519/sha512.c
    ed25519/verify.c)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_compile_options(sparkynet PRIVATE -O3 -Wall
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>)
# 16 KB page size, needed for newer devices
target_link_options(sparkynet PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(sparkynet ${log-lib} ${jnigraphics-lib})

# LazyLuno, the C++ YouTube extractor (beta). own library because the rest of the app code builds with
# -fno-exceptions and LazyLuno throws
add_library(lazyluno SHARED
    lazyluno/lazyluno_jni.cpp
    lazyluno/extractor.cpp
    lazyluno/innertube.cpp
    lazyluno/stream_parser.cpp
    lazyluno/client_spoof.cpp
    lazyluno/cipher.cpp
    lazyluno/url_utils.cpp
    lazyluno/auth.cpp
    lazyluno/http.cpp)
target_include_directories(lazyluno PRIVATE lazyluno/include third_party)
target_compile_options(lazyluno PRIVATE -O2 -Wall -Wno-c++20-designator)
target_link_options(lazyluno PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(lazyluno ${log-lib})
