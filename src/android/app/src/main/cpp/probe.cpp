// libprobe.so: what this phone can do, read by the launcher before any game code is loaded. It is built with the NDK and
// has no part of the game or the runtime in it, so the launcher, its compatibility screen and its reports work on a
// phone where libmain.so would not start. It only asks: Vulkan is opened here, a device is never made.
//
// The report is lines of "key=value", read by Compat.kt.
#include <dlfcn.h>
#include <jni.h>
#include <sys/auxv.h>
#include <unistd.h>

#include <asm/hwcap.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

namespace {

void Add(std::string& out, const char* key, const char* fmt, ...) {
    char value[512];
    va_list a;
    va_start(a, fmt);
    std::vsnprintf(value, sizeof(value), fmt, a);
    va_end(a);
    out += key;
    out += '=';
    out += value;
    out += '\n';
}

void Cpu(std::string& out) {
    unsigned long hw = getauxval(AT_HWCAP);
    Add(out, "cpu.abi", "aarch64");
    Add(out, "cpu.page_size", "%ld", sysconf(_SC_PAGESIZE));
    Add(out, "cpu.cores", "%ld", sysconf(_SC_NPROCESSORS_CONF));
    // LSE atomics (ARMv8.1): upstream's build assumes them, and a Cortex-A53 or A73 has none
    Add(out, "cpu.atomics", "%d", (hw & HWCAP_ATOMICS) != 0);
    Add(out, "cpu.asimd", "%d", (hw & HWCAP_ASIMD) != 0);
    Add(out, "cpu.crc32", "%d", (hw & HWCAP_CRC32) != 0);
    Add(out, "cpu.fphp", "%d", (hw & HWCAP_FPHP) != 0);
    std::ifstream info("/proc/cpuinfo");
    std::string line, parts;
    while (std::getline(info, line)) {
        if (line.rfind("CPU part", 0) != 0) continue;
        std::string part = line.substr(line.find(':') + 2);
        if (parts.find(part) == std::string::npos) parts += (parts.empty() ? "" : ",") + part;
    }
    Add(out, "cpu.parts", "%s", parts.c_str());
}

struct Vk {
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    template <typename T>
    T Get(const char* name) const {
        return reinterpret_cast<T>(gipa(instance, name));
    }
};

bool HasExtension(const std::vector<VkExtensionProperties>& list, const char* name) {
    for (const auto& e : list)
        if (!std::strcmp(e.extensionName, name)) return true;
    return false;
}

void Device(std::string& out, const Vk& vk, VkPhysicalDevice d, int index, uint32_t instance_version) {
    auto properties = vk.Get<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties");
    auto features = vk.Get<PFN_vkGetPhysicalDeviceFeatures>("vkGetPhysicalDeviceFeatures");
    auto extensions = vk.Get<PFN_vkEnumerateDeviceExtensionProperties>("vkEnumerateDeviceExtensionProperties");
    auto formats = vk.Get<PFN_vkGetPhysicalDeviceFormatProperties>("vkGetPhysicalDeviceFormatProperties");
    auto features2 = vk.Get<PFN_vkGetPhysicalDeviceFeatures2>("vkGetPhysicalDeviceFeatures2");
    auto properties2 = vk.Get<PFN_vkGetPhysicalDeviceProperties2>("vkGetPhysicalDeviceProperties2");

    VkPhysicalDeviceProperties p{};
    properties(d, &p);
    VkPhysicalDeviceFeatures f{};
    features(d, &f);
    uint32_t count = 0;
    extensions(d, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> ext(count);
    extensions(d, nullptr, &count, ext.data());

    char key[64];
    auto K = [&](const char* name) {
        std::snprintf(key, sizeof(key), "gpu%d.%s", index, name);
        return key;
    };
    Add(out, K("name"), "%s", p.deviceName);
    Add(out, K("vendor"), "%04x", p.vendorID);
    Add(out, K("device"), "%08x", p.deviceID);
    Add(out, K("api"), "%u.%u.%u", VK_API_VERSION_MAJOR(p.apiVersion), VK_API_VERSION_MINOR(p.apiVersion),
        VK_API_VERSION_PATCH(p.apiVersion));
    Add(out, K("driver"), "%u", p.driverVersion);
    Add(out, K("type"), "%d", int(p.deviceType));

    bool v11 = p.apiVersion >= VK_API_VERSION_1_1 && instance_version >= VK_API_VERSION_1_1 && features2 && properties2;
    bool v12 = v11 && p.apiVersion >= VK_API_VERSION_1_2;
    bool v13 = v11 && p.apiVersion >= VK_API_VERSION_1_3;
    if (v11 && HasExtension(ext, VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME)) {
        VkPhysicalDeviceDriverProperties dp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
        VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &dp};
        properties2(d, &p2);
        Add(out, K("driver_name"), "%s", dp.driverName);
        Add(out, K("driver_info"), "%s", dp.driverInfo);
    }

    // a structure is chained only for what the device names, core or by extension: old drivers do not all skip others
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
    VkPhysicalDeviceHostQueryResetFeatures reset{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES};
    VkPhysicalDeviceDynamicRenderingFeatures rendering{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES};
    VkPhysicalDeviceExtendedDynamicStateFeaturesEXT dynamic{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_FEATURES_EXT};
    void* chain = nullptr;
    auto Chain = [&](auto& s) {
        s.pNext = chain;
        chain = &s;
    };
    bool has_timeline = v12 || HasExtension(ext, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);
    bool has_reset = v12 || HasExtension(ext, VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME);
    bool has_rendering = v13 || HasExtension(ext, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
    bool has_dynamic = v13 || HasExtension(ext, VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME);
    if (v11) {
        if (has_timeline) Chain(timeline);
        if (has_reset) Chain(reset);
        if (has_rendering) Chain(rendering);
        if (has_dynamic) Chain(dynamic);
        VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, chain};
        features2(d, &f2);
    }
    Add(out, K("timeline_semaphore"), "%d", has_timeline && timeline.timelineSemaphore);
    Add(out, K("host_query_reset"), "%d", has_reset && reset.hostQueryReset);
    Add(out, K("dynamic_rendering"), "%d", has_rendering && rendering.dynamicRendering);
    Add(out, K("extended_dynamic_state"), "%d", has_dynamic && dynamic.extendedDynamicState);
    Add(out, K("push_descriptor"), "%d", HasExtension(ext, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME));
    Add(out, K("swapchain"), "%d", HasExtension(ext, VK_KHR_SWAPCHAIN_EXTENSION_NAME));
    Add(out, K("custom_border_color"), "%d", HasExtension(ext, VK_EXT_CUSTOM_BORDER_COLOR_EXTENSION_NAME));

    Add(out, K("geometry_shader"), "%d", f.geometryShader);
    Add(out, K("tessellation_shader"), "%d", f.tessellationShader);
    Add(out, K("bc"), "%d", f.textureCompressionBC);
    Add(out, K("etc2"), "%d", f.textureCompressionETC2);
    Add(out, K("astc"), "%d", f.textureCompressionASTC_LDR);
    Add(out, K("anisotropy"), "%d", f.samplerAnisotropy);
    Add(out, K("fill_non_solid"), "%d", f.fillModeNonSolid);
    Add(out, K("occlusion_precise"), "%d", f.occlusionQueryPrecise);
    Add(out, K("independent_blend"), "%d", f.independentBlend);
    Add(out, K("depth_clamp"), "%d", f.depthClamp);
    Add(out, K("depth_bias_clamp"), "%d", f.depthBiasClamp);
    Add(out, K("frag_stores"), "%d", f.fragmentStoresAndAtomics);

    const VkPhysicalDeviceLimits& l = p.limits;
    Add(out, K("max_image_2d"), "%u", l.maxImageDimension2D);
    Add(out, K("max_vertex_bindings"), "%u", l.maxVertexInputBindings);
    Add(out, K("max_vertex_attributes"), "%u", l.maxVertexInputAttributes);
    Add(out, K("max_stage_samplers"), "%u", l.maxPerStageDescriptorSamplers);
    Add(out, K("max_stage_images"), "%u", l.maxPerStageDescriptorSampledImages);
    Add(out, K("max_stage_ubo"), "%u", l.maxPerStageDescriptorUniformBuffers);
    Add(out, K("max_ubo_range"), "%u", l.maxUniformBufferRange);
    Add(out, K("ubo_align"), "%llu", (unsigned long long)l.minUniformBufferOffsetAlignment);
    Add(out, K("max_sampler_allocations"), "%u", l.maxSamplerAllocationCount);

    auto Format = [&](const char* name, VkFormat format, VkFormatFeatureFlags want) {
        VkFormatProperties fp{};
        formats(d, format, &fp);
        Add(out, K(name), "%d", (fp.optimalTilingFeatures & want) == want);
    };
    VkFormatFeatureFlags depth = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    Format("fmt_d24s8", VK_FORMAT_D24_UNORM_S8_UINT, depth);
    Format("fmt_d32s8", VK_FORMAT_D32_SFLOAT_S8_UINT, depth);
    Format("fmt_d32", VK_FORMAT_D32_SFLOAT, VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT);

    std::string names;
    for (const auto& e : ext) {
        if (!names.empty()) names += ' ';
        names += e.extensionName;
    }
    out += K("extensions");
    out += '=' + names + '\n';
}

std::string Report() {
    std::string out;
    Cpu(out);
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        Add(out, "vulkan.loader", "0");
        Add(out, "vulkan.error", "%s", dlerror());
        return out;
    }
    Add(out, "vulkan.loader", "1");
    Vk vk;
    vk.gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!vk.gipa) {
        Add(out, "vulkan.error", "no vkGetInstanceProcAddr");
        return out;
    }
    uint32_t version = VK_API_VERSION_1_0;
    if (auto enumerate = vk.Get<PFN_vkEnumerateInstanceVersion>("vkEnumerateInstanceVersion")) enumerate(&version);
    Add(out, "vulkan.instance", "%u.%u.%u", VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version),
        VK_API_VERSION_PATCH(version));
    auto create = vk.Get<PFN_vkCreateInstance>("vkCreateInstance");
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    ai.pApplicationName = "probe";
    ai.apiVersion = version >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &ai;
    VkResult r = create(&ci, nullptr, &vk.instance);
    if (r != VK_SUCCESS) {
        Add(out, "vulkan.error", "vkCreateInstance returned %d", int(r));
        return out;
    }
    auto enumerate = vk.Get<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices");
    uint32_t n = 0;
    enumerate(vk.instance, &n, nullptr);
    std::vector<VkPhysicalDevice> devices(n);
    enumerate(vk.instance, &n, devices.data());
    Add(out, "vulkan.devices", "%u", n);
    for (uint32_t i = 0; i < n; i++) Device(out, vk, devices[i], int(i), version);
    vk.Get<PFN_vkDestroyInstance>("vkDestroyInstance")(vk.instance, nullptr);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_org_liveandrecompiled_app_Probe_report(JNIEnv* env, jclass) {
    return env->NewStringUTF(Report().c_str());
}
