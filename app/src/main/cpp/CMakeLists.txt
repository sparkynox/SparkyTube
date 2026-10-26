cmake_minimum_required(VERSION 3.22.1)
project(sparkynet CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(sparkynet SHARED sparkynet.cpp)
find_library(log-lib log)

target_compile_options(sparkynet PRIVATE -O2 -Wall -fno-exceptions -fno-rtti)
# 16 KB page size, needed for newer devices
target_link_options(sparkynet PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(sparkynet ${log-lib})
