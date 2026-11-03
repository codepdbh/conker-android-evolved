// libgpudriver.so: opens a Vulkan driver the player imported, through libadrenotools. Apart from libmain.so, which Zig links
// without the NDK's linker namespaces this needs; libmain.so asks for it only when a driver is chosen (vulkan.cpp).
#include <adrenotools/driver.h>
#include <dlfcn.h>

extern "C" __attribute__((visibility("default"))) void* conker_open_vulkan(const char* hooks, const char* temp,
                                                                           const char* directory, const char* library) {
    return adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, temp, hooks, directory, library, nullptr, nullptr);
}
