#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <unistd.h>
#include <vector>

namespace {
constexpr const char* kAhbExtension = VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;
constexpr const char* kExternalSemaphoreExtension = VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME;
constexpr const char* kExternalSemaphoreFdExtension = VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
constexpr std::size_t kMaxPendingSubmissions = 8;
constexpr jint kDispatchFailed = -2;

struct ImportedBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
};

struct HostBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
};

struct PendingSubmission {
    std::array<ImportedBuffer, 4> imported{};
    HostBuffer uniform{};
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkSemaphore acquireSemaphore = VK_NULL_HANDLE;
    VkSemaphore releaseSemaphore = VK_NULL_HANDLE;
    VkFence completionFence = VK_NULL_HANDLE;
};

struct SyncRenderer {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    std::uint32_t queueFamilyIndex = 0;
    VkQueue queue = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties = nullptr;
    PFN_vkImportSemaphoreFdKHR importSemaphoreFd = nullptr;
    PFN_vkGetSemaphoreFdKHR getSemaphoreFd = nullptr;
    std::vector<PendingSubmission> pending;
};

struct alignas(16) SynthesisParamsNative {
    float sunDirectionAndSolidAngle[4];
    float sunColor[4];
    float skyAmbientColor[4];
    std::uint32_t resolution[2];
    float shadowFloor;
    float padding;
};
static_assert(sizeof(SynthesisParamsNative) == 64);

bool has_extension(VkPhysicalDevice device, const char* name) {
    std::uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (count > 0 && vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) return true;
    }
    return false;
}

bool find_device(VkInstance instance, VkPhysicalDevice& physicalOut, std::uint32_t& queueOut) {
    std::uint32_t deviceCount = 0;
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr) != VK_SUCCESS || deviceCount == 0) return false;
    std::vector<VkPhysicalDevice> devices(deviceCount);
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data()) != VK_SUCCESS) return false;
    for (VkPhysicalDevice device : devices) {
        if (!has_extension(device, kAhbExtension) ||
            !has_extension(device, kExternalSemaphoreExtension) ||
            !has_extension(device, kExternalSemaphoreFdExtension)) continue;
        std::uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());
        for (std::uint32_t i = 0; i < queueCount; ++i) {
            if (queues[i].queueCount > 0 && (queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                physicalOut = device;
                queueOut = i;
                return true;
            }
        }
    }
    return false;
}

std::uint32_t choose_memory_type(
    VkPhysicalDevice physicalDevice,
    std::uint32_t allowedBits,
    VkMemoryPropertyFlags required = 0
) {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &properties);
    for (std::uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((allowedBits & (1u << i)) != 0 &&
            (properties.memoryTypes[i].propertyFlags & required) == required) return i;
    }
    return UINT32_MAX;
}

void destroy_imported(SyncRenderer* renderer, ImportedBuffer& buffer) {
    if (buffer.buffer != VK_NULL_HANDLE) vkDestroyBuffer(renderer->device, buffer.buffer, nullptr);
    if (buffer.memory != VK_NULL_HANDLE) vkFreeMemory(renderer->device, buffer.memory, nullptr);
    buffer = {};
}

void destroy_host(SyncRenderer* renderer, HostBuffer& buffer) {
    if (buffer.buffer != VK_NULL_HANDLE) vkDestroyBuffer(renderer->device, buffer.buffer, nullptr);
    if (buffer.memory != VK_NULL_HANDLE) vkFreeMemory(renderer->device, buffer.memory, nullptr);
    buffer = {};
}

void cleanup_submission(SyncRenderer* renderer, PendingSubmission& submission) {
    if (submission.completionFence != VK_NULL_HANDLE) vkDestroyFence(renderer->device, submission.completionFence, nullptr);
    if (submission.acquireSemaphore != VK_NULL_HANDLE) vkDestroySemaphore(renderer->device, submission.acquireSemaphore, nullptr);
    if (submission.releaseSemaphore != VK_NULL_HANDLE) vkDestroySemaphore(renderer->device, submission.releaseSemaphore, nullptr);
    if (submission.commandBuffer != VK_NULL_HANDLE) vkFreeCommandBuffers(renderer->device, renderer->commandPool, 1, &submission.commandBuffer);
    if (submission.descriptorSet != VK_NULL_HANDLE) vkFreeDescriptorSets(renderer->device, renderer->descriptorPool, 1, &submission.descriptorSet);
    for (auto& buffer : submission.imported) destroy_imported(renderer, buffer);
    destroy_host(renderer, submission.uniform);
    submission = {};
}

