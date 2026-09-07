#include <jni.h>
#include <vulkan/vulkan.h>
#include <cstdint>
#include <vector>

namespace {
bool has_compute_queue(VkPhysicalDevice device) {
    std::uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &count, nullptr);
    if (count == 0) return false;
    std::vector<VkQueueFamilyProperties> properties(count);
    vkGetPhysicalDeviceQueueFamilyProperties(device, &count, properties.data());
    for (const auto& property : properties) {
        if (property.queueCount > 0 && (property.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) return true;
    }
    return false;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_lifeos_core_image_nativebackend_VulkanMmsiBridge_nativeIsVulkanComputeAvailable(
    JNIEnv*,
    jobject
) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "LIFEOS MMSI";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "LIFEOS MMSI";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&createInfo, nullptr, &instance) != VK_SUCCESS) return JNI_FALSE;

    std::uint32_t deviceCount = 0;
    VkResult enumerateResult = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (enumerateResult != VK_SUCCESS || deviceCount == 0) {
        vkDestroyInstance(instance, nullptr);
        return JNI_FALSE;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    enumerateResult = vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    bool available = false;
    if (enumerateResult == VK_SUCCESS) {
        for (VkPhysicalDevice device : devices) {
            if (has_compute_queue(device)) {
                available = true;
                break;
            }
        }
    }
    vkDestroyInstance(instance, nullptr);
    return available ? JNI_TRUE : JNI_FALSE;
}
