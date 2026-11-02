# libprobe.so, the launcher's own native code, with the NDK: no game, no runtime (probe.cpp)
cmake_minimum_required(VERSION 3.22)
project(probe LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(probe SHARED probe.cpp)
# Vulkan's structures are made as {sType}, the rest zero
target_compile_options(probe PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers)
target_link_libraries(probe PRIVATE dl log)
target_link_options(probe PRIVATE -Wl,-z,max-page-size=16384)