void reap_completed(SyncRenderer* renderer) {
    auto it = renderer->pending.begin();
    while (it != renderer->pending.end()) {
        const VkResult status = vkGetFenceStatus(renderer->device, it->completionFence);
        if (status == VK_SUCCESS) {
            cleanup_submission(renderer, *it);
            it = renderer->pending.erase(it);
        } else {
            ++it;
        }
    }
}

bool import_hardware_buffer(
    SyncRenderer* renderer,
    AHardwareBuffer* hardwareBuffer,
    VkDeviceSize requiredBytes,
    ImportedBuffer& out
) {
    if (renderer == nullptr || hardwareBuffer == nullptr || requiredBytes == 0 || renderer->getAhbProperties == nullptr) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_BLOB || desc.height != 1 || desc.layers != 1 || desc.width < requiredBytes) return false;
    if ((desc.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER) == 0) return false;

    VkAndroidHardwareBufferPropertiesANDROID ahbProperties{};
    ahbProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    if (renderer->getAhbProperties(renderer->device, hardwareBuffer, &ahbProperties) != VK_SUCCESS) return false;

    VkExternalMemoryBufferCreateInfo externalInfo{};
    externalInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    externalInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.pNext = &externalInfo;
    bufferInfo.size = requiredBytes;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(renderer->device, &bufferInfo, nullptr, &out.buffer) != VK_SUCCESS) return false;

    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(renderer->device, out.buffer, &requirements);
    const std::uint32_t memoryType = choose_memory_type(
        renderer->physicalDevice,
        requirements.memoryTypeBits & ahbProperties.memoryTypeBits
    );
    if (memoryType == UINT32_MAX) {
        destroy_imported(renderer, out);
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = hardwareBuffer;
    VkMemoryAllocateInfo allocationInfo{};
    allocationInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocationInfo.pNext = &importInfo;
    allocationInfo.allocationSize = ahbProperties.allocationSize;
    allocationInfo.memoryTypeIndex = memoryType;
    if (vkAllocateMemory(renderer->device, &allocationInfo, nullptr, &out.memory) != VK_SUCCESS) {
        destroy_imported(renderer, out);
        return false;
    }
    if (vkBindBufferMemory(renderer->device, out.buffer, out.memory, 0) != VK_SUCCESS) {
        destroy_imported(renderer, out);
        return false;
    }
    out.size = requiredBytes;
    return true;
}

