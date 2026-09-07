#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {
constexpr const char* kAhbExtension = VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;

bool has_device_extension(VkPhysicalDevice device, const char* name) {
    std::uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (count > 0 && vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) return true;
    }
    return false;
}

bool find_compute_ahb_device(
    VkInstance instance,
    VkPhysicalDevice& physicalDeviceOut,
    std::uint32_t& queueFamilyOut
) {
    std::uint32_t deviceCount = 0;
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr) != VK_SUCCESS || deviceCount == 0) return false;
    std::vector<VkPhysicalDevice> devices(deviceCount);
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data()) != VK_SUCCESS) return false;

    for (VkPhysicalDevice device : devices) {
        if (!has_device_extension(device, kAhbExtension)) continue;
        std::uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());
        for (std::uint32_t index = 0; index < queueCount; ++index) {
            if (queues[index].queueCount > 0 && (queues[index].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                physicalDeviceOut = device;
                queueFamilyOut = index;
                return true;
            }
        }
    }
    return false;
}

bool can_import_ahardwarebuffer(AHardwareBuffer* buffer) {
    if (buffer == nullptr) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_BLOB || desc.width == 0 || desc.height != 1 || desc.layers != 1) return false;
    if ((desc.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER) == 0) return false;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "LIFEOS MMSI AHB Probe";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "LIFEOS MMSI";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) return false;

    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    std::uint32_t queueFamily = 0;
    if (!find_compute_ahb_device(instance, physicalDevice, queueFamily)) {
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    const char* extensions[] = { kAhbExtension };
    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = extensions;

    VkDevice device = VK_NULL_HANDLE;
    if (vkCreateDevice(physicalDevice, &deviceInfo, nullptr, &device) != VK_SUCCESS) {
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    auto getProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID")
    );
    bool supported = false;
    if (getProperties != nullptr) {
        VkAndroidHardwareBufferPropertiesANDROID properties{};
        properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        if (getProperties(device, buffer, &properties) == VK_SUCCESS) {
            supported = properties.allocationSize >= desc.width && properties.memoryTypeBits != 0;
        }
    }

    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
    return supported;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_lifeos_core_image_nativebackend_HardwareBufferMmsiInterop_nativeCanImportHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBuffer
) {
    if (hardwareBuffer == nullptr) return JNI_FALSE;
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (buffer == nullptr) return JNI_FALSE;
    return can_import_ahardwarebuffer(buffer) ? JNI_TRUE : JNI_FALSE;
}
