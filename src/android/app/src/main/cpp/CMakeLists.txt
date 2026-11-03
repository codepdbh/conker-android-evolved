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

# libgpudriver.so and the hook libraries: libadrenotools, for a Vulkan driver the player imports. The APK brings no driver
enable_language(C)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../third_party/libadrenotools ${CMAKE_CURRENT_BINARY_DIR}/adrenotools)
add_library(gpudriver SHARED gpudriver.cpp)
target_link_libraries(gpudriver PRIVATE adrenotools dl)
# linkernsbypass has data symbols named like libdl's functions: exported, they break whoever loads this
target_link_options(gpudriver PRIVATE "-Wl,--exclude-libs,libadrenotools.a:liblinkernsbypass.a")
foreach(target gpudriver main_hook file_redirect_hook gsl_alloc_hook hook_impl)
    target_link_options(${target} PRIVATE -Wl,-z,max-page-size=16384)
endforeach()