bool create_uniform(SyncRenderer* renderer, HostBuffer& out, const SynthesisParamsNative& params) {
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = sizeof(params);
    bufferInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(renderer->device, &bufferInfo, nullptr, &out.buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(renderer->device, out.buffer, &requirements);
    const std::uint32_t type = choose_memory_type(
        renderer->physicalDevice,
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (type == UINT32_MAX) {
        destroy_host(renderer, out);
        return false;
    }
    VkMemoryAllocateInfo allocationInfo{};
    allocationInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocationInfo.allocationSize = requirements.size;
    allocationInfo.memoryTypeIndex = type;
    if (vkAllocateMemory(renderer->device, &allocationInfo, nullptr, &out.memory) != VK_SUCCESS ||
        vkBindBufferMemory(renderer->device, out.buffer, out.memory, 0) != VK_SUCCESS) {
        destroy_host(renderer, out);
        return false;
    }
    out.size = requirements.size;
    void* mapped = nullptr;
    if (vkMapMemory(renderer->device, out.memory, 0, sizeof(params), 0, &mapped) != VK_SUCCESS) {
        destroy_host(renderer, out);
        return false;
    }
    std::memcpy(mapped, &params, sizeof(params));
    vkUnmapMemory(renderer->device, out.memory);
    return true;
}

bool create_acquire_semaphore(SyncRenderer* renderer, int& ownedFd, VkSemaphore& semaphore) {
    if (ownedFd < 0) return true;
    VkSemaphoreCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (vkCreateSemaphore(renderer->device, &createInfo, nullptr, &semaphore) != VK_SUCCESS) return false;
    VkImportSemaphoreFdInfoKHR importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
    importInfo.semaphore = semaphore;
    importInfo.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
    importInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    importInfo.fd = ownedFd;
    if (renderer->importSemaphoreFd(renderer->device, &importInfo) != VK_SUCCESS) return false;
    ownedFd = -1; // Vulkan owns the fd after successful import.
    return true;
}

bool create_release_semaphore(SyncRenderer* renderer, VkSemaphore& semaphore) {
    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    createInfo.pNext = &exportInfo;
    return vkCreateSemaphore(renderer->device, &createInfo, nullptr, &semaphore) == VK_SUCCESS;
}

void destroy_renderer(SyncRenderer* renderer) {
    if (renderer == nullptr) return;
    if (renderer->device != VK_NULL_HANDLE) vkDeviceWaitIdle(renderer->device);
    for (auto& submission : renderer->pending) cleanup_submission(renderer, submission);
    renderer->pending.clear();
    if (renderer->commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(renderer->device, renderer->commandPool, nullptr);
    if (renderer->descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(renderer->device, renderer->descriptorPool, nullptr);
    if (renderer->pipeline != VK_NULL_HANDLE) vkDestroyPipeline(renderer->device, renderer->pipeline, nullptr);
    if (renderer->pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(renderer->device, renderer->pipelineLayout, nullptr);
    if (renderer->descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(renderer->device, renderer->descriptorSetLayout, nullptr);
    if (renderer->device != VK_NULL_HANDLE) vkDestroyDevice(renderer->device, nullptr);
    if (renderer->instance != VK_NULL_HANDLE) vkDestroyInstance(renderer->instance, nullptr);
    delete renderer;
}

SyncRenderer* create_renderer(const std::uint32_t* spirv, std::size_t spirvBytes) {
    if (spirv == nullptr || spirvBytes == 0 || spirvBytes % 4 != 0) return nullptr;
    auto* renderer = new SyncRenderer();
    renderer->pending.reserve(kMaxPendingSubmissions);

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "LIFEOS MMSI SyncFD Forward";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "LIFEOS MMSI";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    if (vkCreateInstance(&instanceInfo, nullptr, &renderer->instance) != VK_SUCCESS ||
        !find_device(renderer->instance, renderer->physicalDevice, renderer->queueFamilyIndex)) {
        destroy_renderer(renderer);
        return nullptr;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = renderer->queueFamilyIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* extensions[] = { kAhbExtension, kExternalSemaphoreExtension, kExternalSemaphoreFdExtension };
    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 3;
    deviceInfo.ppEnabledExtensionNames = extensions;
    if (vkCreateDevice(renderer->physicalDevice, &deviceInfo, nullptr, &renderer->device) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    vkGetDeviceQueue(renderer->device, renderer->queueFamilyIndex, 0, &renderer->queue);
    renderer->getAhbProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(renderer->device, "vkGetAndroidHardwareBufferPropertiesANDROID")
    );
    renderer->importSemaphoreFd = reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(
        vkGetDeviceProcAddr(renderer->device, "vkImportSemaphoreFdKHR")
    );
    renderer->getSemaphoreFd = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
        vkGetDeviceProcAddr(renderer->device, "vkGetSemaphoreFdKHR")
    );
    if (renderer->getAhbProperties == nullptr || renderer->importSemaphoreFd == nullptr || renderer->getSemaphoreFd == nullptr) {
        destroy_renderer(renderer);
        return nullptr;
    }

    VkDescriptorSetLayoutBinding bindings[5]{};
    for (std::uint32_t i = 0; i < 4; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[4].binding = 4;
    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[4].descriptorCount = 1;
    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 5;
    layoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(renderer->device, &layoutInfo, nullptr, &renderer->descriptorSetLayout) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &renderer->descriptorSetLayout;
    if (vkCreatePipelineLayout(renderer->device, &pipelineLayoutInfo, nullptr, &renderer->pipelineLayout) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirvBytes;
    shaderInfo.pCode = spirv;
    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vkCreateShaderModule(renderer->device, &shaderInfo, nullptr, &shaderModule) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shaderModule;
    stageInfo.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = renderer->pipelineLayout;
    const VkResult pipelineResult = vkCreateComputePipelines(renderer->device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &renderer->pipeline);
    vkDestroyShaderModule(renderer->device, shaderModule, nullptr);
    if (pipelineResult != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = static_cast<std::uint32_t>(4 * kMaxPendingSubmissions);
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[1].descriptorCount = static_cast<std::uint32_t>(kMaxPendingSubmissions);
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.maxSets = static_cast<std::uint32_t>(kMaxPendingSubmissions);
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(renderer->device, &poolInfo, nullptr, &renderer->descriptorPool) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    VkCommandPoolCreateInfo commandPoolInfo{};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = renderer->queueFamilyIndex;
    if (vkCreateCommandPool(renderer->device, &commandPoolInfo, nullptr, &renderer->commandPool) != VK_SUCCESS) {
        destroy_renderer(renderer);
        return nullptr;
    }
    return renderer;
}

jint dispatch_async(
    SyncRenderer* renderer,
    AHardwareBuffer* albedoAhb,
    AHardwareBuffer* normalDepthAhb,
    AHardwareBuffer* roughnessAhb,
    AHardwareBuffer* outputAhb,
    std::uint32_t width,
    std::uint32_t height,
    const SynthesisParamsNative& params,
    int acquireFenceFd
) {
    if (renderer == nullptr || width == 0 || height == 0) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return kDispatchFailed;
    }
    reap_completed(renderer);
    if (renderer->pending.size() >= kMaxPendingSubmissions) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return kDispatchFailed;
    }

    PendingSubmission submission{};
    const std::size_t pixels = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    const VkDeviceSize sizes[4] = {
        pixels * 4 * sizeof(float),
        pixels * 4 * sizeof(float),
        pixels * sizeof(float),
        pixels * 4 * sizeof(float),
    };
    AHardwareBuffer* ahbs[4] = { albedoAhb, normalDepthAhb, roughnessAhb, outputAhb };
    bool ok = true;
    for (int i = 0; i < 4 && ok; ++i) ok = import_hardware_buffer(renderer, ahbs[i], sizes[i], submission.imported[i]);
    if (ok) ok = create_uniform(renderer, submission.uniform, params);

    VkDescriptorSetAllocateInfo allocateInfo{};
    if (ok) {
        allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocateInfo.descriptorPool = renderer->descriptorPool;
        allocateInfo.descriptorSetCount = 1;
        allocateInfo.pSetLayouts = &renderer->descriptorSetLayout;
        ok = vkAllocateDescriptorSets(renderer->device, &allocateInfo, &submission.descriptorSet) == VK_SUCCESS;
    }
    if (ok) {
        VkDescriptorBufferInfo infos[5]{};
        for (int i = 0; i < 4; ++i) {
            infos[i].buffer = submission.imported[i].buffer;
            infos[i].range = submission.imported[i].size;
        }
        infos[4].buffer = submission.uniform.buffer;
        infos[4].range = sizeof(SynthesisParamsNative);
        VkWriteDescriptorSet writes[5]{};
        for (std::uint32_t i = 0; i < 5; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = submission.descriptorSet;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            writes[i].pBufferInfo = &infos[i];
        }
        vkUpdateDescriptorSets(renderer->device, 5, writes, 0, nullptr);
    }

    VkCommandBufferAllocateInfo commandAllocate{};
    if (ok) {
        commandAllocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        commandAllocate.commandPool = renderer->commandPool;
        commandAllocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandAllocate.commandBufferCount = 1;
        ok = vkAllocateCommandBuffers(renderer->device, &commandAllocate, &submission.commandBuffer) == VK_SUCCESS;
    }
    if (ok) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        ok = vkBeginCommandBuffer(submission.commandBuffer, &beginInfo) == VK_SUCCESS;
        if (ok) {
            vkCmdBindPipeline(submission.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, renderer->pipeline);
            vkCmdBindDescriptorSets(submission.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, renderer->pipelineLayout, 0, 1, &submission.descriptorSet, 0, nullptr);
            vkCmdDispatch(submission.commandBuffer, (width + 7u) / 8u, (height + 7u) / 8u, 1);
            ok = vkEndCommandBuffer(submission.commandBuffer) == VK_SUCCESS;
        }
    }

    int ownedAcquireFd = acquireFenceFd;
    if (ok) ok = create_acquire_semaphore(renderer, ownedAcquireFd, submission.acquireSemaphore);
    if (ok) ok = create_release_semaphore(renderer, submission.releaseSemaphore);
    if (ok) {
        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        ok = vkCreateFence(renderer->device, &fenceInfo, nullptr, &submission.completionFence) == VK_SUCCESS;
    }

    bool submitted = false;
    if (ok) {
        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        if (submission.acquireSemaphore != VK_NULL_HANDLE) {
            submitInfo.waitSemaphoreCount = 1;
            submitInfo.pWaitSemaphores = &submission.acquireSemaphore;
            submitInfo.pWaitDstStageMask = &waitStage;
        }
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &submission.commandBuffer;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &submission.releaseSemaphore;
        ok = vkQueueSubmit(renderer->queue, 1, &submitInfo, submission.completionFence) == VK_SUCCESS;
        submitted = ok;
    }
    if (ownedAcquireFd >= 0) close(ownedAcquireFd);

    if (!ok) {
        if (submitted) vkWaitForFences(renderer->device, 1, &submission.completionFence, VK_TRUE, UINT64_MAX);
        cleanup_submission(renderer, submission);
        return kDispatchFailed;
    }

    VkSemaphoreGetFdInfoKHR getInfo{};
    getInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    getInfo.semaphore = submission.releaseSemaphore;
    getInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int releaseFd = -1;
    const VkResult exportResult = renderer->getSemaphoreFd(renderer->device, &getInfo, &releaseFd);
    if (exportResult != VK_SUCCESS) {
        vkWaitForFences(renderer->device, 1, &submission.completionFence, VK_TRUE, UINT64_MAX);
        cleanup_submission(renderer, submission);
        return -1; // Completed synchronously after export fallback.
    }

    renderer->pending.push_back(std::move(submission));
    return static_cast<jint>(releaseFd);
}

AHardwareBuffer* from_java(JNIEnv* env, jobject buffer) {
    return buffer == nullptr ? nullptr : AHardwareBuffer_fromHardwareBuffer(env, buffer);
}

bool require_shader(JNIEnv* env, jobject buffer, jint bytes) {
    if (bytes <= 0 || bytes % 4 != 0 || buffer == nullptr || env->GetDirectBufferAddress(buffer) == nullptr ||
        env->GetDirectBufferCapacity(buffer) < bytes) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exceptionClass, "SPIR-V shader buffer must be direct and valid");
        return false;
    }
    return true;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_lifeos_core_image_nativebackend_VulkanSyncFdHardwareBufferMmsiRenderer_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject shaderBuffer,
    jint shaderBytes
) {
    if (!require_shader(env, shaderBuffer, shaderBytes)) return 0;
    auto* shader = static_cast<const std::uint32_t*>(env->GetDirectBufferAddress(shaderBuffer));
    return reinterpret_cast<jlong>(create_renderer(shader, static_cast<std::size_t>(shaderBytes)));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_lifeos_core_image_nativebackend_VulkanSyncFdHardwareBufferMmsiRenderer_nativeDispatch(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject albedoBuffer,
    jobject normalDepthBuffer,
    jobject roughnessBuffer,
    jobject outputBuffer,
    jint width,
    jint height,
    jfloat sunX,
    jfloat sunY,
    jfloat sunZ,
    jfloat sunSolidAngle,
    jfloat sunR,
    jfloat sunG,
    jfloat sunB,
    jfloat skyR,
    jfloat skyG,
    jfloat skyB,
    jfloat shadowFloor,
    jint acquireFenceFd
) {
    AHardwareBuffer* albedo = from_java(env, albedoBuffer);
    AHardwareBuffer* normalDepth = from_java(env, normalDepthBuffer);
    AHardwareBuffer* roughness = from_java(env, roughnessBuffer);
    AHardwareBuffer* output = from_java(env, outputBuffer);
    if (handle == 0 || width <= 0 || height <= 0 || albedo == nullptr || normalDepth == nullptr || roughness == nullptr || output == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return kDispatchFailed;
    }

    SynthesisParamsNative params{};
    params.sunDirectionAndSolidAngle[0] = sunX;
    params.sunDirectionAndSolidAngle[1] = sunY;
    params.sunDirectionAndSolidAngle[2] = sunZ;
    params.sunDirectionAndSolidAngle[3] = sunSolidAngle;
    params.sunColor[0] = sunR;
    params.sunColor[1] = sunG;
    params.sunColor[2] = sunB;
    params.sunColor[3] = 1.0f;
    params.skyAmbientColor[0] = skyR;
    params.skyAmbientColor[1] = skyG;
    params.skyAmbientColor[2] = skyB;
    params.skyAmbientColor[3] = 1.0f;
    params.resolution[0] = static_cast<std::uint32_t>(width);
    params.resolution[1] = static_cast<std::uint32_t>(height);
    params.shadowFloor = std::clamp(shadowFloor, 0.0f, 1.0f);

    return dispatch_async(
        reinterpret_cast<SyncRenderer*>(handle),
        albedo,
        normalDepth,
        roughness,
        output,
        static_cast<std::uint32_t>(width),
        static_cast<std::uint32_t>(height),
        params,
        acquireFenceFd
    );
}

extern "C" JNIEXPORT void JNICALL
Java_app_lifeos_core_image_nativebackend_VulkanSyncFdHardwareBufferMmsiRenderer_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    destroy_renderer(reinterpret_cast<SyncRenderer*>(handle));
}
